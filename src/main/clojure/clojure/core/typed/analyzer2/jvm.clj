;;   Copyright (c) Ambrose Bonnaire-Sergeant, Rich Hickey & contributors.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

;; adapted from tools.analyzer.jvm
(ns clojure.core.typed.analyzer2.jvm
  (:refer-clojure :exclude [macroexpand-1])
  (:require [clojure.tools.analyzer.utils :as u]
            [clojure.tools.analyzer.jvm.utils :as ju]
            [clojure.core.typed.analyzer2.jvm.utils :as jana2-utils]
            [clojure.core.typed.analyzer2.env :as env]
            [clojure.tools.analyzer :as ta]
            [clojure.tools.analyzer.ast :as ast]
            [clojure.tools.analyzer.jvm :as taj]
            [clojure.tools.analyzer.passes.jvm.emit-form :as emit-form]
            [clojure.tools.analyzer.passes :as passes]
            [clojure.core.typed.analyzer2.passes.jvm.infer-tag :as infer-tag]
            [clojure.tools.analyzer.passes.elide-meta :as elide-meta]
            [clojure.tools.analyzer.passes.source-info :as source-info]
            [clojure.tools.analyzer.passes.jvm.constant-lifter :as constant-lift]
            [clojure.core.typed.analyzer2.passes.jvm.analyze-host-expr :as analyze-host-expr]
            [clojure.core.typed.analyzer2.passes.jvm.classify-invoke :as classify-invoke]
            [clojure.core.typed.analyzer2.passes.uniquify :as uniquify2]
            [clojure.core.typed.analyzer2.passes.jvm.validate :as validate]
            [clojure.core.typed.analyzer2 :as ana]
            [clojure.core.memoize :as memo])
  (:import (clojure.lang RT Var IObj)))

