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
  "The first paragraph of a markdown string — what post previews show."
  [s]
  (first (str/split (str s) #"\n\s*\n" 2)))

(defn word-count
  "Rough word count of a markdown string (whitespace-separated tokens)."
  [s]
  (count (re-seq #"\S+" (str s))))
