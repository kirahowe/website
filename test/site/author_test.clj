(ns site.author-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [site.author :as author]
            [site.content :as content])
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

(deftest publish-refuses-url-collisions
  ;; "Hello World" is already published; "Hello world" is a different
  ;; filename that slugifies to the same slug, so publishing it on the
  ;; same date would silently collide on the URL. It must throw (the
  ;; queue flush turns that into a warn-and-skip), and the draft must
  ;; stay where it was.
  (let [root (fs/create-temp-dir)
        cfg {:content-path (str root) :entry-types [:post]}
        draft (fs/path root "drafts" "Hello world.md")]
    (try
      (fs/create-dirs (fs/path root "2026" "07" "04"))
      (spit (str (fs/path root "2026" "07" "04" "Hello World.md"))
            "---\ntags: [test]\n---\n\nAlready live.\n")
      (fs/create-dirs (fs/parent draft))
      (spit (str draft)
            "---\ndate: 2026-07-04\ntags: [test]\npublish: true\n---\n\nWould collide.\n")
      (let [index (content/build-index cfg)]
        (is (thrown-with-msg? Exception #"URL collision: /2026/jul/4/hello-world"
                              (#'author/publish-draft! cfg index draft)))
        (is (fs/exists? draft)))
      (finally
        (fs/delete-tree root)))))

(deftest target-url-derivation
  (testing "the URL is the authored date plus the filename slug"
    (is (= "/2026/jul/4/my-post"
           (#'author/target-url {:base "My Post" :date (LocalDate/of 2026 7 4) :meta {}}))))
  (testing "a slug property pins the URL instead"
    (is (= "/2026/jul/4/legacy-path"
           (#'author/target-url {:base "My Post" :date (LocalDate/of 2026 7 4)
                                 :meta {:slug "legacy-path"}})))))

(deftest publish-failure-detection
  (let [root (fs/create-temp-dir)
        cfg {:content-path (str root) :entry-types [:post]}]
    (try
      (fs/create-dirs (fs/path root "2026" "07" "04"))
      (spit (str (fs/path root "2026" "07" "04" "Taken.md"))
            "---\ntags: [x]\n---\n\nLive.\n")
      (let [index (content/build-index cfg)]
        (testing "a queued draft whose URL is already published is a collision"
          (is (= :url-collision
                 (#'author/publish-failure
                  index {:base "taken" :date (LocalDate/of 2026 7 4) :meta {} :publish true}))))
        (testing "a queued draft with a free URL has no failure"
          (is (nil? (#'author/publish-failure
                     index {:base "fresh" :date (LocalDate/of 2026 7 4) :meta {} :publish true}))))
        (testing "unparseable workflow properties are a broken-properties failure"
          (is (= :broken-workflow-properties
                 (#'author/publish-failure
                  index {:base "oops" :error "Unparseable date property" :publish true})))))
      (finally
        (fs/delete-tree root)))))

(deftest broken-draft-still-reports-queued
  ;; A queued draft with a garbled date must still be recognisable as
  ;; queued, so an unattended flush can notify about it rather than
  ;; silently skip it — the date and the publish toggle fail independently.
  (let [root (fs/create-temp-dir)]
    (try
      (fs/create-dirs (fs/path root "drafts"))
      (spit (str (fs/path root "drafts" "Bad.md"))
            "---\ndate: July 15 2026\npublish: true\ntags: []\n---\n\nBody\n")
      (let [status (first (#'author/draft-status root))]
        (is (:error status))            ; the date failed to parse
        (is (true? (:publish status)))) ; ...but we still know it was queued
      (finally
        (fs/delete-tree root)))))

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
                                  :error "Unparseable date property in x.md: \"July 15, 2026\""}))))

  (testing "a queued draft that would collide names the URL already taken"
    (is (= "[queued] 2026-07-04  post  Dupe  ⚠ URL already taken: /2026/jul/4/dupe"
           (#'author/draft-line {:base "Dupe" :date (LocalDate/of 2026 7 4)
                                  :publish true :type :post
                                  :collides-with "/2026/jul/4/dupe"})))))

(deftest parse-tags
  (let [parse #'author/parse-tags]
    (testing "clean one-per-line reply: markers, numbering, quoting, case handled"
      (is (= ["data-engineering" "llms" "clojure" "workflow" "tools"]
             (parse "data-engineering\nLLMs\n- clojure\n1. workflow\n`tools`"))))

    (testing "the model's prose is dropped, real tags survive"
      (is (= ["open-source" "llms" "kernel-development"]
             (parse (str "Data engineering? No, this is about open source.\n"
                         "Let me just give tags:\n"
                         "open-source\nllms\nkernel-development")))))

    (testing "duplicates collapse and the list is capped at ten"
      (is (= ["a" "b"] (parse "a\nA\nb\na")))
      (is (= 10 (count (parse (str/join "\n" (map #(str "t" %) (range 15))))))))))

(deftest set-tags
  (let [set-tags #'author/set-tags]
    (testing "an empty inline tags: [] becomes a block list, body untouched"
      (is (= "---\ndate: 2026-07-19\ntags:\n  - llms\n  - open-source\npublish: false\n---\n\nBody stays put.\n"
             (set-tags "---\ndate: 2026-07-19\ntags: []\npublish: false\n---\n\nBody stays put.\n"
                       ["llms" "open-source"]))))

    (testing "an existing block list is replaced; a tags: line in the body is left alone"
      (let [out (set-tags "---\ntype: link\ntags:\n  - old\npublish: false\n---\n\ntags: not-frontmatter\n"
                          ["old" "new"])]
        (is (re-find #"tags:\n  - old\n  - new\n" out))
        (is (re-find #"\ntags: not-frontmatter\n" out))))

    (testing "no tags property present: one is appended to the header"
      (is (re-find #"publish: false\ntags:\n  - added\n---"
                   (set-tags "---\ndate: 2026-07-19\npublish: false\n---\n\nBody\n" ["added"]))))

    (testing "a file with no YAML frontmatter to edit returns nil"
      (is (nil? (set-tags ";;;\n{:type :post :tags []}\n;;;\n\nBody\n" ["x"])))
      (is (nil? (set-tags "just a bare body\n" ["x"]))))))

(deftest set-slug
  (let [set-slug #'author/set-slug]
    (testing "a draft with no slug gets one appended, body untouched"
      (is (= "---\ndate: 2026-07-19\ntags: []\npublish: false\nslug: why-clojure\n---\n\nBody.\n"
             (set-slug "---\ndate: 2026-07-19\ntags: []\npublish: false\n---\n\nBody.\n"
                       "why-clojure"))))

    (testing "an existing slug line is replaced; a slug: line in the body is left alone"
      (let [out (set-slug "---\ntype: post\nslug: old-one\npublish: false\n---\n\nslug: not-frontmatter\n"
                          "new-one")]
        (is (re-find #"(?m)^slug: new-one$" out))
        (is (not (re-find #"old-one" out)))
        (is (re-find #"\nslug: not-frontmatter\n" out))))

    (testing "a file with no YAML frontmatter to edit returns nil"
      (is (nil? (set-slug ";;;\n{:type :post}\n;;;\n\nBody\n" "x")))
      (is (nil? (set-slug "just a bare body\n" "x"))))))

(deftest parse-phrases
  (let [parse #'author/parse-phrases]
    (testing "keeps readable casing and strips list markers / quoting"
      (is (= ["Simplicity is a choice" "A clear idea"]
             (parse "- Simplicity is a choice\n2. \"A clear idea\""))))
    (testing "drops prose (a colon line) and over-long lines"
      (is (= ["Short and sweet"]
             (parse "Here are some options:\nShort and sweet\nThis line is far too long to be a good title phrase"))))))

(deftest sanitize-note-title
  (let [f #'author/sanitize-note-title]
    (testing "drops filesystem/Obsidian-forbidden characters and collapses whitespace"
      (is (= "Simplicity a choice" (f "Simplicity: a choice")))
      (is (= "AB testing" (f "A/B  testing")))
      (is (= "Wildcard" (f "Wild*card?"))))))

(deftest default-quote-name?
  (let [f #'author/default-quote-name?]
    (testing "the bb-new-quote scaffold name is a default; an authored name is not"
      (is (true? (f "quote 2026-07-21")))
      (is (false? (f "Simplicity is a choice")))
      (is (false? (f "quote about something"))))))

(deftest slug-collision-fn
  (let [slug-collision-fn #'author/slug-collision-fn
        jul19 (LocalDate/of 2026 7 19)]
    (with-redefs [content/build-index
                  (fn [_] {:by-path {"/2026/jul/19/taken" {:path "/2026/jul/19/taken"}}})]
      (let [collides (slug-collision-fn {} jul19)]
        (testing "a slug whose date+slug URL is taken returns the clashing entry"
          (is (= "/2026/jul/19/taken" (:path (collides "taken")))))
        (testing "a free slug is nil"
          (is (nil? (collides "free"))))
        (testing "the same slug on a different date does not collide (URL differs)"
          (is (nil? ((slug-collision-fn {} (LocalDate/of 2025 1 1)) "taken"))))))

    (testing "best-effort: a content index that won't build treats nothing as taken"
      (with-redefs [content/build-index (fn [_] (throw (ex-info "boom" {})))]
        (is (nil? ((slug-collision-fn {} jul19) "anything")))))))
