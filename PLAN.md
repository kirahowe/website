# Personal Website Architecture Plan

A Clojure-based personal website inspired by [Simon Willison's blog](https://simonwillison.net), designed for simplicity, elegance, and extensibility.

## Overview

**Core Philosophy**: Start simple, grow organically. The system reads markdown files from disk, parses frontmatter for metadata, and serves them through a clean date-based URL structure.

**Content Authoring**: All content lives as markdown files in a `content/` directory, making it easy to author with Obsidian or any text editor.

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

All entries share a common frontmatter structure:

```yaml
---
type: post | note | link          # Required
title: "My Post Title"            # Optional for notes
date: 2025-01-15                  # Required, ISO format
tags: [clojure, web, personal]    # Optional
draft: false                      # Optional, default false
link_url: https://example.com     # Required for type: link
link_via: https://source.com      # Optional, for links
---

Content body in markdown...
```

### File Organization

```
content/
├── 2025/
│   ├── 01/
│   │   ├── my-first-post.md
│   │   ├── interesting-link.md
│   │   └── quick-note.md
│   └── 02/
│       └── february-thoughts.md
└── 2024/
    └── 12/
        └── year-in-review.md
```

The file path encodes the date (`YYYY/MM/`), and the filename becomes the slug. This mirrors the URL structure and makes content easy to locate.

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
| **Markdown** | [markdown-clj](https://github.com/yogthos/markdown-clj) | Pure Clojure markdown parser |
| **YAML** | [clj-yaml](https://github.com/clj-commons/clj-yaml) | For frontmatter parsing |
| **Dev Server** | [ring-refresh](https://github.com/weavejester/ring-refresh) or similar | Auto-reload during development |

### Project Structure

```
website-v2/
├── deps.edn                    # Dependencies
├── src/
│   └── site/
│       ├── core.clj            # Entry point, server setup
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
├── content/                    # Markdown content (see above)
└── test/
    └── site/
        └── content_test.clj    # Tests
```

### Core Data Flow

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Markdown   │────▶│   Parser    │────▶│   Content   │
│   Files     │     │ (frontmatter│     │    Maps     │
│             │     │  + body)    │     │             │
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

**Goal**: Load and display markdown content

1. Implement frontmatter parser (YAML extraction)
2. Create content loading functions
   - `load-entry` - single file → entry map
   - `load-all-entries` - directory → sorted entries
3. Build entry index (in-memory, rebuilt on startup)
4. Create single entry view

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

Content is loaded into memory at startup and optionally refreshed. For a personal blog with hundreds of entries, this is simple and fast. No database needed.

```clojure
;; Content index structure (conceptual)
{:entries [{:slug "my-post"
            :type :post
            :title "My Post"
            :date #inst "2025-01-15"
            :tags #{:clojure :web}
            :body-html "<p>...</p>"
            :path "/2025/jan/15/my-post"}
           ...]
 :by-slug {"my-post" <entry>}
 :by-tag {:clojure [<entry> ...]
          :web [<entry> ...]}
 :by-year {2025 [<entry> ...]}
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

  ;; Content
  markdown-clj/markdown-clj {:mvn/version "1.12.1"}
  clj-commons/clj-yaml {:mvn/version "1.0.27"}

  ;; Utilities
  tick/tick {:mvn/version "0.7.5"}}  ; Date/time handling

 :paths ["src" "resources" "content"]

 :aliases
 {:dev {:extra-paths ["dev"]
        :extra-deps {ring/ring-devel {:mvn/version "1.12.1"}}}
  :run {:main-opts ["-m" "site.core"]}}}
```

---

## Next Steps

1. **Confirm this plan** - Any adjustments to content types, URL structure, or tech choices?
2. **Scaffold the project** - Create directory structure and deps.edn
3. **Build Phase 1** - Get a minimal server running
4. **Iterate** - Build each phase, testing as we go

---

## References

- [Simon Willison's Blog Source](https://github.com/simonw/simonwillisonblog) - Inspiration for content model
- [Reitit Documentation](https://cljdoc.org/d/metosin/reitit/)
- [Ring Concepts](https://github.com/ring-clojure/ring/wiki/Concepts)
- [Hiccup Syntax](https://github.com/weavejester/hiccup)
