(ns site.content
  "Loads the content repo (a folder of markdown files with EDN frontmatter)
  into an in-memory index.

  Files are the source of truth; everything built here is derived and
  rebuildable at any time.

  Layout of the content repo:
    drafts/<name>.md          — unpublished; served only via token-gated preview
    pages/<slug>.md           — static pages (about, ...)
    YYYY/MM/DD/<name>.md      — published entries; date comes from the path,
                                slug from the filename unless :slug overrides it"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [site.util :as util]))

(def frontmatter-delimiter ";;;")

(defn parse-frontmatter
  "Splits raw file content into {:meta <edn map> :body <markdown string>}.
  Frontmatter is an EDN map between two `;;;` lines at the top of the file."
  [raw file]
  (let [lines (str/split-lines raw)]
    (when-not (= frontmatter-delimiter (str/trim (or (first lines) "")))
      (throw (ex-info (str "Missing EDN frontmatter (file must start with ;;;): " file)
                      {:file file})))
    (let [end (->> (map-indexed vector (rest lines))
                   (filter #(= frontmatter-delimiter (str/trim (second %))))
                   ffirst)]
      (when-not end
        (throw (ex-info (str "Unterminated frontmatter (no closing ;;;): " file)
                        {:file file})))
      (let [meta-str (str/join "\n" (take end (rest lines)))
            body (str/join "\n" (drop (+ end 2) lines))
            meta (try (edn/read-string meta-str)
                      (catch Exception e
                        (throw (ex-info (str "Invalid EDN frontmatter in " file ": " (ex-message e))
                                        {:file file} e))))]
        (when-not (map? meta)
          (throw (ex-info (str "Frontmatter must be an EDN map: " file)
                          {:file file :meta meta})))
        {:meta meta :body (str/trim body)}))))

(defn check-type!
  "Validates :type against the configured :entry-types. Fails loudly so a
  typo can't silently coin a new type."
  [config meta file]
  (let [allowed (set (:entry-types config))
        t (:type meta)]
    (when-not (keyword? t)
      (throw (ex-info (str "Missing or non-keyword :type in " file)
                      {:file file :type t})))
    (when-not (allowed t)
      (throw (ex-info (str "Unknown entry type " t " in " file
                           " — allowed: " (pr-str (:entry-types config)))
                      {:file file :type t :allowed allowed})))
    t))

(def entry-path-re
  "Published entries live at YYYY/MM/DD/<name>.md relative to the content root."
  #"(\d{4})/(\d{2})/(\d{2})/([^/]+)\.md")

(defn- rel-path [root file]
  (-> (.toPath (io/file root))
      (.relativize (.toPath (io/file (str file))))
      str
      (str/replace "\\" "/")))

(defn- base-name [file]
  (str/replace (.getName (io/file (str file))) #"\.md$" ""))

(defn- normalize-tags [tags]
  (into #{} (map keyword) tags))

(defn load-entry
  "file → entry map. The date comes from the file's path; the slug from the
  filename unless overridden by :slug in frontmatter."
  [config root file]
  (let [rp (rel-path root file)
        [_ y m d fname] (re-matches entry-path-re rp)]
    (when-not y
      (throw (ex-info (str "Entry file not under YYYY/MM/DD/: " rp) {:file rp})))
    (let [{:keys [meta body]} (parse-frontmatter (slurp (io/file (str file))) rp)
          type (check-type! config meta rp)
          date {:year (parse-long y) :month (parse-long m) :day (parse-long d)}]
      (when-not (and (<= 1 (:month date) 12) (<= 1 (:day date) 31))
        (throw (ex-info (str "Invalid date in path: " rp) {:file rp :date date})))
      (let [entry (merge (dissoc meta :slug)
                         {:type type
                          :slug (or (:slug meta) fname)
                          :date date
                          :tags (normalize-tags (:tags meta))
                          :body body
                          :file rp})]
        (assoc entry :path (util/entry-url entry))))))

(defn load-draft
  "Drafts have no date (that's decided at publish time); they are addressed
  by filename and only served through the token-gated preview route."
  [config root file]
  (let [rp (rel-path root file)
        name (base-name file)
        {:keys [meta body]} (parse-frontmatter (slurp (io/file (str file))) rp)
        type (check-type! config meta rp)]
    (merge (dissoc meta :slug)
           {:type type
            :slug (or (:slug meta) name)
            :draft-name name
            :draft? true
            :tags (normalize-tags (:tags meta))
            :body body
            :file rp
            :path (str "/drafts/" name)})))

(defn load-page
  "Static pages: frontmatter is optional; the slug is the filename."
  [root file]
  (let [rp (rel-path root file)
        slug (base-name file)
        raw (slurp (io/file (str file)))
        {:keys [meta body]} (if (str/starts-with? (str/triml raw) frontmatter-delimiter)
                              (parse-frontmatter raw rp)
                              {:meta {} :body raw})]
    {:slug slug
     :title (or (:title meta) (str/capitalize slug))
     :body (str/trim body)
     :path (str "/" slug)}))

(defn- md-files [dir]
  (let [d (io/file dir)]
    (when (.isDirectory d)
      (->> (file-seq d)
           (filter #(and (.isFile %) (str/ends-with? (.getName %) ".md")))))))

(defn load-content
  "Walks the content repo → {:entries [...] :drafts {name entry} :pages {slug page}}.
  Markdown files that are neither dated entries, drafts, nor pages are ignored
  (so stray Obsidian files can't break the site)."
  [config]
  (let [root (:content-path config)
        classified (group-by (fn [f]
                               (let [rp (rel-path root f)]
                                 (cond
                                   (re-matches entry-path-re rp) :entry
                                   (str/starts-with? rp "drafts/") :draft
                                   (str/starts-with? rp "pages/") :page
                                   :else :ignored)))
                             (md-files root))]
    {:entries (mapv #(load-entry config root %) (:entry classified))
     :drafts (into {} (map (fn [f]
                             (let [d (load-draft config root f)]
                               [(:draft-name d) d])))
                   (:draft classified))
     :pages (into {} (map (fn [f]
                            (let [p (load-page root f)]
                              [(:slug p) p])))
                  (:page classified))}))

(defn- date-key [{:keys [date slug]}]
  [(:year date) (:month date) (:day date) slug])

(defn- link-neighbors
  "Adds :newer/:older pointers (path + title + type + date) to each entry.
  Entries are already sorted newest-first."
  [entries]
  (let [brief #(select-keys % [:path :title :type :date])]
    (vec (map-indexed
          (fn [i e]
            (cond-> e
              (pos? i) (assoc :newer (brief (entries (dec i))))
              (< i (dec (count entries))) (assoc :older (brief (entries (inc i))))))
          entries))))

(defn build-index
  "Content repo → the in-memory index every request reads from."
  [config]
  (let [{:keys [entries drafts pages]} (load-content config)
        entries (vec (sort-by date-key #(compare %2 %1) entries))
        dupes (->> entries (map :path) frequencies (filter #(> (val %) 1)) (map key))
        _ (when (seq dupes)
            (throw (ex-info (str "Duplicate entry paths: " (str/join ", " dupes))
                            {:paths (vec dupes)})))
        entries (link-neighbors entries)]
    {:entries entries
     :by-path (into {} (map (juxt :path identity)) entries)
     :by-type (group-by :type entries)
     :by-year (group-by #(-> % :date :year) entries)
     :by-month (group-by (fn [e] [(-> e :date :year) (-> e :date :month)]) entries)
     :by-day (group-by (fn [e] [(-> e :date :year) (-> e :date :month) (-> e :date :day)]) entries)
     :by-tag (reduce (fn [acc e]
                       (reduce (fn [acc t] (update acc t (fnil conj []) e))
                               acc
                               (:tags e)))
                     {}
                     entries)
     :tag-counts (->> entries
                      (mapcat :tags)
                      frequencies
                      (sort-by (fn [[t n]] [(- n) (name t)]))
                      vec)
     :drafts drafts
     :pages pages}))
