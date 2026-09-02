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
  (let [namespaces '[glitter-uikit.scaffold-test
                     glitter-uikit.ffi-test
                     glitter-uikit.container-test
                     glitter-uikit.widget-test
                     glitter-uikit.appkit-test
                     glitter-uikit.controls-test]
        ;; A namespace that fails to REQUIRE used to be printed and then
        ;; forgotten. run-tests only ever sees what loaded, so its counters
        ;; cannot tell a namespace that does not exist from one that would not
        ;; compile, and the suite reported zero failures on half a suite.
        ;;
        ;; Not hypothetical. On jolt v0.7.29, whose ffi/write takes its last two
        ;; arguments the other way round, appkit-test and controls-test both
        ;; fail to load and this runner exited 0 on 19 of its 37 tests. CI was
        ;; green against a runtime the code cannot run on.
        broken (atom [])]
    (doseq [ns namespaces]
      (try (require ns :reload)
           (catch Exception e
             (swap! broken conj ns)
             (println "ERROR requiring" ns ":" (ex-message e)))))
    (let [loaded  (remove (set @broken) namespaces)
          ;; (apply t/run-tests '()) is (t/run-tests), which tests the CURRENT
          ;; namespace and reports a cheerful zero. Guard the empty case.
          results (if (seq loaded)
                    (apply t/run-tests loaded)
                    {:test 0 :pass 0 :fail 0 :error 0})
          failed  (+ (:fail results 0) (:error results 0) (count @broken))]
      (println "----")
      (when (seq @broken)
        (println "FAILED TO LOAD:" (count @broken) "of" (count namespaces)
                 "namespaces:" (pr-str @broken))
        (println "  a namespace that will not load is a failure, not an absence"))
      (println "tests:" (:test results 0)
               "assertions:" (:pass results 0) "passed /"
               failed "failed")
      (when (pos? failed) (exit 1)))))
