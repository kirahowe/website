(ns site.app
  "Builds the Ring handler. Pure — no server started here, so the whole
  app can be exercised as a plain function in tests."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [site.content :as content]
            [site.handlers :as handlers]
            [site.routes :as routes])
  (:import [java.net URLDecoder]))

(def ^:private content-types
  {"css" "text/css" "js" "text/javascript" "json" "application/json"
   "png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg" "gif" "image/gif"
   "svg" "image/svg+xml" "ico" "image/x-icon" "webp" "image/webp"
   "txt" "text/plain" "woff" "font/woff" "woff2" "font/woff2"})

(defn- static-response
  "Serves files under resources/public for asset paths. A request carrying
  the ?v= content hash (how layout links every asset) is cacheable
  forever — a changed asset arrives at a new URL — while a bare request
  keeps a TTL, since nothing busts its cache when the file changes."
  [uri query-string]
  (when (and (re-matches #"/(css|js|images|fonts)/.+" uri)
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
  request (edits show up on refresh) and drafts are viewable."
  [config index-atom]
  (fn [req]
    (try
      (let [uri (:uri req)]
        (or (static-response uri (:query-string req))
            (attachment-response config uri)
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
         :body "<!DOCTYPE html><html><body><h1>500</h1><p>Something broke.</p></body></html>"}))))