(def specials
  "Set of the special forms for clojure in the JVM"
  (into ana/specials
        '#{monitor-enter monitor-exit clojure.core/import* reify* deftype* case*}))

(declare resolve-ns)

;; copied from tools.analyzer.jvm to replace `resolve-ns` and `taj-utils/maybe-class-literal`
(defn desugar-symbol [form env]
  (let [sym-ns (namespace form)]
    (if-let [target (and sym-ns
                         (not (resolve-ns (symbol sym-ns) env))
                         (jana2-utils/maybe-class-literal sym-ns))]          ;; Class/field
      (with-meta (list '. target (symbol (str "-" (name form)))) ;; transform to (. Class -field)
                 (meta form))
      form)))

;; copied from tools.analyzer.jvm to replace `resolve-ns` and `taj-utils/maybe-class-literal`
(defn desugar-host-expr [form env]
  (let [[op & expr] form]
    (if (symbol? op)
      (let [opname (name op)
            opns   (namespace op)]
        (if-let [target (and opns
                             (not (resolve-ns (symbol opns) env))
                             (jana2-utils/maybe-class-literal opns))] ; (class/field ..)

          (let [op (symbol opname)]
            (with-meta (list '. target (if (zero? (count expr))
                                         op
                                         (list* op expr)))
                       (meta form)))

          (cond
            (.startsWith opname ".")     ; (.foo bar ..)
            (let [[target & args] expr
                  target (if-let [target (jana2-utils/maybe-class-literal target)]
                           (with-meta (list 'do target)
                                      {:tag 'java.lang.Class})
                           target)
                  args (list* (symbol (subs opname 1)) args)]
              (with-meta (list '. target (if (= 1 (count args)) ;; we don't know if (.foo bar) is
                                           (first args) args))  ;; a method call or a field access
                         (meta form)))

            (.endsWith opname ".") ;; (class. ..)
            (with-meta (list* 'new (symbol (subs opname 0 (dec (count opname)))) expr)
                       (meta form))

            :else form)))
      form)))

(defn macroexpand-1
  "If form represents a macro form or an inlineable function, returns its expansion,
   else returns form."
  ([form] (macroexpand-1 form (taj/empty-env)))
  ([form env]
       (cond

        (seq? form)
        (let [[op & args] form]
          (if (specials op)
            form
            (let [v (ana/resolve-sym op env)
                  m (meta v)
                  local? (-> env :locals (get op))
                  macro? (and (not local?) (:macro m)) ;; locals shadow macros
                  inline-arities-f (:inline-arities m)
                  inline? (and (not local?)
                               (or (not inline-arities-f)
                                   (inline-arities-f (count args)))
                               (:inline m))
                  t (:tag m)]
              (cond

               macro?
               (let [res (apply v form (:locals env) (rest form))] ; (m &form &env & args)
                 (if (u/obj? res)
                   (vary-meta res merge (meta form))
                   res))

               inline?
               (let [res (apply inline? args)]
                 (if (u/obj? res)
                   (vary-meta res merge
                              (and t {:tag t})
                              (meta form))
                   res))

               :else
               (desugar-host-expr form env)))))

        (symbol? form)
        (desugar-symbol form env)

        :else
        form)))

;;redefine passes mainly to move dependency on `uniquify-locals`
;; to `uniquify2/uniquify-locals`

(defn compile-passes [pre-passes post-passes info]
  (let [with-state (filter (comp :state info) (concat pre-passes post-passes))
        state      (zipmap with-state (mapv #(:state (info %)) with-state))

        pfns-fn    (fn [passes]
                     (reduce (fn [f pass]
                               (let [i (info pass)
                                     pass (cond
                                            ;; passes with :state meta take 2 arguments: state and ast
                                            (:state i)
                                            (fn [ast]
                                              (let [pass-state (-> ast :env ::ana/state (get pass))]
                                                (pass pass-state ast)))
                                            ;; otherwise, a pass just takes ast
                                            :else pass)]
                                 #(pass (f %))))
                             (fn [ast] ast)
                             passes))
        pre-passes  (pfns-fn pre-passes)
        post-passes (pfns-fn post-passes)]
    {:pre (fn [ast]
            (let [state (or (-> ast :env ::ana/state)
                            (u/update-vals state #(%)))]
              (pre-passes (assoc-in ast [:env ::ana/state] state))))
     :post post-passes}))

(defn schedule
  "Takes a set of Vars that represent tools.analyzer passes and returns a map
   m of two functions, such that (ast/walk ast (:pre m) (:post m)) runs all
   passes on ast.

   Each pass must have a :pass-info element in its Var's metadata and it must point
   to a map with the following parameters (:before, :after, :affects and :state are
   optional):
   * :after    a set of Vars, the passes that must be run before this pass
   * :before   a set of Vars, the passes that must be run after this pass
   * :depends  a set of Vars, the passes this pass depends on, implies :after
   * :walk     a keyword, one of:
                 - :none if the pass does its own tree walking and cannot be composed
                         with other passes
                 - :post if the pass requires a postwalk and can be composed with other
                         passes
                 - :pre  if the pass requires a prewalk and can be composed with other
                         passes
                 - :any  if the pass can be composed with other passes in both a prewalk
                         or a postwalk
   * :state    a no-arg function that should return an atom holding an init value that will be
               passed as the first argument to the pass (the pass will thus take the ast
               as the second parameter), the atom will be the same for the whole tree traversal
               and thus can be used to preserve state across the traversal
   An opts map might be provided, valid parameters:
   * :debug?   if true, returns a vector of the scheduled passes rather than the concrete
               function"
  [passes & [opts]]
  {:pre [(set? passes)
         (every? var? passes)]}
  (let [info        (@#'passes/indicize (mapv (fn [p] (merge {:name p} (:pass-info (meta p)))) passes))
        passes+deps (into passes (mapcat :depends (vals info)))]
    (if (not= passes passes+deps)
      (recur passes+deps [opts])
      (let [[{pre-passes  :passes :as pre}
             {post-passes :passes :as post}
             :as ps]
            (-> (passes/schedule-passes info)
                (update-in [0 :passes] #(vec (cons #'ana/analyze-outer %))))

            _ (assert (= 2 (count ps)))
            _ (assert (= :pre (:walk pre)))
            _ (assert (= :post (:walk post)))
            ]
        (if (:debug? opts)
          (mapv #(select-keys % [:passes :walk]) ps)
          (compile-passes pre-passes post-passes info))))))

(def default-passes
  "Set of passes that will be run by default on the AST by #'run-passes"
  ;taj/default-passes
  #{;#'warn-on-reflection
    ;#'warn-earmuff

    #'uniquify2/uniquify-locals

;KEEP
    #'source-info/source-info
    #'elide-meta/elide-meta
    #'constant-lift/constant-lift
;KEEP

    ; not compatible with core.typed
    ;#'trim/trim

    ; FIXME is this needed? introduces another pass
    ; TODO does this still introduce another pass with `uniquify2/uniquify-locals`?
    ;#'box
    ;#'box/box

;KEEP
    #'analyze-host-expr/analyze-host-expr
    ;#'validate-loop-locals
    #'validate/validate
    #'infer-tag/infer-tag
;KEEP

;KEEP
    #'classify-invoke/classify-invoke
;KEEP
    })

(def scheduled-default-passes
  (schedule default-passes))

(comment
  (clojure.pprint/pprint
    (schedule default-passes
                     {:debug? true}))
  )

(def default-passes-opts
  "Default :passes-opts for `analyze`"
  {:collect/what                    #{:constants :callsites}
   :collect/where                   #{:deftype :reify :fn}
   :collect/top-level?              false
   :collect-closed-overs/where      #{:deftype :reify :fn :loop :try}
   :collect-closed-overs/top-level? false})

; (U Sym nil) -> (U Sym nil)
(defn resolve-ns
  "Resolves the ns mapped by the given sym in the global env"
  [ns-sym {:keys [ns]}]
  {:pre [((some-fn symbol? nil?) ns-sym)]
   :post [(or (and (symbol? %)
                   (not (namespace %)))
              (nil? %))]}
  (when ns-sym
    (some-> (or (get (ns-aliases ns) ns-sym)
                (find-ns ns-sym))
            ns-name)))

;Any -> Any
(defn resolve-sym
  "Resolves the value mapped by the given sym in the global env"
  [sym {:keys [ns] :as env}]
  (when (symbol? sym)
    (ns-resolve ns sym)))

; copied from tools.analyzer.jvm
; - remove usage of *env*
(defn create-var
  "Creates a Var for sym and returns it.
   The Var gets interned in the env namespace."
  [sym {:keys [ns]}]
  (let [v (get (ns-interns ns) (symbol (name sym)))]
    (if (and v (or (class? v)
                   (= ns (ns-name (.ns ^Var v) ))))
      v
      (let [meta (dissoc (meta sym) :inline :inline-arities :macro)
            meta (if-let [arglists (:arglists meta)]
                   (assoc meta :arglists (taj/qualify-arglists arglists))
                   meta)]
       (intern ns (with-meta sym meta))))))

; no global namespaces tracking (since resolve-{sym,ns} is now platform dependent),
; mostly used for passes configuration.
(defn global-env []
  (atom {}))

(defn parse-monitor-enter
  [[_ target :as form] env]
  (when-not (= 2 (count form))
    (throw (ex-info (str "Wrong number of args to monitor-enter, had: " (dec (count form)))
                    (merge {:form form}
                           (u/-source-info form env)))))
  {:op       :monitor-enter
   :env      env
   :form     form
   :target   (ana/unanalyzed target (u/ctx env :ctx/expr))
   :children [:target]})

(defn parse-monitor-exit
  [[_ target :as form] env]
  (when-not (= 2 (count form))
    (throw (ex-info (str "Wrong number of args to monitor-exit, had: " (dec (count form)))
                    (merge {:form form}
                           (u/-source-info form env)))))
  {:op       :monitor-exit
   :env      env
   :form     form
   :target   (ana/unanalyzed target (u/ctx env :ctx/expr))
   :children [:target]})

(defn parse-import*
  [[_ class :as form] env]
  (when-not (= 2 (count form))
    (throw (ex-info (str "Wrong number of args to import*, had: " (dec (count form)))
                    (merge {:form form}
                           (u/-source-info form env)))))
  {:op    :import
   :env   env
   :form  form
   :class class})

(defn analyze-method-impls
  [[method [this & params :as args] & body :as form] env]
  (when-let [error-msg (cond
                        (not (symbol? method))
                        (str "Method method must be a symbol, had: " (class method))
                        (not (vector? args))
                        (str "Parameter listing should be a vector, had: " (class args))
                        (not (first args))
                        (str "Must supply at least one argument for 'this' in: " method))]
    (throw (ex-info error-msg
                    (merge {:form     form
                            :in       (:this env)
                            :method   method
                            :args     args}
                           (u/-source-info form env)))))
  (let [meth        (cons (vec params) body) ;; this is an implicit arg
        this-expr   {:name  this
                     :env   env
                     :form  this
                     :op    :binding
                     :o-tag (:this env)
                     :tag   (:this env)
                     :local :this}
        env         (assoc-in (dissoc env :this) [:locals this] (u/dissoc-env this-expr))
        method-expr (ana/analyze-fn-method meth env)]
    (assoc (dissoc method-expr :variadic?)
      :op       :method
      :form     form
      :this     this-expr
      :name     (symbol (name method))
      :children (into [:this] (:children method-expr)))))

; copied from tools.analyzer.jvm
; - removed *env* update
;; HACK
(defn -deftype [cname class-name args interfaces]

  (doseq [arg [class-name cname]]
    (memo/memo-clear! ju/members* [arg])
    (memo/memo-clear! ju/members* [(str arg)]))

  (let [interfaces (mapv #(symbol (.getName ^Class %)) interfaces)]
    (eval (list 'let []
                (list 'deftype* cname class-name args :implements interfaces)
                (list 'import class-name)))))

(defn parse-reify*
  [[_ interfaces & methods :as form] env]
  (let [interfaces (conj (disj (set (mapv ju/maybe-class interfaces)) Object)
                         IObj)
        name (gensym "reify__")
        class-name (symbol (str (namespace-munge *ns*) "$" name))
        menv (assoc env :this class-name)
        methods (mapv #(assoc (analyze-method-impls % menv) :interfaces interfaces)
                      methods)]

    (-deftype name class-name [] interfaces)

    (ana/pre-wrapping-meta
     {:op         :reify
      :env        env
      :form       form
      :class-name class-name
      :methods    methods
      :interfaces interfaces
      :children   [:methods]})))

(defn parse-opts+methods [methods]
  (loop [opts {} methods methods]
    (if (keyword? (first methods))
      (recur (assoc opts (first methods) (second methods)) (nnext methods))
      [opts methods])))

(defn parse-deftype*
  [[_ name class-name fields _ interfaces & methods :as form] env]
  (let [interfaces (disj (set (mapv ju/maybe-class interfaces)) Object)
        fields-expr (mapv (fn [name]
                            {:env     env
                             :form    name
                             :name    name
                             :mutable (let [m (meta name)]
                                        (or (and (:unsynchronized-mutable m)
                                                 :unsynchronized-mutable)
                                            (and (:volatile-mutable m)
                                                 :volatile-mutable)))
                             :local   :field
                             :op      :binding})
                          fields)
        menv (assoc env
               :context :ctx/expr
               :locals  (zipmap fields (map u/dissoc-env fields-expr))
               :this    class-name)
        [opts methods] (parse-opts+methods methods)
        methods (mapv #(assoc (analyze-method-impls % menv) :interfaces interfaces)
                      methods)]

    (-deftype name class-name fields interfaces)

    {:op         :deftype
     :env        env
     :form       form
     :name       name
     :class-name class-name ;; internal, don't use as a Class
     :fields     fields-expr
     :methods    methods
     :interfaces interfaces
     :children   [:fields :methods]}))

(defn parse-case*
  [[_ expr shift mask default case-map switch-type test-type & [skip-check?] :as form] env]
  (let [[low high] ((juxt first last) (keys case-map)) ;;case-map is a sorted-map
        e (u/ctx env :ctx/expr)
        test-expr (ana/unanalyzed expr e)
        [tests thens] (reduce (fn [[te th] [min-hash [test then]]]
                                (let [test-expr (ana/analyze-const test e)
                                      then-expr (ana/unanalyzed then env)]
                                  [(conj te {:op       :case-test
                                             :form     test
                                             :env      e
                                             :hash     min-hash
                                             :test     test-expr
                                             :children [:test]})
                                   (conj th {:op       :case-then
                                             :form     then
                                             :env      env
                                             :hash     min-hash
                                             :then     then-expr
                                             :children [:then]})]))
                              [[] []] case-map)
        default-expr (ana/unanalyzed default env)]
    {:op          :case
     :form        form
     :env         env
     :test        (assoc test-expr :case-test true)
     :default     default-expr
     :tests       tests
     :thens       thens
     :shift       shift
     :mask        mask
     :low         low
     :high        high
     :switch-type switch-type
     :test-type   test-type
     :skip-check? skip-check?
     :children    [:test :tests :thens :default]}))

(defn parse
  "Extension to clojure.core.typed.analyzer2/-parse for JVM special forms"
  [form env]
  ((case (first form)
     monitor-enter        parse-monitor-enter
     monitor-exit         parse-monitor-exit
     clojure.core/import* parse-import*
     reify*               parse-reify*
     deftype*             parse-deftype*
     case*                parse-case*
     #_:else              ana/-parse)
   form env))

(declare parse)

(defn analyze
  "Analyzes a clojure form using tools.analyzer augmented with the JVM specific special ops
   and returns its AST, after running #'run-passes on it.

   If no configuration option is provides, analyze will setup tools.analyzer using the extension
   points declared in this namespace.

   If provided, opts should be a map of options to analyze, currently the only valid
   options are :bindings and :passes-opts (if not provided, :passes-opts defaults to the
   value of `default-passes-opts`).
   If provided, :bindings should be a map of Var->value pairs that will be merged into the
   default bindings for tools.analyzer, useful to provide custom extension points.
   If provided, :passes-opts should be a map of pass-name-kw->pass-config-map pairs that
   can be used to configure the behaviour of each pass.

   E.g.
   (analyze form env {:bindings  {#'ana/macroexpand-1 my-mexpand-1}})"
  ([form] (analyze form (taj/empty-env) {}))
  ([form env] (analyze form env {}))
  ([form env opts]
     (with-bindings (merge {Compiler/LOADER     (RT/makeClassLoader)
                            #'ana/macroexpand-1 macroexpand-1
                            #'ana/create-var    create-var
                            #'ana/scheduled-passes    scheduled-default-passes
                            #'ana/parse         parse
                            #'ana/var?          var?
                            #'ana/resolve-ns    resolve-ns
                            #'ana/resolve-sym   resolve-sym
                            #'*ns*              (the-ns (:ns env))}
                           (:bindings opts))
       (env/ensure (global-env)
         (env/with-env (u/mmerge (env/deref-env) {:passes-opts (get opts :passes-opts default-passes-opts)})
           (ana/run-passes (ana/unanalyzed form env)))))))

(deftype ExceptionThrown [e ast])

(defn ^:private throw! [e]
  (throw (.e ^ExceptionThrown e)))

(defmethod emit-form/-emit-form :unanalyzed
  [{:keys [form] :as ast} opts]
  (assert (not (#{:hygienic :qualified-symbols} opts))
          "Cannot support emit-form options on unanalyzed form")
  #_(throw (Exception. "Cannot emit :unanalyzed form"))
  #_(prn (str "WARNING: emit-form: did not analyze: " form))
  form)

(defn eval-ast [a {:keys [handle-evaluation-exception]
                   :or {handle-evaluation-exception throw!}
                   :as opts}]
  (let [frm (emit-form/emit-form a)
        ;_ (prn "frm" frm)
        result (try (eval frm) ;; eval the emitted form rather than directly the form to avoid double macroexpansion
                    (catch Exception e
                      (handle-evaluation-exception (ExceptionThrown. e a))))]
    (merge a {:result result})))

(defn analyze+eval
  "Like analyze but evals the form after the analysis and attaches the
   returned value in the :result field of the AST node.

   If evaluating the form will cause an exception to be thrown, the exception
   will be caught and wrapped in an ExceptionThrown object, containing the
   exception in the `e` field and the AST in the `ast` field.

   The ExceptionThrown object is then passed to `handle-evaluation-exception`,
   which by defaults throws the original exception, but can be used to provide
   a replacement return value for the evaluation of the AST.

   Unrolls `do` forms to handle the Gilardi scenario.

   Useful when analyzing whole files/namespaces."
  ([form] (analyze+eval form (taj/empty-env) {}))
  ([form env] (analyze+eval form env {}))
  ([form env {:keys [additional-gilardi-condition
                     eval-fn
                     annotate-do
                     statement-opts-fn
                     stop-gildardi-check
                     analyze-fn]
              :or {additional-gilardi-condition (fn [form env] true)
                   eval-fn eval-ast
                   annotate-do (fn [a _ _] a)
                   statement-opts-fn identity
                   stop-gildardi-check (fn [form env] false)
                   analyze-fn analyze}
              :as opts}]
     (env/ensure (global-env)
       (let [env (merge env (u/-source-info form env))
             [mform raw-forms] (with-bindings {Compiler/LOADER     (RT/makeClassLoader)
                                               #'*ns*              (the-ns (:ns env))
                                               #'ana/resolve-ns    resolve-ns
                                               #'ana/resolve-sym   resolve-sym
                                               #'ana/macroexpand-1 (get-in opts [:bindings #'ana/macroexpand-1] 
                                                                           macroexpand-1)}
                                 (loop [form form raw-forms []]
                                   (let [mform (if (stop-gildardi-check form env)
                                                 form
                                                 (ana/macroexpand-1 form env))]
                                     (if (= mform form)
                                       [mform (seq raw-forms)]
                                       (recur mform (conj raw-forms
                                                          (if-let [[op & r] (and (seq? form) form)]
                                                            (if (or (jana2-utils/macro? op  env)
                                                                    (jana2-utils/inline? op r env))
                                                              (vary-meta form assoc ::ana/resolved-op (ana/resolve-sym op env))
                                                              form)
                                                            form)))))))]
         (if (and (seq? mform) (= 'do (first mform)) (next mform)
                  (additional-gilardi-condition mform env))
           ;; handle the Gilardi scenario
           (let [[statements ret] (u/butlast+last (rest mform))
                 statements-expr (mapv (fn [s] (analyze+eval s (-> env
                                                                (u/ctx :ctx/statement)
                                                                (assoc :ns (ns-name *ns*)))
                                                            (statement-opts-fn opts)))
                                       statements)
                 ret-expr (analyze+eval ret (assoc env :ns (ns-name *ns*)) opts)]
             (annotate-do
               {:op         :do
                :top-level  true
                :form       mform
                :statements statements-expr
                :ret        ret-expr
                :children   [:statements :ret]
                :env        env
                :result     (:result ret-expr)
                :raw-forms  raw-forms}
               statements-expr
               ret-expr))
           (let [a (analyze-fn mform env opts)
                 e (eval-fn a (assoc opts :original-form mform))]
             (merge e {:raw-forms raw-forms})))))))
