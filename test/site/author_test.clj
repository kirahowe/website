(ns site.author-test
  (:require [clojure.test :refer [deftest is testing]]
            [site.author :as author]))

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
