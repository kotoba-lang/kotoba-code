(ns demo.math-test
  (:require [clojure.test :refer [deftest is]]
            [demo.math :as m]))

(deftest add-sums
  (is (= 5 (m/add 2 3)))
  (is (= 0 (m/add 0 0))))
