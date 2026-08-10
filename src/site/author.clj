(ns site.author
  "Authoring tasks (`bb new`, `bb suggest-tags`, `bb suggest-slug`). These
  run in babashka only — they use babashka.fs and babashka.process, which
  the server never needs. They always operate on the dev environment: the
  local content directory."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [site.tui :as tui]
            [site.config :as config]
            [site.content :as content]
            [site.util :as util])
  (:import [java.time LocalDate]))

(defn frontmatter-template
  "YAML frontmatter, matching what Obsidian's Properties panel edits.
  `date` defaults to today — the day you start the draft, but it's yours
  to edit (e.g. to backdate something written over several days). `publish`
  starts false; it's a legacy workflow toggle older drafts still
  carry, but the site ignores it now — publishing happens through the
  admin app. A post needs nothing beyond tags — the filename is the title."
  [type]
  (let [date (LocalDate/now)]
    (case type
      :post (str "---\ndate: " date "\ntags: []\npublish: false\n---\n\n")
      :link (str "---\ntype: link\ndate: " date "\nlink: \nvia: \ntags: []\npublish: false\n---\n\n")
      :quote (str "---\ntype: quote\ndate: " date "\nauthor: \nsource: \nvia: \ntags: []\npublish: false\n---\n\n")
      :release (str "---\ntype: release\ndate: " date "\nlink: \ntags: []\npublish: false\n---\n\n")
      :tool (str "---\ntype: tool\ndate: " date "\nlink: \nsource: \ntags: []\npublish: false\n---\n\n")
      (str "---\ntype: " (name type) "\ndate: " date "\ntags: []\npublish: false\n---\n\n"))))

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

(defn- foreign-redirect-rows
  "[[old-url live-url] ...] for every previous URL on a foreign host —
  the redirects that must be served at the old domains' edge, since this
  site never sees those requests. Same-site previous URLs are excluded:
  the app itself 301s them."
  [base-url entries]
  (for [e entries
        url (:previous-urls e)
        :when (nil? (content/local-previous-path base-url url))]
    [url (str base-url (:path e))]))

(defn redirects
  "bb redirects — every previous URL of the published entries, split by
  who serves the redirect. Same-site paths the app already 301s are
  summarized on stderr; foreign-host URLs — the ones the old domains
  must redirect themselves — come out on stdout as a Cloudflare
  bulk-redirects CSV, ready for `bb redirects > redirects.csv`."
  [& _]
  (let [cfg (config/load-config :dev)
        index (try (content/build-index cfg)
                   (catch Exception e (die "Content error: " (ex-message e))))
        rows (foreign-redirect-rows (:base-url cfg) (:entries index))]
    (binding [*out* *err*]
      (println (str (count (:redirects index))
                    " same-site previous paths — the app serves these 301s itself."))
      (println (str (count rows)
                    " foreign-host previous URLs — set these up where the old domain lives:")))
    (when (seq rows)
      (println "source_url,target_url,status_code")
      (doseq [[old live] rows]
        (println (str old "," live ",301"))))))

(defn new-draft
  "bb new [type] [title words...] — scaffolds drafts/<Title>.md in the
  content repo. The filename is the title, kept verbatim — spaces,
  capitalisation and all. With no type, pick one from the configured entry types; when
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
  "drafts/<name>.md by exact name, else by slug (so `bb suggest-tags
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

(defn- draft-status
  "Every drafts/*.md file paired with its authored workflow properties
  (:date, :publish), its :type and its parsed :meta — read leniently with
  content/parse-frontmatter, never validated with check-type!, so a
  typo'd type shows up rather than crashing the scan. A draft whose
  frontmatter fails to parse (e.g. a garbled date property) carries
  :error in place of :date/:type, but still reports :publish (the toggle
  and the date fail independently) so a draft still carrying the legacy
  flag is recognisably marked. Used by `bb drafts` (every draft) and the
  interactive pickers (which also read :tags — nil-safe: a draft with
  none carries an empty vec)."
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
  slugifies. The same slug + date the server derives for a published
  entry, so a draft landing here would collide with it."
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
  moves. `bb drafts` reads it to mark a queued draft that would fail."
  [index {:keys [error] :as d}]
  (cond
    error :broken-workflow-properties
    (get (:by-path index) (target-url d)) :url-collision))

(defn- draft-sort-key
  "Sort key for `bb drafts`: queued drafts first (authored date ascending,
  nil last), then everything else, alphabetically by filename for a
  stable order."
  [{:keys [publish date base]}]
  [(not publish) (nil? date) date base])

