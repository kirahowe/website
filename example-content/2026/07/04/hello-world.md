;;;
{:type :post
 :title "Hello, world"
 :tags [:meta :clojure]}
;;;

This site is a folder of markdown files served by a small babashka program.

## How it works

Every entry is a markdown file with EDN frontmatter. The date comes from the
file's path — this file lives at `2026/07/04/hello-world.md`, so it's
published at `/2026/jul/4/hello-world`.

```clojure
(defn entry-url [{:keys [date slug]}]
  (str "/" (:year date) "/" (month-slug (:month date)) "/" (:day date) "/" slug))
```

Posts, notes, links, and quotes all flow through the same pipeline, and the
whole thing rebuilds from the files at any time.
