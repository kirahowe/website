(ns site.search
  "Naive in-memory full-text search: every term must appear somewhere
  (title, tags, or body); results are scored by where and how often terms
  hit. Plenty for hundreds of entries; Datalevin's FTS is the designated
  upgrade if this ever outgrows itself."
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
