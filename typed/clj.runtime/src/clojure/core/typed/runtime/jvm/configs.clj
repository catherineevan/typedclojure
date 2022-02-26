;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

; mostly copied from clojure.core's data-reader discovery impl
(ns ^:no-doc clojure.core.typed.runtime.jvm.configs
  "Alpha - wip, subject to change"
  (:import [clojure.lang LineNumberingPushbackReader]
           [java.io InputStreamReader]
           [java.net URL]))

(defn- config-urls [features]
  (let [cl (.. Thread currentThread getContextClassLoader)]
    (concat
      (when (:clj features)
        (enumeration-seq (.getResources cl "typedclojure_config.clj")))
      (when (:cljs features)
        (enumeration-seq (.getResources cl "typedclojure_config.cljs")))
      (enumeration-seq (.getResources cl "typedclojure_config.cljc")))))

(defn- load-config-files [features ^URL url]
  (with-open [rdr (LineNumberingPushbackReader.
                    (InputStreamReader.
                      (.openStream url) "UTF-8"))]
    (binding [*file* (.getFile url)]
      (let [read-opts (if (.endsWith (.getPath url) "cljc")
                        {:eof nil :read-cond :allow :features features}
                        {:eof nil})
            new-config (read read-opts rdr)]
        (when (not (map? new-config))
          (throw (ex-info (str "Not a valid Typed Clojure config map")
                          {:url url})))
        new-config))))

(defn- load-configs [features]
  (reduce (fn [configs url]
            (conj configs (load-config-files features url)))
          #{} (config-urls features)))

(def *clj-configs
  (delay (load-configs #{:clj})))

(def *cljs-configs
  (delay (load-configs #{:cljs})))

(defn- register-config-anns [configs require-fn]
  (run! (fn [{:keys [ann]}]
          (run! require-fn ann))
        configs))

(defn- register-config-exts [configs require-fn]
  (run! (fn [{:keys [ext]}]
          (run! require-fn ext))
        configs))

(defn- clj-reloading-require [nsym]
  (require nsym :reload))

(defn register-clj-config-anns [] (register-config-anns @*clj-configs require))
(defn register-clj-config-exts [] (register-config-exts @*clj-configs clj-reloading-require))

(defn- cljs-require [nsym]
  ((requiring-resolve 'typed.cljs.checker.util/with-analyzer-bindings*)
   (fn []
     ((requiring-resolve 'typed.cljs.checker.util/with-cljs-typed-env*)
      #(do
         (requiring-resolve 'typed.cljs.checker.util/with-core-cljs*)
         ((requiring-resolve 'cljs.analyzer.api/analyze)
          ((requiring-resolve 'cljs.analyzer.api/empty-env))
          `(cljs.core/require '~nsym)))))))

(defn register-cljs-config-anns [] (register-config-anns @*cljs-configs cljs-require))
(defn register-cljs-config-exts [] (register-config-exts @*cljs-configs clj-reloading-require))
