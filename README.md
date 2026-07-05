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
2. Make it a repo and push it somewhere private:
   ```sh
   cd ~/Library/Mobile\ Documents/iCloud~md~obsidian/Documents/<VaultName>
   printf '.obsidian/\n.DS_Store\n' > .gitignore
   git init && git add -A && git commit -m "content repo"
   gh repo create my-content --private --source . --push
   ```
3. Point the site at it — either set `:content-path` in `config.edn` or:
   ```sh
   CONTENT_PATH="$HOME/Library/Mobile Documents/iCloud~md~obsidian/Documents/<VaultName>" bb dev
   ```

Day to day: **phone** → open Obsidian, write in `drafts/` (iCloud syncs it).
**Laptop** → `bb publish <name>` moves it into today's date folder, commits,
and pushes; the server picks it up from git. Files outside `drafts/`,
`pages/`, and the date tree are ignored by the site, and `.obsidian/` never
gets committed.

> iCloud tip: right-click the vault folder → "Keep Downloaded" so iCloud
> can't evict the `.git` directory out from under you.

## Content layout

Configured via `:content-path` in `config.edn` or the `CONTENT_PATH` env var:

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

## Deploying to Fly.io

`Dockerfile` and `fly.toml` are included. The machine clones the content
repo at boot and pulls every `CONTENT_SYNC_SECONDS` (default 300),
reindexing when anything changed — so publishing is just a git push.

```sh
# once:
fly launch --copy-config --no-deploy     # then edit `app` in fly.toml if taken
fly secrets set ADMIN_TOKEN=$(openssl rand -hex 16)
fly secrets set CONTENT_GIT_URL="https://x-access-token:<PAT>@github.com/<you>/<content-repo>.git"

# every code change:
fly deploy
```

For a private content repo, mint a fine-grained GitHub PAT with read-only
Contents access to that one repo and embed it in `CONTENT_GIT_URL` as shown
(a public repo needs no token). Content changes never require a deploy.

- Every public page gets CDN-friendly cache headers; put Cloudflare (or any
  CDN) in front and traffic spikes never reach the server.
- Want publishes live in seconds instead of minutes? Add a GitHub Action to
  the content repo that runs
  `curl -X POST "https://<your-app>.fly.dev/admin/reindex?token=$ADMIN_TOKEN"`
  on push — the endpoint pulls before reindexing.

Running anywhere else is the same idea without the Fly wrapper:

```sh
CONTENT_PATH=/srv/content CONTENT_GIT_URL=... ADMIN_TOKEN=... PORT=8080 bb run
```
