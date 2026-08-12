(ns site.handlers
  "Route match → Ring response. Public pages get CDN-friendly cache
  headers; search and draft previews are never cached."
  (:require [clojure.string :as str]
            [site.feed :as feed]
            [site.search :as search]
            [site.util :as util]
            [site.views.archive :as v.archive]
            [site.views.entry :as v.entry]
            [site.views.follow :as v.follow]
            [site.views.home :as v.home]
            [site.views.layout :as v.layout]
            [site.views.search :as v.search])
  (:import [java.net URLDecoder]))

(def public-cache "public, max-age=300, stale-while-revalidate=86400")
(def homepage-browser-cache "public, max-age=0, must-revalidate")
(def homepage-cdn-cache "public, max-age=86400")
(def no-store "no-store")
(def default-type-page-entries 10)

(defn- html
  ([body] (html body 200 public-cache))
  ([body status cache]
   {:status status
    :headers {"Content-Type" "text/html; charset=utf-8"
              "Cache-Control" cache}
    :body body}))

(defn not-found [config]
  (html (v.layout/not-found config) 404 "public, max-age=60"))

(defn- atom-ok [body]
  {:status 200
   :headers {"Content-Type" "application/atom+xml; charset=utf-8"
             "Cache-Control" public-cache}
   :body body})

(defn query-params
  "\"q=hello+world&x=1\" → {\"q\" \"hello world\", \"x\" \"1\"}"
  [req]
  (into {}
        (for [kv (str/split (or (:query-string req) "") #"&")
              :when (seq kv)
              :let [[k v] (str/split kv #"=" 2)]]
          [(URLDecoder/decode k "UTF-8")
           (URLDecoder/decode (or v "") "UTF-8")])))

(defn- some-entries
  "Render a listing when there are entries; 404 when the archive is empty
  (an empty year/month/tag is indistinguishable from a mistyped URL)."
  [config entries render]
  (if (seq entries)
    (html (render entries))
    (not-found config)))

(defn- filter-year [entries year]
  (if year
    (filterv #(= year (-> % :date :year)) entries)
    entries))

(defn- filter-tag [entries tag]
  (if tag
    (filterv #(contains? (set (:tags %)) tag) entries)
    entries))

(defn- paginate
  "The requested 1-based slice of entries plus pager metadata, or nil when
  the page is past the end. A bad configured size falls back to the default
  rather than making every listing disappear."
  [config entries page]
  (let [configured (:type-page-entries config)
        per-page (if (and (int? configured) (pos? configured))
                   configured
                   default-type-page-entries)
        page (or page 1)
        total (count entries)
        pages (quot (+ total (dec per-page)) per-page)
        start (* (dec page) per-page)]
    (when (and (pos? total) (<= page pages))
      {:entries (subvec (vec entries) start (min total (+ start per-page)))
       :page page
       :pages pages
       :total total})))

(defn- tag-param
  "The ?tag= facet on a listing, as a keyword (or nil) — lets a sidebar tag
  refine the current type/year view instead of leaving for its own page."
  [req]
  (some-> (get (query-params req) "tag") not-empty keyword))

(defn handle
  "Dispatch a matched route against the index."
  [config index {:keys [handler params]} req]
  (case handler
    :home
    (assoc-in (html (v.home/home config index) 200 homepage-browser-cache)
              [:headers "Cloudflare-CDN-Cache-Control"] homepage-cdn-cache)

    :year
    (let [{:keys [year]} params
          tag (tag-param req)]
      (some-entries config (filter-tag (get (:by-year index) year) tag)
                    #(v.archive/year-page config index year tag %)))

    :month
    (let [{:keys [year month]} params]
      (some-entries config (get (:by-month index) [year month])
                    #(v.archive/month-page config index year month %)))

    :day
    (let [{:keys [year month day]} params]
      (some-entries config (get (:by-day index) [year month day])
                    #(v.archive/day-page config year month day %)))

    :entry
    (let [canonical (util/entry-url {:date params :slug (:slug params)})]
      (if-let [entry (get (:by-path index) canonical)]
        ;; Serve one canonical URL; /2026/07/04/x redirects to /2026/jul/4/x
        (if (= (:uri req) canonical)
          (html (v.entry/entry-page config index entry))
          {:status 301 :headers {"Location" canonical}})
        (not-found config)))

    :archive
    ;; "Archive" in the nav lands on the most recent year that has content.
    (if-let [year (first (sort > (keys (:by-year index))))]
      {:status 302 :headers {"Location" (str "/" year)}}
      (html (v.home/home config index)))

    :type-list
    (let [{:keys [type year page]} params
          tag (tag-param req)
          all-entries (-> (get (:by-type index) type)
                          (filter-year year)
                          (filter-tag tag))]
      (if-let [pagination (paginate config all-entries page)]
        (html (v.archive/type-page config index type year tag all-entries pagination))
        (not-found config)))

    :tag
    (let [{:keys [tag year]} params]
      (some-entries config (filter-year (get (:by-tag index) tag) year)
                    #(v.archive/tag-page config tag year %)))

    :tags
    (html (v.archive/tags-page config index))

    :search
    ;; ?q= is the query; ?type= and ?tag= are the facet filters the results
    ;; page offers. An unknown type is ignored rather than 404ing.
    (let [params (query-params req)
          not-blank #(when-not (str/blank? %) %)
          q (not-blank (get params "q"))
          type (some->> (not-blank (get params "type")) keyword
                        ((set (:entry-types config))))
          tag (some-> (not-blank (get params "tag")) keyword)
          matches (if q (search/search index q) [])]
      (html (v.search/search-page config index {:q q :type type :tag tag} matches)
            200 no-store))

    :draft
    ;; Drafts are a dev-mode concern only — the production server never
    ;; renders them.
    (let [entry (get (:drafts index) (:name params))]
      (if (and (:dev? config) entry)
        (html (v.entry/draft-page config entry) 200 no-store)
        (not-found config)))

    :page
    (if-let [page (get (:pages index) (:slug params))]
      (html (v.layout/static-page config page))
      (not-found config))

    :follow
    (html (v.follow/follow-page config (get (:pages index) "follow")))

    :feed
    (atom-ok (feed/atom-feed config index))

    :type-feed
    ;; Configured but unused types have no listing and therefore no feed.
    (let [{:keys [type]} params]
      (if-let [entries (seq (get (:by-type index) type))]
        (atom-ok (feed/type-atom config type entries))
        (not-found config)))

    :tag-feed
    ;; A feed for a tag that has no entries 404s, like its listing would.
    (let [{:keys [tag]} params]
      (if-let [entries (seq (get (:by-tag index) tag))]
        (atom-ok (feed/tag-atom config tag entries))
        (not-found config)))))
