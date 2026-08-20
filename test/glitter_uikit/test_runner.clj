(ns glitter-uikit.test-runner
  "Entry point for `jolt -M:test`. Requires each glitter-uikit test namespace
  and runs clojure.test against it. Prints a summary; exits non-zero if
  anything failed (so the :test task fails CI). Adapted from glitter's
  test/glitter/test_runner.clj — identical shape, glitter-uikit's own list."
  (:require [clojure.test :as t]))

;; Surface full causes on :error — the default report swallows the throwable.
(defmethod t/report :error [m]
  (t/with-test-out
    (t/inc-report-counter :error)
    (println "\nERROR in" (t/testing-vars-str m))
    (when (seq t/*testing-contexts*) (println (t/testing-contexts-str)))
    (when-let [message (:message m)] (println message))
    (when-let [e (:actual m)]
      (if (instance? Throwable e)
        (do (println "  ->" (.getName (class e)) ":" (ex-message e))
            (when-let [d (ex-data e)] (prn d))
            (when-let [c (ex-cause e)]
              (println "  caused by:" (.getName (class c)) ":" (ex-message c))))
        (prn e)))))

(defn- exit
  "Terminate the process with `code`.

  Call System/exit DIRECTLY. `System/exit` is a static-method interop FORM, not
  a var, so `(resolve 'System/exit)` is ALWAYS nil — under Jolt and on the JVM
  alike. A cond guarded on that resolve never fires and silently falls through
  to nil, so the suite prints its failures and still exits 0. `jolt.host` ships
  no `exit` either. Carried verbatim from glitter's runner, where this was
  found and fixed."
  [code]
  (System/exit code))

(defn -main [& _]
  (let [namespaces '[glitter-uikit.scaffold-test]]
    (doseq [ns namespaces]
      (try (require ns :reload)
           (catch Exception e
             (println "ERROR requiring" ns ":" (ex-message e)))))
    (let [results (apply t/run-tests namespaces)
          failed (+ (:fail results 0) (:error results 0))]
      (println "----")
      (println "tests:" (:test results 0)
               "assertions:" (:pass results 0) "passed /"
               failed "failed")
      (when (pos? failed) (exit 1)))))
