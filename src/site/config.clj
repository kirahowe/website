(ns site.config
  "Loads config.edn and applies environment overrides."
  (:require [clojure.edn :as edn]))

(defn- env [k]
  (let [v (System/getenv k)]
    (when-not (or (nil? v) (= "" v)) v)))

(defn load-config
  "config.edn, overridden by environment variables, overridden by `overrides`.

  Env vars: CONTENT_PATH, PORT, ADMIN_TOKEN (gates draft previews and
  the /admin/reindex endpoint)."
  ([] (load-config {}))
  ([overrides]
   (let [base (edn/read-string (slurp "config.edn"))
         from-env (cond-> {}
                    (env "CONTENT_PATH") (assoc :content-path (env "CONTENT_PATH"))
                    (env "PORT") (assoc :port (parse-long (env "PORT")))
                    (env "ADMIN_TOKEN") (assoc :admin-token (env "ADMIN_TOKEN")))]
     (merge base from-env overrides))))
