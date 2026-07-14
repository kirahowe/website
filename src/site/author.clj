(ns site.author
  "Authoring tasks (`bb new`, `bb publish`, `bb suggest-tags`). These run
  in babashka only — they use babashka.fs and babashka.process, which the
  server never needs. They always operate on the dev environment: the
  vault."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [site.config :as config]
            [site.content :as content]
            [site.markdown :as markdown]
            [site.util :as util])
  (:import [java.time LocalDate]))

(defn frontmatter-template
  "YAML frontmatter, matching what Obsidian's Properties panel edits.
  A post needs nothing beyond tags — the filename is the title."
  [type]
  (case type
    :post "---\ntags: []\n---\n\n"
    :link "---\ntype: link\nlink: \nvia: \ntags: []\n---\n\n"
    :quote "---\ntype: quote\nauthor: \nsource: \ntags: []\n---\n\n"
    (str "---\ntype: " (name type) "\ntags: []\n---\n\n")))

(defn- die [& msg]
  (println (apply str msg))
  (System/exit 1))

(defn- warn [& msg]
  (println (apply str "⚠ " msg)))

(defn reindex
  "bb reindex — parse and index every content file, failing loudly on any
  problem. Run it to validate the whole tree locally; the server does
  exactly this on boot and on every timed pull."
  [& _]
  (let [cfg (config/load-config :dev)]
    (try
      (let [index (content/build-index cfg)]
        (println (str "OK — " (count (:entries index)) " entries, "
                      (count (:drafts index)) " drafts, "
                      (count (:pages index)) " pages from "
                      (:content-path cfg))))
      (catch Exception e
        (die "Content error: " (ex-message e))))))

