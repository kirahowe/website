(ns site.author-test
  (:require [clojure.test :refer [deftest is testing]]
            [site.author :as author])
  (:import [java.time LocalDate]))

(deftest strip-publish-property
  (testing "removes the publish line from YAML frontmatter"
    (is (= "---\ntags: []\ndate: 2026-07-15\n---\n\nBody\n"
           (author/strip-publish-property
            "---\ntags: []\npublish: false\ndate: 2026-07-15\n---\n\nBody\n"))))

  (testing "leaves other properties and the body untouched"
    (let [raw "---\ntype: link\nlink: https://example.com\npublish: true\ntags:\n  - a\n---\n\nSome body text.\n"
          stripped (author/strip-publish-property raw)]
      (is (not (re-find #"publish" stripped)))
      (is (re-find #"type: link" stripped))
      (is (re-find #"link: https://example\.com" stripped))
      (is (re-find #"tags:\n  - a" stripped))
      (is (re-find #"Some body text\.\n" stripped))))

  (testing "no frontmatter at all is returned unchanged"
    (is (= "just a bare draft\npublish: true\n"
           (author/strip-publish-property "just a bare draft\npublish: true\n"))))

  (testing "EDN frontmatter is returned unchanged"
    (is (= ";;;\n{:type :post :publish true}\n;;;\nbody"
           (author/strip-publish-property ";;;\n{:type :post :publish true}\n;;;\nbody"))))

  (testing "a stray publish: line in the body (after the closing ---) is not removed"
    (let [raw "---\ntags: []\n---\n\nHow to publish: write, then flip the flag.\n"]
      (is (= raw (author/strip-publish-property raw)))))

  (testing "no trailing newline is preserved as-is"
    (is (= "---\ntags: []\n---\nBody, no trailing newline"
           (author/strip-publish-property
            "---\ntags: []\npublish: false\n---\nBody, no trailing newline")))))

(deftest draft-sort-key
  (testing "queued drafts sort before non-queued ones"
    (is (< (compare (#'author/draft-sort-key {:publish true :date nil :base "b"})
                    (#'author/draft-sort-key {:publish false :date nil :base "a"}))
           0)))

  (testing "within a group, earlier authored dates sort first"
    (let [earlier (#'author/draft-sort-key {:publish true :date (LocalDate/of 2026 1 1) :base "a"})
          later (#'author/draft-sort-key {:publish true :date (LocalDate/of 2026 7 15) :base "b"})]
      (is (< (compare earlier later) 0))))

  (testing "no date sorts after any date, within a group"
    (let [dated (#'author/draft-sort-key {:publish false :date (LocalDate/of 2026 1 1) :base "a"})
          undated (#'author/draft-sort-key {:publish false :date nil :base "a"})]
      (is (< (compare dated undated) 0))))

  (testing "ties break alphabetically by filename"
    (let [a (#'author/draft-sort-key {:publish false :date nil :base "a draft"})
          z (#'author/draft-sort-key {:publish false :date nil :base "z draft"})]
      (is (< (compare a z) 0)))))

(deftest draft-line
  (testing "queued, dated draft"
    (is (= "[queued] 2026-07-15  link  My great idea"
           (#'author/draft-line {:base "My great idea" :date (LocalDate/of 2026 7 15)
                                  :publish true :type :link}))))

  (testing "non-queued draft with no date"
    (is (= "         no date     post  Another draft"
           (#'author/draft-line {:base "Another draft" :date nil
                                  :publish false :type :post}))))

  (testing "a draft with unparseable workflow properties shows the error, not a crash"
    (is (= "         ERROR: Unparseable date property in x.md: \"July 15, 2026\"  Bad draft"
           (#'author/draft-line {:base "Bad draft"
                                  :error "Unparseable date property in x.md: \"July 15, 2026\""})))))
