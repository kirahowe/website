(ns site.feed-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [site.content :as content]
            [site.feed :as feed])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- index-of [files]
  (let [dir (str (Files/createTempDirectory "feed-test" (into-array FileAttribute [])))
        config {:entry-types [:post] :content-path dir :base-url "https://example.com"
                :site-title "Test"}]
    (doseq [[path content] files]
      (let [f (io/file dir path)]
        (.mkdirs (.getParentFile f))
        (spit f content)))
    [config (content/build-index config)]))

(deftest item-timestamps
  (let [[config index] (index-of
                        {"2026/07/10/new.md" "---\npublished: 2026-07-10T21:30:05-03:00\n---\nN"
                         "2026/07/10/undated.md" "U"
                         ;; filed under an earlier day, but went out last
                         "2026/07/09/backdated.md" "---\npublished: 2026-07-11T08:00:00-03:00\n---\nB"})
        body (feed/atom-feed config index)]
    (testing "a stamped entry's item carries its publish instant, in UTC"
      (is (str/includes? body "<published>2026-07-11T00:30:05Z</published>")))
    (testing "an unstamped one keeps noon UTC on its date"
      (is (str/includes? body "<published>2026-07-10T12:00:00Z</published>")))
    (testing "the feed's updated is its latest item, not its first"
      (is (str/includes? body "<updated>2026-07-11T11:00:00Z</updated><author>")))))
