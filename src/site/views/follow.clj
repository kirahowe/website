(ns site.views.follow
  "The /follow page: static prose from pages/follow.md with the email follow
  form between the body and its closing note. The prose is editable in the
  content repo like any other page; the form is code (it POSTs to
  Buttondown)."
  (:require [site.markdown :as markdown]
            [site.views.components :as c]
            [site.views.layout :as layout]))

(defn follow-page
  "config and the pages/follow.md page map (may be nil) → the /follow page.
  The form sits between the body and its closing note: the last rendered
  block trails the form, everything before it leads. That's a rule about
  the page's shape, not an index into a document that lives in the content
  repo and gets edited freely."
  [config page]
  (let [title (or (:title page) "Follow")
        blocks (when (:body page) (rest (markdown/render (:body page) nil)))
        [intro detail] (split-at (max 0 (dec (count blocks))) blocks)]
    (layout/page config {:title title :path "/follow"}
                 [:article.article
                  [:h1 title]
                  intro
                  (c/follow-form config {:id "follow-page-email"})
                  detail])))
