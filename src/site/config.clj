(ns site.config
  "Configuration is file-first: config.edn (committed) merged with
  config.local.edn (gitignored — machine-local settings like your vault
  path). The only environment variable is ADMIN_TOKEN, because it's the
  only secret."
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
  "config.edn ← config.local.edn ← ADMIN_TOKEN env var ← `overrides`.

  ADMIN_TOKEN gates draft previews and /admin/reindex — the only secret,
  so the only env var. (For local dev, :admin-token in config.local.edn
  works too.)"
  ([] (load-config {}))
  ([overrides]
   (resolve-config
    (read-edn "config.edn")
    (read-edn "config.local.edn")
    (if-let [token (env "ADMIN_TOKEN")] {:admin-token token} {})
    overrides)))
