(ns site.views.home
  "The home feed: recent entries grouped by day, whole days only, at
  least :home-entries items — then a link onward to the month archive
  where the feed left off. No infinite scroll, no page numbers."
  (:require [site.util :as util]
            [site.views.components :as c]
            [site.views.layout :as layout]))

(def default-home-entries 10)

(defn- take-whole-days
  "Accumulates day groups (newest first) until at least n entries are
  included — a day is never split.
  → {:shown [entries...] :next-entry <first excluded entry or nil>}"
  [entries n]
  (loop [groups (partition-by util/day-key entries)
         shown []
         cnt 0]
    (cond
      (empty? groups) {:shown shown :next-entry nil}
      (>= cnt n) {:shown shown :next-entry (ffirst groups)}
      :else (recur (rest groups)
                   (into shown (first groups))
                   (+ cnt (count (first groups)))))))

(defn- older-link
  "Continues into the archives at the month of the first entry that
  didn't make the cut."
  [entry]
  (let [month-key [(-> entry :date :year) (-> entry :date :month)]]
    [:nav.older
     [:a {:href (util/month-url month-key)}
      (str "Older → " (util/month-label month-key))]]))

(defn home [config index]
  (let [n (or (:home-entries config) default-home-entries)
        {:keys [shown next-entry]} (take-whole-days (:entries index) n)]
    (layout/page config nil
                 (c/day-grouped-list shown)
                 (when next-entry (older-link next-entry)))))
