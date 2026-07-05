(ns site.views.layout
  "Site chrome. `page` wraps hiccup content and renders to an HTML string;
  views are pure functions of data."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [site.markdown :as markdown]))

(defn- nav [config]
  [:nav.site-nav
   (for [t (:entry-types config)]
     [:a {:href (str "/" (name t) "s")} (str/capitalize (str (name t) "s"))])
   [:a {:href "/tags"} "Tags"]
   [:a {:href "/search"} "Search"]
   [:a {:href "/about"} "About"]])

(defn page
  "config, title (nil for the homepage), hiccup content → full HTML string."
  [config title & content]
  (str
   (h/html {:mode :html}
           (h/raw "<!DOCTYPE html>\n")
           [:html {:lang "en"}
            [:head
             [:meta {:charset "utf-8"}]
             [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
             [:title (if title
                       (str title " — " (:site-title config))
                       (:site-title config))]
             [:link {:rel "stylesheet" :href "/css/style.css"}]
             [:link {:rel "alternate" :type "application/rss+xml"
                     :title (:site-title config) :href "/feed.xml"}]]
            [:body
             [:header.site-header
              [:a.site-title {:href "/"} (:site-title config)]
              (nav config)]
             [:main content]
             [:footer.site-footer
              [:p (:site-description config) " · " [:a {:href "/feed.xml"} "RSS"]]]]])))

(defn static-page [config {:keys [title body]}]
  (page config title
        [:article.static-page
         [:h1 title]
         (markdown/render body)]))

(defn not-found [config]
  (page config "Not found"
        [:article.not-found
         [:h1 "404"]
         [:p "Nothing lives at this address. Try " [:a {:href "/"} "the homepage"] "."]]))
