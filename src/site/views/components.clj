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
  "The byline row: type badge, date, tags, permalink."
  [entry]
  [:div.entry-meta
   (type-badge entry)
   (when (:date entry) (date-link (:date entry)))
   (tag-links entry)
   [:a.permalink {:href (:path entry) :title "Permalink"} "#"]])

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
  "How an entry appears in a list. Short types show in full; posts show
  their first paragraph with a link to read on."
  [entry]
  [:article.entry-card {:class (name (:type entry))}
   (entry-meta entry)
   (entry-header entry)
   (if (= :post (:type entry))
     (list
      [:div.entry-body.lede (markdown/render (markdown/lede (:body entry)))]
      [:p.read-on [:a {:href (:path entry)} "Read on →"]])
     (entry-body entry))])

(defn entry-list [entries]
  [:div.entry-list (map entry-card entries)])
