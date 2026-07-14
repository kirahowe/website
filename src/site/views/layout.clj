(ns site.views.layout
  "Site chrome. `page` wraps hiccup content and renders to an HTML string;
  views are pure functions of data."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [site.markdown :as markdown]))

(def ^:private fonts
  (str "https://fonts.googleapis.com/css2?family=Caveat:wght@700"
       "&family=IBM+Plex+Mono:wght@400;500;600;700"
       "&family=IBM+Plex+Sans:ital,wght@0,400;0,500;0,600;0,700;1,400&display=swap"))

;; The wordmark logotype, inlined once so it inherits the theme colour via
;; currentColor and inverts on hover. Read once, not per request.
(def ^:private wordmark
  (delay (slurp (io/resource "public/images/wordmark.svg"))))

(defn- header [config home?]
  [:header.site-header
   [:a {:class (if home? "brand" "brand brand-sm") :href "/"} (h/raw @wordmark)]
   [:nav.site-nav
    (for [t (:entry-types config)]
      [:a {:class (str "type " (name t)) :href (str "/" (name t) "s")}
       (str/capitalize (str (name t) "s"))])
    [:span.nav-sep]
    [:a {:href "/tags"} "Tags"]
    [:a {:href "/archive"} "Archive"]
    [:a {:href "/search"} "Search"]
    [:a {:href "/about"} "About"]]])

(defn- footer [config]
  (let [year (.getYear (java.time.LocalDate/now))]
    [:footer.site-footer
     [:span "© " year " " (:site-title config) " · built with Clojure + hiccup"]
     [:span.social
      [:a {:href "/feed.xml"} "RSS"]
      (for [[label url] (:social config)]
        [:a {:href url} label])]]))

(defn page
  "config, title (nil for the homepage), hiccup content → full HTML string."
  [config title & content]
  (let [full-title (if title
                     (str title " — " (:site-title config))
                     (:site-title config))
        og-image   (str (:base-url config) "/images/og.png")]
    (str
     (h/html {:mode :html}
             (h/raw "<!DOCTYPE html>\n")
             [:html {:lang "en"}
              [:head
               [:meta {:charset "utf-8"}]
               [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
               [:title full-title]
               [:meta {:name "description" :content (:site-description config)}]
               ;; Open Graph — how the site renders when shared (link previews)
               [:meta {:property "og:type" :content "website"}]
               [:meta {:property "og:site_name" :content (:site-title config)}]
               [:meta {:property "og:title" :content full-title}]
               [:meta {:property "og:description" :content (:site-description config)}]
               [:meta {:property "og:url" :content (:base-url config)}]
               [:meta {:property "og:image" :content og-image}]
               [:meta {:property "og:image:type" :content "image/png"}]
               [:meta {:property "og:image:width" :content "2400"}]
               [:meta {:property "og:image:height" :content "1260"}]
               [:meta {:property "og:image:alt" :content (:site-title config)}]
               ;; Twitter/X uses its own namespace; large summary card
               [:meta {:name "twitter:card" :content "summary_large_image"}]
               [:meta {:name "twitter:title" :content full-title}]
               [:meta {:name "twitter:description" :content (:site-description config)}]
               [:meta {:name "twitter:image" :content og-image}]
               [:link {:rel "preconnect" :href "https://fonts.gstatic.com" :crossorigin true}]
               [:link {:rel "stylesheet" :href fonts}]
               [:link {:rel "stylesheet" :href "/css/style.css"}]
               [:link {:rel "alternate" :type "application/rss+xml"
                       :title (:site-title config) :href "/feed.xml"}]]
              [:body
               (header config (nil? title))
               [:main content]
               (footer config)]]))))

(defn static-page [config {:keys [title body]}]
  (page config title
        [:article.article
         [:h1 title]
         (markdown/render-article body nil)]))

(defn not-found [config]
  (page config "Not found"
        [:article.article
         [:h1 "404"]
         [:p "Nothing lives at this address. Try "
          [:a {:href "/"} "the homepage"] "."]]))
