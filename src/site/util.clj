(ns site.util
  "Dates, URLs, and small helpers. Months in URLs are lowercase
  three-letter slugs (/2026/jul/4/...)."
  (:require [clojure.string :as str]))

(def month-slugs
  ["jan" "feb" "mar" "apr" "may" "jun" "jul" "aug" "sep" "oct" "nov" "dec"])

(def month-names
  ["January" "February" "March" "April" "May" "June"
   "July" "August" "September" "October" "November" "December"])

(def ^:private month-slug->num
  (into {} (map-indexed (fn [i s] [s (inc i)])) month-slugs))

(defn month-slug [n] (month-slugs (dec n)))
(defn month-name [n] (month-names (dec n)))

(defn parse-year
  "\"2026\" → 2026, else nil. Years are exactly four digits."
  [s]
  (when (and s (re-matches #"\d{4}" s))
    (parse-long s)))

(defn parse-month
  "Accepts \"jul\", \"Jul\", \"7\", \"07\" → 1..12, else nil."
  [s]
  (when s
    (let [s (str/lower-case s)]
      (or (month-slug->num s)
          (when (re-matches #"\d{1,2}" s)
            (let [n (parse-long s)]
              (when (<= 1 n 12) n)))))))

(defn parse-day
  "\"4\" or \"04\" → 4, else nil."
  [s]
  (when (and s (re-matches #"\d{1,2}" s))
    (let [n (parse-long s)]
      (when (<= 1 n 31) n))))

(defn entry-url
  "Canonical URL path for an entry: /2026/jul/4/my-slug"
  [{:keys [date slug]}]
  (str "/" (:year date) "/" (month-slug (:month date)) "/" (:day date) "/" slug))

(defn day-key
  "Grouping/sorting key for an entry's day: [2026 7 4]"
  [{:keys [date]}]
  [(:year date) (:month date) (:day date)])

(defn day-url [{:keys [year month day]}]
  (str "/" year "/" (month-slug month) "/" day))

(defn month-url [[year month]]
  (str "/" year "/" (month-slug month)))

(defn month-label [[year month]]
  (str (month-name month) " " year))

(defn tag-url [tag]
  (str "/tags/" (name tag)))

(defn type-url [type]
  (str "/" (name type) "s"))

(defn type-feed-url
  "An entry type's scoped Atom feed."
  [type]
  (str (type-url type) "/feed.xml"))

(defn tag-feed-url
  "A tag's scoped Atom feed. Built here so the page that advertises it and
  the feed that names itself can't drift apart."
  [tag]
  (str (tag-url tag) "/feed.xml"))

(defn feed-scope
  "A page's own Atom feed, from {:kind :value} — :type with an entry type,
  :tag with a tag — or nil for a page with none. Returns {:href :title
  :scope}: :title names the feed to a reader discovering it (the <link> in
  <head>), :scope names it inside the sentence under the signup form. One
  descriptor resolved in one place, so the two can't name different feeds."
  [{:keys [kind value]}]
  (case kind
    :type (let [label (str (name value) "s")]
            {:href (type-feed-url value)
             :title (str "Atom for " (str/capitalize label))
             :scope label})
    :tag {:href (tag-feed-url value)
          :title (str "Atom for #" (name value))
          :scope (str "#" (name value))}
    nil))

(defn format-date
  "{:year 2026 :month 7 :day 4} → \"July 4, 2026\""
  [{:keys [year month day]}]
  (str (month-name month) " " day ", " year))

(defn full-date
  "{:year 2026 :month 7 :day 4} → \"Jul 4, 2026\" — the compact-but-complete
  form used in dense ledger rows (the related list, the sidebar recents),
  where the list spans years so the year has to be there."
  [{:keys [year month day]}]
  (str (str/capitalize (month-slug month)) " " day ", " year))

(defn ordinal
  "1 → \"1st\", 2 → \"2nd\", 11 → \"11th\", 23 → \"23rd\"."
  [n]
  (let [suffix (if (<= 11 (mod n 100) 13)
                 "th"
                 (case (mod n 10) 1 "st" 2 "nd" 3 "rd" "th"))]
    (str n suffix)))

(defn host
  "\"https://youtu.be/xyz\" → \"youtu.be\" — the source hint shown on
  outbound entries (links, releases, tools)."
  [url]
  (some-> url
          (str/replace #"^\w+://" "")
          (str/replace #"^www\." "")
          (str/split #"/")
          first
          not-empty))

(defn slugify
  "\"My Post Title!\" → \"my-post-title\""
  [s]
  (-> (str/lower-case (str s))
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")))
