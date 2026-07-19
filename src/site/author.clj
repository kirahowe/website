(ns site.author
  "Authoring tasks (`bb new`, `bb publish`, `bb suggest-tags`). These run
  in babashka only — they use babashka.fs and babashka.process, which the
  server never needs. They always operate on the dev environment: the
  vault."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [site.tui :as tui]
            [site.config :as config]
            [site.content :as content]
            [site.markdown :as markdown]
            [site.util :as util])
  (:import [java.time LocalDate]))

(defn frontmatter-template
  "YAML frontmatter, matching what Obsidian's Properties panel edits.
  `date` defaults to today — the day you start the draft, but it's yours
  to edit (e.g. to backdate something written over several days); `bb
  publish` files the entry under it. `publish` starts false; flipping it
  to true (e.g. from the phone) queues the draft for `bb publish-queued`.
  A post needs nothing beyond tags — the filename is the title."
  [type]
  (let [date (LocalDate/now)]
    (case type
      :post (str "---\ndate: " date "\ntags: []\npublish: false\n---\n\n")
      :link (str "---\ntype: link\ndate: " date "\nlink: \nvia: \ntags: []\npublish: false\n---\n\n")
      :quote (str "---\ntype: quote\ndate: " date "\nauthor: \nsource: \ntags: []\npublish: false\n---\n\n")
      (:release :tool) (str "---\ntype: " (name type) "\ndate: " date "\nlink: \ntags: []\npublish: false\n---\n\n")
      (str "---\ntype: " (name type) "\ndate: " date "\ntags: []\npublish: false\n---\n\n"))))

(defn- die [& msg]
  (println (apply str msg))
  (System/exit 1))

(defn- warn [& msg]
  (println (apply str "⚠ " msg)))

(defn- applescript-str
  "A string quoted for embedding in an AppleScript literal."
  [s]
  (str "\"" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) "\""))

