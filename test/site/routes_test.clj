(ns site.routes-test
  (:require [clojure.test :refer [deftest is testing]]
            [site.routes :as routes]))

(def config {:entry-types [:post :note :link :quote :release :tool]})

(defn- match [uri]
  (routes/match-route config (routes/path-segments uri)))

(deftest date-routes
  (is (= {:handler :home} (match "/")))
  (is (= {:handler :year :params {:year 2026}} (match "/2026")))
  (is (= {:handler :month :params {:year 2026 :month 7}} (match "/2026/jul")))
  (testing "numeric and capitalized months are accepted"
    (is (= {:handler :month :params {:year 2026 :month 7}} (match "/2026/07")))
    (is (= {:handler :month :params {:year 2026 :month 7}} (match "/2026/Jul"))))
  (is (= {:handler :day :params {:year 2026 :month 7 :day 4}} (match "/2026/jul/4")))
  (testing "zero-padded day accepted"
    (is (= {:handler :day :params {:year 2026 :month 7 :day 4}} (match "/2026/jul/04"))))
  (is (= {:handler :entry :params {:year 2026 :month 7 :day 4 :slug "hello-world"}}
         (match "/2026/jul/4/hello-world")))
  (testing "trailing slashes don't matter"
    (is (= {:handler :year :params {:year 2026}} (match "/2026/")))))

(deftest type-routes
  (is (= {:handler :type-list :params {:type :post}} (match "/posts")))
  (is (= {:handler :type-list :params {:type :note}} (match "/notes")))
  (is (= {:handler :type-list :params {:type :quote}} (match "/quotes")))
  (is (= {:handler :type-list :params {:type :link :year 2026}} (match "/2026/links")))
  (is (= {:handler :type-list :params {:type :release}} (match "/releases")))
  (is (= {:handler :type-list :params {:type :tool}} (match "/tools")))
  (is (= {:handler :type-list :params {:type :post :page 2}}
         (match "/posts/page/2")))
  (is (= {:handler :type-list :params {:type :post :year 2026 :page 3}}
         (match "/2026/posts/page/3")))
  (testing "page 1 and non-canonical page numbers do not duplicate the index"
    (is (nil? (match "/posts/page/1")))
    (is (nil? (match "/posts/page/02"))))
  (testing "unknown plural falls through to page lookup"
    (is (= {:handler :page :params {:slug "essays"}} (match "/essays")))))

(deftest tag-routes
  (is (= {:handler :tags} (match "/tags")))
  (is (= {:handler :tag :params {:tag :clojure}} (match "/tags/clojure")))
  (is (= {:handler :tag :params {:tag :clojure :year 2026}} (match "/tags/clojure/2026")))
  (is (= {:handler :tag-feed :params {:tag :clojure}} (match "/tags/clojure/feed.xml"))))

(deftest other-routes
  (is (= {:handler :search} (match "/search")))
  (is (= {:handler :feed} (match "/feed.xml")))
  (is (= {:handler :page :params {:slug "about"}} (match "/about")))
  (is (= {:handler :follow} (match "/follow")))
  (is (= {:handler :draft :params {:name "an-idea-brewing"}} (match "/drafts/an-idea-brewing"))))

(deftest no-match
  (testing "malformed date-ish URLs are nil (404), not crashes"
    (is (nil? (match "/2026/notamonth")))
    (is (nil? (match "/2026/jul/99")))
    (is (nil? (match "/2026/jul/4/slug/extra")))
    (is (nil? (match "/tags/clojure/notayear"))))
  (testing "a five-digit segment isn't a year — it falls through to page lookup"
    (is (= {:handler :page :params {:slug "20261"}} (match "/20261")))))
