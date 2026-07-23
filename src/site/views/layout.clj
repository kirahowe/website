(ns site.views.layout
  "Site chrome. `page` wraps hiccup content and renders to an HTML string;
  views are pure functions of data."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [site.markdown :as markdown]))

;; The wordmark logotype, inlined once so it inherits the theme colour via
;; currentColor and inverts on hover. Read once, not per request.
(def ^:private wordmark
  (delay (slurp (io/resource "public/images/wordmark.svg"))))

;; Cache-busting asset URLs: every asset a page links carries its content
;; hash (?v=), so a changed file arrives at a brand-new URL and the old
;; one can sit in browser and CDN caches forever without ever going
;; stale. Hashed once per process in prod (assets ship inside the deploy
;; image, so a change always means a fresh process); per render under
;; `bb dev`, so an edited stylesheet busts through on the next reload.
(defn- asset-hash [path]
  (let [bytes (with-open [in (io/input-stream (io/resource (str "public" path)))]
                (.readAllBytes in))]
    (subs (format "%032x" (BigInteger. 1 (.digest (java.security.MessageDigest/getInstance "MD5") bytes)))
          0 8)))

(def ^:private asset-hash-cached (memoize asset-hash))

(defn- asset-url [config path]
  (str path "?v=" ((if (:dev? config) asset-hash asset-hash-cached) path)))

;; Injected only under `bb dev`: opens an SSE stream to site.livereload and
;; refreshes the page when a watched file changes. Reconnecting after the
;; server itself restarts also reloads, so code edits (which need a `bb dev`
;; restart) still land in the browser without a manual refresh.
(def ^:private livereload-script
  (str "(function(){var opened=false;"
       "var es=new EventSource('/__livereload');"
       "es.addEventListener('reload',function(){location.reload()});"
       "es.addEventListener('open',function(){if(opened)location.reload();opened=true});"
       "})();"))

;; Privacy-friendly, self-hosted analytics (Umami). Emitted in the head of
;; every page in production only — counting local `bb dev` views would skew
;; the stats, so it is gated the same way livereload is (but inverted).
(def ^:private analytics-script
  [:script {:defer true
            :src "https://kirasumami.pikapod.net/script.js"
            :data-website-id "e54cf840-1328-45a5-a617-7f15ef917005"}])

(defn- header [config home?]
  [:header.site-header
   [:a {:class (if home? "brand" "brand brand-sm") :href "/"} (h/raw @wordmark)]
   [:nav.site-nav
    ;; :nav-types, not :entry-types — only types with at least one
    ;; published entry get a link, so an unused type is never a
    ;; permanent dead link to a 404.
    (for [t (:nav-types config)]
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
     [:span "© " year " " (:site-title config) " / built with Clojure + hiccup"]
     [:span.social
      [:a {:href "/feed.xml"} "RSS"]
      (for [[label url] (:social config)]
        [:a {:href url} label])]]))

(defn page
  "config, opts, hiccup content → full HTML string. opts:
    :title      the page title; nil for the homepage
    :path       the page's own path (\"/2026/jul/4/x\") — absolutized into
                its rel=canonical and og:url. Facetted views pass their
                clean path, so ?tag= variants canonicalize to the plain
                listing; pages with no stable URL (drafts, 404) pass none.
    :canonical  an absolute URL that replaces the self rel=canonical — an
                entry whose canonical home is elsewhere points there."
  [config {:keys [title path canonical]} & content]
  (let [full-title (if title
                     (str title " — " (:site-title config))
                     (:site-title config))
        self-url   (when path (str (:base-url config) path))
        og-image   (str (:base-url config) (asset-url config "/images/og.png"))]
    (str
     (h/html {:mode :html}
             (h/raw "<!DOCTYPE html>\n")
             [:html {:lang "en"}
              [:head
               [:meta {:charset "utf-8"}]
               [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
               ;; Read during HTML parse, before any stylesheet loads, so the
               ;; browser paints a dark canvas on the first frame of every
               ;; navigation — no white flash while style.css/fonts arrive.
               ;; "dark light" = dark by default, light for OS-light users,
               ;; matching the prefers-color-scheme switch in style.css.
               [:meta {:name "color-scheme" :content "dark light"}]
               [:title full-title]
               [:meta {:name "description" :content (:site-description config)}]
               ;; The page's one true URL: an external :canonical wins (the
               ;; content's home is elsewhere), else the page's own address.
               (when-let [href (or canonical self-url)]
                 [:link {:rel "canonical" :href href}])
               ;; Open Graph — how the site renders when shared (link previews).
               ;; og:url stays this page's own URL even when rel=canonical
               ;; points elsewhere: a share of this page is about this page.
               [:meta {:property "og:type" :content "website"}]
               [:meta {:property "og:site_name" :content (:site-title config)}]
               [:meta {:property "og:title" :content full-title}]
               [:meta {:property "og:description" :content (:site-description config)}]
               [:meta {:property "og:url" :content (or self-url (:base-url config))}]
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
               [:link {:rel "stylesheet" :href (asset-url config "/css/style.css")}]
               [:link {:rel "alternate" :type "application/rss+xml"
                       :title (:site-title config) :href "/feed.xml"}]
               (when-not (:dev? config) analytics-script)]
              [:body
               (header config (nil? title))
               [:main content]
               (footer config)
               (when (:dev? config) [:script (h/raw livereload-script)])]]))))

(defn static-page [config {:keys [title body path]}]
  (page config {:title title :path path}
        [:article.article
         [:h1 title]
         (markdown/render-article body nil)]))

(defn not-found [config]
  (page config {:title "Not found"}
        [:article.article
         [:h1 "404"]
         [:p "Nothing lives at this address. Try "
          [:a {:href "/"} "the homepage"] "."]]))
