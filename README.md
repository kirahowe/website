# website-v2

A personal weblog — posts, notes, links, and quotes — rendered straight
from an **Obsidian vault** by a small Clojure program running entirely
under [babashka](https://babashka.org). No JVM, no database, no build
step, and zero external dependencies (everything used, including
nextjournal/markdown and clj-yaml, is built into bb ≥ 1.12.196).

The guiding rule: **you write in Obsidian's native dialect; the machine
adapts.** YAML properties, `[[wikilinks]]`, pasted images — the site's
indexer and renderer translate all of it at read time. Nothing in the
vault exists for the machine's benefit.

See [PLAN.md](PLAN.md) for the original architecture and its rationale.

## Quick start

```sh
# install babashka ≥ 1.12.196 (https://github.com/babashka/babashka#installation), then:
bb dev        # serve the vault configured in config/dev.edn at http://localhost:8100
bb test       # run the test suite
```

(No vault yet? Point `:content-path` in `config/dev.edn` at `example-content`.)

## How content reaches the site: iCloud vault + a separate git repo

Two directories, and **git never lives inside iCloud** — that's what keeps
iCloud from corrupting it:

```
iCloud vault (source of truth)         Publish repo (transport, disposable)
~/…/Obsidian/Documents/Blog/           ~/code/projects/kirahowe-content/
├── drafts/       ← you write here      ├── .git/     ← OUTSIDE iCloud
├── 2026/07/…     ← published           ├── 2026/07/…
├── attachments/  ← pasted images       ├── attachments/
├── pages/                              └── pages/
├── templates/, dev.md  (never published)
└── .obsidian/          (never published)
        │                                      │
        │  iCloud ⇄ phone                      │  git push ⇄ GitHub ⇄ server
        └──────────► bb publish / bb sync ─────┘
```

- The **vault** is a plain Obsidian vault in iCloud (so your phone sees
  it). It contains no `.git` — nothing git-related ever syncs to your
  phone or gets mangled by iCloud.
- The **publish repo** is a normal git checkout *outside* iCloud. It's a
  mirror of the vault's publishable content and is disposable: if it ever
  wedges, delete it and re-clone. `bb publish` / `bb sync` mirror the
  vault into it and push; the server pulls from GitHub as usual.
- Your Mac is the only bridge between the two sync systems, and only when
  you run `bb publish` / `bb sync`.

One-time setup:

1. In Obsidian, create a dedicated vault in iCloud (everything that gets
   published ends up on the server). Its `.obsidian/` and top-level
   `dev.md`/`templates/` stay private — only the date tree, `pages/`, and
   `attachments/` are mirrored out.
2. Make a git checkout **outside** iCloud and push it once (public — the
   server pulls it anonymously):
   ```sh
   mkdir -p ~/code/projects/kirahowe-content && cd $_
   git init -b main
   printf '.DS_Store\n' > .gitignore && git add -A && git commit -m "init"
   gh repo create kirahowe-content --public --source . --push
   ```
3. In `config/dev.edn`, point `:content-path` at the vault and `:publish-repo` at
   that checkout. In `config/prod.edn`, point `:content-git-url` at the repo.
   Then `bb dev`, and `bb publish` / `bb sync` to push content live.

> iCloud tip: right-click the vault folder → "Keep Downloaded" so iCloud
> keeps a real local copy instead of evicting files to placeholders.

## Writing

- **New note in `drafts/`.** The filename is the title. A bare note with
  no frontmatter publishes as a post — frontmatter is entirely optional.
- **Properties, not metadata.** Frontmatter is YAML — Obsidian's
  Properties panel. `tags` autocomplete against the vault; other entry
  types set `type: link` / `type: quote` plus their natural fields
  (`link`, `via`, `author`, `source`).
- **Link with `[[wikilinks]]`.** They resolve by filename to the entry's
  URL at render time. An unresolved link (e.g. to a still-unpublished
  draft) degrades to plain text — never a dead link — and `bb publish`
  warns about it.
- **Paste images.** Obsidian files them under `attachments/` (the vault
  is preconfigured); the server serves them at `/attachments/...`. An
  image never requires a site deploy.
- **Slugs are automatic**: `slugify(filename)`. A `slug:` property exists
  only to pin a URL (e.g. one inherited from an old blog).

```sh
bb new post My great idea      # scaffolds drafts/My great idea.md
# ...write — with `bb dev` running, preview at localhost:8100/drafts/My great idea
bb suggest-tags my-great-idea  # LLM-suggested tags, printed ready to paste
bb reindex                     # optional: validate that everything parses
bb publish my-great-idea       # lints, moves into today's date folder, mirrors + pushes
```

A file is a draft because it lives in `drafts/`; publishing is moving it
into the date tree. No flags to forget. `bb publish` warns about
unresolved wikilinks, missing attachments, missing or never-seen tags,
and a link entry without a URL — but a warning never blocks a publish.

**Editing or deleting something already published?** Change it in Obsidian,
then `bb sync` — it re-mirrors the vault into the publish repo and pushes,
deletions included. `bb publish` does this automatically for the draft it
publishes; `bb sync` is for everything else.

`bb publish` **is** the manual publish. The live site picks the push up
on its next timed pull (≤ `:content-sync-seconds`); to go live right
now, `fly apps restart <app>` — `bb publish` prints this command for you.

## Content layout

Whatever `:content-path` points at:

```
my-vault/
├── drafts/                      # all writing starts here (flat — phone-friendly)
│   └── An idea brewing.md
├── pages/                       # static pages → /about, etc.
│   └── about.md
├── attachments/                 # pasted images → served at /attachments/...
│   └── screenshot.png
└── 2026/07/04/                  # published entries; the path IS the date
    └── Hello world.md           # → /2026/jul/4/hello-world
```

Frontmatter, when an entry needs any (a plain post doesn't):

```markdown
---
type: link
link: https://example.com/article
via: https://news.ycombinator.com/item?id=1
tags:
  - clojure
slug: custom-slug
---

Body in markdown, with [[Hello world|wikilinks]] and ![[screenshot.png]].
```

- `type` defaults to `post`; a typo'd type fails indexing loudly
- quotes use `author:` and `source:` (the URL), and stay untitled unless
  a `title:` property says otherwise
- the original EDN frontmatter (`;;;`-delimited) is still accepted

## Configuration

Three committed files under `config/`, no environment variables, no secrets:

- **`config/config.edn`** — the base that always applies: site title,
  base URL, entry types, `:llm-command` (what `bb suggest-tags` shells
  out to).
- **`config/dev.edn`** — merged in by `bb dev` and the authoring tasks:
  your vault path, `:content-git-url nil` (no git syncing locally),
  personal `:llm-command` override, `:port 8100`.
- **`config/prod.edn`** — merged in by `bb run`: the clone target, the
  content repo URL, the sync interval, `:port 8080` (what Fly routes to).

The port is environment-specific, so dev and prod never collide on one.

Dev-only behavior (draft previews at `/drafts/<name>`, per-request
reindexing) follows the environment, so dev and prod can't drift apart.
The production server exposes no admin surface at all: it just pulls the
content repo on a timer.

## URLs

| URL | Shows |
|-----|-------|
| `/2026` · `/2026/jul` · `/2026/jul/4` | date archives |
| `/2026/jul/4/hello-world` | single entry |
| `/posts` · `/notes` · `/links` · `/quotes` · `/releases` · `/tools` | by type (`/2026/posts` filters by year) |
| `/tags` · `/tags/clojure` · `/tags/clojure/2026` | by tag |
| `/search?q=...` | full-text search |
| `/feed.xml` | RSS |
| `/attachments/...` | images from the vault |

## Deploying to Fly.io

`Dockerfile` and `fly.toml` are included. `prod.edn` holds the content
repo settings:

```clojure
{:content-path "content"                                    ; clone target
 :content-git-url "https://github.com/<you>/<content-repo>"}
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
- A failed sync can never take the site down: bad network or a broken
  content push is logged, the last good index keeps serving, and the
  server retries every tick until the content is fixed. Even a broken
  clone at boot serves an empty site rather than crash-looping.

Running anywhere else is the same idea without the Fly wrapper: the same
config files, and just `bb run`.
