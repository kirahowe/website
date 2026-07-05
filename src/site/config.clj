(ns site.config
  "Configuration is file-first and env-var-free: config.edn (committed)
  merged with config.local.edn (gitignored — machine-local settings like
  your vault path). Dev-only behavior is switched by which bb task you
  run, not by configuration, so dev and prod can't drift apart."
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
  "Pure merge: base ← local ← overrides, then ~ expansion."
  [base local overrides]
  (-> (merge base local overrides)
      (update :content-path expand-home)))

(defn load-config
  "config.edn ← config.local.edn ← `overrides` (e.g. {:dev? true} from
  the `bb dev` task)."
  ([] (load-config {}))
  ([overrides]
   (resolve-config (read-edn "config.edn")
                   (read-edn "config.local.edn")
                   overrides)))
