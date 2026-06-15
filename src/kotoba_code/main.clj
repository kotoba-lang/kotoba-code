(ns kotoba-code.main
  "CLI entry — drive a coding task with kotoba-code.

    clojure -M:run \"<task>\" <project-root> [model-id]

  Model selection (model-neutral; pick the backend that fits):
    - OpenRouter (default): set OR_KEY; model-id e.g. moonshotai/kimi-k2.7-code
    - Murakumo gateway:     model-id starting with 'murakumo:' (no key; LiteLLM 127.0.0.1:4000)

  Optional kotoba-Datom session persistence (resumable, as-of):
    KOTOBA_URL + KOTOBA_GRAPH (+ KOTOBA_TOKEN) → checkpoints land on the kotoba node.
    KC_SESSION sets the session/thread id (default \"kotoba-code\")."
  (:require [clojure.string :as str]
            [kotoba-code.host :as host]
            [kotoba-code.agent :as agent]
            [kotoba-code.gate :as gate]
            [kotoba-code.resilience :as resilience]
            [langchain.model :as model]
            [langchain.kotoba-db :as kdb]
            [langgraph.checkpoint :as cp])
  (:gen-class))

(defn- build-model [model-id]
  (cond
    (str/starts-with? (or model-id "") "murakumo:")
    (model/openai-model (merge {:url "http://127.0.0.1:4000/v1/chat/completions"
                                :model (subs model-id (count "murakumo:"))
                                :max-tokens 8000 :http-fn host/http-fn}
                               host/json-caps))
    :else
    (model/openai-model (merge {:url "https://openrouter.ai/api/v1/chat/completions"
                                :model (or model-id "moonshotai/kimi-k2.7-code")
                                :api-key (System/getenv "OR_KEY")
                                :max-tokens 8000 :http-fn host/http-fn}
                               host/json-caps))))

(defn- kotoba-checkpointer
  "Builds a kotoba-Datom checkpointer from env, or nil if KOTOBA_URL is unset."
  []
  (when-let [url (System/getenv "KOTOBA_URL")]
    (let [graph (System/getenv "KOTOBA_GRAPH")
          token (System/getenv "KOTOBA_TOKEN")
          conn  (kdb/kotoba-conn url graph {:token token})]
      (cp/datomic-checkpointer conn {:db-api (kdb/kotoba-api host/http+json)}))))

(defn -main [& args]
  (let [[task root model-id] args]
    (when (or (nil? task) (nil? root))
      (println "usage: clojure -M:run \"<task>\" <project-root> [model-id]")
      (System/exit 2))
    (let [h     (host/fs-host root)
          model (resilience/retrying-model
                 (build-model model-id)
                 {:on-retry (fn [n e]
                              (println (format "  [retry %d] model call failed (%s); backing off…"
                                               n (.getMessage ^Throwable e))))})
          cpr   (kotoba-checkpointer)
          rlim  (some-> (System/getenv "KC_RECURSION_LIMIT") Integer/parseInt)
          a     (agent/build-agent (cond-> {:model model :host h :checkpointer cpr}
                                     rlim (assoc :recursion-limit rlim)))
          sess  (or (System/getenv "KC_SESSION") "kotoba-code")
          _     (println (str "── kotoba-code ── root=" root
                              " model=" (or model-id "moonshotai/kimi-k2.7-code")
                              (when cpr " kotoba-Datom=on") " session=" sess))
          gate-rounds (or (some-> (System/getenv "KC_GATE_ROUNDS") Integer/parseInt) 1)
          {:keys [green? test-out answer error rounds]} (gate/run-gated a task h {:session-id sess :rounds gate-rounds})]
      (println "\n── agent ──\n" (or answer ""))
      (when error (println "\n── error ──" error "(working tree rolled back)"))
      (println "\n── gate ──" (if green? "GREEN ✓" "NOT GREEN ✗ (working tree rolled back)")
               (when rounds (str "[" rounds "/" gate-rounds " rounds]")))
      (when (seq test-out) (println (subs test-out (max 0 (- (count test-out) 600)))))
      (System/exit (if green? 0 1)))))