(defn- draft-line
  "One line of `bb drafts` output for a draft-status entry: the queue
  marker, its authored date (or that it has none), its type, and its
  filename — or, if its frontmatter failed to parse, the error in place
  of date/type. A draft whose target URL is already owned by a published
  entry carries the clash appended."
  [{:keys [base date publish type error collides-with]}]
  (str (if publish "[queued] " "         ")
       (if error
         (str "ERROR: " error)
         (str (format "%-10s" (if date (str date) "no date")) "  " (name type)))
       "  " base
       (when collides-with (str "  ⚠ URL already taken: " collides-with))))

(defn list-drafts
  "bb drafts — lists every draft in drafts/, queued ones first. There is
  no publish flush anymore; the queue marker just surfaces the draft's
  legacy publish: true flag, which the site otherwise ignores. The
  collision marker still warns when a queued draft's target URL is
  already owned by a published entry."
  [& _]
  (let [cfg (config/load-config :dev)
        ;; The index lets us flag URL collisions; if the tree won't build
        ;; the listing still works, just without collision markers.
        index (try (content/build-index cfg) (catch Exception _ nil))
        drafts (draft-status (:content-path cfg))]
    (if (empty? drafts)
      (println "No drafts.")
      (doseq [d (sort-by draft-sort-key drafts)]
        (println (draft-line
                  (cond-> d
                    (and index (:publish d) (not (:error d))
                         (= :url-collision (publish-failure index d)))
                    (assoc :collides-with (target-url d)))))))))

(defn- ask-llm
  "Runs the configured :llm-command through an interactive shell so
  aliases and shell functions (like a wrapped `claude`) resolve; the
  prompt goes in on stdin. `+m` disables job control at invocation: an
  interactive shell otherwise grabs the controlling terminal's foreground
  process group at startup, which both deadlocks the captured subprocess
  and (once the spinner is drawing) gets us SIGTTOU'd — suspended — for
  writing to a terminal we no longer own. Unsetting monitor from inside the
  command string is too late; the grab already happened."
  [cfg prompt]
  (let [cmd (or (:llm-command cfg) "claude")
        {:keys [exit out err]} (p/sh {:in prompt :out :string :err :string}
                                     "zsh" "+m" "-ic" (str cmd " -p"))]
    (when-not (zero? exit)
      (die "LLM command failed (" cmd " -p): " (str/trim (str err))))
    out))

(defn- existing-tags
  "Tags already in use across the site, most-used first, as strings — the
  vocabulary we hand the tagging model so it can reuse a fitting tag.
  Best-effort: yields nothing if the content index won't build, so a broken
  file elsewhere never blocks a suggestion."
  [cfg]
  (try
    (map (comp name first) (:tag-counts (content/build-index cfg)))
    (catch Exception _ nil)))

(defn- tag-prompt
  "Prompt for the tagging model. Strict about output shape — the parser
  still guards against stray prose, but a clear contract keeps the model
  from wrapping the tags in commentary in the first place. `existing` is
  the site's current tag vocabulary (may be empty), shown so the model can
  reuse a fitting tag instead of coining a near-duplicate."
  [title body existing]
  (str "You are tagging an entry on a personal blog. Choose 2-8 topic tags "
       "that capture what it is about.\n\n"
       "Rules:\n"
       "- Output ONLY the tags, one per line.\n"
       "- Each tag is lowercase kebab-case, e.g. software-engineering, llms, clojure.\n"
       "- You may invent new, specific tags whenever they fit. You are not necessarily "
       "limited to the existing vocabulary, but you may also use existing tags where appropriate.\n"
       "- No preamble, no numbering, no bullets, no commentary — nothing but the tags.\n\n"
       (when (seq existing)
         (str "The existing vocabulary — tags already used on the site:\n"
              (str/join ", " existing) "\n\n"))
       "Title: " title "\n\n"
       body))

