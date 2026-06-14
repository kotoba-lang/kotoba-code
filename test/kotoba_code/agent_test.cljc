(ns kotoba-code.agent-test
  "Proves the kotoba-code ReAct loop drives tools and the gate enforces green/rollback,
  using a deterministic mock model + an in-memory mock host (no network, no fs, no shell)."
  (:require [clojure.test :refer [deftest is testing]]
            [langchain.model :as model]
            [langchain.message :as msg]
            [kotoba-code.agent :as agent]
            [kotoba-code.gate :as gate]))

(defn- mock-host [{:keys [test-output]}]
  (let [calls (atom [])]
    {:calls calls
     :read-file   (fn [p]   (swap! calls conj [:read p])  "(ns x)")
     :write-file  (fn [p _] (swap! calls conj [:write p]) (str "written " p))
     :run-clojure (fn [f]   (swap! calls conj [:clj f])   "nil")
     :run-tests   (fn []    (swap! calls conj [:test])    test-output)
     :list-dir    (fn [p]   (swap! calls conj [:ls p])    "src/\ntest/")
     :search      (fn [pat] (swap! calls conj [:search pat]) "src/demo/math.clj:4: (defn add …)")
     :rollback    (fn []    (swap! calls conj [:rollback]) :rolled-back)}))

;; model that drives one tool call, then finishes once it has seen a tool result
(defn- one-tool-then-done [tool-name tool-input]
  (model/mock-model
   (fn [messages _opts]
     (if (some #(= :tool (:role %)) messages)
       (msg/ai "DONE")
       (msg/ai "" {:tool-calls [{:id "t1" :name tool-name :input tool-input}]})))))

(deftest react-loop-executes-a-tool-and-finishes
  (let [host  (mock-host {:test-output "Ran 1 tests containing 1 assertions.\n0 failures, 0 errors."})
        model (one-tool-then-done "run_tests" {})
        a     (agent/build-agent {:model model :host host})
        final (agent/run-task a "make the suite pass")
        roles (map :role (:messages final))]
    (testing "the loop actually executed a tool (a :tool message is present)"
      (is (some #(= :tool %) roles)))
    (testing "run_tests was invoked on the host"
      (is (some #(= [:test] %) @(:calls host))))
    (testing "the loop terminated with a final assistant answer"
      (is (re-find #"DONE" (agent/answer final))))))

(deftest gate-passes-when-suite-is-green
  (let [host  (mock-host {:test-output "Ran 3 tests containing 9 assertions.\n0 failures, 0 errors."})
        model (one-tool-then-done "run_tests" {})
        a     (agent/build-agent {:model model :host host})
        {:keys [green?]} (gate/run-gated a "task" host {})]
    (is (true? green?))
    (is (not (some #(= [:rollback] %) @(:calls host))) "no rollback on green")))

(deftest gate-rolls-back-when-suite-is-red
  (let [host  (mock-host {:test-output "Ran 2 tests containing 4 assertions.\n1 failures, 0 errors."})
        ;; model writes a (bad) file, then claims done
        model (one-tool-then-done "write_file" {:path "src/x.clj" :content "(ns x) bad"})
        a     (agent/build-agent {:model model :host host})
        {:keys [green?]} (gate/run-gated a "task" host {})]
    (is (false? green?))
    (is (some #(= [:rollback] %) @(:calls host)) "rollback fired on red")))

(deftest react-loop-can-drive-the-new-tools
  (let [host  (mock-host {:test-output "0 failures, 0 errors."})
        model (one-tool-then-done "list_dir" {:path "."})
        a     (agent/build-agent {:model model :host host})
        final (agent/run-task a "explore the project")]
    (is (some #(= :tool (:role %)) (:messages final)))
    (is (some #(= [:ls "."] %) @(:calls host)) "list_dir tool reached the host")))

(deftest green?-detects-suite-state
  (is (gate/green? "Ran 1 tests containing 1 assertions.\n0 failures, 0 errors."))
  (is (not (gate/green? "Ran 1 tests containing 1 assertions.\n1 failures, 0 errors.")))
  (is (not (gate/green? nil))))
