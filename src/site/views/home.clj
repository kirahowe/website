(ns site.views.home
  (:require [site.views.components :as c]
            [site.views.layout :as layout]))

(def recent-count 30)

(defn home [config index]
  (layout/page config nil
               (c/entry-list (take recent-count (:entries index)))))
