(ns site.views.follow
  "The /follow page: static prose from pages/follow.md, then the email follow
  form. The prose is editable in the content repo like any other page; the
  form is code (it POSTs to Buttondown)."
  (:require [site.markdown :as markdown]
            [site.views.components :as c]
            [site.views.layout :as layout]))

(defn follow-page
  "config and the pages/follow.md page map (may be nil) → the /follow page."
  [config page]
  (let [title (or (:title page) "Follow")]
    (layout/page config {:title title :path "/follow"}
                 [:article.article
                  [:h1 title]
                  (when (:body page) (rest (markdown/render (:body page) nil)))
                  (c/follow-form config {:id "follow-page-email"})])))