(defn- tag-line?
  "Whether a cleaned line plausibly *is* a tag rather than the model's
  prose: a short run (≤4 words) of letters, digits, spaces and hyphens,
  with no sentence punctuation. Drops leakage like \"Let me just give
  tags:\" or \"data-engineering? No\" before it can be slugified to junk."
  [s]
  (boolean (re-matches #"(?i)[a-z0-9]+(?:[ -][a-z0-9]+){0,3}" s)))

(defn- parse-tags
  "Turn the model's reply into a clean, de-duplicated tag list: strip list
  markers and stray quoting, keep only lines that look like tags, slugify."
  [raw]
  (->> (str/split-lines raw)
       (map #(-> %
                 str/trim
                 (str/replace #"^(?:[-*#>]|\d+[.)])\s+" "")
                 (str/replace #"[`\"']" "")
                 str/trim))
       (filter tag-line?)
       (map util/slugify)
       (remove str/blank?)
       distinct
       (take 10)))

(defn- render-tags-yaml
  "Tags as an Obsidian-style YAML block list (what the Properties panel
  writes), including the trailing newline."
  [tags]
  (apply str "tags:\n" (map #(str "  - " % "\n") tags)))

(defn- edit-frontmatter
  "Apply `f` to the YAML frontmatter property block (the text between the
  opening and closing `---`) and return the rebuilt file, or nil when `raw`
  has no YAML frontmatter to edit. Only the header is touched; the body
  passes through unchanged."
  [raw f]
  (when-let [open (re-find #"^---[ \t]*\r?\n" raw)]
    (let [after-open (subs raw (count open))
          m (re-matcher #"(?m)^---[ \t]*\r?\n" after-open)]
      (when (.find m)
        (let [props (subs after-open 0 (.start m))
              closing (subs after-open (.start m) (.end m))
              body (subs after-open (.end m))]
          (str open (f props) closing body))))))

(defn- append-property
  "props with `line` appended (props' own trailing newline ensured first) —
  how set-tags/set-slug add a property the frontmatter didn't already have."
  [props line]
  (str props
       (when-not (or (str/blank? props) (str/ends-with? props "\n")) "\n")
       line))

(defn- set-tags
  "Return `raw` with its YAML frontmatter's `tags` property set to `tags`,
  replacing any existing tags block (inline `tags: []` or a block list) or
  appending one if absent. Only the frontmatter header is touched; the body
  passes through unchanged. Returns nil when `raw` has no YAML frontmatter
  to edit, so the caller can fall back to printing for a manual paste."
  [raw tags]
  (edit-frontmatter raw
   (fn [props]
     (let [block (render-tags-yaml tags)
           tags-re #"(?m)^[ \t]*tags[ \t]*:[^\n]*(?:\r?\n[ \t]+-[^\n]*)*\r?\n?"]
       (if (re-find tags-re props)
         (str/replace-first props tags-re block)
         (append-property props block))))))

(defn- set-slug
  "Return `raw` with its YAML frontmatter's `slug` property set to `slug`,
  replacing an existing slug line or appending one. Returns nil when there
  is no YAML frontmatter to edit."
  [raw slug]
  (edit-frontmatter raw
   (fn [props]
     (let [line (str "slug: " slug "\n")
           slug-re #"(?m)^[ \t]*slug[ \t]*:[^\n]*\r?\n?"]
       (if (re-find slug-re props)
         (str/replace-first props slug-re line)
         (append-property props line))))))

(defn- suggest-tags-for!
  "Ask the configured LLM for tags for one entry, let the author pick which
  to keep, then write them into the entry's frontmatter (merging with any it
  already has). Falls back to printing a YAML block to paste by hand if the
  file has no YAML frontmatter to edit."
  [cfg src]
  (let [base (str/replace (fs/file-name src) #"\.md$" "")
        raw (slurp (str src))
        {:keys [meta body]} (content/parse-frontmatter raw (str src))
        had (map name (:tags meta))
        reply (tui/spin (str "Asking " (or (:llm-command cfg) "claude") " for tags…")
                        #(ask-llm cfg (tag-prompt base body (existing-tags cfg))))
        suggested (vec (remove (set had) (parse-tags reply)))]
    (when (empty? suggested)
      (warn "The LLM returned no usable tags — add your own with +, or cancel."))
    (let [chosen (tui/choose-many suggested
                                  :label (str "Tags for \"" base "\":")
                                  :render identity
                                  :add (fn [s] (not-empty (util/slugify s))))]
      (cond
        (nil? chosen) (println "Cancelled — no tags added.")
        (empty? chosen) (println "Nothing selected — no tags added.")
        :else
        (let [merged (distinct (concat had chosen))]
          (if-let [updated (set-tags raw merged)]
            (do
              (spit (str src) updated)
              (println (str "Added to " base ":"))
              (doseq [t chosen] (println (str "  + " t))))
            (do
              (warn "No YAML frontmatter to edit — paste this into " base ":")
              (print (render-tags-yaml merged)))))))))

(defn- untagged-published
  "Published entries with no tags yet, as picker candidates: an absolute
  :file (the index stores paths relative to the content root), a display
  :base, its :type, and :published? to flag it in the list. Reads the
  content index; if that won't build, warns and yields nothing so the
  picker still offers drafts."
  [cfg]
  (try
    (->> (:entries (content/build-index cfg))
         (filter (comp empty? :tags))
         (map (fn [{:keys [file file-title type]}]
                {:file (fs/path (:content-path cfg) file)
                 :base file-title
                 :type type
                 :published? true})))
    (catch Exception e
      (warn "couldn't scan published entries for missing tags: " (ex-message e))
      nil)))

(defn- pick-untagged
  "Interactive picker for `bb suggest-tags` with no name: every draft or
  published entry that has no tags yet, alphabetical, rendered with its
  type (published ones flagged — an entry can go out untagged and want
  tags after the fact). Returns the chosen file, or nil (nothing to tag,
  or the author cancelled)."
  [cfg root]
  (let [drafts (->> (draft-status root)
                    (remove :error)
                    (filter (comp empty? :tags))
                    (map #(assoc (select-keys % [:file :base :type]) :published? false)))
        candidates (->> (concat drafts (untagged-published cfg))
                        (sort-by :base))]
    (if (empty? candidates)
      (do (println "Nothing is missing tags.") nil)
      (some-> (tui/choose candidates
                          :label "Entry to tag (no tags yet):"
                          :render (fn [{:keys [base type published?]}]
                                    (format "%-7s %s%s" (name type) base
                                            (if published? "  ·  published" ""))))
              :file))))

(defn suggest-tags
  "bb suggest-tags [draft name] — has the configured LLM read a draft,
  propose tags, and (after you pick which to keep) write them into the
  draft's frontmatter. With no name, pick from any draft or published
  entry that has no tags yet."
  [& args]
  (let [cfg (config/load-config :dev)
        root (:content-path cfg)
        fname (str/join " " args)
        src (if (str/blank? fname)
              (pick-untagged cfg root)
              (or (find-draft root fname)
                  (die "No such draft: " fname)))]
    (when src
      (suggest-tags-for! cfg src))))

(defn- slug-prompt
  "Prompt for the slug model — the URL tail for the entry. Same output
  contract as the tag prompt, so the tag parser cleans the reply too."
  [title body]
  (str "You are choosing a URL slug for an entry on a personal blog.\n\n"
       "Rules:\n"
       "- Suggest 3 or 4 candidates, one per line.\n"
       "- Each slug is lowercase kebab-case, short (2-4 words), and reads well "
       "in a URL, e.g. repl-driven-development, why-clojure.\n"
       "- Capture the core topic; concise and memorable beats a literal restatement.\n"
       "- No preamble, no numbering, no bullets, no commentary — nothing but the slugs.\n\n"
       "Title: " title "\n\n"
       body))

(defn- title-prompt
  "Prompt for the quote-title model: a short phrase that captures the
  quote, used as both the entry's title and — slugified — its URL slug.
  Same terse output contract as the slug and tag prompts."
  [body]
  (str "You are choosing a short title for a quote on a personal blog. "
       "The title is shown as a heading and, lowercased and hyphenated, "
       "becomes the entry's URL slug.\n\n"
       "Rules:\n"
       "- Suggest 3 or 4 candidates, one per line.\n"
       "- Each is a short phrase, 2 to 5 words, that captures the heart of "
       "the quote; concise and evocative beats a literal restatement, "
       "e.g. Simplicity is a choice.\n"
       "- Sentence case, no trailing punctuation, no quotation marks.\n"
       "- No preamble, no numbering, no bullets, no commentary — nothing but "
       "the phrases.\n\n"
       body))

(defn- phrase-line?
  "Whether a cleaned line plausibly is a short title phrase rather than the
  model's prose: 1-6 words containing a letter or digit, with no
  sentence-ending punctuation."
  [s]
  (and (<= 1 (count (str/split (str/trim s) #"\s+")) 6)
       (re-find #"[A-Za-z0-9]" s)
       (not (re-find #"[.!?:]" s))))

(defn- parse-phrases
  "Turn the model's reply into clean, de-duplicated title phrases: strip
  list markers and stray quoting, keep only lines that look like a short
  phrase, and preserve their readable casing (unlike parse-tags, which
  slugifies)."
  [raw]
  (->> (str/split-lines raw)
       (map #(-> %
                 str/trim
                 (str/replace #"^(?:[-*#>]|\d+[.)])\s+" "")
                 (str/replace #"[`\"']" "")
                 str/trim))
       (filter phrase-line?)
       distinct
       (take 6)))

(defn- sanitize-note-title
  "A model-suggested phrase reduced to a safe Obsidian note filename: drop
  the characters Obsidian and the filesystem reject in a name, collapse runs
  of whitespace, trim. The slug still derives from the result."
  [s]
  (-> (str s)
      (str/replace #"[\\/:*?\"<>|]" "")
      (str/replace #"\s+" " ")
      str/trim))

(defn- slug-collision-fn
  "A lookup: slug → the published entry it would collide with, or nil. The
  key is the draft's URL (its authored `date`, or today, + slug) — the
  same URL a published entry would occupy, so two entries sharing a slug
  on different dates don't count. Best-effort: if the content index won't
  build it treats nothing as taken."
  [cfg date]
  (let [by-path (try (:by-path (content/build-index cfg)) (catch Exception _ nil))
        y (.getYear date) m (.getMonthValue date) d (.getDayOfMonth date)]
    (fn [slug]
      (when by-path
        (get by-path (util/entry-url {:date {:year y :month m :day d} :slug slug}))))))

(defn- suggest-plain-slug!
  "Ask the configured LLM for slug candidates for a non-quote draft, let
  the author pick one (or type their own with +), then write it to the
  draft's `slug` property. Never offers — and refuses a typed — slug that
  would collide with an existing entry's URL. Falls back to printing the
  line to paste by hand if the file has no YAML frontmatter to edit."
  [cfg src raw meta body base date]
  (let [current (or (:slug meta) (util/slugify base))
        collides (slug-collision-fn cfg date)
        reply (tui/spin (str "Asking " (or (:llm-command cfg) "claude") " for slugs…")
                        #(ask-llm cfg (slug-prompt base body)))
        ;; Slugs clean up exactly like tags — kebab-case, one per line —
        ;; then drop the current slug and any that would collide.
        suggested (vec (remove #(or (= % current) (collides %)) (parse-tags reply)))
        chosen (tui/choose suggested
                           :label (str "Slug for \"" base "\" (now: " current "):")
                           :render identity
                           :add (fn [s] (not-empty (util/slugify s))))]
    (cond
      (nil? chosen) (println "Cancelled — slug unchanged.")
      (= chosen current) (println "Slug unchanged.")
      (collides chosen) (die "Slug \"" chosen "\" would collide with "
                             (:path (collides chosen)) " — nothing written.")
      :else
      (if-let [updated (set-slug raw chosen)]
        (do (spit (str src) updated)
            (println (str "Set slug of " base " to: " chosen)))
        (do (warn "No YAML frontmatter to edit — add this to " base ":")
            (println (str "slug: " chosen)))))))

(defn- suggest-quote-name!
  "The quote branch: a quote has no meaningful title and only a
  filename-derived slug, so suggest one short phrase and rename the draft
  file to it. The filename is the note's title and, slugified, its URL
  slug — no `title`/`slug` properties, the same single source every
  other type uses. The phrase is slugified for the collision check, so a URL
  that's already taken is refused; an existing draft of that name is never
  clobbered."
  [cfg src base body date]
  (let [collides (slug-collision-fn cfg date)
        current-slug (util/slugify base)
        reply (tui/spin (str "Asking " (or (:llm-command cfg) "claude") " for a title…")
                        #(ask-llm cfg (title-prompt body)))
        suggested (vec (remove #(let [s (util/slugify (sanitize-note-title %))]
                                  (or (str/blank? s) (= s current-slug) (collides s)))
                               (parse-phrases reply)))
        chosen (tui/choose suggested
                           :label (str "Title for the quote (now: " base "):")
                           :render identity
                           :add (fn [s] (not-empty (sanitize-note-title s))))]
    (if (nil? chosen)
      (println "Cancelled — draft not renamed.")
      (let [new-base (sanitize-note-title chosen)
            slug (util/slugify new-base)
            target (fs/path (fs/parent src) (str new-base ".md"))]
        (cond
          (str/blank? slug)
          (die "\"" chosen "\" has no letters or digits to slugify — nothing renamed.")
          (= new-base base)
          (println "Name unchanged.")
          (collides slug)
          (die "Slug \"" slug "\" (from \"" new-base "\") would collide with "
               (:path (collides slug)) " — nothing renamed.")
          (fs/exists? target)
          (die "A draft named \"" new-base "\" already exists — nothing renamed.")
          :else
          (do (fs/move src target)
              (println (str "Renamed: " base " → " new-base))
              (println (str "Slug:    " slug))))))))

(defn- suggest-slug-for!
  "Name one draft for its URL. A quote — untitled, addressed only by its
  filename — is renamed to a short suggested phrase (the note's title
  and, slugified, its URL); every other type gets a pinned `slug`
  property. Either way the author picks a candidate (or types their own with
  +), and a URL that would collide with an existing entry is refused."
  [cfg src]
  (let [base (str/replace (fs/file-name src) #"\.md$" "")
        raw (slurp (str src))
        {:keys [meta body]} (content/parse-frontmatter raw (str src))
        date (or (try (:date (content/workflow-properties raw (str src)))
                      (catch Exception _ nil))
                 (LocalDate/now))]
    (if (= :quote (:type meta))
      (suggest-quote-name! cfg src base body date)
      (suggest-plain-slug! cfg src raw meta body base date))))

(defn- default-quote-name?
  "A quote filename still in its scaffold form — `quote YYYY-MM-DD`, the
  auto name `bb new quote` gives an unnamed quote. These are the quotes
  `bb suggest-slug` offers to rename; a quote the author has already named
  is left alone."
  [base]
  (boolean (re-matches #"quote \d{4}-\d{2}-\d{2}" base)))

(defn- needs-slugging?
  "Whether a draft is a candidate for a no-name `bb suggest-slug`: a quote
  still under its auto-generated name (the flow renames it), or any other
  type that has no pinned `slug` property yet."
  [{:keys [type meta base]}]
  (if (= :quote type)
    (default-quote-name? base)
    (not (:slug meta))))

(defn- pick-slugless
  "Interactive picker for `bb suggest-slug` with no name: drafts that still
  want a URL — a quote under its auto-generated name (which the flow
  renames), or another type with no `slug` property yet — alphabetical,
  rendered with their type. Returns the chosen file, or nil (nothing to do,
  or the author cancelled)."
  [root]
  (let [candidates (->> (draft-status root)
                        (remove :error)
                        (filter needs-slugging?)
                        (map #(select-keys % [:file :base :type]))
                        (sort-by :base))]
    (if (empty? candidates)
      (do (println "Every draft already has a slug or a name.") nil)
      (some-> (tui/choose candidates
                          :label "Draft to name or slug:"
                          :render (fn [{:keys [base type]}]
                                    (format "%-7s %s" (name type) base)))
              :file))))

(defn suggest-slug
  "bb suggest-slug [draft name] — has the configured LLM read a draft,
  propose URL slugs, and (after you pick one, or type your own) pin the
  choice to the draft's `slug` property. For a quote — untitled and
  addressed only by its filename — it proposes a short phrase and renames
  the note to it instead, so the filename becomes both the note title and,
  slugified, the URL. With no name, pick from any draft that still wants a
  URL — an unnamed quote, or another type with no slug yet."
  [& args]
  (let [cfg (config/load-config :dev)
        root (:content-path cfg)
        fname (str/join " " args)
        src (if (str/blank? fname)
              (pick-slugless root)
              (or (find-draft root fname)
                  (die "No such draft: " fname)))]
    (when src
      (suggest-slug-for! cfg src))))
