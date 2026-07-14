(ns site.content-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [site.content :as content])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def config
  {:entry-types [:post :note :link :quote :release :tool]
   :content-path "example-content"})

(deftest frontmatter-parsing
  (testing "valid frontmatter"
    (let [{:keys [meta body]} (content/parse-frontmatter
                               ";;;\n{:type :note\n :tags [:a]}\n;;;\n\nHello *world*\n"
                               "test.md")]
      (is (= :note (:type meta)))
      (is (= [:a] (:tags meta)))
      (is (= "Hello *world*" body))))

  (testing "body may contain the delimiter"
    (let [{:keys [body]} (content/parse-frontmatter
                          ";;;\n{:type :note}\n;;;\nbefore\n;;;\nafter"
                          "test.md")]
      (is (= "before\n;;;\nafter" body))))

  (testing "no frontmatter at all is a bare note: empty meta, body intact"
    (let [{:keys [meta body]} (content/parse-frontmatter "just text" "test.md")]
      (is (= {} meta))
      (is (= "just text" body))))

  (testing "unterminated frontmatter fails loudly"
    (is (thrown-with-msg? Exception #"Unterminated"
                          (content/parse-frontmatter ";;;\n{:type :note}\nno end" "test.md"))))

  (testing "invalid EDN fails loudly"
    (is (thrown-with-msg? Exception #"Invalid EDN"
                          (content/parse-frontmatter ";;;\n{:type\n;;;\nbody" "test.md"))))

  (testing "non-map frontmatter fails loudly"
    (is (thrown-with-msg? Exception #"must be an EDN map"
                          (content/parse-frontmatter ";;;\n:just-a-keyword\n;;;\nbody" "test.md")))))

(deftest yaml-frontmatter
  (testing "Obsidian property names map onto the entry model"
    (let [{:keys [meta body]} (content/parse-frontmatter
                               (str "---\n"
                                    "type: link\n"
                                    "link: https://example.com\n"
                                    "via: https://news.example.com\n"
                                    "tags:\n  - clojure\n  - web\n"
                                    "date: 2025-01-09\n"
                                    "---\n\nBody here\n")
                               "test.md")]
      (is (= :link (:type meta)))
      (is (= "https://example.com" (:link-url meta)))
      (is (= "https://news.example.com" (:link-via meta)))
      (is (= ["clojure" "web"] (vec (:tags meta))))
      (is (nil? (:date meta)) "the path is the date; a date property is workflow noise")
      (is (= "Body here" body))))

  (testing "quotes: author → :source, source → :source-url"
    (let [{:keys [meta]} (content/parse-frontmatter
                          "---\ntype: quote\nauthor: Ada Lovelace\nsource: https://example.com/notes\n---\nQ"
                          "test.md")]
      (is (= "Ada Lovelace" (:source meta)))
      (is (= "https://example.com/notes" (:source-url meta)))))

  (testing "blank properties are treated as absent"
    (let [{:keys [meta]} (content/parse-frontmatter
                          "---\ntype: link\nlink: https://example.com\nvia: \ntags:\n---\nB"
                          "test.md")]
      (is (nil? (:link-via meta)))
      (is (nil? (:tags meta)))))

  (testing "a single-string tag still becomes a collection"
    (let [{:keys [meta]} (content/parse-frontmatter
                          "---\ntags: solo\n---\nB"
                          "test.md")]
      (is (= ["solo"] (vec (:tags meta)))))))

(deftest type-validation
  (testing "typo'd type cannot silently coin a new type"
    (is (thrown-with-msg? Exception #"Unknown entry type"
                          (content/check-type! config {:type :postt} "x.md"))))
  (testing "missing type defaults to :post — a bare note is a post"
    (is (= :post (content/check-type! config {} "x.md"))))
  (testing "valid type passes"
    (is (= :quote (content/check-type! config {:type :quote} "x.md")))))

(deftest index-building
  (let [index (content/build-index config)]
    (testing "all example entries load, newest first"
      (is (= 8 (count (:entries index))))
      (is (= "/2026/jul/4/nextjournal-markdown" (:path (first (:entries index)))))
      (is (apply >= (map #(-> % :date :year) (:entries index)))))

    (testing "date comes from the path"
      (let [e (get (:by-path index) "/2026/jul/4/hello-world")]
        (is (= {:year 2026 :month 7 :day 4} (:date e)))
        (is (= :post (:type e)))))

    (testing "slug override via frontmatter"
      (is (some? (get (:by-path index) "/2025/nov/12/repl-driven")))
      (is (nil? (get (:by-path index) "/2025/nov/12/repl-driven-development"))))

    (testing "same-day entries both load"
      (is (= 2 (count (get (:by-day index) [2026 7 4])))))

    (testing "type and tag groupings"
      (is (= 2 (count (get (:by-type index) :post))))
      (is (= 2 (count (get (:by-type index) :link))))
      (is (= 1 (count (get (:by-type index) :quote))))
      (is (= 1 (count (get (:by-type index) :note))))
      (is (= 1 (count (get (:by-type index) :release))))
      (is (= 1 (count (get (:by-type index) :tool))))
      (is (= 5 (count (get (:by-tag index) :clojure)))))

    (testing "tags are normalized to keywords"
      (is (every? keyword? (mapcat :tags (:entries index)))))

    (testing "neighbors are linked newest-first"
      (let [newest (first (:entries index))
            oldest (peek (:entries index))]
        (is (nil? (:newer newest)))
        (is (some? (:older newest)))
        (is (nil? (:older oldest)))
        (is (some? (:newer oldest)))))

    (testing "drafts load separately and never join entries"
      (is (= 1 (count (:drafts index))))
      (is (:draft? (get (:drafts index) "an-idea-brewing")))
      (is (not-any? :draft? (:entries index))))

    (testing "pages load"
      (is (= "About" (:title (get (:pages index) "about")))))))

(deftest obsidian-native-entries
  (let [dir (str (Files/createTempDirectory "content-test" (into-array FileAttribute [])))
        day (io/file dir "2026" "07" "10")]
    (.mkdirs day)
    (spit (io/file day "My Great Idea.md") "---\ntags:\n  - clojure\n---\nSee [[Everything fails]] for more.")
    (spit (io/file day "Everything fails.md") "---\ntype: quote\nauthor: W. Vogels\n---\nEverything fails, all the time.")
    (let [index (content/build-index (assoc config :content-path dir))
          post (get (:by-path index) "/2026/jul/10/my-great-idea")
          quote (get (:by-path index) "/2026/jul/10/everything-fails")]
      (testing "filename is the title; slug is slugified from it"
        (is (= "My Great Idea" (:title post)))
        (is (= :post (:type post)) "no type property means post"))
      (testing "quotes stay untitled unless a title property is set"
        (is (nil? (:title quote)))
        (is (= "W. Vogels" (:source quote))))
      (testing "entries carry the wikilink target map"
        (is (= "/2026/jul/10/everything-fails"
               (get (:wikilinks post) "everything fails")))))))

(deftest broken-content
  (let [dir (str (Files/createTempDirectory "content-test" (into-array FileAttribute [])))]
    (testing "unknown type in a dated entry fails indexing"
      (let [f (io/file dir "2026" "01" "05")]
        (.mkdirs f)
        (spit (io/file f "bad.md") ";;;\n{:type :essay}\n;;;\nbody")
        (is (thrown-with-msg? Exception #"Unknown entry type"
                              (content/build-index (assoc config :content-path dir))))))))
