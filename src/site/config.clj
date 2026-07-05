(ns site.config
  "Configuration is file-first: config.edn (committed) merged with
  config.local.edn (gitignored — machine-local settings like your vault
  path). Environment variables are reserved for actual secrets, plus
  container-level overrides a platform like Fly injects."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- env [k]
  (let [v (System/getenv k)]
    (when-not (or (nil? v) (= "" v)) v)))

(defn- read-edn [path]
  (let [f (io/file path)]
    (when (.exists f)
      (edn/read-string (slurp f)))))

(defn- expand-home
  "\"~/vault\" → \"/Users/you/vault\" — vault paths live under home."
  [p]
  (if (and p (str/starts-with? p "~"))
    (str (System/getProperty "user.home") (subs p 1))
    p))

(defn resolve-config
  "Pure merge: base ← local ← env ← overrides, then ~ expansion."
  [base local env-map overrides]
  (-> (merge base local env-map overrides)
      (update :content-path expand-home)))

(defn load-config
  "config.edn ← config.local.edn ← env ← `overrides`.

  Secrets (env-only, never commit these):
    ADMIN_TOKEN      gates draft previews and /admin/reindex
    CONTENT_GIT_URL  when it embeds a repo access token

  Container-level (set by fly.toml, not by hand):
    CONTENT_PATH, CONTENT_SYNC_SECONDS, PORT"
  ([] (load-config {}))
  ([overrides]
   (resolve-config
    (read-edn "config.edn")
    (read-edn "config.local.edn")
    (cond-> {}
      (env "ADMIN_TOKEN") (assoc :admin-token (env "ADMIN_TOKEN"))
      (env "CONTENT_GIT_URL") (assoc :content-git-url (env "CONTENT_GIT_URL"))
      (env "CONTENT_PATH") (assoc :content-path (env "CONTENT_PATH"))
      (env "CONTENT_SYNC_SECONDS") (assoc :content-sync-seconds
                                          (parse-long (env "CONTENT_SYNC_SECONDS")))
      (env "PORT") (assoc :port (parse-long (env "PORT"))))
    overrides)))