(defn- notify!
  "Best-effort macOS desktop notification. Never throws and never blocks —
  a missing osascript or a failed notification must not break a publish
  run — so anything that goes wrong here is swallowed. This is what makes
  an unattended flush's failures noticeable, rather than a log line the
  author never reads."
  [title message]
  (try
    (p/sh "osascript" "-e"
          (str "display notification " (applescript-str message)
               " with title " (applescript-str title)))
    (catch Exception _ nil)))

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
  "bb new [type] [title words...] — scaffolds drafts/<Title>.md in the
  content repo. The filename is the title, exactly as Obsidian would
  create it. With no type, pick one from the configured entry types; when
  invoked with nothing at all, it then also prompts for an optional title."
  [& args]
  (let [cfg (config/load-config :dev)
        [type-str & title-words] args
        type (if type-str
               (keyword (str/replace type-str #"^:" ""))
               (tui/choose (:entry-types cfg)
                           :label "Entry type:"
                           :render name))]
    ;; Validate the type before prompting for a title, so cancelling the
    ;; picker (or a typo'd type) exits without a spurious title prompt.
    (when-not type
      (die "Usage: bb new <type> <title words...>"))
    (when-not ((set (:entry-types cfg)) type)
      (die "Unknown type " type ". Allowed: "
           (str/join ", " (map name (:entry-types cfg)))))
    (let [title (cond
                  (seq title-words) (str/join " " title-words)
                  ;; `bb new post` (type given, no title) keeps its old
                  ;; behavior: fall through to the type+date default name.
                  type-str nil
                  ;; Fully interactive (`bb new`) — offer to name it now.
                  :else (tui/input "Title (optional): "))
          fname (-> (or title (str (name type) " " (LocalDate/now)))
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
      (die "No :publish-repo set in config/dev.edn — the git checkout (outside "
           "iCloud) the vault mirrors into. See the README."))
    (when-not (fs/exists? (fs/path repo ".git"))
      (die "Not a git repo: " repo "\nClone your content repo there, or "
           "`git init` it, then set :publish-repo in config/dev.edn."))
    (str repo)))

(defn- mirror-and-push! [cfg message]
  (let [repo (publish-repo-path cfg)]
    (mirror! (:content-path cfg) repo)
    (git-push! cfg repo message)))

;; --- publishing -----------------------------------------------------------

(defn strip-publish-property
  "Removes any `publish:` line from a file's YAML frontmatter block only —
  never from the body. The publish toggle is authoring workflow and
  shouldn't leak into the public repo (the `date` property stays; it's
  real entry data the site just chooses to ignore). EDN frontmatter and
  bodies with no frontmatter pass through unchanged, byte-for-byte."
  [raw]
  (if-let [open (re-find #"^---[ \t]*\r?\n" raw)]
    (let [after-open (subs raw (count open))
          m (re-matcher #"(?m)^---[ \t]*\r?\n" after-open)]
      (if (.find m)
        (let [fm-end (.end m)
              header (subs after-open 0 fm-end)
              body (subs after-open fm-end)]
          (str open
               (str/replace header #"(?m)^[ \t]*publish[ \t]*:.*\r?\n" "")
               body))
        raw))
    raw))

(defn- publish-draft!
  "Core of publishing one draft: validate, refuse URL collisions, lint,
  file it under its authored `date` property (or today, if it has none),
  strip the publish toggle, and remove it from drafts/. Returns the base
  name on success. Throws rather than dying, so callers — the interactive
  `bb publish` and the unattended `bb publish-queued` — decide how to
  handle failure."
  [cfg index src]
  (let [root (:content-path cfg)
        base (str/replace (fs/file-name src) #"\.md$" "")
        raw (slurp (str src))
        {:keys [meta]} (content/parse-frontmatter raw (str src))]
    ;; Validate with the same parser the server uses — a broken file should
    ;; fail here, not after it's live.
    (content/check-type! cfg meta (str src))
    (let [{authored-date :date} (content/workflow-properties raw (str src))
          date (or authored-date (LocalDate/now))
          y (.getYear date)
          m (.getMonthValue date)
          d (.getDayOfMonth date)
          dir (fs/path root (str y) (format "%02d" m) (format "%02d" d))
          target (fs/path dir (str base ".md"))
          ;; The same slug the server will derive: a `slug` property pins
          ;; it, otherwise it comes from the filename.
          slug (or (:slug meta) (util/slugify base))
          url (util/entry-url {:date {:year y :month m :day d} :slug slug})]
      ;; No two published entries may share a URL (slug + date) — distinct
      ;; titles that slugify identically collide too, not just files with
      ;; the same name.
      (when-let [existing (get (:by-path index) url)]
        (throw (ex-info (str "URL collision: " url " already belongs to " (:file existing))
                        {:reason :url-collision :url url :existing (:file existing)})))
      (when (fs/exists? target)
        (throw (ex-info (str "Target already exists: " target) {:target (str target)})))
      (lint-draft! cfg index (get (:drafts index) base))
      (fs/create-dirs dir)
      ;; Not fs/move — the publish toggle must not leak into the public
      ;; repo, so the file that lands in the date folder has it stripped.
      (spit (str target) (strip-publish-property raw))
      (fs/delete src)
      (println "Published:" (str target))
      (println "URL path:  " url)
      (when authored-date
        (println (str "Date:      " date " (from the draft's date property)")))
      base)))

(defn- draft-status
  "Every drafts/*.md file paired with its authored workflow properties
  (:date, :publish), its :type and its parsed :meta — read leniently with
  content/parse-frontmatter, never validated with check-type!, so a
  typo'd type shows up rather than crashing the scan. A draft whose
  frontmatter fails to parse (e.g. a garbled date property) carries
  :error in place of :date/:type, but still reports :publish (the toggle
  and the date fail independently) so a queued-but-broken draft is
  recognisably queued. Used by `bb drafts` (every draft), the publish
  queue (just the ones with publish: true), and the interactive pickers
  (which also read :tags — nil-safe: a draft with none carries an empty
  vec)."
  [root]
  (let [dir (fs/path root "drafts")]
    (when (fs/exists? dir)
      (map (fn [f]
             (let [base (str/replace (fs/file-name f) #"\.md$" "")
                   raw (try (slurp (str f)) (catch Exception _ nil))]
               (try
                 (let [{:keys [meta]} (content/parse-frontmatter raw (str f))
                       {:keys [date publish]} (content/workflow-properties raw (str f))]
                   {:file f :base base :date date :publish publish
                    :type (or (:type meta) :post) :meta meta
                    :tags (vec (:tags meta))})
                 (catch Exception e
                   {:file f :base base :error (ex-message e)
                    :publish (content/queued-flag raw (str f))}))))
           (fs/list-dir dir "*.md")))))

(defn- target-url
  "The canonical URL a draft would publish to: its authored date (or
  today) and its slug — a `slug` property pins it, else the filename
  slugifies. The same slug + date the server derives and that
  publish-draft! guards against colliding."
  [{:keys [base date meta]}]
  (let [d (or date (LocalDate/now))]
    (util/entry-url {:date {:year (.getYear d) :month (.getMonthValue d)
                            :day (.getDayOfMonth d)}
                     :slug (or (:slug meta) (util/slugify base))})))

(defn- publish-failure
  "Why a queued draft would fail to publish right now, or nil if it would
  succeed: :broken-workflow-properties (its date/publish won't parse) or
  :url-collision (an already-published entry owns the URL it would land
  at). Purely derived — no persisted state — so a failure clears itself
  once the draft is fixed, un-queued, published, or the colliding entry
  moves. Both the flush (to notify) and `bb drafts` (to mark) read it."
  [index {:keys [error] :as d}]
  (cond
    error :broken-workflow-properties
    (get (:by-path index) (target-url d)) :url-collision))

(defn- failure-blurb [reason]
  (case reason
    :url-collision "its URL (slug + date) is already taken"
    :broken-workflow-properties "its date/publish properties won't parse"
    "publishing threw"))

(defn- publish-queue!
  "Publishes every queued draft, in authored-date order, then pushes
  once. A draft that fails to publish never blocks the rest: it's warned
  about and — because the flush may be running unattended (the autopublish
  agent) — raised as a desktop notification too, so the failure is
  noticeable rather than buried in a log. The shared core of bare `bb
  publish` and `bb publish-queued`."
  [no-git?]
  (let [cfg (config/load-config :dev)
        root (:content-path cfg)
        index (try (content/build-index cfg)
                   (catch Exception e
                     (die "Content error, not publishing: " (ex-message e))))
        queued (sort-by (juxt (comp nil? :date) :date)
                        (filter :publish (draft-status root)))
        fail! (fn [{:keys [base] :as d} detail]
                (warn "failed to publish " base " — " detail)
                (notify! "Publish failed"
                         (str base " — " (failure-blurb (publish-failure index d)))))]
    (if (empty? queued)
      (println "No drafts queued (set the publish property to true to queue one).")
      (let [;; doall — this is side-effecting (moves files); --no-git skips
            ;; the str/join that would otherwise force it, so every draft
            ;; would stop getting published after the first one left lazy.
            published (doall
                       (keep (fn [{:keys [file error] :as d}]
                              (if error
                                (do (fail! d error) nil)
                                (try
                                  (publish-draft! cfg index file)
                                  (catch Exception e
                                    (fail! d (ex-message e))
                                    nil))))
                            queued))]
        (when (seq published)
          (if no-git?
            (println "Skipped git (--no-git). Run `bb sync` when you're ready to push.")
            (mirror-and-push! cfg (str "Publish " (str/join ", " published)))))))))

(defn- draft-sort-key
  "Sort key for `bb drafts` and the interactive publish picker: queued
  drafts first (in the order `bb publish` would publish them — authored
  date ascending, nil last), then everything else, alphabetically by
  filename for a stable order."
  [{:keys [publish date base]}]
  [(not publish) (nil? date) date base])

(defn- publish-named!
  "Publish one draft by name: find it, gate on a clean content tree (the
  same broken-tree gate as the queue flush — nothing publishes while the
  tree won't index), file it, and — unless --no-git — mirror and push.
  The core of both `bb publish <name>` and picking a single draft
  interactively."
  [fname no-git?]
  (let [cfg (config/load-config :dev)
        root (:content-path cfg)
        src (find-draft root fname)]
    (when-not src
      (die "No such draft: " fname "\nUsage: bb publish [<draft name>] [--no-git]"))
    ;; The same broken-tree gate as the queue flush: nothing publishes
    ;; while the content tree doesn't index cleanly.
    (let [index (try (content/build-index cfg)
                     (catch Exception e
                       (die "Content error, not publishing: " (ex-message e))))
          base (try (publish-draft! cfg index src)
                    (catch Exception e
                      (die (ex-message e))))]
      (if no-git?
        (println "Skipped git (--no-git). Run `bb sync` when you're ready to push.")
        (mirror-and-push! cfg (str "Publish " base))))))

(def ^:private publish-all-queued
  "Sentinel option offered at the top of the interactive publish picker
  when drafts are queued — selecting it flushes the whole queue."
  ::publish-all-queued)

(defn- publish-interactive!
  "The picker behind a bare, interactive `bb publish`: choose a single
  draft to publish, or — when any are queued — the queue-flush shortcut at
  the top (equivalent to bare `bb publish` in a script). Drafts are listed
  in the same order `bb drafts` shows them."
  [no-git?]
  (let [cfg (config/load-config :dev)
        drafts (->> (draft-status (:content-path cfg))
                    (remove :error)
                    (sort-by draft-sort-key))
        queued (filter :publish drafts)]
    (if (empty? drafts)
      (println "No drafts to publish (bb new <type> to start one).")
      (let [options (concat (when (seq queued) [publish-all-queued]) drafts)
            render (fn [o]
                     (if (= o publish-all-queued)
                       (str "▶ Publish all " (count queued) " queued")
                       (str (if (:publish o) "[queued] " "         ") (:base o))))
            choice (tui/choose options :label "Publish which draft?" :render render)]
        (cond
          (nil? choice) (println "Nothing selected.")
          (= choice publish-all-queued) (publish-queue! no-git?)
          :else (publish-named! (:base choice) no-git?))))))

(defn publish
  "bb publish [name words...] [--no-git] — the manual publish. Given a
  name: validates that draft, lints it, and files it into its authored
  `date` property's YYYY/MM/DD/ folder in the vault (today's, if it has
  none). Given no name at an interactive terminal: presents a picker of
  every draft (queued ones marked, with a shortcut to flush the whole
  queue) so you needn't type a name. Given no name with no terminal (a
  script, the launchd agent's context): publishes every draft marked
  publish: true, the same as `bb publish-queued`. No path publishes
  anything while the content tree has errors. Either way, unless
  --no-git, mirrors the vault into the publish repo and pushes once; the
  live site picks it up on its next timed pull, or immediately if you
  restart the machine."
  [& args]
  (let [no-git? (boolean (some #{"--no-git"} args))
        fname (str/join " " (remove #(str/starts-with? % "--") args))]
    (cond
      (not (str/blank? fname)) (publish-named! fname no-git?)
      (tui/interactive?) (publish-interactive! no-git?)
      :else (publish-queue! no-git?))))

(defn publish-queued
  "bb publish-queued [--no-git] — same as bare `bb publish`: publishes
  every draft with publish: true in its frontmatter, in authored-date
  order, then pushes once. Kept as its own task name because it's the
  stable name the installed autopublish launchd agent invokes — the
  agent's plist calls it by name, so it can't be renamed out from under
  an install without reinstalling. Never publishes from a broken content
  tree, and one bad draft is warned about and skipped rather than
  blocking the rest of the queue."
  [& args]
  (publish-queue! (boolean (some #{"--no-git"} args))))

(defn- draft-line
  "One line of `bb drafts` output for a draft-status entry: the queue
  marker, its authored date (or that it has none), its type, and its
  filename — or, if its frontmatter failed to parse, the error in place
  of date/type. A queued draft that would collide on its URL carries the
  clash appended, so the preview shows what a flush would refuse."
  [{:keys [base date publish type error collides-with]}]
  (str (if publish "[queued] " "         ")
       (if error
         (str "ERROR: " error)
         (str (format "%-10s" (if date (str date) "no date")) "  " (name type)))
       "  " base
       (when collides-with (str "  ⚠ URL already taken: " collides-with))))

(defn list-drafts
  "bb drafts — lists every draft in drafts/, queued ones first in the
  order a bare `bb publish` would publish them, so you can see what it
  (or `bb publish-queued`) would do before running it — including a queued
  draft that would fail to publish because its URL is already taken."
  [& _]
  (let [cfg (config/load-config :dev)
        ;; The index lets us flag URL collisions; if the tree won't build
        ;; the listing still works, just without collision markers.
        index (try (content/build-index cfg) (catch Exception _ nil))
        drafts (draft-status (:content-path cfg))]
    (if (empty? drafts)
      (println "No drafts.")
      (do
        (doseq [d (sort-by draft-sort-key drafts)]
          (println (draft-line
                    (cond-> d
                      (and index (:publish d) (not (:error d))
                           (= :url-collision (publish-failure index d)))
                      (assoc :collides-with (target-url d))))))
        (println)
        (println "Publish everything queued: bb publish")
        (println "Publish just one draft:    bb publish <name>")))))

;; --- autopublish: the launchd agent that runs publish-queued ------------

(def ^:private autopublish-label "com.website.autopublish")

(defn- autopublish-plist-path []
  (fs/expand-home (str "~/Library/LaunchAgents/" autopublish-label ".plist")))

(defn- autopublish-log-path []
  (fs/expand-home "~/Library/Logs/website-autopublish.log"))

(defn- uid []
  (str/trim (:out (p/sh "id" "-u"))))

(defn- launchd-target [u]
  (str "gui/" u "/" autopublish-label))

(defn- plist-xml [{:keys [bb-path work-dir log-path]}]
  (str
   "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
   "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
   "<plist version=\"1.0\">\n"
   "<dict>\n"
   "  <key>Label</key>\n"
   "  <string>" autopublish-label "</string>\n"
   "  <key>ProgramArguments</key>\n"
   "  <array>\n"
   "    <string>" bb-path "</string>\n"
   "    <string>publish-queued</string>\n"
   "  </array>\n"
   "  <key>WorkingDirectory</key>\n"
   "  <string>" work-dir "</string>\n"
   "  <key>StartInterval</key>\n"
   "  <integer>300</integer>\n"
   "  <key>RunAtLoad</key>\n"
   "  <true/>\n"
   "  <key>StandardOutPath</key>\n"
   "  <string>" log-path "</string>\n"
   "  <key>StandardErrorPath</key>\n"
   "  <string>" log-path "</string>\n"
   "  <key>EnvironmentVariables</key>\n"
   "  <dict>\n"
   "    <key>PATH</key>\n"
   "    <string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string>\n"
   "  </dict>\n"
   "</dict>\n"
   "</plist>\n"))

(defn- autopublish-install! []
  (when-not (fs/exists? "bb.edn")
    (die "Run `bb autopublish install` from the project root (no bb.edn here)."))
  (let [bb-path (some-> (fs/which "bb") str)]
    (when-not bb-path
      (die "Couldn't find `bb` on PATH."))
    (let [work-dir (str (fs/cwd))
          log-path (str (autopublish-log-path))
          plist (str (autopublish-plist-path))
          u (uid)]
      (fs/create-dirs (fs/parent plist))
      (spit plist (plist-xml {:bb-path bb-path :work-dir work-dir :log-path log-path}))
      ;; Clear any old copy first; failure here just means there wasn't one.
      (p/sh "launchctl" "bootout" (launchd-target u))
      (let [{:keys [exit err]} (p/sh "launchctl" "bootstrap" (str "gui/" u) plist)]
        (when-not (zero? exit)
          (die "launchctl bootstrap failed: " (str/trim (str err)))))
      (println "Installed" autopublish-label)
      (println "Runs:" bb-path "publish-queued")
      (println "Working directory:" work-dir)
      (println "Every 5 minutes, and once now (RunAtLoad).")
      (println "Log:" log-path)
      (println "Uninstall with: bb autopublish uninstall"))))

(defn- autopublish-uninstall! []
  (let [u (uid)
        plist (str (autopublish-plist-path))]
    (p/sh "launchctl" "bootout" (launchd-target u))
    (when (fs/exists? plist)
      (fs/delete plist))
    (println "Uninstalled" autopublish-label)))

(defn- tail-lines [path n]
  (when (fs/exists? path)
    (take-last n (str/split-lines (slurp (str path))))))

(defn- autopublish-status []
  (let [u (uid)
        {:keys [exit out]} (p/sh "launchctl" "print" (launchd-target u))]
    (if-not (zero? exit)
      (println "not installed (bb autopublish install)")
      (do
        (doseq [line (str/split-lines out)
                :when (re-find #"^\s*(state|last exit code)\s*=" line)]
          (println (str/trim line)))
        (let [log (autopublish-log-path)]
          (when (fs/exists? log)
            (doseq [line (tail-lines log 5)]
              (println line))))))))

(defn autopublish
  "bb autopublish [install|uninstall|status] — manages the launchd user
  agent that runs `bb publish-queued` every 5 minutes, so flipping the
  publish property on the phone reaches the live site without you
  touching the Mac. Defaults to status."
  [& args]
  (case (first args)
    "install" (autopublish-install!)
    "uninstall" (autopublish-uninstall!)
    (nil "status") (autopublish-status)
    (die "Usage: bb autopublish [install|uninstall|status]")))

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

(defn- suggest-tags-for!
  "Have the configured LLM read one draft and print proposed tags as a
  YAML block ready to paste into its properties."
  [cfg src]
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
    (doseq [t tags] (println (str "  - " t)))))

(defn- pick-untagged-draft
  "Interactive picker for `bb suggest-tags` with no name: the drafts that
  have no tags yet, alphabetical, rendered with their type. Returns the
  chosen draft's file, or nil (nothing to tag, or the author cancelled)."
  [root]
  (let [candidates (->> (draft-status root)
                        (remove :error)
                        (filter (comp empty? :tags))
                        (sort-by :base))]
    (if (empty? candidates)
      (do (println "No drafts are missing tags.") nil)
      (some-> (tui/choose candidates
                          :label "Draft to tag (no tags yet):"
                          :render (fn [{:keys [base type]}]
                                    (format "%-7s %s" (name type) base)))
              :file))))

(defn suggest-tags
  "bb suggest-tags [draft name] — has the configured LLM read a draft and
  propose tags, printed as a YAML block ready to paste into the draft's
  properties. With no name, pick from the drafts that have no tags yet."
  [& args]
  (let [cfg (config/load-config :dev)
        root (:content-path cfg)
        fname (str/join " " args)
        src (if (str/blank? fname)
              (pick-untagged-draft root)
              (or (find-draft root fname)
                  (die "No such draft: " fname)))]
    (when src
      (suggest-tags-for! cfg src))))
