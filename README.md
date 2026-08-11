# website

My personal weblog of posts, links, quotes, and more — markdown files in a git repo, rendered by a small Clojure program using [babashka](https://babashka.org).
Writing and publishing happen in a companion admin app (`website-admin`, its own repo); the tooling here turns the published files into a website — parsing frontmatter, `[[wikilinks]]`, pasted images, and organizing the content. The format is Obsidian's, where the site began, and dev mode serves a local checkout of the content repo directly.

This project was written almost entirely by Claude (Opus 4.8 and Fable 5). See [PLAN.md](PLAN.md) for the original AI-generated architecture and its rationale. More details about how it works are below, and instructions in case you ever want to clone this and up a similar website.

## Quick start

```sh
# install babashka ≥ 1.12.196 (https://github.com/babashka/babashka#installation), then:
bb dev        # serve the content directory configured in config/dev.edn at http://localhost:8100
bb test       # run the test suite
```

(To see the website with some realistic-looking fake data point `:content-path` in `config/dev.edn` at `example-content`.)

## How content reaches the site

Content lives in its own public git repo (the *content repo*), separate from
this code repo. The admin app writes it: drafts live in a private drafts
repo, and publishing commits the finished entry straight into the content
repo's date tree through the GitHub API — from any device, no Mac involved.

```
admin app (PWA)                    Content repo (public, on GitHub)
drafts → publish button ─────────► ├── 2026/07/…      the path IS the date
                                   ├── pages/
                                   └── attachments/
                                        │
                                        │  push webhook: POST /refresh
                                        ▼
                                   Server: git pull + reindex, in seconds
```

The server clones the content repo at boot and refreshes two ways:

- **The push webhook.** The content repo POSTs `/refresh` on every push;
  the server pulls and reindexes within seconds of a publish. The payload's
  sha lets it skip even the pull when it already serves that revision, and
  a debounce coalesces a burst of pushes (one publish is several commits)
  into at most one sync per window — trailing pushes are caught by one
  scheduled sync, never dropped.
- **A fallback pull** every `:content-sync-seconds` (30 minutes in prod)
  heals a webhook delivery GitHub failed to make — it doesn't retry them.

Publishing never deploys anything: content changes flow through git, code
changes through `fly deploy`, and neither triggers the other.

One-time setup:

1. Create the content repo (public — the server pulls it anonymously) with
   the layout under "Content layout" below, and point `:content-git-url`
   in `config/prod.edn` at it.
2. In the content repo's settings, add a webhook: payload URL
   `https://<your-site>/refresh`, content type `application/json`, just
   the push event. No secret needed — the endpoint is unauthenticated by
   design, because all it can do is trigger a debounced pull of a public
   repo (and the site keeps its no-secrets property).
3. For local writing, point `:content-path` in `config/dev.edn` at a
   local checkout of the content repo (or any folder with the same
   layout) and `bb dev`.

## Writing

- **A file is an entry.** The filename is the title. A bare file with no
  frontmatter publishes as a post — frontmatter is entirely optional.
  Drafts belong to the admin app; locally, `bb new` scaffolds one under
  the local content dir's `drafts/` for previewing with `bb dev`.
- **Properties, not metadata.** Frontmatter is YAML — the shape
  Obsidian's Properties panel writes. Set `tags`; other entry
  types set `type: note` / `type: link` / `type: quote` plus their
  natural fields (`link`, `via`, `author`, `source`).
- **`date` and `publish` are workflow properties, not entry data** — the
  site itself ignores both (a published entry's date is its folder path).
  `date` is what publishing files the entry under, falling back to today;
  `publish` is a legacy queue toggle older drafts still carry.
- **Link with `[[wikilinks]]`.** They resolve by filename to the entry's
  URL at render time. An unresolved link (e.g. to a still-unpublished
  draft) degrades to plain text — never a dead link.
- **Paste images.** Pasted images live under `attachments/`; the admin
  app stages and promotes them. The server serves them at
  `/attachments/...` — an image never requires a site deploy.
- **Slugs are automatic**: `slugify(filename)`. A `slug:` property exists
  only to pin a URL (e.g. one inherited from an old blog).
- **Content that lived (or lives) elsewhere.** Two properties keep the
  web tidy. `canonical:` marks a cross-post whose canonical home is
  elsewhere: the page emits a `rel=canonical` pointing there and credits
  the original visibly ("originally published at …"). `previously:`
  lists URLs the content used to live at: paths on this site's own
  domain 301 to the entry automatically (consulted only where the site
  would otherwise 404, so an old URL can never shadow live content),
  and `bb redirects` exports the foreign-domain ones as a Cloudflare
  bulk-redirects CSV to serve from wherever the old domain now points.

```sh
bb new post My great idea      # scaffolds drafts/My great idea.md in the dev content dir
# ...write — with `bb dev` running, preview at localhost:8100/drafts/My great idea
bb suggest-tags my-great-idea  # LLM-suggested tags, written into the draft
bb suggest-slug my-great-idea  # LLM-suggested URL slugs, pinned to the draft
bb reindex                     # validate that everything parses
bb drafts                      # list every draft in the dev content dir
```

Don't feel like typing a name? Run these with no argument and they hand
you a menu instead (↑/↓ or j/k, Enter to pick, q/Esc to cancel):
`bb new` picks an entry type (and then asks for an optional title), and
`bb suggest-tags` / `bb suggest-slug` list every draft or entry that
still wants tags or a slug. It's a tiny pure-babashka picker
(`src/site/tui.clj`), no `gum`/`fzf` to install.

**Publishing** is the admin app's job: it refuses a blank or unconfirmed
slug, refuses URL collisions, files the entry into the content repo's
`YYYY/MM/DD/` folder, and promotes its images — then the push webhook has
it live in seconds. Editing or deleting something already published is a
commit to the content repo, through the admin app or by hand; any push
refreshes the site the same way.

## Content layout

Whatever `:content-path` points at:

```
content-repo/
├── drafts/                      # local drafts (never published from here)
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
- a `note` is one thought, short enough that the feed *is* the page: it
  publishes whole there, links and emphasis live, with no "…n min read"
  onward link (a post previews its first paragraph and offers the rest)
- quotes use `author:` and `source:` (the URL), and stay untitled unless
  a `title:` property says otherwise
- `canonical:` marks a cross-post whose canonical home is elsewhere;
  `previously:` lists URLs the content used to live at (see "Writing")
- the original EDN frontmatter (`;;;`-delimited) is still accepted

## Configuration

Three committed files under `config/`, no environment variables, no secrets:

- **`config/config.edn`** — the base that always applies: site title,
  base URL, entry types, `:llm-command` (what `bb suggest-tags` shells
  out to).
- **`config/dev.edn`** — merged in by `bb dev` and the authoring tasks:
  your content directory path, `:content-git-url nil` (no git syncing locally),
  personal `:llm-command` override, `:port 8100`.
- **`config/prod.edn`** — merged in by `bb prod`: the clone target, the
  content repo URL, the fallback sync interval, `:port 8080` (what Fly
  routes to).

The port is environment-specific, so dev and prod never collide on one.

Dev-only behavior (draft previews at `/drafts/<name>`, per-request
reindexing) follows the environment, so dev and prod can't drift apart.
The production server exposes no admin surface: its one non-page endpoint
is `POST /refresh`, which can do nothing but trigger a debounced pull of
the public content repo — and it only exists when content comes from git,
so dev doesn't even have that.

## URLs

| URL | Shows |
|-----|-------|
| `/2026` · `/2026/jul` · `/2026/jul/4` | date archives |
| `/2026/jul/4/hello-world` | single entry |
| `/posts` · `/notes` · `/links` · `/quotes` · `/releases` · `/tools` | paginated by type (`/posts/page/2`; `/2026/posts` filters by year) |
| `/tags` · `/tags/clojure` · `/tags/clojure/2026` | by tag |
| `/search?q=...` | full-text search |
| `/feed.xml` | RSS |
| `/attachments/...` | images from the content repo |

## Deploying to Fly.io

`Dockerfile` and `fly.toml` are included. `prod.edn` holds the content
repo settings:

```clojure
{:content-path "content"                                    ; clone target
 :content-git-url "https://github.com/<you>/<content-repo>"}
```

The machine clones the content repo at boot; the repo's push webhook
POSTs `/refresh` and the pull + reindex happens within seconds, with the
timed pull every `:content-sync-seconds` as the fallback — so publishing
is just a git push; content changes never require a deploy.

```sh
# once:
fly launch --copy-config --no-deploy     # then edit `app` in fly.toml if taken

# purge only the homepage after a successful content refresh
fly secrets set CLOUDFLARE_ZONE_ID=<zone-id> CLOUDFLARE_API_TOKEN=<token>

# every code change:
fly deploy
```

The Cloudflare token only needs `Zone.Cache Purge` permission for this zone.
The homepage tells browsers to revalidate on every visit while
`Cloudflare-CDN-Cache-Control` keeps it at the edge for a day. Each successful
content reindex purges that one URL; other public pages retain their normal
cache policy. A missing credential or failed purge never turns a successful
content sync into a failure.

- Every public page gets CDN-friendly cache headers; put Cloudflare (or any
  CDN) in front and traffic spikes never reach the server.
- Publishes go live within seconds of the git push (the webhook), or at
  worst within `:content-sync-seconds` (the fallback) — no deploy, no
  admin surface beyond the pull-only `/refresh`.
- A failed sync can never take the site down: bad network or a broken
  content push is logged, the last good index keeps serving, and the
  server retries every tick until the content is fixed. Even a broken
  clone at boot serves an empty site rather than crash-looping.

Running anywhere else is the same idea without the Fly wrapper: the same
config files, and just `bb prod`.
