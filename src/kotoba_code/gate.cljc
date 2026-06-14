(ns kotoba-code.gate
  "The test gate + rollback — the safety net the prototype harness proved necessary:
  a run is only a success if the suite is green afterwards; otherwise the agent's
  edits are reverted so a broken tree is never left behind."
  (:require [kotoba-code.agent :as agent]))

(defn green?
  "Whether a test-runner output reports a fully green suite."
  [test-output]
  (boolean (and test-output (re-find #"0 failures,\s*0 errors" test-output))))

(defn run-gated
  "Run the agent on `task`, then enforce the gate against the host's own test run.
  Rolls back (`(:rollback host)`) on a RED gate AND on ANY thrown error during the
  agent run — a crashing agent loop (model error after retries, recursion-limit,
  etc.) must never leave partial edits behind (that leak bit the Phase 2c batch).
  Returns {:green? bool :test-out string :final state :answer string :error str?}."
  [agent-graph task host {:keys [session-id] :as opts}]
  (try
    (let [final    (agent/run-task agent-graph task (select-keys opts [:session-id]))
          test-out ((:run-tests host))
          ok       (green? test-out)]
      (when (and (not ok) (:rollback host)) ((:rollback host)))
      {:green?  ok
       :test-out test-out
       :final   final
       :answer  (agent/answer final)})
    (catch #?(:clj Throwable :cljs :default) e
      (when (:rollback host) ((:rollback host)))
      {:green?   false
       :error    (ex-message e)
       :test-out ""
       :final    nil
       :answer   nil})))
