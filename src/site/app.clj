(ns site.app
  "Builds the Ring handler. Pure — no server started here, so the whole
  app can be exercised as a plain function in tests."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [site.content :as content]
            [site.handlers :as handlers]
            [site.routes :as routes]
            [site.sync :as sync])
  (:import [java.net URLDecoder]))

(def ^:private content-types
  {"css" "text/css" "js" "text/javascript" "json" "application/json"
   "png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg" "gif" "image/gif"
   "svg" "image/svg+xml" "ico" "image/x-icon" "webp" "image/webp"
   "txt" "text/plain" "csv" "text/csv"
   "woff" "font/woff" "woff2" "font/woff2"})

(defn- static-response
  "Serves files under resources/public for asset paths. A request carrying
  the ?v= content hash (how layout links every asset) is cacheable
  forever — a changed asset arrives at a new URL — while a bare request
  keeps a TTL, since nothing busts its cache when the file changes.

  The favicon files are named exceptions at the root: browsers, feed
  readers and bookmarking services request /favicon.ico directly, never
  by way of a linked, hashed URL, so it (and its SVG/apple-touch-icon
  siblings) must resolve at that bare path or they're never found. Each
  is spelled out rather than opening the root to `.+`, so nothing else
  up there becomes accidentally servable."
  [uri query-string]
  (when (and (re-matches #"/(?:(?:css|js|images|fonts)/.+|favicon\.(?:svg|ico)|apple-touch-icon\.png)" uri)
             (not (str/includes? uri "..")))
    (when-let [res (io/resource (str "public" uri))]
      (let [ext (peek (str/split uri #"\."))
            versioned? (re-find #"(?:^|&)v=" (str query-string))]
        {:status 200
         :headers {"Content-Type" (get content-types ext "application/octet-stream")
                   "Cache-Control" (if versioned?
                                     "public, max-age=31536000, immutable"
                                     "public, max-age=86400")}
         :body (io/input-stream res)}))))

(defn- attachment-response
  "Serves images pasted into the vault: /attachments/<file> comes from
  the content repo's attachments/ folder, so an image never needs a
  site deploy."
  [config uri]
  (when (str/starts-with? uri "/attachments/")
    (let [name (URLDecoder/decode (subs uri (count "/attachments/")) "UTF-8")
          ext (str/lower-case (peek (str/split name #"\.")))
          f (io/file (:content-path config) "attachments" name)]
      (when (and (not (str/includes? name ".."))
                 (content-types ext)
                 (.isFile f))
        {:status 200
         :headers {"Content-Type" (content-types ext)
                   "Cache-Control" "public, max-age=86400"}
         :body (io/input-stream f)}))))

(defn- webhook-sha
  "The pushed HEAD (\"after\") out of a GitHub push webhook payload, nil
  for anything else — a ping event, a bare curl, a body that isn't JSON.
  Never throws: an unreadable body just means we can't skip the pull."
  [req]
  (try
    (let [after (some-> (:body req) slurp (json/parse-string) (get "after"))]
      (when (string? after) after))
    (catch Exception _ nil)))

(defn- refresh-response
  "POST /refresh — the content repo's push webhook lands here. Only
  exists when content comes from git (prod); dev serves the vault
  directly and has nothing to refresh. Unauthenticated by design: all it
  can do is pull a public repo, sync/refresh! bounds how often, and the
  site keeps its no-secrets property."
  [config index-atom refresh-state req]
  (when (and (= :post (:request-method req))
             (= "/refresh" (:uri req))
             (:content-git-url config))
    (let [result (sync/refresh! config index-atom refresh-state (webhook-sha req))]
      {:status (if (= :error result) 503 200)
       :headers {"Content-Type" "text/plain; charset=utf-8"
                 "Cache-Control" "no-store"}
       :body (name result)})))

(defn- previous-url-redirect
  "301 for a URI some entry records as a previous URL (its :previous-urls).
  Consulted only after normal routing 404s, so an old URL can never shadow
  live content."
  [index uri]
  (let [trimmed (str/replace (str uri) #"/+$" "")
        uri (if (str/blank? trimmed) uri trimmed)]
    (when-let [target (get (:redirects index) uri)]
      {:status 301
       :headers {"Location" target
                 "Cache-Control" handlers/public-cache}})))

(defn make-app
  "→ Ring handler. In :dev? mode the content index is rebuilt on every
  request (edits show up on refresh) and drafts are viewable. POST
  /refresh — the content repo's push webhook — is answered here too,
  before routing, like the other non-page responses."
  [config index-atom]
  (let [refresh-state (sync/make-refresh-state)]
    (fn [req]
      (try
        (let [uri (:uri req)]
          (or (static-response uri (:query-string req))
              (attachment-response config uri)
              (refresh-response config index-atom refresh-state req)
              (let [index (if (:dev? config)
                            (content/build-index config)
                            @index-atom)
                    ;; The header renders from config alone, but which nav
                    ;; links exist depends on the content — hand it along.
                    config (assoc config :nav-types (:nav-types index))
                    match (routes/match-route config (routes/path-segments uri))
                    resp (if match
                           (handlers/handle config index match req)
                           (handlers/not-found config))]
                (if (= 404 (:status resp))
                  (or (previous-url-redirect index uri) resp)
                  resp))))
        (catch Exception e
          (binding [*out* *err*]
            (println "ERROR" (:uri req) "—" (ex-message e)))
          {:status 500
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body "<!DOCTYPE html><html><body><h1>500</h1><p>Something broke.</p></body></html>"})))))
