(ns kotoba-code.resilience-test
  (:require [clojure.test :refer [deftest is testing]]
            [langchain.model :as model]
            [langchain.message :as msg]
            [kotoba-code.resilience :as r]))

(defn- flaky-model
  "A ChatModel that throws on its first (fail-n) calls, then returns `ok-msg`."
  [fail-n ok-msg calls]
  (reify model/ChatModel
    (-generate [_ _messages _opts]
      (let [n (swap! calls inc)]
        (if (<= n fail-n)
          (throw (ex-info "OpenAI-compatible API error" {:status 503}))
          ok-msg)))))

(deftest retries-transient-errors-then-succeeds
  (let [calls (atom 0)
        m (r/retrying-model (flaky-model 2 (msg/ai "recovered") calls)
                            {:attempts 4 :backoff-ms 0})]
    (testing "two transient failures are absorbed; the third call succeeds"
      (is (= "recovered" (msg/text (model/-generate m [(msg/user "hi")] {}))))
      (is (= 3 @calls) "called 3 times (2 fail + 1 success)"))))

(deftest gives-up-after-attempts-and-rethrows
  (let [calls (atom 0)
        m (r/retrying-model (flaky-model 99 (msg/ai "never") calls)
                            {:attempts 3 :backoff-ms 0})]
    (testing "persistent failure rethrows after :attempts tries"
      (is (thrown? clojure.lang.ExceptionInfo
                   (model/-generate m [(msg/user "hi")] {})))
      (is (= 3 @calls) "tried exactly :attempts times"))))

(deftest passes-through-on-first-success
  (let [calls (atom 0)
        m (r/retrying-model (flaky-model 0 (msg/ai "ok") calls) {:backoff-ms 0})]
    (is (= "ok" (msg/text (model/-generate m [(msg/user "hi")] {}))))
    (is (= 1 @calls) "no retry when the first call succeeds")))
