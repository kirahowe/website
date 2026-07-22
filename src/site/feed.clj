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
  "The most recent entries (:feed-entries, default 20) as an RSS 2.0 XML
  string."
  [config index]
  (let [n (or (:feed-entries config) default-feed-entries)]
    (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
         (h/html {:mode :xml}
                 [:rss {:version "2.0"}
                  [:channel
                   [:title (:site-title config)]
                   [:link (:base-url config)]
                   [:description (:site-description config)]
                   (map #(item config %) (take n (:entries index)))]]))))
