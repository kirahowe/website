(ns site.views.archive
  "Archive listings: by date, by type, by tag — plus the tag index.
  Date archives group by day; month pages link to the neighboring
  months that actually have content."
  (:require [clojure.string :as str]
            [site.util :as util]
            [site.views.components :as c]
            [site.views.layout :as layout]))

(defn- listing
  "Flat cross-date listing (types, tags) — cards keep their dates."
  [config title subnav entries]
  (layout/page config title
               [:h1 title]
               subnav
               (if (seq entries)
                 (c/entry-list entries)
                 [:p.empty "Nothing here yet."])))

(defn- months-subnav
  "For a year page: links to the months that actually have entries."
  [index year]
  (let [months (->> (:months index)
                    (filter #(= year (first %)))
                    (map second)
                    sort)]
    (when (seq months)
      [:nav.subnav
       (for [m months]
         [:a {:href (str "/" year "/" (util/month-slug m))} (util/month-name m)])])))

(defn year-page [config index year entries]
  (layout/page config (str year)
               [:h1 (str year)]
               (months-subnav index year)
               (c/day-grouped-list entries)))

(defn month-page [config index year month entries]
  (let [months (:months index)                       ; newest first
        i (.indexOf months [year month])
        newer (when (pos? i) (months (dec i)))
        older (when (< (inc i) (count months)) (months (inc i)))]
    (layout/page config (str (util/month-name month) " " year)
                 [:h1 (str (util/month-name month) " " year)]
                 [:nav.subnav
                  (when newer
                    [:a {:href (util/month-url newer)} (str "← " (util/month-label newer))])
                  [:a {:href (str "/" year)} (str year)]
                  (when older
                    [:a {:href (util/month-url older)} (str (util/month-label older) " →")])]
                 (c/day-grouped-list entries))))

(defn day-page [config year month day entries]
  (let [date {:year year :month month :day day}]
    (layout/page config (util/format-date date)
                 [:h1 (util/format-date date)]
                 [:nav.subnav
                  [:a {:href (util/month-url [year month])}
                   (str "← " (util/month-name month) " " year)]]
                 (c/entry-list entries {:show-date? false}))))

(defn type-page [config type year entries]
  (let [plural (str/capitalize (str (name type) "s"))]
    (listing config (if year (str plural " · " year) plural)
             (when year
               [:nav.subnav [:a {:href (str "/" (name type) "s")} (str "← all " (str/lower-case plural))]])
             entries)))

(defn tag-page [config tag year entries]
  (listing config (if year (str "#" (name tag) " · " year) (str "#" (name tag)))
           (when year
             [:nav.subnav [:a {:href (str "/tags/" (name tag))} (str "← all #" (name tag))]])
           entries))

(defn tags-page [config index]
  (layout/page config "Tags"
               [:h1 "Tags"]
               (if (seq (:tag-counts index))
                 [:ul.tag-index
                  (for [[tag n] (:tag-counts index)]
                    [:li [:a.tag {:href (str "/tags/" (name tag))} (str "#" (name tag))]
                     [:span.count (str " × " n)]])]
                 [:p.empty "No tags yet."])))