(defn new-draft
  "bb new <type> <title words...> — scaffolds drafts/<Title>.md in the
  content repo. The filename is the title, exactly as Obsidian would
  create it."
  [& args]
  (let [cfg (config/load-config :dev)
        [type-str & title-words] args
        type (when type-str (keyword (str/replace type-str #"^:" "")))
        title (when (seq title-words) (str/join " " title-words))]
    (when-not type
      (die "Usage: bb new <type> <title words...>"))
    (when-not ((set (:entry-types cfg)) type)
      (die "Unknown type " type ". Allowed: "
           (str/join ", " (map name (:entry-types cfg)))))
    (let [fname (-> (or title (str (name type) " " (LocalDate/now)))
                    (str/replace "/" "-"))
          target (fs/path (:content-path cfg) "drafts" (str fname ".md"))]
      (when (fs/exists? target)
        (die "Already exists: " target))
      (fs/create-dirs (fs/parent target))
      (spit (str target) (frontmatter-template type))
      (println "Draft created:" (str target))
      (println (str "Preview at /drafts/" fname " (with bb dev running)")))))

(defn- find-draft
  "drafts/<name>.md by exact name, else by slug (so `bb publish
  my-great-idea` finds drafts/My great idea.md)."
  [root name]
  (let [dir (fs/path root "drafts")
        base (str/replace name #"\.md$" "")
        exact (fs/path dir (str base ".md"))]
    (cond
      (fs/exists? exact) exact
      (fs/exists? dir) (->> (fs/list-dir dir "*.md")
                            (filter #(= (util/slugify base)
                                        (util/slugify (str/replace (fs/file-name %) #"\.md$" ""))))
                            first))))

(defn- lint-draft!
  "Pre-publish checks. Everything here warns rather than blocks: an
  unresolved wikilink degrades to plain text on the site, so nothing
  below can break the published page — but you want to know."
  [cfg index draft]
  (let [known-links (:wikilinks draft)
        known-tags (set (keys (:by-tag index)))
        body (:body draft)]
    (doseq [target (distinct (markdown/wikilinks-in body))
            :when (not (get known-links (str/lower-case target)))]
      (warn "unresolved wikilink [[" target "]] — it will render as plain text"))
    (doseq [file (distinct (markdown/attachments-in body))
            :when (not (fs/exists? (fs/path (:content-path cfg) "attachments" file)))]
      (warn "missing attachment: attachments/" file))
    (when (empty? (:tags draft))
      (warn "no tags — try: bb suggest-tags " (:draft-name draft)))
    (when-let [new-tags (seq (remove known-tags (:tags draft)))]
      (println (str "New tags (first use): " (str/join ", " (map name new-tags)))))
    (when (and (#{:link :release :tool} (:type draft))
               (not (:link-url draft)))
      (warn "a " (name (:type draft)) " without a link property has no outbound URL"))))

(defn- fly-app-name []
  (when (fs/exists? "fly.toml")
    (second (re-find #"(?m)^app\s*=\s*\"([^\"]+)\"" (slurp "fly.toml")))))

;; --- vault → publish repo mirror -----------------------------------------
;;
;; The vault (iCloud) is the source of truth. Git never lives inside it —
;; instead a separate checkout outside iCloud (:publish-repo) is the
;; transport the server pulls from. Publishing/syncing mirrors the vault's
;; publishable content into that repo and pushes. The two sync systems
;; (iCloud, git) never share a file.

(def ^:private mirrored-dirs
  "Top-level vault folders that cross into the public repo. drafts/,
  templates/, dev.md and .obsidian/ are deliberately absent — they stay
  private to the vault."
  #{"pages" "attachments"})

(defn- mirrored?
  "A top-level vault entry that gets published: a YYYY date folder, or one
  of the always-published folders."
  [f]
  (and (fs/directory? f)
       (let [n (fs/file-name f)]
         (or (re-matches #"\d{4}" n) (mirrored-dirs n)))))

(defn- mirror!
  "Replace the publish repo's mirrored content with the vault's, so that
  deletions in the vault propagate too. Leaves the repo's own files
  (.git, README, .gitignore) untouched."
  [vault repo]
  (doseq [f (filter mirrored? (fs/list-dir repo))]
    (fs/delete-tree f))
  (doseq [f (filter mirrored? (fs/list-dir vault))]
    (fs/copy-tree f (fs/path repo (fs/file-name f)))))

(defn- git-push!
  "Stage everything in the repo, commit, and push. No-op (with a note) when
  the mirror already matches. A push that fails on a fresh repo (no remote
  yet) is reported with the one-liner to create it, not treated as fatal."
  [cfg repo message]
  (if (str/blank? (:out (p/sh {:dir (str repo)} "git" "status" "--porcelain")))
    (println "Nothing to sync — the repo already matches the vault.")
    (do
      (p/shell {:dir (str repo)} "git" "add" "-A")
      (p/shell {:dir (str repo)} "git" "commit" "-m" message)
      (let [{:keys [exit err]} (p/sh {:dir (str repo)} "git" "push")]
        (if (zero? exit)
          (do (println "Committed and pushed.")
              (println (str "Live within ~" (or (:content-sync-seconds cfg) 300)
                            "s on the next content sync."))
              (when-let [app (fly-app-name)]
                (println (str "To go live right now: fly apps restart " app))))
          (do (println "Committed locally, but the push failed:" (str/trim (str err)))
              (println (str "First publish? Create the remote from " repo ":\n"
                            "  gh repo create <content-repo> --public --source . --push"))))))))

(defn- publish-repo-path
  "The git checkout the vault mirrors into. Fails loudly if unset or not a
  git repo — publishing without it configured is a setup mistake."
  [cfg]
  (let [repo (some-> (:publish-repo cfg) fs/expand-home)]
    (when-not repo
      (die "No :publish-repo set in dev.edn — the git checkout (outside "
           "iCloud) the vault mirrors into. See the README."))
    (when-not (fs/exists? (fs/path repo ".git"))
      (die "Not a git repo: " repo "\nClone your content repo there, or "
           "`git init` it, then set :publish-repo in dev.edn."))
    (str repo)))

(defn- mirror-and-push! [cfg message]
  (let [repo (publish-repo-path cfg)]
    (mirror! (:content-path cfg) repo)
    (git-push! cfg repo message)))

(defn publish
  "bb publish <name words...> [--no-git] — the manual publish: validates
  the draft, lints it, moves it into today's YYYY/MM/DD/ folder in the
  vault, then (unless --no-git) mirrors the vault into the publish repo
  and pushes. The live site picks it up on its next timed pull, or
  immediately if you restart the machine."
  [& args]
  (let [no-git? (boolean (some #{"--no-git"} args))
        fname (str/join " " (remove #(str/starts-with? % "--") args))]
    (when (str/blank? fname)
      (die "Usage: bb publish <draft name> [--no-git]"))
    (let [cfg (config/load-config :dev)
          root (:content-path cfg)
          src (find-draft root fname)]
      (when-not src
        (die "No such draft: " fname))
      (let [base (str/replace (fs/file-name src) #"\.md$" "")]
        ;; Validate with the same parser the server uses — a broken file
        ;; should fail here, not after it's live.
        (let [{:keys [meta]} (content/parse-frontmatter (slurp (str src)) (str src))]
          (content/check-type! cfg meta (str src)))
        ;; Lint against the full index; a problem elsewhere in the tree
        ;; shouldn't block publishing this draft, just surface it.
        (try
          (let [index (content/build-index cfg)]
            (lint-draft! cfg index (get (:drafts index) base)))
          (catch Exception e
            (warn "skipping lints — content tree has errors: " (ex-message e))))
        (let [today (LocalDate/now)
              y (.getYear today)
              m (.getMonthValue today)
              d (.getDayOfMonth today)
              dir (fs/path root (str y) (format "%02d" m) (format "%02d" d))
              target (fs/path dir (str base ".md"))
              slug (util/slugify base)]
          (when (fs/exists? target)
            (die "Target already exists: " target))
          (fs/create-dirs dir)
          (fs/move src target)
          (println "Published:" (str target))
          (println "URL path:  " (str "/" y "/" (util/month-slug m) "/" d "/" slug))
          (if no-git?
            (println "Skipped git (--no-git). Run `bb sync` when you're ready to push.")
            (mirror-and-push! cfg (str "Publish " base))))))))

(defn sync-content
  "bb sync — mirror the vault's publishable content into the publish repo
  and push, without publishing a new draft. Use after editing or deleting
  an already-published entry in Obsidian: deletions propagate too."
  [& _]
  (let [cfg (config/load-config :dev)]
    ;; Validate the whole tree first — never push a broken index.
    (try (content/build-index cfg)
         (catch Exception e
           (die "Content error, not syncing: " (ex-message e))))
    (mirror-and-push! cfg "Sync content")))

(defn- ask-llm
  "Runs the configured :llm-command through an interactive shell so
  aliases and shell functions (like a wrapped `claude`) resolve; the
  prompt goes in on stdin."
  [cfg prompt]
  (let [cmd (or (:llm-command cfg) "claude")
        {:keys [exit out err]} (p/sh {:in prompt :out :string :err :string}
                                     "zsh" "-ic" (str cmd " -p"))]
    (when-not (zero? exit)
      (die "LLM command failed (" cmd " -p): " (str/trim (str err))))
    out))

(defn suggest-tags
  "bb suggest-tags <draft name> — has the configured LLM read the draft
  and propose tags, printed as a YAML block ready to paste into the
  note's properties."
  [& args]
  (let [fname (str/join " " args)]
    (when (str/blank? fname)
      (die "Usage: bb suggest-tags <draft name>"))
    (let [cfg (config/load-config :dev)
          src (find-draft (:content-path cfg) fname)]
      (when-not src
        (die "No such draft: " fname))
      (let [base (str/replace (fs/file-name src) #"\.md$" "")
            {:keys [body]} (content/parse-frontmatter (slurp (str src)) (str src))
            prompt (str "Suggest 3-7 topic tags for this blog entry. Tags are short, "
                        "lowercase, kebab-case (e.g. data-engineering, llms, clojure). "
                        "Reply with only the tags, one per line — no other text.\n\n"
                        "Title: " base "\n\n" body)
            tags (->> (str/split-lines (ask-llm cfg prompt))
                      (map #(-> % str/trim (str/replace #"^[-*#]\s*" "") (str/replace #"^`|`$" "")))
                      (remove str/blank?)
                      (map util/slugify)
                      distinct
                      (take 10))]
        (when (empty? tags)
          (die "The LLM returned no usable tags."))
        (println (str "Suggested tags for \"" base "\":\n"))
        (println "tags:")
        (doseq [t tags] (println (str "  - " t)))))))
