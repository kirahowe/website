(ns site.views-test
  "End-to-end: exercises the whole Ring app as a plain function against
  example-content — routing, content loading, markdown rendering, views."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [site.app :as app]
            [site.content :as content]))

(def config
  {:site-title "Test Site"
   :site-description "Testing"
   :base-url "https://example.com"
   :content-path "example-content"
   :entry-types [:post :link :quote :release :tool]})

(def handler
  (app/make-app config (atom (content/build-index config))))

(def dev-handler
  (app/make-app (assoc config :dev? true)
                (atom (content/build-index config))))

(defn- GET
  ([uri] (GET uri nil))
  ([uri query] (handler {:request-method :get :uri uri :query-string query})))

(deftest pages-render
  (testing "home is a day-grouped feed; posts preview, short types are full"
    (let [{:keys [status body headers]} (GET "/")]
      (is (= 200 status))
      (is (str/includes? (get headers "Cache-Control") "public"))
      (is (str/includes? body "Hello, world"))
      (is (str/includes? body "nextjournal/markdown"))
      (is (str/includes? body "Rich Hickey"))
      ;; all 8 example entries fit within :home-entries, so the oldest shows
      (is (str/includes? body "Babashka"))
      ;; feed previews are the first paragraph only, as plain-text excerpts
      (is (not (str/includes? body "where code sleeps")))
      ;; ...but quotes are short-form: the whole body publishes to the feed
      (is (str/includes? body "prerequisite for reliability"))
      ;; ...as do links (and releases/tools): a pull-quote in a link body
      ;; survives into the feed as a blockquote, not dropped by the excerpt
      (is (str/includes? body "a lousy data structure"))
      (is (str/includes? body "min read"))                     ; reading-time hint
      (is (str/includes? body "Untitled entries are fine"))    ; untitled post excerpt shows
      ;; day headings link to the day archives
      (is (str/includes? body "July 4, 2026"))
      (is (str/includes? body "\"/2026/jul/4\""))))

  (testing "single entry page renders markdown"
    (let [{:keys [status body]} (GET "/2026/jul/4/hello-world")]
      (is (= 200 status))
      (is (str/includes? body "How it works</h2>"))  ; ## heading
      (is (str/includes? body "<code"))              ; inline code
      (is (str/includes? body "entry-url")))
    (let [{:keys [body]} (GET "/2025/nov/12/repl-driven")]
      (is (str/includes? body "where code sleeps"))       ; full body lives on the entry page
      (is (not (str/includes? body "words]")))))          ; no preview link there

  (testing "non-canonical entry URL redirects to canonical"
    (let [{:keys [status headers]} (GET "/2026/07/04/hello-world")]
      (is (= 301 status))
      (is (= "/2026/jul/4/hello-world" (get headers "Location")))))

  (testing "archives"
    (is (= 200 (:status (GET "/2026"))))
    (is (= 200 (:status (GET "/2026/jul"))))
    (is (= 200 (:status (GET "/2026/jul/4"))))
    (is (str/includes? (:body (GET "/2026/jul/4")) "Hello, world"))
    (is (= 404 (:status (GET "/2024")))))

  (testing "month pages link to the neighboring months that have content"
    (let [{:keys [body]} (GET "/2026/jun")]
      (is (str/includes? body "\"/2026/jul\""))     ; newer neighbor
      (is (str/includes? body "\"/2026/may\"")))    ; older neighbor
    (let [{:keys [body]} (GET "/2026/may")]
      (is (str/includes? body "\"/2026/apr\"")))    ; older neighbor (new release entry)
    (let [{:keys [body]} (GET "/2026/mar")]
      (is (str/includes? body "\"/2025/nov\""))))   ; skips empty months, crosses years

  (testing "type and tag listings"
    (is (str/includes? (:body (GET "/posts")) "REPL-driven"))
    (is (str/includes? (:body (GET "/quotes")) "Simplicity"))
    (is (str/includes? (:body (GET "/tags/clojure")) "Babashka"))
    (is (= 200 (:status (GET "/2026/posts"))))
    (is (= 404 (:status (GET "/2024/posts"))))
    (is (str/includes? (:body (GET "/releases")) "website v1.0"))
    (is (str/includes? (:body (GET "/tools")) "vault-publish")))

  (testing "the per-type count summary singularizes a lone entry"
    ;; June has exactly one entry (a quote), so the month summary must
    ;; read "1 quote", not "1 quotes".
    (let [{:keys [body]} (GET "/2026/jun")]
      (is (str/includes? body ">quote</a>"))
      (is (not (str/includes? body ">quotes</a>")))))

  (testing "quote renders with attribution, full body, and one via credit"
    (let [{:keys [body]} (GET "/2026/jun/21/rich-hickey-on-simplicity")]
      ;; a quote-type entry carries the .quote class (its hanging serif marks);
      ;; other blockquotes get the plain left-border style
      (is (str/includes? body "<blockquote class=\"quote\">"))
      (is (str/includes? body "Rich Hickey"))
      (is (str/includes? body "prerequisite for reliability"))
      ;; the via credit sits on the cite line — once, even on a titled quote
      (is (str/includes? body "news.ycombinator.com"))
      (is (= 1 (count (re-seq #"class=\"via\"" body))))))

  (testing "a quote's via credit reaches the feed too"
    (let [{:keys [body]} (GET "/quotes")]
      (is (str/includes? body "news.ycombinator.com"))))

  (testing "link entry title points at the external URL"
    (let [{:keys [body]} (GET "/2025/aug/30/babashka")]
      (is (str/includes? body "https://babashka.org"))
      (is (str/includes? body "via"))))

  (testing "release and tool titles link out too"
    (is (str/includes? (:body (GET "/2026/apr/18/website-v1")) "releases/tag/v1.0"))
    (is (str/includes? (:body (GET "/2026/mar/7/vault-publish")) "github.com/kirahowe/vault-publish")))

  (testing "static page"
    (let [{:keys [status body]} (GET "/about")]
      (is (= 200 status))
      (is (str/includes? body "personal weblog"))))

  (testing "search: relevance order, highlights, facet rails"
    (let [{:keys [status body headers]} (GET "/search" "q=babashka")]
      (is (= 200 status))
      (is (= "no-store" (get headers "Cache-Control")))
      (is (str/includes? body "sorted by relevance"))
      ;; matched terms are marked, in titles and snippets
      (is (str/includes? body "<mark class=\"hit\">Babashka</mark>"))
      ;; results are the same cards the feed uses, one day group per hit
      (is (str/includes? body "class=\"day-group\""))
      (is (str/includes? body "min read]"))                ; post continuation link
      ;; the title match ranks above the body-only matches
      (is (< (str/index-of body "babashka.org")
             (str/index-of body "/2026/jul/4/hello-world")))
      ;; both rails render, with filter links carrying the query
      (is (str/includes? body "Filter by type"))
      (is (str/includes? body "Refine"))
      (is (str/includes? body "/search?q=babashka&amp;type=link"))
      (is (str/includes? body "/search?q=babashka&amp;tag=clojure"))))

  (testing "search: a deep body match shows an ellipsised snippet"
    (let [{:keys [body]} (GET "/search" "q=pipeline")]
      (is (str/includes? body "<mark class=\"hit\">pipeline</mark>"))
      (is (str/includes? body "… "))))

  (testing "search: type and tag filters narrow the results"
    (let [{:keys [body]} (GET "/search" "q=babashka&type=link")]
      (is (str/includes? body "babashka.org"))                       ; the link stays
      (is (not (str/includes? body "/2026/jul/4/hello-world")))      ; the post is filtered out
      (is (str/includes? body "Found <b>2 links</b>")))
    (let [{:keys [body]} (GET "/search" "q=babashka&tag=libraries")]
      (is (str/includes? body "nextjournal/markdown"))
      (is (not (str/includes? body "babashka.org"))))
    ;; an unknown type is ignored, not an error
    (is (= 200 (:status (GET "/search" "q=babashka&type=zebra")))))

  (testing "search: blank prompt and empty result states"
    (is (str/includes? (:body (GET "/search")) "Searches titles, tags"))
    (is (str/includes? (:body (GET "/search" "q=xyzzy")) "Nothing found")))

  (testing "feed"
    (let [{:keys [status body headers]} (GET "/feed.xml")]
      (is (= 200 status))
      (is (str/includes? (get headers "Content-Type") "rss"))
      (is (str/includes? body "<rss"))
      (is (str/includes? body "Hello, world"))))

  (testing "404"
    (is (= 404 (:status (GET "/nope"))))
    (is (= 404 (:status (GET "/2026/jul/4/nope"))))))

(deftest drafts-are-dev-only
  (testing "production server: drafts don't exist"
    (is (= 404 (:status (GET "/drafts/an-idea-brewing"))))
    (is (= 404 (:status (GET "/drafts/an-idea-brewing" "preview=anything")))))

  (testing "dev mode: drafts render, uncached"
    (let [{:keys [status body headers]}
          (dev-handler {:request-method :get :uri "/drafts/an-idea-brewing"})]
      (is (= 200 status))
      (is (= "no-store" (get headers "Cache-Control")))
      (is (str/includes? body "Draft"))))

  (testing "drafts never leak into public listings, search, or the feed"
    (is (not (str/includes? (:body (GET "/")) "isn't ready")))
    (is (not (str/includes? (:body (GET "/feed.xml")) "isn't ready")))
    (is (not (str/includes? (:body (GET "/search" "q=brewing")) "isn't ready")))))

(deftest nav-hides-empty-types
  ;; :essay is a configured type with no published entries — the nav
  ;; must not link it (the link would be a permanent 404), while types
  ;; with entries keep their links.
  (let [cfg (update config :entry-types conj :essay)
        h (app/make-app cfg (atom (content/build-index cfg)))
        body (:body (h {:request-method :get :uri "/"}))]
    (is (not (str/includes? body "\"/essays\"")))
    (is (str/includes? body "\"/posts\""))
    (is (str/includes? body "\"/tools\"")))
  (testing "the 404 page's nav hides them too"
    (let [{:keys [status body]} (GET "/nope")]
      (is (= 404 status))
      (is (str/includes? body "\"/posts\"")))))

(deftest home-limits-to-whole-days
  (let [h (app/make-app (assoc config :home-entries 2)
                        (atom (content/build-index config)))
        body (:body (h {:request-method :get :uri "/"}))]
    (testing "both same-day entries show (days are never split)"
      (is (str/includes? body "Hello, world"))
      (is (str/includes? body "nextjournal/markdown")))
    (testing "the feed stops at the whole-day cut (later days aren't in the feed)"
      (is (not (str/includes? body "June 21, 2026"))))
    (testing "the feed continues into the month archive of the next entry"
      (is (str/includes? body "Older"))
      (is (str/includes? body "\"/2026/jun\"")))))

(deftest feed-count-is-configurable
  (testing ":feed-entries caps how many items the RSS feed carries"
    (let [h (app/make-app (assoc config :feed-entries 1)
                          (atom (content/build-index config)))
          body (:body (h {:request-method :get :uri "/feed.xml"}))]
      (is (= 1 (count (re-seq #"<item>" body))))))
  (testing "without :feed-entries it falls back to the default"
    (let [body (:body (GET "/feed.xml"))]
      ;; example-content has more than one entry, so the default (20)
      ;; lets several items through
      (is (< 1 (count (re-seq #"<item>" body)))))))

(deftest static-assets
  (let [{:keys [status headers]} (GET "/css/style.css")]
    (is (= 200 status))
    (is (= "text/css" (get headers "Content-Type"))))
  (testing "no path traversal"
    (is (not= 200 (:status (GET "/css/../../config/config.edn"))))))

(deftest no-admin-http-surface
  (testing "there is no reindex endpoint — the server has no admin routes"
    (is (= 404 (:status (handler {:request-method :post
                                  :uri "/admin/reindex"
                                  :query-string "token=anything"}))))))
