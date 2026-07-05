(ns site.markdown
  "The only namespace that touches the markdown library, so rendering
  concerns stay in one place."
  (:require [clojure.string :as str]
            [nextjournal.markdown :as md]))

(defn render
  "markdown string → hiccup"
  [s]
  (md/->hiccup (str s)))

(defn lede
  "The first paragraph of a markdown string (used for post summaries in lists)."
  [s]
  (first (str/split (str s) #"\n\s*\n" 2)))
