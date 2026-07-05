(ns site.handlers
  "Route match → Ring response. Public pages get CDN-friendly cache
  headers; search and draft previews are never cached."
  (:require [clojure.string :as str]
            [site.feed :as feed]
            [site.search :as search]
            [site.util :as util]
            [site.views.archive :as v.archive]
            [site.views.entry :as v.entry]
            [site.views.home :as v.home]
            [site.views.layout :as v.layout]
            [site.views.search :as v.search])
  (:import [java.net URLDecoder]))

(def public-cache "public, max-age=300, stale-while-revalidate=86400")
(def no-store "no-store")

(defn- html
  ([body] (html body 200 public-cache))
  ([body status cache]
   {:status status
    :headers {"Content-Type" "text/html; charset=utf-8"
              "Cache-Control" cache}
    :body body}))

(defn not-found [config]
  (html (v.layout/not-found config) 404 "public, max-age=60"))

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

(defn handle
  "Dispatch a matched route against the index."
  [config index {:keys [handler params]} req]
  (case handler
    :home
    (html (v.home/home config index))

    :year
    (let [{:keys [year]} params]
      (some-entries config (get (:by-year index) year)
                    #(v.archive/year-page config index year %)))

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
          (html (v.entry/entry-page config entry))
          {:status 301 :headers {"Location" canonical}})
        (not-found config)))

    :type-list
    (let [{:keys [type year]} params]
      (some-entries config (filter-year (get (:by-type index) type) year)
                    #(v.archive/type-page config type year %)))

    :tag
    (let [{:keys [tag year]} params]
      (some-entries config (filter-year (get (:by-tag index) tag) year)
                    #(v.archive/tag-page config tag year %)))

    :tags
    (html (v.archive/tags-page config index))

    :search
    (let [q (get (query-params req) "q")
          q (when-not (str/blank? q) q)
          results (if q (search/search index q) [])]
      (html (v.search/search-page config q results) 200 no-store))

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

    :feed
    {:status 200
     :headers {"Content-Type" "application/rss+xml; charset=utf-8"
               "Cache-Control" public-cache}
     :body (feed/rss config index)}))
