(ns kotoba-code.resilience
  "Resilience wrappers for the model seam. A single transient API error
  (rate limit, 5xx, network blip) should not kill a whole agent run — the
  batch on 2026-06-14 lost segment 26 to exactly that. `retrying-model`
  wraps any ChatModel so -generate retries with linear backoff."
  (:require [langchain.model :as model]))

(defn retrying-model
  "Wrap a ChatModel so -generate retries on exception (transient API errors).
   opts:
     :attempts   total tries before giving up (default 4)
     :backoff-ms base backoff; attempt n sleeps (* backoff-ms n) (default 1500)
     :on-retry   (fn [attempt ^Throwable e]) side-effect hook (e.g. logging)"
  ([m] (retrying-model m {}))
  ([m {:keys [attempts backoff-ms on-retry] :or {attempts 4 backoff-ms 1500}}]
   (reify model/ChatModel
     (-generate [_ messages opts]
       (loop [n 1]
         (let [r (try {:ok (model/-generate m messages opts)}
                      (catch Throwable e {:err e}))]
           (if (contains? r :ok)
             (:ok r)
             (if (< n attempts)
               (do (when on-retry (on-retry n (:err r)))
                   (when (pos? backoff-ms) (Thread/sleep (* (long backoff-ms) n)))
                   (recur (inc n)))
               (throw (:err r))))))))))
