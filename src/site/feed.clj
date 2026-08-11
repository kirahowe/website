(ns site.feed
  "Atom 1.0 feeds, rendered with hiccup in XML mode."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [site.markdown :as markdown]
            [site.util :as util])
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

(defn- entry-element [config entry]
  (let [url (str (:base-url config) (:path entry))
        timestamp (rfc-3339 (:date entry))]
    [:entry
     [:title (:title entry)]
     [:id url]
     [:link {:rel "alternate" :type "text/html" :href url}]
     [:published timestamp]
     [:updated timestamp]
     ;; `type=html` is escaped HTML by definition; feed readers decode the
     ;; text and then render it as HTML. Hiccup performs the XML escaping.
     [:content {:type "html"}
      (absolutize (:base-url config)
                  (str (h/html (markdown/render (:body entry) (:wikilinks entry)))))]]))

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
