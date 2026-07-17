(ns site.markdown
  "The only namespace that touches the markdown library, so rendering
  concerns stay in one place. Obsidian's dialect is handled here:
  [[wikilinks]] resolve against the entry index at the AST level (so
  code blocks stay literal), and ![[image embeds]] become /attachments/
  images."
  (:require [clojure.string :as str]
            [nextjournal.markdown :as md]
            [nextjournal.markdown.utils :as md.utils]))

(def ^:private parse-ctx
  (update md.utils/empty-doc :text-tokenizers conj md.utils/internal-link-tokenizer))

(def ^:private image-extensions
  #{"png" "jpg" "jpeg" "gif" "webp" "svg"})

(defn- attachment-url [file-name]
  (str "/attachments/" (str/replace (str/trim file-name) " " "%20")))

(defn- embeds->images
  "Obsidian image embeds → standard markdown images served from
  /attachments/: ![[img.png]] and ![[img.png|alt text]]."
  [line]
  (str/replace line #"!\[\[([^\]|]+?)(?:\|([^\]]*))?\]\]"
               (fn [[whole target alt]]
                 (let [ext (str/lower-case (or (peek (str/split target #"\.")) ""))]
                   (if (image-extensions ext)
                     (str "![" (or alt "") "](" (attachment-url target) ")")
                     whole)))))

(defn- absolutize-attachments
  "Relative attachments/ refs (Obsidian's markdown-style links) → the
  served path, which works from any page depth."
  [line]
  (str/replace line "](attachments/" "](/attachments/"))

(defn- fence-line? [line]
  (boolean (re-matches #"\s*(```|~~~).*" line)))

(defn- preprocess
  "Line-level Obsidian-isms that must become standard markdown before
  parsing; fenced code blocks pass through untouched."
  [s]
  (->> (reduce (fn [[out in-fence?] line]
                 (cond
                   (fence-line? line) [(conj out line) (not in-fence?)]
                   in-fence? [(conj out line) true]
                   :else [(conj out (-> line embeds->images absolutize-attachments))
                          false]))
               [[] false]
               (str/split-lines (str s)))
       first
       (str/join "\n")))

(defn- wikilink-renderer
  "[[Name]] / [[Name|label]] → an anchor when the name resolves to an
  entry, a plain (classed) span when it doesn't — an unpublished target
  must never leak a dead link."
  [resolve-target]
  (fn [_ctx {:keys [text]}]
    (let [[target label] (str/split (str text) #"\|" 2)
          label (str/trim (or label target))]
      (if-let [url (resolve-target target)]
        [:a.internal {:href url} label]
        [:span.unresolved-link label]))))

(defn render
  "markdown string → hiccup. `wikilinks` is {lowercased filename → url}
  (built by the content index and carried on each entry); without it,
  [[links]] render as plain text."
  ([s] (render s nil))
  ([s wikilinks]
   (let [resolve-target (fn [t]
                          (let [t (-> (str t)
                                      (str/split #"#") first        ; drop heading anchors
                                      (str/split #"/") peek         ; path-qualified links
                                      str/trim)]
                            (get wikilinks (str/lower-case t))))
         renderers (assoc md/default-hiccup-renderers
                          :internal-link (wikilink-renderer resolve-target))]
     (md/->hiccup renderers (md/parse parse-ctx (preprocess s))))))

;; --- introspection for publish-time lints --------------------------------

(defn- lines-outside-fences [s]
  (first (reduce (fn [[out in-fence?] line]
                   (cond
                     (fence-line? line) [out (not in-fence?)]
                     in-fence? [out true]
                     :else [(conj out line) false]))
                 [[] false]
                 (str/split-lines (str s)))))

(defn wikilinks-in
  "Targets of [[wikilinks]] (embeds excluded, code fences skipped) — what
  a publish lint checks for resolvability."
  [s]
  (->> (lines-outside-fences s)
       (mapcat #(re-seq #"(?<!!)\[\[([^\]|#]+)[^\]]*\]\]" %))
       (map (comp str/trim second))))

(defn attachments-in
  "Image files a markdown string references, via ![[embeds]] or
  (/)attachments/ links — what a publish lint checks exist."
  [s]
  (->> (lines-outside-fences s)
       (mapcat (fn [line]
                 (concat (map second (re-seq #"!\[\[([^\]|]+?)(?:\|[^\]]*)?\]\]" line))
                         (map second (re-seq #"\]\(/?attachments/([^)\s]+)\)" line)))))
       (map #(-> % str/trim (str/replace "%20" " ")))
       (filter #(image-extensions (str/lower-case (or (peek (str/split % #"\.")) ""))))))

(defn lede
  "The first paragraph of a markdown string — what post previews show."
  [s]
  (first (str/split (str s) #"\n\s*\n" 2)))

(defn word-count
  "Rough word count of a markdown string (whitespace-separated tokens)."
  [s]
  (count (re-seq #"\S+" (str s))))

(defn read-time
  "Reading-time estimate in whole minutes (~200 wpm), at least 1."
  [s]
  (max 1 (Math/round (/ (word-count s) 200.0))))

(defn plain
  "A markdown string as plain text: images and embeds drop, links keep
  their labels, syntax marks strip, whitespace collapses. Search snippets
  run this over whole bodies; `excerpt` over the lede."
  [s]
  (-> (str s)
      (str/replace #"!\[\[[^\]]*\]\]" "")               ; ![[embeds]]
      (str/replace #"!\[[^\]]*\]\([^)]*\)" "")           ; ![alt](img)
      (str/replace #"\[\[[^\]|]+\|([^\]]+)\]\]" "$1")    ; [[t|label]] → label
      (str/replace #"\[\[([^\]]+)\]\]" "$1")             ; [[t]] → t
      (str/replace #"\[([^\]]+)\]\([^)]*\)" "$1")        ; [text](url) → text
      (str/replace #"[*_`>#]" "")                         ; emphasis / code / heading marks
      (str/replace #"\s+" " ")
      str/trim))

(defn excerpt
  "The first paragraph as plain text — markdown syntax stripped — for the
  one-line previews in feed rows and listings."
  [s]
  (plain (lede s)))

(defn- add-class [[tag & more] cls]
  (let [[attrs children] (if (map? (first more))
                           [(first more) (rest more)]
                           [{} more])]
    (into [tag (update attrs :class #(if % (str % " " cls) cls))] children)))

(defn render-article
  "Like `render`, but returns the body's block children as a sequence with
  the first paragraph tagged `.lead` — the opening-paragraph emphasis on
  post and page bodies."
  [s wikilinks]
  (let [[_div & children] (render s wikilinks)
        [before [lead & after]] (split-with #(not (and (vector? %) (= :p (first %))))
                                            children)]
    (concat before
            (when lead [(add-class lead "lead")])
            after)))
