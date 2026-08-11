(ns site.views.follow
  "The /follow page: static prose from pages/follow.md with the email follow
  form between its opening block and the remaining detail. The prose is
  editable in the content repo like any other page; the form is code (it
  POSTs to Buttondown)."
  (:require [site.markdown :as markdown]
            [site.views.components :as c]
            [site.views.layout :as layout]))

(defn follow-page
  "config and the pages/follow.md page map (may be nil) → the /follow page."
  [config page]
  (let [title (or (:title page) "Follow")
        blocks (when (:body page) (rest (markdown/render (:body page) nil)))
        [intro detail] (split-at 3 blocks)]
    (layout/page config {:title title :path "/follow"}
                 [:article.article
                  [:h1 title]
                  intro
                  (c/follow-form config {:id "follow-page-email"})
                  detail])))
