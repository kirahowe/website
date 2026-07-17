(ns site.views.entry
  "The single-entry page: a full reading article, then a footer of tag
  chips, a dense related grid, and a short bio. Quotes render as a large
  blockquote; outbound types (links, releases, tools) title-link to source."
  (:require [clojure.string :as str]
            [site.markdown :as markdown]
            [site.util :as util]
            [site.views.components :as c]
            [site.views.layout :as layout]))

(def ^:private default-bio
  "Kira Howe is a software engineer writing about Clojure, data, and the
  craft of building small, durable software.")

(defn- first-name [config]
  (first (str/split (:site-title config) #"\s+")))

(defn- article-head [entry]
  (list
   (when-let [title (:title entry)]
     [:h1 (if (:link-url entry)
            [:a {:href (:link-url entry)} title]
            title)
      ;; a quote's via credit sits on its source line, not the title
      (when-not (= :quote (:type entry))
        (c/via-link entry))])
   [:div.article-meta
    (util/format-date (:date entry))
    (cond
      (:link-url entry)
      (list [:span.sep "/"] (util/host (:link-url entry)))
      (= :post (:type entry))
      (list [:span.sep "/"] (str (markdown/read-time (:body entry)) " min read")))]))

(defn- article-body [entry]
  (if (= :quote (:type entry))
    (list
     (c/quote-blockquote entry)
     (c/quote-source entry))
    (markdown/render-article (:body entry) (:wikilinks entry))))

(defn- post-footer [config index entry]
  (let [rel (c/related (:entries index) entry 8)]
    [:div.post-footer
     (when (seq (:tags entry))
       [:section.section
        [:div.tag-chips (c/tag-links (:tags entry))]])
     (when (seq rel)
       [:section.section
        [:h2 "Related"]
        [:div.related (map c/related-item rel)]])
     [:section.section
      [:p.bio (or (:bio config) default-bio)]
      [:a.more {:href "/about"} (str "More about " (first-name config) " →")]]]))

(defn entry-page [config index entry]
  (layout/page config (or (:title entry) (util/format-date (:date entry)))
               [:article.article
                (article-head entry)
                (article-body entry)]
               (post-footer config index entry)))

(defn draft-page [config entry]
  (layout/page config (str "Draft: " (or (:title entry) (:draft-name entry)))
               [:div.draft-banner "Draft — dev preview, not published."]
               [:article.article
                (when (:title entry) [:h1 (:title entry)])
                (when (seq (:tags entry))
                  [:div.tag-chips (c/tag-links (:tags entry))])
                (article-body entry)]))
