# website-v2

A personal weblog — posts, notes, links, and quotes — rendered from a folder
of markdown files by a small Clojure program running entirely under
[babashka](https://babashka.org). No JVM, no database, no build step, and
zero external dependencies (everything used, including nextjournal/markdown,
is built into bb ≥ 1.12.196).

See [PLAN.md](PLAN.md) for the full architecture and its rationale.

## Quick start

```sh
# install babashka ≥ 1.12.196 (https://github.com/babashka/babashka#installation), then:
bb dev        # serve example-content at http://localhost:8080, reindexing every request
bb test       # run the test suite
```

## How content works

Content lives in a **separate repo** (an Obsidian vault) configured via
`:content-path` in `config.edn` or the `CONTENT_PATH` env var. Layout:

```
my-content/
├── drafts/                 # all writing starts here (flat — phone-friendly)
│   └── an-idea.md
├── pages/                  # static pages → /about, etc.
│   └── about.md
└── 2026/07/04/             # published entries; the path IS the date
    └── hello-world.md      # → /2026/jul/4/hello-world
```

Every entry is markdown with EDN frontmatter:

```markdown
;;;
{:type :post
 :title "Hello, world"
 :tags [:clojure :meta]}
;;;

Body in markdown...
```

- `:type` must be one of the types in `config.edn` — a typo fails indexing loudly
- the slug is the filename unless `:slug` overrides it
- link entries add `:link-url` (and optionally `:link-via`); quotes add
  `:source` / `:source-url`

## Authoring workflow

```sh
bb new post My great idea      # scaffolds drafts/my-great-idea.md
# ...write (drafts are previewable at /drafts/my-great-idea?preview=$ADMIN_TOKEN)
bb publish my-great-idea       # moves it into today's date folder, commits, pushes
```

A file is a draft because it lives in `drafts/`; publishing is moving it
into the date tree. No flags to forget.

## URLs

| URL | Shows |
|-----|-------|
| `/2026` · `/2026/jul` · `/2026/jul/4` | date archives |
| `/2026/jul/4/hello-world` | single entry |
| `/posts` · `/notes` · `/links` · `/quotes` | by type (`/2026/posts` filters by year) |
| `/tags` · `/tags/clojure` · `/tags/clojure/2026` | by tag |
| `/search?q=...` | full-text search |
| `/feed.xml` | RSS |

## Production

```sh
CONTENT_PATH=/path/to/content ADMIN_TOKEN=... PORT=8080 bb run
```

- Every public page gets CDN-friendly cache headers; put Cloudflare (or any
  CDN) in front and traffic spikes never reach the server.
- Push-to-publish: after the content repo updates on the server
  (webhook or cron `git pull`), `POST /admin/reindex?token=$ADMIN_TOKEN`
  swaps in the new index.
