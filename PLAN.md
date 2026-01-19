# Personal Website Architecture Plan

A Clojure-based personal website inspired by [Simon Willison's blog](https://simonwillison.net), designed for simplicity, elegance, and extensibility.

## Overview

**Core Philosophy**: Start simple, grow organically. The system reads markdown files from disk, parses EDN frontmatter for metadata, and serves them through a clean date-based URL structure.

**Content Authoring**: Content lives in a **separate repository** as markdown files, keeping content completely decoupled from code. This makes authoring with Obsidian seamless — just open the content repo as a vault.

---

## Content Model

### Entry Types

Three content types, unified by shared metadata:

| Type | Purpose | Typical Length |
|------|---------|----------------|
| **post** | Full articles, essays, tutorials | Long-form |
| **note** | Quick thoughts, observations | Short, may lack title |
| **link** | Bookmarks with commentary | Short + external URL |

### Frontmatter Schema

All entries use **EDN frontmatter** (delimited by `;;;`) — no YAML. Clean, readable, and native to Clojure:

```markdown
;;;
{:type :post
 :title "My Post Title"
 :tags [:clojure :web :personal]
 :slug "custom-slug"              ; Optional, overrides filename
 :draft false                     ; Optional, default false
 :link-url "https://example.com"  ; Required for :link type
 :link-via "https://source.com"}  ; Optional, for links
;;;

Content body in markdown...
```

**Note**: The date is derived from the file path (`YYYY/MM/DD/`), not stored in frontmatter. This avoids duplication and ensures the filesystem is the source of truth for dates.

### File Organization

Content lives in a **separate repository**, structured to mirror URLs exactly:

```
my-content/                       # Separate repo, opened in Obsidian
├── 2025/
│   ├── 01/
│   │   ├── 15/
│   │   │   ├── my-first-post.md
│   │   │   └── interesting-link.md
│   │   └── 16/
│   │       └── quick-note.md
│   └── 02/
│       └── 03/
│           └── february-thoughts.md
└── 2024/
    └── 12/
        └── 31/
            └── year-in-review.md
```

**Path structure**: `YYYY/MM/DD/filename.md`

- The **date** comes from the directory path
- The **slug** defaults to the filename (without `.md`), but can be overridden via `:slug` in frontmatter
- Multiple entries on the same day are supported (multiple files in the same `DD/` folder)

---

## URL Structure

### Date-Based Navigation

| URL Pattern | Shows |
|-------------|-------|
| `/` | Homepage with recent entries (all types) |
| `/2025` | All entries from 2025 |
| `/2025/jan` | All entries from January 2025 |
| `/2025/jan/15` | All entries from January 15, 2025 |
| `/2025/jan/15/my-post` | Single entry |

**Note**: Month names are lowercase three-letter abbreviations (`jan`, `feb`, etc.) for readability.

### Type & Tag Filtering

| URL Pattern | Shows |
|-------------|-------|
| `/posts` | All posts |
| `/notes` | All notes |
| `/links` | All links |
| `/tags` | Tag cloud / list |
| `/tags/clojure` | All entries tagged "clojure" |

### Combined Filters

| URL Pattern | Shows |
|-------------|-------|
| `/2025/posts` | All posts from 2025 |
| `/tags/clojure/2025` | Clojure-tagged entries from 2025 |

### Other Routes

| URL Pattern | Purpose |
|-------------|---------|
| `/search` | Search page (query param: `?q=term`) |
| `/feed.xml` | RSS/Atom feed |
| `/about` | Static about page |

---

## Technical Architecture

### Stack

| Layer | Choice | Rationale |
|-------|--------|-----------|
| **Web Server** | [Ring](https://github.com/ring-clojure/ring) | Clojure's standard HTTP abstraction |
| **Routing** | [Reitit](https://github.com/metosin/reitit) | Fast, data-driven routing with great composability |
| **HTML** | [Hiccup](https://github.com/weavejester/hiccup) | Clojure data structures as HTML |
| **Markdown** | [nextjournal/markdown](https://github.com/nextjournal/markdown) | Well-maintained, produces Hiccup-compatible AST |
| **Frontmatter** | `clojure.edn/read-string` | Native EDN — no extra dependency |
| **Dev Server** | [ring-refresh](https://github.com/weavejester/ring-refresh) or similar | Auto-reload during development |

### Project Structure

**Code repository** (this repo):

```
website-v2/
├── deps.edn                    # Dependencies
├── src/
│   └── site/
│       ├── core.clj            # Entry point, server setup
│       ├── config.clj          # Configuration (content path, etc.)
│       ├── routes.clj          # Route definitions
│       ├── handlers.clj        # Request handlers
│       ├── content.clj         # Content loading & parsing
│       ├── views/
│       │   ├── layout.clj      # Base HTML layout
│       │   ├── home.clj        # Homepage view
│       │   ├── entry.clj       # Single entry view
│       │   ├── archive.clj     # List/archive views
│       │   └── components.clj  # Reusable UI components
│       └── util.clj            # Date formatting, helpers
├── resources/
│   └── public/
│       ├── css/
│       │   └── style.css       # Styles
│       └── images/             # Static images
└── test/
    └── site/
        └── content_test.clj    # Tests
```

**Content repository** (separate repo, Obsidian vault):

```
my-content/
├── .obsidian/                  # Obsidian config (gitignored or not)
├── 2025/
│   └── 01/
│       └── 15/
│           └── my-post.md
└── pages/                      # Static pages (about, etc.)
    └── about.md
```

The code repo references the content repo path via configuration (env var or config file).

### Core Data Flow

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Markdown   │────▶│   Parser    │────▶│   Content   │
│   Files     │     │ (EDN front- │     │    Maps     │
│ (separate   │     │  matter +   │     │             │
│   repo)     │     │  markdown)  │     │             │
└─────────────┘     └─────────────┘     └─────────────┘
                                               │
                                               ▼
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   HTTP      │◀────│   Hiccup    │◀────│   Handlers  │
│  Response   │     │   Views     │     │  (routing)  │
└─────────────┘     └─────────────┘     └─────────────┘
```

---

## Implementation Plan

### Phase 1: Foundation

**Goal**: Serve a single hardcoded entry

1. Set up `deps.edn` with Ring, Reitit, Hiccup
2. Create minimal server in `core.clj`
3. Define basic route structure
4. Create base HTML layout
5. Serve a "Hello World" page

### Phase 2: Content System

**Goal**: Load and display markdown content from the external content repo

1. Implement EDN frontmatter parser (extract `;;;` delimited block)
2. Integrate nextjournal/markdown for body parsing
3. Create content loading functions
   - `load-entry` - single file → entry map (date from path, slug from filename or frontmatter)
   - `load-all-entries` - walk content directory → sorted entries
4. Build entry index (in-memory, rebuilt on startup)
5. Create single entry view

### Phase 3: Archives & Navigation

**Goal**: Date-based browsing works

1. Implement date-based filtering
2. Create archive views (year, month, day)
3. Add pagination for long lists
4. Build navigation components (breadcrumbs, prev/next)

### Phase 4: Types & Tags

**Goal**: Filter by content type and tags

1. Implement type filtering routes
2. Create tag index
3. Build tag cloud component
4. Combined filtering (type + date, tag + date)

### Phase 5: Search

**Goal**: Full-text search

1. Build simple in-memory search index
2. Create search handler and view
3. Highlight matches in results

### Phase 6: Polish & Deploy

**Goal**: Production-ready

1. Add RSS/Atom feed
2. Implement caching headers
3. Create 404 and error pages
4. Add static page support (about, etc.)
5. Set up deployment (Fly.io, Railway, or similar)

---

## Key Design Decisions

### 1. In-Memory Content Index

Content is loaded from the external content repo into memory at startup and optionally refreshed. For a personal blog with hundreds of entries, this is simple and fast. No database needed.

```clojure
;; Content index structure (conceptual)
{:entries [{:slug "my-post"              ; From filename or :slug in frontmatter
            :type :post
            :title "My Post"
            :date {:year 2025 :month 1 :day 15}  ; Derived from file path
            :tags #{:clojure :web}
            :body "<hiccup AST>"         ; From nextjournal/markdown
            :path "/2025/jan/15/my-post"}
           ...]
 :by-path {"/2025/jan/15/my-post" <entry>}
 :by-tag {:clojure [<entry> ...]
          :web [<entry> ...]}
 :by-year {2025 [<entry> ...]}
 :by-month {[2025 1] [<entry> ...]}
 :by-day {[2025 1 15] [<entry> ...]}
 :by-type {:post [<entry> ...]
           :note [<entry> ...]
           :link [<entry> ...]}}
```

### 2. Pure Functions for Views

Views are pure functions: `(data) → hiccup`. No side effects, easy to test.

```clojure
(defn entry-view [entry]
  [:article.entry
   [:h1 (:title entry)]
   [:time (:date entry)]
   [:div.content (:body-html entry)]])
```

### 3. Development Workflow

- **File watcher**: Rebuild content index when markdown files change
- **Ring middleware**: Auto-reload code changes
- **REPL-driven**: Evaluate views instantly

### 4. Static Generation Option (Future)

The architecture supports both:
- **Dynamic**: Ring server renders on request
- **Static**: Generate HTML files to disk for CDN hosting

Same view functions work for both modes.

---

## Extension Points

The design leaves room for future additions:

| Feature | How to Add |
|---------|------------|
| **Comments** | Add Disqus/Utterances embed, or build own with SQLite |
| **Webmentions** | New handler + storage for incoming mentions |
| **Series** | Add `series` to frontmatter, group in templates |
| **Drafts Preview** | Query param `?preview=true` shows drafts |
| **API** | JSON handlers alongside HTML handlers |
| **Full-text DB** | Swap in SQLite FTS if in-memory search is too slow |
| **Asset pipeline** | Add SCSS compilation, image optimization |

---

## Dependencies (deps.edn)

```clojure
{:deps
 {org.clojure/clojure {:mvn/version "1.12.0"}

  ;; Web
  ring/ring-core {:mvn/version "1.12.1"}
  ring/ring-jetty-adapter {:mvn/version "1.12.1"}
  metosin/reitit {:mvn/version "0.7.2"}

  ;; HTML
  hiccup/hiccup {:mvn/version "2.0.0-RC3"}

  ;; Markdown (produces hiccup-compatible AST)
  io.github.nextjournal/markdown {:mvn/version "0.6.157"}

  ;; Utilities
  tick/tick {:mvn/version "0.7.5"}}  ; Date/time handling

 :paths ["src" "resources"]

 :aliases
 {:dev {:extra-paths ["dev"]
        :extra-deps {ring/ring-devel {:mvn/version "1.12.1"}}}
  :run {:main-opts ["-m" "site.core"]}}}
```

**Note**: EDN frontmatter parsing uses `clojure.edn/read-string` — no extra dependency needed.

---

## Next Steps

1. **Confirm this plan** - Any adjustments to content types, URL structure, or tech choices?
2. **Scaffold the project** - Create directory structure and deps.edn
3. **Build Phase 1** - Get a minimal server running
4. **Iterate** - Build each phase, testing as we go

---

## References

- [Simon Willison's Blog Source](https://github.com/simonw/simonwillisonblog) - Inspiration for content model
- [nextjournal/markdown](https://github.com/nextjournal/markdown) - Markdown parser
- [Reitit Documentation](https://cljdoc.org/d/metosin/reitit/)
- [Ring Concepts](https://github.com/ring-clojure/ring/wiki/Concepts)
- [Hiccup Syntax](https://github.com/weavejester/hiccup)
