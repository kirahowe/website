(ns site.markdown
  "The only namespace that touches the markdown library, so rendering
  concerns stay in one place."
  (:require [nextjournal.markdown :as md]))

(defn render
  "markdown string → hiccup"
  [s]
  (md/->hiccup (str s)))
