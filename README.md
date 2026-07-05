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

## Your real content: an Obsidian vault that is also a git repo

The content is a **dedicated Obsidian vault** whose root is also the root
of a git repo. One-time setup:

1. In Obsidian, create a new vault (keep it separate from your personal
   vault — everything committed here ends up on the server). Put it in
   iCloud so your phone sees it: Obsidian's iCloud vaults live at
   `~/Library/Mobile Documents/iCloud~md~obsidian/Documents/<VaultName>`
   on your Mac.
2. Make it a repo and push it (public — the server pulls it anonymously,
   and everything in it is destined for the website anyway):
   ```sh
   cd ~/Library/Mobile\ Documents/iCloud~md~obsidian/Documents/<VaultName>
   printf '.obsidian/\n.DS_Store\n' > .gitignore
   git init && git add -A && git commit -m "content repo"
   gh repo create my-content --public --source . --push
   ```
3. Point the site at it with a `config.local.edn` in the project root
   (gitignored, merged over `config.edn` — no env vars needed):
   ```clojure
   {:content-path "~/Library/Mobile Documents/iCloud~md~obsidian/Documents/<VaultName>"
    :content-git-url nil}   ; the server pulls from git; your laptop reads the vault directly
   ```
   Then just `bb dev`.

Day to day: **phone** → open Obsidian, write in `drafts/` (iCloud syncs it).
**Laptop** → `bb publish <name>` moves it into today's date folder, commits,
and pushes; the server picks it up from git. Files outside `drafts/`,
`pages/`, and the date tree are ignored by the site, and `.obsidian/` never
gets committed.

> iCloud tip: right-click the vault folder → "Keep Downloaded" so iCloud
> can't evict the `.git` directory out from under you.

## Configuration

Everything lives in **`config.edn`** (committed): site title, base URL,
entry types, port, content path, content repo URL, sync interval, home
feed size. **`config.local.edn`** (gitignored) merges over it for
machine-local settings — your vault path, `:content-git-url nil` to turn
off git syncing locally.

There are **no environment variables and no secrets**. Dev-only behavior
(draft previews at `/drafts/<name>`, per-request reindexing) is switched
by which task you run — `bb dev` versus `bb run` — not by configuration,
so dev and prod can't drift apart. The production server exposes no admin
surface at all: it just pulls the content repo on a timer.

## Content layout

Whatever `:content-path` points at:

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
# ...write — with `bb dev` running, preview at localhost:8080/drafts/my-great-idea
bb reindex                     # optional: validate that everything parses
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

## Deploying to Fly.io

`Dockerfile` and `fly.toml` are included. Set in `config.edn`:

```clojure
{:content-path "content"                                    ; clone target
 :content-git-url "https://github.com/<you>/<content-repo>.git"}
```

The machine clones the content repo at boot and pulls every
`:content-sync-seconds` (default 300), reindexing when anything changed —
so publishing is just a git push; content changes never require a deploy.

```sh
# once:
fly launch --copy-config --no-deploy     # then edit `app` in fly.toml if taken

# every code change:
fly deploy
```

- Every public page gets CDN-friendly cache headers; put Cloudflare (or any
  CDN) in front and traffic spikes never reach the server.
- Publishes go live within `:content-sync-seconds` (default 5 minutes) of
  the git push — no deploy, no webhook, no admin endpoint.

Running anywhere else is the same idea without the Fly wrapper: the same
`config.edn`, and just `bb run`.
