(ns site.views.archive
  "Archive listings: by date, by type, by tag — plus the tag index."
  (:require [clojure.string :as str]
            [site.util :as util]
            [site.views.components :as c]
            [site.views.layout :as layout]))

(defn- listing [config title subnav entries]
  (layout/page config title
               [:h1 title]
               subnav
               (if (seq entries)
                 (c/entry-list entries)
                 [:p.empty "Nothing here yet."])))

(defn- months-subnav
  "For a year page: links to the months that actually have entries."
  [index year]
  (let [months (->> (keys (:by-month index))
                    (filter #(= year (first %)))
                    (map second)
                    sort)]
    (when (seq months)
      [:nav.subnav
       (for [m months]
         [:a {:href (str "/" year "/" (util/month-slug m))} (util/month-name m)])])))

(defn year-page [config index year entries]
  (listing config (str year) (months-subnav index year) entries))

(defn month-page [config year month entries]
  (listing config (str (util/month-name month) " " year)
           [:nav.subnav [:a {:href (str "/" year)} (str "← " year)]]
           entries))

(defn day-page [config year month day entries]
  (listing config (util/format-date {:year year :month month :day day})
           [:nav.subnav
            [:a {:href (str "/" year "/" (util/month-slug month))}
             (str "← " (util/month-name month) " " year)]]
           entries))

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
