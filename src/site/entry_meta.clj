(ns site.entry-meta
  "One description of an entry's metadata — its tags and its credit links
  (via, source, canonical, the outbound link itself) — as plain data, no
  hiccup. Every surface that shows these facts (the HTML views in
  site.views.*, and the Atom feeds in site.feed) renders from the functions
  here, so a fact about an entry is stated once and can't drift between the
  page and the feed.

  A credit map is {:kind :label :url :rel :description :placement}:
    :kind        one of :link :via :source :canonical
    :label       the link's visible text — \"via\", \"source\", \"link\",
                 \"canonical\"
    :url         where the credit points
    :rel         the Atom/HTML link relation (\"related\", \"via\",
                 \"canonical\") — related covers both :link and :source,
                 which are otherwise-unrelated Atom vocabulary but the same
                 shape of fact: a URL this entry points at
    :description accessible phrasing for when the link text alone doesn't
                 name what it's a credit for (e.g. \"via\" doesn't say via
                 what); nil means the link's own text already says enough
    :placement   :head when the entry's own metadata line carries the
                 credit (beside its title on the page, in a feed item's
                 metadata line), :cite when a quote's attribution line
                 carries it instead"
  (:require [site.util :as util]))

(def ^:private credit-order
  "Display order for `credits` — the outbound link first (the thing the
  entry is actually about), then how it was found, then where its code
  lives, then where else it's published."
  [:link :via :source :canonical])

(def ^:private url-key
  {:link :link-url :via :link-via :source :source-url :canonical :canonical-url})

(def ^:private label
  {:link "link" :via "via" :source "source" :canonical "canonical"})

(def ^:private rel
  {:link "related" :via "via" :source "related" :canonical "canonical"})

(defn- placement
  "Where a credit of `kind` sits, or nil when the entry earns no credit of
  that kind at all (only :source is ever gated this way — see below).

  A quote's :via and :source describe its attribution — who said it and
  where it was found — not the entry's own metadata, so both sit on the
  cite line (:cite) rather than beside a title a quote doesn't have.

  :source is further gated by type, not just by placement: a tool's source
  code is a fact worth publishing (where the thing itself lives), and a
  quote's source is its whole reason for existing. For every other type
  :source-url would just be noise — nothing else on the site claims a
  \"source\" the way a tool or a quote does — so it earns no credit, and no
  Atom <link> or feed's meta line mentions it, no matter what the
  frontmatter says."
  [entry kind]
  (case kind
    :via (if (= :quote (:type entry)) :cite :head)
    :source (case (:type entry)
              :quote :cite
              :tool :head
              nil)
    (:link :canonical) :head))

(defn- description
  "Accessible phrasing for a credit whose link text alone (\"via\",
  \"source\") doesn't say what it's a credit for. :link and :canonical
  need none — their surrounding text already names them (the title itself
  for :link; \"originally published at\" for :canonical)."
  [entry kind url]
  (case kind
    :via (str "via " (util/host url))
    :source (when (= :tool (:type entry)) (str "Source code at " (util/host url)))
    nil))

(defn credit
  "The entry's credit of `kind`, or nil when the entry carries no URL for
  it, or (for :source) when its type doesn't earn one at all — see
  `placement`."
  [entry kind]
  (when-let [url (get entry (url-key kind))]
    (when-let [p (placement entry kind)]
      {:kind kind
       :label (label kind)
       :url url
       :rel (rel kind)
       :description (description entry kind url)
       :placement p})))

(defn credits
  "Every credit link the entry carries, in display order (`credit-order`),
  omitting any kind whose URL is absent or (for :source) whose type
  doesn't earn one. Both :head and :cite placements are included — a
  quote's source and via are still real credits, just rendered on its cite
  line rather than its (nonexistent) title; a feed's structured <link>
  elements want them either way."
  [entry]
  (into [] (keep #(credit entry %)) credit-order))

(defn head-credits
  "The credits an entry's own metadata carries — beside the title on a
  page, in the metadata line of a feed item — as opposed to the ones a
  quote's attribution line carries instead."
  [entry]
  (filterv #(= :head (:placement %)) (credits entry)))

(defn head-credit
  "The entry's `kind` credit when its own metadata carries it (:head
  placement), else nil — including when the credit exists but belongs to
  the entry's cite line instead (a quote's :via and :source)."
  [entry kind]
  (let [c (credit entry kind)]
    (when (= :head (:placement c)) c)))

(defn tags
  "An entry's tags in display order (by name) — the order the chips, the
  feed rows and the Atom categories all use."
  [entry]
  (sort-by name (:tags entry)))
