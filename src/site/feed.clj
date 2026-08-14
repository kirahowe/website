(ns site.feed
  "Atom 1.0 feeds, rendered with hiccup in XML mode. Requiring
  site.views.components is deliberate: a feed item's <content> is site
  HTML, not a re-derivation of it, and sharing the same rendering atoms
  (entry-body, quote-blockquote, quote-source, credit-link, tag-links,
  canonical-note) is what stops the page and the feed from drifting apart
  the way hand-duplicated markup always eventually does."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [site.entry-meta :as m]
            [site.util :as util]
            [site.views.components :as c])
  (:import [java.time LocalDate ZoneOffset]
           [java.time.format DateTimeFormatter]))

(def default-feed-entries 20)

(defn- rfc-3339 [{:keys [year month day]}]
  (-> (LocalDate/of year month day)
      (.atTime 12 0)
      (.atOffset ZoneOffset/UTC)
      (.format DateTimeFormatter/ISO_OFFSET_DATE_TIME)))

(defn- absolutize
  "Root-relative src/href in an entry's rendered HTML → absolute URLs under
  `base-url`. On the site `/attachments/x.jpg` resolves against our own host;
  in a feed there is nothing to resolve it against, because the base URI in
  effect for the Atom document never reaches HTML that rides inside it as
  escaped text. Readers fall back to their own origin, so every pasted image
  and every wikilink arrives broken.

  Rewritten on the rendered string rather than the hiccup tree so it also
  reaches raw HTML blocks, which the renderer passes through opaquely. At
  this point a code sample showing markup is already escaped (`src=&quot;/`),
  so only real attributes match. Protocol-relative `//host/...` already
  carries a host and is left alone."
  [base-url html]
  (str/replace html #"\b(src|href)=([\"'])/(?!/)"
               (fn [[_ attr quote]] (str attr "=" quote base-url "/"))))

(defn- category-scheme
  "The `<category scheme>` for `segment` (\"tags\" or \"types\") under
  `base-url` — one helper so the two schemes below can't quietly diverge
  from the URLs they echo or claim to identify."
  [base-url segment]
  (str base-url "/" segment "/"))

(defn- credit-links
  "One Atom <link> per credit the entry carries (site.entry-meta/credits)
  — the outbound target, the via credit, a tool's source, a cross-post's
  canonical home — so a feed reader's software can act on them, not just
  a human reading the rendered text inside <content>. rel=\"alternate\"
  is left alone as the entry's own permalink; a credit never claims it,
  so <id>/alternate are still the only things that identify the item."
  [entry]
  (for [{:keys [rel url label]} (m/credits entry)]
    [:link {:rel rel :href url :title label}]))

(defn- category-elements
  "One <category> per tag, under a scheme that doubles as the tag's own
  listing URL — scheme+term reconstructs `/tags/<term>`, so the category
  is both a label and an address. Plus one <category> for the entry's
  type, under a scheme that is deliberately NOT an address (the site has
  no /types/ page): it exists only so a reader's software can tell the
  type term apart from a tag term that happens to share its name, sitting
  in the same <entry>."
  [config entry]
  (let [tag-scheme (category-scheme (:base-url config) "tags")
        type-scheme (category-scheme (:base-url config) "types")]
    (concat
     (for [t (m/tags entry)]
       [:category {:scheme tag-scheme :term (name t) :label (str "#" (name t))}])
     [[:category {:scheme type-scheme :term (name (:type entry))}]])))

(defn- item-body
  "An entry's body exactly as the site itself renders it: a quote is its
  blockquote plus attribution line (the framing and the cite line survive
  syndication, not just the words), everything else is `c/entry-body`.
  Shared with the HTML views, not re-derived, so the feed can't quietly
  fall out of step with what a browser shows.

  The blockquote's closing mark is dropped (marks? false): it's real markup,
  but its opening partner is a CSS ::before on .quote that no stylesheet
  carries into a feed reader's document, so leaving it in would close a
  quotation that never visibly opened."
  [entry]
  (if (= :quote (:type entry))
    (list (c/quote-blockquote entry false) (c/quote-source entry))
    (c/entry-body entry)))

