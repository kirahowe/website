(ns site.author
  "Authoring tasks (`bb new`, `bb publish`). These run in babashka only —
  they use babashka.fs and babashka.process, which the server never needs."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [site.config :as config]
            [site.content :as content]
            [site.util :as util])
  (:import [java.time LocalDate]))

(defn frontmatter-template [type title]
  (str ";;;\n"
       "{:type " type
       (when title (str "\n :title \"" title "\""))
       "\n :tags []}\n"
       ";;;\n\n"))

(defn- die [& msg]
  (println (apply str msg))
  (System/exit 1))

(defn reindex
  "bb reindex — parse and index every content file, failing loudly on any
  problem. Run it to validate the whole tree locally; the server does
  exactly this on boot and on every timed pull."
  [& _]
  (let [cfg (config/load-config)]
    (try
      (let [index (content/build-index cfg)]
        (println (str "OK — " (count (:entries index)) " entries, "
                      (count (:drafts index)) " drafts, "
                      (count (:pages index)) " pages from "
                      (:content-path cfg))))
      (catch Exception e
        (die "Content error: " (ex-message e))))))

(defn new-draft
  "bb new <type> <title words...> — scaffolds drafts/<slug>.md in the
  content repo from a per-type frontmatter template."
  [& args]
  (let [cfg (config/load-config)
        [type-str & title-words] args
        type (when type-str (keyword (str/replace type-str #"^:" "")))
        title (when (seq title-words) (str/join " " title-words))]
    (when-not type
      (die "Usage: bb new <type> <title words...>"))
    (when-not ((set (:entry-types cfg)) type)
      (die "Unknown type " type ". Allowed: "
           (str/join ", " (map name (:entry-types cfg)))))
    (let [slug (util/slugify (or title (str (name type) "-" (LocalDate/now))))
          target (fs/path (:content-path cfg) "drafts" (str slug ".md"))]
      (when (fs/exists? target)
        (die "Already exists: " target))
      (fs/create-dirs (fs/parent target))
      (spit (str target) (frontmatter-template type title))
      (println "Draft created:" (str target)))))

(defn publish
  "bb publish <name> [--no-git] — validates the draft, moves it into
  today's YYYY/MM/DD/ folder, and (unless --no-git) commits and pushes
  the content repo."
  [& args]
  (let [no-git? (boolean (some #{"--no-git"} args))
        fname (first (remove #(str/starts-with? % "--") args))]
    (when-not fname
      (die "Usage: bb publish <draft name> [--no-git]"))
    (let [cfg (config/load-config)
          root (:content-path cfg)
          base (str/replace (fs/file-name fname) #"\.md$" "")
          src (fs/path root "drafts" (str base ".md"))]
      (when-not (fs/exists? src)
        (die "No such draft: " src))
      ;; Validate with the same parser the server uses — a broken file
      ;; should fail here, not after it's live.
      (let [{:keys [meta]} (content/parse-frontmatter (slurp (str src)) (str src))]
        (content/check-type! cfg meta (str src)))
      (let [today (LocalDate/now)
            y (.getYear today)
            m (.getMonthValue today)
            d (.getDayOfMonth today)
            dir (fs/path root (str y) (format "%02d" m) (format "%02d" d))
            target (fs/path dir (str base ".md"))]
        (when (fs/exists? target)
          (die "Target already exists: " target))
        (fs/create-dirs dir)
        (fs/move src target)
        (println "Published:" (str target))
        (println "URL path:  " (str "/" y "/" (util/month-slug m) "/" d "/" base))
        (if (and (not no-git?) (fs/exists? (fs/path root ".git")))
          (do (p/shell {:dir root} "git" "add" "-A")
              (p/shell {:dir root} "git" "commit" "-m" (str "Publish " base))
              (p/shell {:dir root} "git" "push")
              (println "Committed and pushed."))
          (println "Skipped git (no repo or --no-git)."))))))
