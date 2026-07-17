(ns site.search-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [site.search :as search]))

(def index
  {:entries [{:title "Clojure workflows" :type :post :tags #{:clojure} :body "REPL all day"}
             {:title "Gardening" :type :link :tags #{:outdoors} :body "clojure appears once here"}
             {:title nil :type :post :tags #{} :body "nothing relevant"}]})

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

(deftest filters-and-facets
  (let [results (search/search index "clojure")]
    (testing "type filter"
      (is (= ["Clojure workflows"]
             (map :title (search/apply-filters results {:type :post})))))

    (testing "tag filter"
      (is (= ["Gardening"]
             (map :title (search/apply-filters results {:tag :outdoors})))))

    (testing "nil filters pass everything through"
      (is (= results (search/apply-filters results {}))))

    (testing "type counts sort by count; config nav order breaks ties"
      (is (= [[:post 1] [:link 1]]
             (search/type-counts {:entry-types [:post :link]} results)))
      (is (= [[:link 1] [:post 1]]
             (search/type-counts {:entry-types [:link :post]} results))))

    (testing "tag counts sort by count, then name"
      (is (= [[:clojure 1] [:outdoors 1]] (search/tag-counts results))))))

(deftest match-segments
  (testing "splits around case-insensitive hits"
    (is (= [{:text "Notes on " :hit? false}
            {:text "Datomic" :hit? true}
            {:text " in production" :hit? false}]
           (search/match-segments "Notes on Datomic in production" ["datomic"]))))

  (testing "adjacent terms each mark"
    (is (= [{:text "x" :hit? true} {:text "y" :hit? true}]
           (search/match-segments "xy" ["x" "y"]))))

  (testing "the longest term wins a shared start"
    (is (= [{:text "databases" :hit? true}]
           (search/match-segments "databases" ["data" "databases"]))))

  (testing "no hit → one plain segment; empty text → none"
    (is (= [{:text "abc" :hit? false}] (search/match-segments "abc" ["z"])))
    (is (= [] (search/match-segments "" ["z"])))))

(deftest snippets
  (let [far (str (apply str (repeat 40 "pad ")) "the needle sits here "
                 (apply str (repeat 40 "pad")))]
    (testing "the excerpt wins when a term already hits it"
      (is (= "has needle" (search/snippet "has needle" far ["needle"]))))

    (testing "a deep hit gets a window with ellipsised cuts"
      (let [s (search/snippet "no hit in the lede" far ["needle"])]
        (is (str/includes? s "needle"))
        (is (str/starts-with? s "… "))
        (is (str/ends-with? s " …"))))

    (testing "title/tag-only matches fall back to the excerpt"
      (is (= "plain excerpt"
             (search/snippet "plain excerpt" "nothing relevant" ["zebra"]))))))
