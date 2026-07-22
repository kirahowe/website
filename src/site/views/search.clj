(ns site.views.search
  "The search page: a command-prompt query bar, relevance-ranked results,
  and facet rails that narrow by type or tag without re-typing the query.
  Results are the same cards the home feed uses — each under its own date
  heading (relevance order doesn't group by day) with matches marked."
  (:require [site.search :as search]
            [site.views.components :as c]
            [site.views.layout :as layout])
  (:import [java.net URLEncoder]))

(defn- search-url
  "/search?q=... carrying whichever filters stay active."
  [q {:keys [type tag]}]
  (str "/search?q=" (URLEncoder/encode (str q) "UTF-8")
       (when type (str "&type=" (name type)))
       (when tag (str "&tag=" (URLEncoder/encode (name tag) "UTF-8")))))

(defn- prompt
  "The query bar. A fresh query drops any active filters — narrowing
  belongs to the facet rails; the × (shown once a query is active) is a
  plain link back to the blank prompt, so clearing needs no JS."
  [q]
  [:form.search-prompt {:action "/search" :method "get" :role "search"}
   [:span.prompt-mark {:aria-hidden "true"} ">"]
   [:input {:type "search" :name "q" :value (or q "")
            :placeholder "type to search…"
            :autofocus true :autocomplete "off" :aria-label "Search"}]
   (when q [:a.prompt-clear {:href "/search" :aria-label "Clear search"} "×"])
   [:button {:type "submit"} [:span "enter"] [:span.key "↵"]]])

;; --- facet rails -----------------------------------------------------------

(defn- type-facets
  "The type rail. `matches` has only the tag filter applied, so each count
  says what choosing that row would show; the active row links to its own
  removal."
  [config q type tag matches]
  (c/side-section "Filter by type"
                  [:div.facet-list
                   [:a.facet {:class (when-not type "active")
                              :href (search-url q {:tag tag})}
                    [:span.dot] [:span.title "all"] [:span.n (count matches)]]
                   (for [[t n] (search/type-counts config matches)]
                     [:a.facet {:class (str (name t) (when (= t type) " active"))
                                :href (search-url q {:type (when (not= t type) t)
                                                     :tag tag})}
                      (c/dot t) [:span.title (str (name t) "s")] [:span.n n]])]))

(def ^:private max-tag-facets 12)

(defn- tag-facets
  "The refine rail: tags across `matches` (type filter applied), most
  common first; the active tag links to its own removal."
  [q type tag matches]
  (let [counts (take max-tag-facets (search/tag-counts matches))]
    (when (seq counts)
      (c/side-section "Refine"
                      [:div.facet-list
                       (for [[t n] counts]
                         [:a.facet {:class (when (= t tag) "active")
                                    :href (search-url q {:type type
                                                         :tag (when (not= t tag) t)})}
                          [:span.hash "#"] [:span.title (name t)] [:span.n n]])]))))

;; --- the page ----------------------------------------------------------------

(defn- summary
  "\"Found 7 entries for datomic / sorted by relevance\" — the noun takes
  the active type's name, and an active tag rides along."
  [q type tag shown]
  (let [n (count shown)
        noun (if type
               (str (name type) (when (not= 1 n) "s"))
               (if (= 1 n) "entry" "entries"))]
    [:p.search-summary
     "Found " [:b n " " noun] " for " [:span.q q]
     (when tag (list " " [:span.sep "/"] " " [:span.q (str "#" (name tag))]))
     " " [:span.sep "/"] " sorted by relevance"]))

(defn search-page
  "q may be nil (a blank prompt). `matches` is the full relevance-ranked
  match set for q; :type/:tag narrow what's shown and feed the facet rails."
  [config index {:keys [q type tag]} matches]
  (let [terms (search/parse-query q)
        shown (search/apply-filters matches {:type type :tag tag})]
    (layout/page config {:title "Search" :path "/search"}
                 (prompt q)
                 (cond
                   (nil? q)
                   [:p.search-hint
                    "Searches titles, tags, and full text across "
                    (count (:entries index)) " entries."]

                   (empty? matches)
                   [:p.search-summary
                    "Nothing found for " [:span.q q]
                    " — fewer or shorter words match more."]

                   :else
                   (c/cols
                    (list (summary q type tag shown)
                          ;; one card per result, each under its own date
                          ;; heading — relevance order, so no day grouping
                          (for [e shown] (c/day-group [e] {:terms terms})))
                    (c/sidebar
                     (type-facets config q type tag
                                  (search/apply-filters matches {:tag tag}))
                     (tag-facets q type tag
                                 (search/apply-filters matches {:type type}))))))))
