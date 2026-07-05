(ns site.views.search
  (:require [site.views.components :as c]
            [site.views.layout :as layout]))

(defn search-page
  "q may be nil (blank form) or a query with results."
  [config q results]
  (layout/page config "Search"
               [:h1 "Search"]
               [:form.search-form {:action "/search" :method "get"}
                [:input {:type "search" :name "q" :value (or q "")
                         :placeholder "Search entries…" :autofocus true}]
                [:button {:type "submit"} "Search"]]
               (when q
                 (list
                  [:p.search-count
                   (str (count results) " result" (when (not= 1 (count results)) "s")
                        " for “" q "”")]
                  (c/entry-list results)))))
