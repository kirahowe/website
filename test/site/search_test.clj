(ns site.search-test
  (:require [clojure.test :refer [deftest is testing]]
            [site.search :as search]))

(def index
  {:entries [{:title "Clojure workflows" :tags #{:clojure} :body "REPL all day"}
             {:title "Gardening" :tags #{:outdoors} :body "clojure appears once here"}
             {:title nil :tags #{} :body "nothing relevant"}]})

(deftest search-behavior
  (testing "title matches outrank body matches"
    (let [results (search/search index "clojure")]
      (is (= 2 (count results)))
      (is (= "Clojure workflows" (:title (first results))))))

  (testing "every term must match (AND semantics)"
    (is (= 1 (count (search/search index "clojure repl"))))
    (is (empty? (search/search index "clojure zebra"))))

  (testing "case-insensitive"
    (is (= 2 (count (search/search index "CLOJURE")))))

  (testing "blank query is empty, not everything"
    (is (empty? (search/search index "")))
    (is (empty? (search/search index "   ")))
    (is (empty? (search/search index nil)))))
