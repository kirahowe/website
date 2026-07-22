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

(defn- month-index [year entries]
  [:div.month-index
   (for [[month es] (->> entries
                         (group-by #(-> % :date :month))
                         (sort-by key #(compare %2 %1)))
         :let [slug (util/month-slug month)
               es (sort-by util/day-key #(compare %2 %1) es)]]
     [:section.month-block
      [:div.month-head
       [:a.month-name {:href (str "/" year "/" slug)} (util/month-name month)]
       [:span.month-count (c/type-summary es false)]]
      (for [e es]
        (c/index-row {:href (:path e)
                      :date (util/ordinal (-> e :date :day))
                      :type (:type e)
                      :title (c/entry-label e)}))])])

(defn- year-nav
  "The Years side list — one link per year in `years` (newest first), the
  `current` year marked. Callers scope `years` and supply `href` (year →
  URL): the archive lists every year and links to the whole year, a type
  listing lists only the years holding that type and stays scoped to it."
  [years current href]
  (c/side-section "Years"
                  [:div.year-nav
                   (for [y (sort > years)]
                     [:a {:class (when (= y current) "current") :href (href y)} y])]))

(defn- facet-tags
  "The Tags side facet for a listing. `entries` are the listing's already
  filtered rows; `href` maps a tag to a URL that adds it to the current
  view, so a tag click refines in place rather than leaving for its global
  page. `active` (the tag applied now, if any) is dropped from the cloud —
  it's cleared from the header instead. Counts always reflect what's on
  screen."
  [entries active href]
  (let [counts (->> entries (mapcat :tags) (remove #{active}) frequencies
                    (sort-by (fn [[t n]] [(- n) (name t)])))]
    (when (seq counts)
      (c/side-section "Tags" (c/tag-cloud (take 8 counts) href)))))

(defn- filter-chip
  "An applied facet in a listing header — a year or a #tag — shown small and
  muted next to the title, with an × that clears just that facet back to
  `clear-href` (leaving any other facet in place)."
  [label clear-href]
  [:span.filter label
   [:a.filter-x {:href clear-href :title "Clear filter"
                 :aria-label (str "Clear " label " filter")} "×"]])

(defn- header-parts
  "The <h1> parts of a listing header: the base `label` then each facet chip
  in `chips`, every chip led by a slash in the chip's own muted formatting.
  The count and its slash are appended by `page-header`, so the header reads
  as one slashed row — `Links / #tag × / 8 entries` — like the metadata
  layer, each slash matching the item that follows it."
  [label chips]
  (cons label (mapcat (fn [chip] [[:span.sep.filter "/"] chip]) (remove nil? chips))))

(defn year-page [config index year tag entries]
  (let [heading (header-parts (str year)
                              [(when tag (filter-chip (str "#" (name tag)) (str "/" year)))])
        ;; With a tag applied, scope the Years list and the month index to
        ;; the years/rows that actually hold it — no dead links, no rows
        ;; that don't match — and carry the tag as we jump between years.
        years (if tag
                (distinct (map #(-> % :date :year) (get (:by-tag index) tag)))
                (keys (:by-year index)))
        year-href (fn [y] (str "/" y (when tag (str "?tag=" (name tag)))))
        tag-href (fn [t] (str "/" year "?tag=" (name t)))]
    (layout/page config (cond-> (str year) tag (str " / #" (name tag)))
                 (c/page-header heading (c/count-label (count entries)))
                 (c/cols (month-index year entries)
                         (c/sidebar (year-nav years year year-href)
                                    (facet-tags entries tag tag-href))))))

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

(defn type-page [config index type year tag entries]
  (let [slug (str (name type) "s")
        ;; The listing's canonical URL for any (year, tag) selection — the
        ;; one place the path shape lives, so chips, the × clears, the Years
        ;; nav and the tag facet all stay consistent.
        path (fn [y t] (str (if y (str "/" y "/" slug) (str "/" slug))
                            (when t (str "?tag=" (name t)))))
        label (str/capitalize slug)
        ;; Year and tag each render as their own muted chip; each × clears
        ;; only itself, leaving the other facet applied.
        heading (header-parts label
                              [(when year (filter-chip (str year) (path nil tag)))
                               (when tag (filter-chip (str "#" (name tag)) (path year nil)))])
        ;; Facet the Years list to this type — every year that holds one,
        ;; not the global year set — regardless of the year filter in view,
        ;; and narrowed further to the applied tag so no year link dead-ends.
        scope (cond->> (get (:by-type index) type)
                tag (filter #(contains? (set (:tags %)) tag)))
        years (distinct (map #(-> % :date :year) scope))]
    (layout/page config (cond-> label year (str " / " year) tag (str " / #" (name tag)))
                 (c/page-header heading (c/count-label (count entries)))
                 (c/cols (c/feed entries)
                         (c/sidebar (year-nav years year #(path % tag))
                                    (facet-tags entries tag #(path year %)))))))

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
  (let [heading (header-parts (list [:span.hash "#"] (name tag))
                              [(when year (filter-chip (str year) (str "/tags/" (name tag))))])]
    (layout/page config (str "#" (name tag) (when year (str " / " year)))
                 (c/page-header heading (c/count-label (count entries)))
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
