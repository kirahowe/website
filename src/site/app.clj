(ns site.app
  "Builds the Ring handler. Pure — no server started here, so the whole
  app can be exercised as a plain function in tests."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [site.content :as content]
            [site.handlers :as handlers]
            [site.routes :as routes]))

(def ^:private content-types
  {"css" "text/css" "js" "text/javascript" "json" "application/json"
   "png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg" "gif" "image/gif"
   "svg" "image/svg+xml" "ico" "image/x-icon" "webp" "image/webp"
   "txt" "text/plain" "woff" "font/woff" "woff2" "font/woff2"})

(defn- static-response
  "Serves files under resources/public for asset paths."
  [uri]
  (when (and (re-matches #"/(css|js|images|fonts)/.+" uri)
             (not (str/includes? uri "..")))
    (when-let [res (io/resource (str "public" uri))]
      (let [ext (peek (str/split uri #"\."))]
        {:status 200
         :headers {"Content-Type" (get content-types ext "application/octet-stream")
                   "Cache-Control" "public, max-age=86400"}
         :body (io/input-stream res)}))))

(defn make-app
  "→ Ring handler. In :dev? mode the content index is rebuilt on every
  request (edits show up on refresh) and drafts are viewable."
  [config index-atom]
  (fn [req]
    (try
      (let [uri (:uri req)]
        (or (static-response uri)
            (let [index (if (:dev? config)
                          (content/build-index config)
                          @index-atom)
                  match (routes/match-route config (routes/path-segments uri))]
              (if match
                (handlers/handle config index match req)
                (handlers/not-found config)))))
      (catch Exception e
        (binding [*out* *err*]
          (println "ERROR" (:uri req) "—" (ex-message e)))
        {:status 500
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body "<!DOCTYPE html><html><body><h1>500</h1><p>Something broke.</p></body></html>"}))))
