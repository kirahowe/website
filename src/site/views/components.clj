(ns site.views.components
  "Reusable feed and sidebar pieces. Entries are modelled as
  {:type :title :path :date :tags :body :link-url :source :source-url}; the
  five page types compose from the functions here (entry-row, feed, sidebar
  sections, page-header, type-summary)."
  (:require [clojure.set :as set]
            [site.markdown :as markdown]
            [site.util :as util]))

(def type-order [:post :link :quote :release :tool])

;; --- small atoms ---------------------------------------------------------

(defn dot
  "The type colour dot: a marker reading its colour from the entry type.
  Carries the type name as a tooltip for rows where it's the only type cue."
  [type]
  [:span.dot {:class (name type) :title (name type)}])

(defn- outbound
  "Links, releases and tools point at their source; everything else at its
  own page."
  [entry]
  (or (:link-url entry) (:path entry)))

(defn entry-label
  "A one-line label for an entry in a dense list: its title, a short quote
  excerpt, or — for untitled entries — the date."
  [entry]
  (or (:title entry)
      (when (= :quote (:type entry))
        (let [ex (markdown/excerpt (:body entry))]
          (str "“" (subs ex 0 (min 60 (count ex))) "”")))
      (util/format-date (:date entry))))

(defn tag-links
  "An entry's #tag links, sorted by name — the one tag atom, used by the
  feed foot, the post footer, and draft pages."
  [tags]
  (for [t (sort-by name tags)]
    [:a.tag {:href (str "/tags/" (name t))} (str "#" (name t))]))

(defn quote-source
  "The \"— source\" line under a quote, linked when a URL is known. Shared
  by the feed row and the entry page."
  [{:keys [source source-url]}]
  (when source
    [:p.quote-cite "— " (if source-url
                          [:a {:href source-url} source]
                          source)]))

(defn via-link
  "The “(via)” credit link shown after an outbound entry's title when the
  entry records where it was found."
  [entry]
  (when-let [via (:link-via entry)]
    [:span.via " (" [:a {:href via} "via"] ")"]))

;; --- feed row ------------------------------------------------------------

(defn- entry-foot
  "The dense foot under a feed row: the type (with its colour dot) and the
  entry's tags flowing together on a single wrapping line."
  [entry]
  [:div.entry-foot {:class (name (:type entry))}
   [:span.entry-kind (dot (:type entry)) (name (:type entry))]
   (when (seq (:tags entry))
     (cons [:span.sep "/"] (tag-links (:tags entry))))])

(defn- entry-title [entry]
  (when (:title entry)
    [:h3.entry-title [:a {:href (outbound entry)} (:title entry)] (via-link entry)]))

(defn entry-row
  "One entry in the feed: a quote renders as a blockquote with a linked
  source; every other type renders as title + excerpt — with a reading-time
  link tacked onto the end of a post excerpt. Both close with the
  type/tags foot."
  [entry]
  [:article.entry
   (if (= :quote (:type entry))
     (list
      [:blockquote (markdown/excerpt (:body entry)) [:span.quote-close "”"]]
      (quote-source entry))
     (list
      (entry-title entry)
      [:p.entry-excerpt (markdown/excerpt (:body entry))
       (when (= :post (:type entry))
         (list " " [:a.excerpt-more {:href (:path entry)}
                    (str "[…" (markdown/read-time (:body entry)) " min read]")]))]))
   (entry-foot entry)])

(defn day-group [entries]
  (let [date (:date (first entries))]
    [:section.day-group
     [:h2.day-heading [:a {:href (util/day-url date)} (util/format-date date)]]
     (map entry-row entries)]))

(defn feed
  "Entries (newest first) grouped under linked day headings — the shape the
  home page, tag pages and month pages all share."
  [entries]
  (for [group (partition-by util/day-key entries)]
    (day-group group)))

;; --- page header + counts ------------------------------------------------

(defn page-header
  "Slim banner atop tag / year / month pages: a title (hiccup) and a plain
  entry count."
  [title count]
  [:header.page-header
   [:h1 title (when count [:span.count count])]])

(defn count-label [n]
  (str n " " (if (= 1 n) "entry" "entries")))

(defn type-summary
  "A per-type breakdown line — \"4 posts / 1 quote / 9 links\" — in nav
  order; the word singularizes on a count of one. When `link?`, each type
  word links to its listing (whose URL stays plural)."
  [entries link?]
  (let [counts (frequencies (map :type entries))]
    (interpose [:span.sep "/"]
               (for [t type-order :when (counts t)
                     :let [n (counts t)
                           slug (str (name t) "s")
                           word (str (name t) (when (> n 1) "s"))]]
                 [:span n " "
                  (if link? [:a {:href (str "/" slug)} word] word)]))))

;; --- sidebar -------------------------------------------------------------

(defn sidebar [& sections]
  (into [:aside.sidebar] (remove nil? sections)))

(defn side-section [title & body]
  (into [:section.side [:h2 title]] body))

(defn cols
  "The two-column shell: main feed on the left, sidebar on the right, with a
  vertical hairline divider carried by .main."
  [main aside]
  [:div.cols [:div.main main] aside])

(defn side-link [{:keys [path title type]}]
  [:a.side-link {:href path} (when type (dot type)) [:span.title title]])

(defn recent-links
  "The N most recent titled entries, each with its type dot."
  [entries n]
  (side-section "Recent"
                (for [e (take n (filter :title entries))]
                  (side-link {:path (:path e) :title (entry-label e) :type (:type e)}))))

(defn tag-cloud
  "Tag links with counts — the sidebar widget and the tags index share it."
  [tag-counts]
  [:div.tag-cloud
   (for [[t n] tag-counts]
     [:a.tag {:href (str "/tags/" (name t))} (str "#" (name t)) " " [:b n]])])

(defn top-tags [tag-counts n]
  (when (seq tag-counts)
    (side-section "Top tags" (tag-cloud (take n tag-counts)))))

;; --- ledger rows (year archive, related list) -----------------------------

(defn index-row
  "The one dense-list row: a right-aligned mono date, the type dot, then
  the title. The year archive and the related list both render as columns
  of these."
  [{:keys [href date type title]}]
  [:a.index-row {:href href}
   [:span.meta date]
   (dot type)
   [:span.title title]])

(defn related
  "Up to n other entries, most-shared-tags first (recency breaks ties)."
  [entries entry n]
  (let [tags (set (:tags entry))]
    (->> entries
         (remove #(= (:path %) (:path entry)))
         (sort-by #(- (count (set/intersection tags (set (:tags %))))))
         (take n))))

(defn related-item [e]
  (index-row {:href (:path e)
              :date (util/short-date (:date e))
              :type (:type e)
              :title (entry-label e)}))
