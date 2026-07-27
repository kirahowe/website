(ns site.feed
  "RSS 2.0, rendered with hiccup in XML mode — no XML library needed."
  (:require [hiccup2.core :as h]
            [site.markdown :as markdown]
            [site.util :as util])
  (:import [java.time ZonedDateTime ZoneOffset]
           [java.time.format DateTimeFormatter]))

(def default-feed-entries 20)

(defn- rfc-1123 [{:keys [year month day]}]
  (.format DateTimeFormatter/RFC_1123_DATE_TIME
           (ZonedDateTime/of year month day 12 0 0 0 ZoneOffset/UTC)))

(defn- item [config entry]
  (let [url (str (:base-url config) (:path entry))]
    [:item
     [:title (:title entry)]
     [:link url]
     [:guid url]
     [:pubDate (rfc-1123 (:date entry))]
     ;; Escaped HTML content — hiccup escapes the rendered string for us.
     [:description (str (h/html (markdown/render (:body entry) (:wikilinks entry))))]]))

(defn rss
  "The most recent of `entries` (:feed-entries cap, default 20) as an RSS
  2.0 XML string. The 2-arity is the site feed; the 3-arity takes its own
  entries and channel identity — `path` is the site-relative page the
  channel stands for, `feed-path` the feed's own address — so a scoped
  feed (a tag's) is the same channel shape pointed elsewhere."
  ([config index]
   (rss config (:entries index) {:title (:site-title config)
                                 :path ""
                                 :feed-path "/feed.xml"
                                 :description (:site-description config)}))
  ([config entries {:keys [title path feed-path description]}]
   (let [n (or (:feed-entries config) default-feed-entries)]
     (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          (h/html {:mode :xml}
                  [:rss {:version "2.0"
                         :xmlns:atom "http://www.w3.org/2005/Atom"}
                   [:channel
                    [:title title]
                    [:link (str (:base-url config) path)]
                    ;; The feed's own address. Without it a reader that
                    ;; re-resolves the feed has only the channel link to go
                    ;; on — an HTML page — and re-runs autodiscovery there,
                    ;; which is how a scoped feed silently widens into the
                    ;; site feed.
                    [:atom:link {:rel "self"
                                 :type "application/rss+xml"
                                 :href (str (:base-url config) feed-path)}]
                    [:description description]
                    (map #(item config %) (take n entries))]])))))

(defn tag-rss
  "A tag's entries as RSS — the site channel scoped to one tag, linking
  the tag's listing page."
  [config tag entries]
  (rss config entries
       {:title (str (:site-title config) " / #" (name tag))
        :path (util/tag-url tag)
        :feed-path (util/tag-feed-url tag)
        :description (str "Entries tagged #" (name tag))}))
