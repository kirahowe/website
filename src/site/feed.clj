(ns site.feed
  "RSS 2.0, rendered with hiccup in XML mode — no XML library needed."
  (:require [hiccup2.core :as h]
            [site.markdown :as markdown])
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
  entries and channel identity — `path` is site-relative — so a scoped
  feed (a tag's) is the same channel shape pointed elsewhere."
  ([config index]
   (rss config (:entries index) {:title (:site-title config)
                                 :path ""
                                 :description (:site-description config)}))
  ([config entries {:keys [title path description]}]
   (let [n (or (:feed-entries config) default-feed-entries)]
     (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          (h/html {:mode :xml}
                  [:rss {:version "2.0"}
                   [:channel
                    [:title title]
                    [:link (str (:base-url config) path)]
                    [:description description]
                    (map #(item config %) (take n entries))]])))))

(defn tag-rss
  "A tag's entries as RSS — the site channel scoped to one tag, linking
  the tag's listing page."
  [config tag entries]
  (rss config entries
       {:title (str (:site-title config) " / #" (name tag))
        :path (str "/tags/" (name tag))
        :description (str "Entries tagged #" (name tag))}))
