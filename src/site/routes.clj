(ns site.routes
  "Hand-rolled router. The URL space is small and regular, so routing is
  just: split the path into segments, match on their shape.

  /                        home (recent entries)
  /2026                    year archive
  /2026/jul                month archive (numeric months accepted too)
  /2026/jul/4              day archive
  /2026/jul/4/my-post      single entry
  /posts /notes /links ... type listings (derived from config :entry-types)
  /2026/posts              type listing filtered by year
  /tags                    tag index
  /tags/clojure            entries tagged clojure
  /tags/clojure/2026       ... filtered by year
  /search?q=...            search
  /feed.xml                RSS
  /drafts/<name>?preview=  token-gated draft preview
  /<slug>                  static page (pages/<slug>.md), e.g. /about"
  (:require [clojure.string :as str]
            [site.util :as util])
  (:import [java.net URLDecoder]))

(defn- decode [s]
  (try (URLDecoder/decode s "UTF-8")
       (catch Exception _ s)))

(defn path-segments [uri]
  (->> (str/split (str uri) #"/")
       (remove str/blank?)
       (mapv decode)))

(defn type-plurals
  "{\"posts\" :post, \"notes\" :note, ...} from config."
  [config]
  (into {} (map (fn [t] [(str (name t) "s") t])) (:entry-types config)))

(defn match-route
  "segments → {:handler <kw> :params {...}} or nil (→ 404)."
  [config segments]
  (let [plural->type (type-plurals config)
        [a b c d] segments]
    (case (count segments)
      0 {:handler :home}

      1 (cond
          (= a "feed.xml") {:handler :feed}
          (= a "search") {:handler :search}
          (= a "archive") {:handler :archive}
          (= a "tags") {:handler :tags}
          (plural->type a) {:handler :type-list :params {:type (plural->type a)}}
          (util/parse-year a) {:handler :year :params {:year (util/parse-year a)}}
          :else {:handler :page :params {:slug a}})

      2 (cond
          (= a "tags")
          {:handler :tag :params {:tag (keyword b)}}

          (= a "drafts")
          {:handler :draft :params {:name b}}

          (and (util/parse-year a) (plural->type b))
          {:handler :type-list :params {:type (plural->type b)
                                        :year (util/parse-year a)}}

          (and (util/parse-year a) (util/parse-month b))
          {:handler :month :params {:year (util/parse-year a)
                                    :month (util/parse-month b)}}

          :else nil)

      3 (cond
          (and (= a "tags") (util/parse-year c))
          {:handler :tag :params {:tag (keyword b)
                                  :year (util/parse-year c)}}

          (and (util/parse-year a) (util/parse-month b) (util/parse-day c))
          {:handler :day :params {:year (util/parse-year a)
                                  :month (util/parse-month b)
                                  :day (util/parse-day c)}}

          :else nil)

      4 (when (and (util/parse-year a) (util/parse-month b) (util/parse-day c))
          {:handler :entry :params {:year (util/parse-year a)
                                    :month (util/parse-month b)
                                    :day (util/parse-day c)
                                    :slug d}})

      nil)))
