(ns site.views.components
  "Reusable pieces. Entry rendering dispatches on :type via multimethods
  with a :default, so a new type renders acceptably before it gets a
  custom look."
  (:require [site.markdown :as markdown]
            [site.util :as util]))

(defn date-link
  "2026/jul/4 as three archive links."
  [{:keys [year month day]}]
  (let [ms (util/month-slug month)]
    [:time.entry-date {:datetime (format "%04d-%02d-%02d" year month day)}
     [:a {:href (str "/" year)} year]
     "/"
     [:a {:href (str "/" year "/" ms)} ms]
     "/"
     [:a {:href (str "/" year "/" ms "/" day)} day]]))

(defn tag-links [entry]
  (when (seq (:tags entry))
    [:span.tags
     (for [t (sort-by name (:tags entry))]
       [:a.tag {:href (str "/tags/" (name t))} (str "#" (name t))])]))

(defn type-badge [entry]
  [:a.type-badge {:class (name (:type entry))
                  :href (str "/" (name (:type entry)) "s")}
   (name (:type entry))])

(defn entry-meta
  "The byline row: type badge, date, tags, permalink. Pass
  {:show-date? false} inside day-grouped lists, where the heading
  already carries the date."
  ([entry] (entry-meta entry {}))
  ([entry {:keys [show-date?] :or {show-date? true}}]
   [:div.entry-meta
    (type-badge entry)
    (when (and show-date? (:date entry)) (date-link (:date entry)))
    (tag-links entry)
    [:a.permalink {:href (:path entry) :title "Permalink"} "#"]]))

;; --- type-specific rendering -------------------------------------------

(defmulti entry-header
  "Heading for an entry; may be nil (untitled notes)."
  :type)

(defmethod entry-header :default [entry]
  (when (:title entry)
    [:h2.entry-title [:a {:href (:path entry)} (:title entry)]]))

(defmethod entry-header :link [entry]
  [:h2.entry-title
   [:a.outbound {:href (:link-url entry)} (or (:title entry) (:link-url entry))]
   (when-let [via (:link-via entry)]
     [:span.via " (" [:a {:href via} "via"] ")"])])

(defmulti entry-body
  "Full rendered body for an entry."
  :type)

(defmethod entry-body :default [entry]
  [:div.entry-body (markdown/render (:body entry))])

(defmethod entry-body :quote [entry]
  [:div.entry-body
   [:blockquote (markdown/render (:body entry))]
   (when-let [source (:source entry)]
     [:p.quote-source "— " (if-let [url (:source-url entry)]
                             [:a {:href url} source]
                             source)])])

;; --- listings ------------------------------------------------------------

(defn entry-card
  "How an entry appears in the feed and listings: in full, never truncated.
  The title links to the entry's own page."
  ([entry] (entry-card entry {}))
  ([entry opts]
   [:article.entry-card {:class (name (:type entry))}
    (entry-meta entry opts)
    (entry-header entry)
    (entry-body entry)]))

(defn entry-list
  ([entries] (entry-list entries {}))
  ([entries opts]
   [:div.entry-list (map #(entry-card % opts) entries)]))

(defn day-grouped-list
  "Entries (newest first) grouped under linked day headings, the way the
  home page and date archives read."
  [entries]
  (for [group (partition-by util/day-key entries)]
    (let [date (:date (first group))]
      [:section.day-group
       [:h2.day-heading
        [:a {:href (util/day-url date)} (util/format-date date)]]
       (entry-list group {:show-date? false})])))
