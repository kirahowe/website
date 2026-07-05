(ns site.views.home
  (:require [site.views.components :as c]
            [site.views.layout :as layout]))

(defn home
  "The whole published feed, newest first, nothing truncated."
  [config index]
  (layout/page config nil
               (c/entry-list (:entries index))))
