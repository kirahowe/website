(ns site.views.archive
  "Archive listings: year (a month index), month (a day-grouped feed with a
  calendar), day, plus type and tag listings and the tag index. The feed
  pages reuse the home-page shell; the year page is its own dense index."
  (:require [clojure.string :as str]
            [site.util :as util]
            [site.views.components :as c]
            [site.views.layout :as layout])
  (:import [java.time LocalDate YearMonth]))

;; --- year archive: a month index with per-type counts --------------------

(defn- month-index [index year]
  [:div.month-index
   (for [[month entries] (->> (get (:by-year index) year)
                              (group-by #(-> % :date :month))
                              (sort-by key #(compare %2 %1)))
         :let [slug (util/month-slug month)
               entries (sort-by util/day-key #(compare %2 %1) entries)]]
     [:section.month-block
      [:div.month-head
       [:a.month-name {:href (str "/" year "/" slug)} (util/month-name month)]
       [:span.month-count (c/type-summary entries false)]]
      (for [e entries]
        (c/index-row {:href (:path e)
                      :date (util/ordinal (-> e :date :day))
                      :type (:type e)
                      :title (c/entry-label e)}))])])

(defn- year-nav [index current]
  (c/side-section "Jump to year"
                  [:div.year-nav
                   (for [y (sort > (keys (:by-year index)))]
                     [:a {:class (when (= y current) "current") :href (str "/" y)} y])]))

(defn- year-tags [entries]
  (let [counts (->> entries (mapcat :tags) frequencies
                    (sort-by (fn [[t n]] [(- n) (name t)])))]
    (when (seq counts)
      (c/side-section "This year" (c/tag-cloud (take 8 counts))))))

(defn year-page [config index year entries]
  (layout/page config (str year)
               (c/page-header (str year) (c/count-label (count entries)))
               (c/cols (month-index index year)
                       (c/sidebar (year-nav index year)
                                  (year-tags entries)))))

;; --- month page: the feed + a mini calendar ------------------------------

(defn- calendar
  "A Monday-first month grid; days that have entries link to their archive,
  the rest are dimmed."
  [index year month]
  (let [ndays (.lengthOfMonth (YearMonth/of year month))
        start (dec (.getValue (.getDayOfWeek (LocalDate/of year month 1)))) ; Mon=0
        active (into #{}
                     (comp (filter (fn [[y m _]] (and (= y year) (= m month))))
                           (map (fn [[_ _ d]] d)))
                     (keys (:by-day index)))
        slug (util/month-slug month)
        total (+ start ndays)
        cells (concat (repeat start nil)
                      (range 1 (inc ndays))
                      (repeat (mod (- 7 (mod total 7)) 7) nil))]
    [:section.side
     [:div.calendar-head
      [:a {:href (str "/" year)} year] [:span "»"] [:span (util/month-name month)]]
     [:table.calendar
      [:thead [:tr (for [d ["M" "T" "W" "T" "F" "S" "S"]] [:th d])]]
      [:tbody
       (for [week (partition 7 cells)]
         [:tr
          (for [d week]
            (cond
              (nil? d) [:td]
              (active d) [:td.on [:a {:href (str "/" year "/" slug "/" d)} d]]
              :else [:td d]))])]]]))

(defn- nearby [newer older]
  (when (or newer older)
    (c/side-section "Nearby"
                    [:div.year-nav
                     (when newer [:a {:href (util/month-url newer)} (util/month-label newer)])
                     (when older [:a {:href (util/month-url older)} (util/month-label older)])])))

(defn month-page [config index year month entries]
  (let [months (:months index)                       ; newest first
        i (.indexOf months [year month])
        newer (when (pos? i) (nth months (dec i)))
        older (when (< (inc i) (count months)) (nth months (inc i)))
        title (str (util/month-name month) " " year)]
    (layout/page config title
                 (c/page-header title (c/count-label (count entries)))
                 [:div.type-summary (c/type-summary entries true)]
                 (c/cols (c/feed entries)
                         (c/sidebar (calendar index year month)
                                    (nearby newer older))))))

(defn day-page [config year month day entries]
  (layout/page config (util/format-date {:year year :month month :day day})
               (c/feed entries)))

;; --- type and tag listings ----------------------------------------------

(defn type-page [config type year entries]
  (let [plural (str/capitalize (str (name type) "s"))
        title (if year (str plural " / " year) plural)]
    (layout/page config title
                 (c/page-header title (c/count-label (count entries)))
                 (c/feed entries))))

(defn- related-tags [entries tag]
  (let [counts (->> entries (mapcat :tags) (remove #{tag}) frequencies
                    (sort-by (fn [[t n]] [(- n) (name t)])))]
    (when (seq counts)
      (c/side-section "Related tags" (c/tag-cloud (take 6 counts))))))

(defn- tag-feeds [tag]
  (c/side-section "Feeds"
                  (c/side-link {:path "/feed.xml" :title (str "RSS for #" (name tag))})
                  (c/side-link {:path "/tags" :title "All tags"})))

(defn tag-page [config tag year entries]
  (let [title (str "#" (name tag) (when year (str " / " year)))]
    (layout/page config title
                 (c/page-header (list [:span.hash "#"] (name tag))
                                (c/count-label (count entries)))
                 (c/cols (c/feed entries)
                         (c/sidebar (related-tags entries tag)
                                    (tag-feeds tag))))))

(defn tags-page [config index]
  (let [tags (:tag-counts index)]
    (layout/page config "Tags"
                 (c/page-header "Tags" (str (count tags) " tags"))
                 (if (seq tags)
                   [:div.tag-index (c/tag-cloud tags)]
                   [:p.empty "No tags yet."]))))
