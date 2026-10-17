(ns site.sitemap
  "robots.txt and sitemap.xml (RFC 9309; sitemaps.org protocol 0.9).

  The sitemap lists every canonical, indexable HTML URL the site serves:
  the homepage, static pages, type listings, the tag index and each tag,
  the year and month archives, and every entry. It leaves out paged
  listings (page 2 of a listing is reachable from page 1 and isn't a
  destination), search (its URL space is unbounded), the /archive
  redirect, and any entry whose rel=canonical points at another site,
  because a sitemap is a list of canonical URLs and that one isn't ours.

  No <lastmod>: the only date an entry carries is its publication date,
  and crawlers read lastmod as a modification time, discounting a sitemap
  whose lastmods prove unreliable."
  (:require [hiccup2.core :as h]
            [site.util :as util]))

(defn robots
  "Everything may be crawled except search. Search results are generated
  from the query string, so a crawler following them would wander an
  unbounded space of pages that only repeat the entries the sitemap
  already lists."
  [config]
  (str "User-agent: *\n"
       "Disallow: /search\n"
       "\n"
       "Sitemap: " (:base-url config) "/sitemap.xml\n"))

(defn- self-canonical?
  "False for an entry cross-posted from elsewhere: its canonical home is
  another site, so its page here is a copy."
  [config entry]
  (let [canonical (:canonical-url entry)]
    (or (nil? canonical)
        (= canonical (str (:base-url config) (:path entry))))))

(defn paths
  "Every path the sitemap lists, in a stable order."
  [config index]
  (distinct
   (concat
    ["/"]
    (sort (map :path (vals (:pages index))))
    ["/follow" "/tags"]
    (map util/type-url (:nav-types index))
    (map (comp util/tag-url first) (:tag-counts index))
    (map #(str "/" %) (sort > (keys (:by-year index))))
    (for [[y m] (:months index)] (str "/" y "/" (util/month-slug m)))
    (->> (:entries index)
         (filter #(self-canonical? config %))
         (map :path)))))

(defn sitemap [config index]
  (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
       (h/html {:mode :xml}
               [:urlset {:xmlns "http://www.sitemaps.org/schemas/sitemap/0.9"}
                (for [p (paths config index)]
                  [:url [:loc (str (:base-url config) p)]])])))
