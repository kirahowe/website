(ns site.routes
  "Hand-rolled router. The URL space is small and regular, so routing is
  just: split the path into segments, match on their shape.

  /                        home (recent entries)
  /2026                    year archive
  /2026/jul                month archive (numeric months accepted too)
  /2026/jul/4              day archive
  /2026/jul/4/my-post      single entry
  /posts /links ... type listings (derived from config :entry-types)
  /posts/feed.xml         Atom scoped to an entry type
  /2026/posts              type listing filtered by year
  /posts/page/2            older entries of a type
  /2026/posts/page/2       ... filtered by year
  /tags                    tag index
  /tags/clojure            entries tagged clojure
  /tags/clojure/2026       ... filtered by year
  /tags/clojure/feed.xml   Atom scoped to the tag
  /search?q=...            search
  /follow                  email follow page (Buttondown signup)
  /feed.xml                Atom
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
  "{\"posts\" :post, \"links\" :link, ...} from config."
  [config]
  (into {} (map (fn [t] [(str (name t) "s") t])) (:entry-types config)))

(defn- parse-page
  "Canonical listing page number: 2, 3, ... . Page 1 keeps the listing's
  existing URL, and leading-zero spellings are rejected rather than creating
  duplicate URLs."
  [s]
  (when (and s (re-matches #"[1-9]\d*" s))
    (let [n (parse-long s)]
      (when (and n (< 1 n)) n))))

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
          (= a "follow") {:handler :follow}
          (plural->type a) {:handler :type-list :params {:type (plural->type a)}}
          (util/parse-year a) {:handler :year :params {:year (util/parse-year a)}}
          :else {:handler :page :params {:slug a}})

      2 (cond
          (and (plural->type a) (= b "feed.xml"))
          {:handler :type-feed :params {:type (plural->type a)}}

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
          (and (plural->type a) (= b "page") (parse-page c))
          {:handler :type-list :params {:type (plural->type a)
                                        :page (parse-page c)}}

          (and (= a "tags") (= c "feed.xml"))
          {:handler :tag-feed :params {:tag (keyword b)}}

          (and (= a "tags") (util/parse-year c))
          {:handler :tag :params {:tag (keyword b)
                                  :year (util/parse-year c)}}

          (and (util/parse-year a) (util/parse-month b) (util/parse-day c))
          {:handler :day :params {:year (util/parse-year a)
                                  :month (util/parse-month b)
                                  :day (util/parse-day c)}}

          :else nil)

      4 (cond
          (and (util/parse-year a) (plural->type b) (= c "page") (parse-page d))
          {:handler :type-list :params {:type (plural->type b)
                                        :year (util/parse-year a)
                                        :page (parse-page d)}}

          (and (util/parse-year a) (util/parse-month b) (util/parse-day c))
          {:handler :entry :params {:year (util/parse-year a)
                                    :month (util/parse-month b)
                                    :day (util/parse-day c)
                                    :slug d}}

          :else nil)

      nil)))
