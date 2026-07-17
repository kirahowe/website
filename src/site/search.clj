(ns site.search
  "Naive in-memory full-text search: every term must appear somewhere
  (title, tags, or body); results are scored by where and how often terms
  hit. The result-shaping the search page needs lives here too — type/tag
  filters, facet counts, match segmentation for highlighting, and snippet
  selection. Plenty for hundreds of entries; Datalevin's FTS is the
  designated upgrade if this ever outgrows itself."
  (:require [clojure.string :as str]))

(defn- occurrences [haystack needle]
  (loop [idx 0 n 0]
    (if-let [i (str/index-of haystack needle idx)]
      (recur (+ i (count needle)) (inc n))
      n)))

(defn- searchable [entry]
  {:title (str/lower-case (str (:title entry)))
   :tags (str/lower-case (str/join " " (map name (:tags entry))))
   :body (str/lower-case (str (:body entry)))})

(defn- score [{:keys [title tags body]} term]
  (+ (* 5 (occurrences title term))
     (* 3 (occurrences tags term))
     (occurrences body term)))

(defn parse-query [q]
  (->> (str/split (str/lower-case (str q)) #"\s+")
       (remove str/blank?)
       vec))

(defn search
  "→ entries matching every term, best first, each with :search-score."
  [index q]
  (let [terms (parse-query q)]
    (if (empty? terms)
      []
      (->> (:entries index)
           (keep (fn [entry]
                   (let [s (searchable entry)]
                     (when (every? #(pos? (score s %)) terms)
                       (assoc entry :search-score
                              (reduce + (map #(score s %) terms)))))))
           (sort-by :search-score >)
           vec))))

;; --- filters & facets -----------------------------------------------------

(defn apply-filters
  "Narrows results to an entry type and/or a tag; a nil filter passes
  everything through."
  [results {:keys [type tag]}]
  (cond->> results
    type (filter #(= type (:type %)))
    tag (filter #(contains? (:tags %) tag))
    true vec))

(defn type-counts
  "[[type n] ...] over results, biggest first; config nav order breaks ties."
  [config results]
  (let [nav-order (zipmap (:entry-types config) (range))]
    (->> (frequencies (map :type results))
         (sort-by (fn [[t n]] [(- n) (nav-order t Long/MAX_VALUE)]))
         vec)))

(defn tag-counts
  "[[tag n] ...] over results, biggest first, tag name breaking ties —
  the same shape as the index's :tag-counts."
  [results]
  (->> (mapcat :tags results)
       frequencies
       (sort-by (fn [[t n]] [(- n) (name t)]))
       vec))

;; --- highlighting & snippets ----------------------------------------------

(defn- first-hit
  "Earliest occurrence of any term in `lower` at or after idx → [start len],
  preferring the longest term when several start together; nil when nothing
  hits."
  [lower terms idx]
  (->> terms
       (keep #(when-let [i (str/index-of lower % idx)] [i (count %)]))
       (sort-by (fn [[i len]] [i (- len)]))
       first))

(defn match-segments
  "text → [{:text s :hit? bool} ...] split around case-insensitive term
  matches, in order; a single non-hit segment when nothing matches."
  [text terms]
  (let [text (str text)
        lower (str/lower-case text)
        terms (remove str/blank? terms)]
    (loop [idx 0 out []]
      (if-let [[i len] (when (seq terms) (first-hit lower terms idx))]
        (let [out (if (< idx i)
                    (conj out {:text (subs text idx i) :hit? false})
                    out)]
          (recur (+ i len)
                 (conj out {:text (subs text i (+ i len)) :hit? true})))
        (if (< idx (count text))
          (conj out {:text (subs text idx) :hit? false})
          out)))))

(def ^:private snippet-window
  "How much text to show around a match when the hit is deep in the body."
  180)

(defn- hits? [text terms]
  (let [lower (str/lower-case (str text))]
    (boolean (some #(str/index-of lower %) terms))))

(defn snippet
  "What prose to quote under a result: the excerpt when a term already hits
  it, else a word-snapped window around the first hit in the full plain
  text (… marks the cuts), else the excerpt again (title/tag-only match)."
  [excerpt full-text terms]
  (let [full (str full-text)]
    (if (or (hits? excerpt terms) (not (hits? full terms)))
      excerpt
      (let [[i _] (first-hit (str/lower-case full) terms 0)
            raw-start (max 0 (- i (quot snippet-window 2)))
            start (if (pos? raw-start)
                    (min i (or (some-> (str/index-of full " " raw-start) inc)
                               raw-start))
                    0)
            raw-end (min (count full) (+ start snippet-window))
            end (if (< raw-end (count full))
                  (max (inc i) (or (str/last-index-of full " " raw-end) raw-end))
                  raw-end)]
        (str (when (pos? start) "… ")
             (str/trim (subs full start end))
             (when (< end (count full)) " …"))))))
