;;   Copyright (c) Ambrose Bonnaire-Sergeant, Rich Hickey & contributors.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^:no-doc typed.clj.ext.clojure.core__defmacro
  "Typing rules clojure.core/defmacro"
  (:require [typed.cljc.checker.check-below :as below]
            [typed.cljc.checker.type-rep :as r]
            [typed.cljc.checker.utils :as u]
            [typed.cljc.checker.check.unanalyzed :refer [defuspecial]]))

;;======================
;; clojure.core/defmacro

(defuspecial defuspecial__defmacro
  "defuspecial implementation for clojure.core/defmacro"
  [expr expected]
  (assoc expr
         u/expr-type (below/maybe-check-below
                       (r/ret r/-any)
                       expected)))
