(ns site.config
  "Loads config.edn and applies environment overrides."
  (:require [clojure.edn :as edn]))

(defn- env [k]
  (let [v (System/getenv k)]
    (when-not (or (nil? v) (= "" v)) v)))

(defn load-config
  "config.edn, overridden by environment variables, overridden by `overrides`.

  Env vars:
    CONTENT_PATH          where the content lives (your vault locally;
                          the clone target in production)
    CONTENT_GIT_URL       when set, the server clones this repo at boot
                          and pulls on a timer (production only; may embed
                          an access token, so env-only — never config.edn)
    CONTENT_SYNC_SECONDS  pull interval, default 300
    ADMIN_TOKEN           gates draft previews and /admin/reindex
    PORT"
  ([] (load-config {}))
  ([overrides]
   (let [base (edn/read-string (slurp "config.edn"))
         from-env (cond-> {}
                    (env "CONTENT_PATH") (assoc :content-path (env "CONTENT_PATH"))
                    (env "CONTENT_GIT_URL") (assoc :content-git-url (env "CONTENT_GIT_URL"))
                    (env "CONTENT_SYNC_SECONDS") (assoc :content-sync-seconds
                                                        (parse-long (env "CONTENT_SYNC_SECONDS")))
                    (env "PORT") (assoc :port (parse-long (env "PORT")))
                    (env "ADMIN_TOKEN") (assoc :admin-token (env "ADMIN_TOKEN")))]
     (merge base from-env overrides))))
