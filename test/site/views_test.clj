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
   :entry-types [:post :note :link :quote]
   :admin-token "secret"})

(def handler
  (app/make-app config (atom (content/build-index config))))

(defn- GET
  ([uri] (GET uri nil))
  ([uri query] (handler {:request-method :get :uri uri :query-string query})))

(deftest pages-render
  (testing "home shows recent entries of every type"
    (let [{:keys [status body headers]} (GET "/")]
      (is (= 200 status))
      (is (str/includes? (get headers "Cache-Control") "public"))
      (is (str/includes? body "Hello, world"))
      (is (str/includes? body "nextjournal/markdown"))
      (is (str/includes? body "Rich Hickey"))))

  (testing "single entry page renders markdown"
    (let [{:keys [status body]} (GET "/2026/jul/4/hello-world")]
      (is (= 200 status))
      (is (str/includes? body "How it works</h2>"))  ; ## heading
      (is (str/includes? body "<code"))              ; inline code
      (is (str/includes? body "entry-url"))))

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

  (testing "type and tag listings"
    (is (str/includes? (:body (GET "/posts")) "REPL-driven"))
    (is (str/includes? (:body (GET "/quotes")) "Simplicity"))
    (is (str/includes? (:body (GET "/tags/clojure")) "Babashka"))
    (is (= 200 (:status (GET "/2026/posts"))))
    (is (= 404 (:status (GET "/2024/posts")))))

  (testing "quote renders with attribution"
    (let [{:keys [body]} (GET "/2026/jun/21/rich-hickey-on-simplicity")]
      (is (str/includes? body "<blockquote>"))
      (is (str/includes? body "Rich Hickey"))))

  (testing "link entry title points at the external URL"
    (let [{:keys [body]} (GET "/2025/aug/30/babashka")]
      (is (str/includes? body "https://babashka.org"))
      (is (str/includes? body "via"))))

  (testing "static page"
    (let [{:keys [status body]} (GET "/about")]
      (is (= 200 status))
      (is (str/includes? body "personal weblog"))))

  (testing "search"
    (let [{:keys [status body headers]} (GET "/search" "q=babashka")]
      (is (= 200 status))
      (is (= "no-store" (get headers "Cache-Control")))
      (is (str/includes? body "Babashka"))))

  (testing "feed"
    (let [{:keys [status body headers]} (GET "/feed.xml")]
      (is (= 200 status))
      (is (str/includes? (get headers "Content-Type") "rss"))
      (is (str/includes? body "<rss"))
      (is (str/includes? body "Hello, world"))))

  (testing "404"
    (is (= 404 (:status (GET "/nope"))))
    (is (= 404 (:status (GET "/2026/jul/4/nope"))))))

(deftest drafts-are-gated
  (testing "no token → 404, wrong token → 404, right token → 200 uncached"
    (is (= 404 (:status (GET "/drafts/an-idea-brewing"))))
    (is (= 404 (:status (GET "/drafts/an-idea-brewing" "preview=wrong"))))
    (let [{:keys [status body headers]} (GET "/drafts/an-idea-brewing" "preview=secret")]
      (is (= 200 status))
      (is (= "no-store" (get headers "Cache-Control")))
      (is (str/includes? body "Draft"))))

  (testing "drafts never leak into public listings, search, or the feed"
    (is (not (str/includes? (:body (GET "/")) "isn't ready")))
    (is (not (str/includes? (:body (GET "/feed.xml")) "isn't ready")))
    (is (not (str/includes? (:body (GET "/search" "q=brewing")) "isn't ready")))))

(deftest drafts-without-token-configured
  (let [h (app/make-app (dissoc config :admin-token)
                        (atom (content/build-index config)))]
    (testing "no ADMIN_TOKEN configured → previews are off entirely"
      (is (= 404 (:status (h {:request-method :get
                              :uri "/drafts/an-idea-brewing"
                              :query-string "preview="})))))))

(deftest static-assets
  (let [{:keys [status headers]} (GET "/css/style.css")]
    (is (= 200 status))
    (is (= "text/css" (get headers "Content-Type"))))
  (testing "no path traversal"
    (is (not= 200 (:status (GET "/css/../../config.edn"))))))

(deftest reindex-endpoint
  (testing "requires POST and token"
    (is (= 404 (:status (handler {:request-method :post :uri "/admin/reindex"}))))
    (let [{:keys [status body]} (handler {:request-method :post
                                          :uri "/admin/reindex"
                                          :query-string "token=secret"})]
      (is (= 200 status))
      (is (str/includes? body "Reindexed: 6 entries")))))
