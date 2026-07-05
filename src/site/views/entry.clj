(ns site.views.entry
  (:require [site.util :as util]
            [site.views.components :as c]
            [site.views.layout :as layout]))

(defn- neighbor-label [{:keys [title type date]}]
  (or title (str (name type) ", " (util/format-date date))))

(defn- neighbors [entry]
  (when (or (:newer entry) (:older entry))
    [:nav.entry-neighbors
     [:span.newer
      (when-let [n (:newer entry)]
        [:a {:href (:path n)} "← " (neighbor-label n)])]
     [:span.older
      (when-let [o (:older entry)]
        [:a {:href (:path o)} (neighbor-label o) " →"])]]))

(defn entry-page [config entry]
  (layout/page config (or (:title entry) (util/format-date (:date entry)))
               [:article.entry-page {:class (name (:type entry))}
                (c/entry-meta entry)
                (c/entry-header entry)
                (c/entry-body entry)
                (neighbors entry)]))

(defn draft-page [config entry]
  (layout/page config (str "Draft: " (or (:title entry) (:draft-name entry)))
               [:div.draft-banner "Draft — not published. Don't share this URL."]
               [:article.entry-page.draft {:class (name (:type entry))}
                [:div.entry-meta (c/type-badge entry) (c/tag-links entry)]
                (c/entry-header entry)
                (c/entry-body entry)]))