(defn- item-meta
  "The facts most feed readers show only inside <content>, never in their
  own chrome: the type, the outbound target (a feed item's <title> is
  plain text, so this is the only place that link is clickable), the
  credits that trail a title on the site, a cross-post's canonical note,
  and the entry's tags — the same run of slashed parts as
  site.views.entry/article-head's meta line.

  The spacing is in the markup rather than left to CSS, unlike every
  other line on the site: a reader renders this HTML in its own document,
  with none of style.css, so a separator or a tag list that relies on a
  flex gap arrives as `link/babashka.org(via)/#clojure#tools`.

  Deliberately omits the site's permalink glyph: in a feed the permalink
  already IS the item's own <id>/rel=\"alternate\", and the inlined SVG
  has no intrinsic size outside the site's own CSS."
  [entry]
  (let [sep (list " " [:span.sep "/"] " ")
        link (m/head-credit entry :link)
        ;; the type, what the entry points at, and the credits that trail
        ;; a title on the site — one part, because the credits are
        ;; parentheticals on what precedes them, not items in the run
        lead (list (name (:type entry))
                   (when link
                     (list sep [:a {:href (:url link)} (util/host (:url link))]))
                   (c/title-credits entry))
        parts (remove nil?
                      [lead
                       (c/canonical-note entry)
                       (when (seq (m/tags entry))
                         (interpose " " (c/tag-links entry)))])]
    (into [:p.entry-meta] (interpose sep parts))))

(defn- entry-element [config entry]
  (let [url (str (:base-url config) (:path entry))
        timestamp (rfc-3339 (:date entry))]
    [:entry
     [:title (:title entry)]
     [:id url]
     [:link {:rel "alternate" :type "text/html" :href url}]
     (credit-links entry)
     (category-elements config entry)
     [:published timestamp]
     [:updated timestamp]
     ;; `type=html` is escaped HTML by definition; feed readers decode the
     ;; text and then render it as HTML. Hiccup performs the XML escaping.
     ;; The metadata line rides along inside <content> rather than as
     ;; separate elements because most readers show only the body — a
     ;; reader that never surfaces <category>/<link> would otherwise never
     ;; show an entry's tags, via credit, or cross-post home at all.
     [:content {:type "html"}
      (absolutize (:base-url config)
                  (str (h/html (item-body entry) (item-meta entry))))]]))

(defn atom-feed
  "The most recent of `entries` (:feed-entries cap, default 20) as an Atom
  1.0 feed. The 2-arity is the site feed; the 3-arity supplies the entries
  and identity for a scoped feed such as a tag feed."
  ([config index]
   (atom-feed config (:entries index) {:title (:site-title config)
                                       :path ""
                                       :feed-path "/feed.xml"
                                       :description (:site-description config)}))
  ([config entries {:keys [title path feed-path description]}]
   (let [n (or (:feed-entries config) default-feed-entries)
         entries (vec (take n entries))
         ;; Atom requires feed-level updated. A stable epoch keeps an empty
         ;; site feed valid without making its representation change on every
         ;; request; non-empty feeds use their newest entry.
         updated (if-let [entry (first entries)]
                   (rfc-3339 (:date entry))
                   "1970-01-01T00:00:00Z")
         page-url (str (:base-url config) path)
         feed-url (str (:base-url config) feed-path)]
     (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          (h/html {:mode :xml}
                  [:feed {:xmlns "http://www.w3.org/2005/Atom"}
                   [:title title]
                   [:subtitle description]
                   [:id feed-url]
                   [:link {:rel "self" :type "application/atom+xml" :href feed-url}]
                   [:link {:rel "alternate" :type "text/html" :href page-url}]
                   [:updated updated]
                   [:author [:name (:site-title config)]]
                   (map #(entry-element config %) entries)])))))

(defn tag-atom
  "A tag's entries as Atom, linking to the tag's listing page."
  [config tag entries]
  (atom-feed config entries
             {:title (str (:site-title config) " / #" (name tag))
              :path (util/tag-url tag)
              :feed-path (util/tag-feed-url tag)
              :description (str "Entries tagged #" (name tag))}))

(defn type-atom
  "An entry type's entries as Atom, linking to its listing page."
  [config type entries]
  (let [label (str (name type) "s")]
    (atom-feed config entries
               {:title (str (:site-title config) " / " (str/capitalize label))
                :path (util/type-url type)
                :feed-path (util/type-feed-url type)
                :description (str "All " label " published on " (:site-title config))})))
