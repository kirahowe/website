(ns site.cdn
  "Cloudflare cache operations used after a successful content refresh.

  Purging is deliberately best-effort: stale content is preferable to taking
  a successful content refresh down because Cloudflare is unavailable."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn- canonical-home [base-url]
  (str (str/replace (str base-url) #"/+$" "") "/"))

(defn- successful-response? [{:keys [status body]}]
  (and (<= 200 status 299)
       (string? body)
       (true? (:success (json/parse-string body true)))))

(defn purge-home!
  "Best-effort purge of the canonical homepage at Cloudflare.

  Returns :purged, :skipped (credentials are not configured), or :failed.
  Every failure is logged and swallowed, so callers can keep reporting a
  successful content sync. The API token is read from config and never logged."
  ([config]
   (purge-home! http/request config))
  ([request-fn {:keys [cloudflare-zone-id cloudflare-api-token base-url]}]
   (if (and (seq cloudflare-zone-id)
            (seq cloudflare-api-token)
            (seq base-url))
     (let [url (str "https://api.cloudflare.com/client/v4/zones/"
                    cloudflare-zone-id "/purge_cache")
           homepage (canonical-home base-url)]
       (try
         (let [response (request-fn
                          {:method :post
                           :uri url
                           :timeout 5000
                           :throw false
                           :headers {"Authorization" (str "Bearer " cloudflare-api-token)
                                     "Content-Type" "application/json"}
                           :body (json/generate-string {:files [homepage]})})]
           (if (successful-response? response)
             (do (println "Cloudflare: purged homepage cache") :purged)
             (do (binding [*out* *err*]
                   (println "Cloudflare homepage purge failed: HTTP" (:status response)))
                 :failed)))
         (catch Exception e
           (binding [*out* *err*]
             (println "Cloudflare homepage purge failed:" (ex-message e)))
           :failed)))
     :skipped)))
