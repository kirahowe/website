(ns site.config
  "Configuration is file-first and env-var-free. It all lives in config/:
  config/config.edn is the base that always applies (site identity, entry
  types). On top of it, exactly one environment file is merged:
  config/dev.edn (`bb dev` — your vault, no git syncing) or config/prod.edn
  (`bb prod` — the cloned content repo). The port is environment-specific
  (dev 8100, prod 8080). Dev-only behavior follows the environment, so dev
  and prod can't drift apart on a forgotten flag."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

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
  "Pure merge: base ← env file, stamped with :env (and :dev?, which the
  server keys request-time behavior on), then ~ expansion."
  [base env-config env]
  (-> (merge base env-config)
      (assoc :env env :dev? (= env :dev))
      (update :content-path expand-home)))

(defn load-config
  "config/config.edn ← config/<env>.edn, where env is :dev or :prod.
  Authoring tasks (`bb new`, `bb publish`, ...) run with :dev — they
  operate on the vault."
  [env]
  (resolve-config (read-edn "config/config.edn")
                  (read-edn (str "config/" (name env) ".edn"))
                  env))
