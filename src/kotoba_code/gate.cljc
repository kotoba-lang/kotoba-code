(ns kotoba-code.gate
  "The test gate + rollback — the safety net the prototype harness proved necessary:
  a run is only a success if the suite is green afterwards; otherwise the agent's
  edits are reverted so a broken tree is never left behind."
  (:require [kotoba-code.agent :as agent]))

(defn green?
  "Whether a test-runner output reports a fully green suite."
  [test-output]
  (boolean (and test-output (re-find #"0 failures,\s*0 errors" test-output))))

(defn- retry-task
  "Augment the task with the still-red test output so the next round can fix it.
  The agent's prior edits are still on disk, so it continues from there."
  [task round test-out]
  (str task
       "\n\n[gate round " round " FAILED — `clojure -X:test` is still RED.]\n"
       "Your previous edits are still on disk. Read them, diagnose the failures below,"
       " and fix until the suite is fully green.\n--- test output (tail) ---\n"
       (subs test-out (max 0 (- (count test-out) 1800)))))

(defn run-gated
  "Run the agent on `task`, then enforce the gate against the host's own test run.
  If the suite is RED and `:rounds` > 1, the failure is fed back to the agent and it
  continues (its edits persist on disk between rounds) — premature DONEs no longer
  throw away the work. Rolls back (`(:rollback host)`) only when still RED after the
  last round, AND on ANY thrown error during a run (a crash must never leak edits).
  Returns {:green? bool :test-out string :final state :answer string :rounds int :error str?}."
  [agent-graph task host {:keys [session-id rounds] :or {rounds 1} :as opts}]
  (try
    (loop [round 1
           cur   task]
      (let [final    (agent/run-task agent-graph cur (select-keys opts [:session-id]))
            test-out ((:run-tests host))
            ok       (green? test-out)]
        (cond
          ok
          {:green? true :test-out test-out :final final :answer (agent/answer final) :rounds round}

          (< round rounds)
          (recur (inc round) (retry-task task round test-out))

          :else
          (do (when (:rollback host) ((:rollback host)))
              {:green? false :test-out test-out :final final
               :answer (agent/answer final) :rounds round}))))
    (catch #?(:clj Throwable :cljs :default) e
      (when (:rollback host) ((:rollback host)))
      {:green?   false
       :error    (ex-message e)
       :test-out ""
       :final    nil
       :answer   nil})))
