---
name: verify
description: Build/launch/drive recipe for verifying changes to this site end-to-end.
---

# Verifying changes to this site

Babashka app, zero external deps. Tests: `bb test` (that's CI, not verification).

## Launch

- `bb dev` (background) → http://localhost:8100, serving the real Obsidian
  vault (`config/dev.edn` :content-path), reindexed per request — realistic
  content volume (~100 tags, years of entries).
- Ready when `curl -s localhost:8100/` returns 200 (a couple of seconds).
- Stop it with `lsof -ti tcp:8100 | xargs kill`.

## Drive

- Server-rendered pages and feeds: plain `curl` against :8100.
- Client-side JS: Playwright from the scratchpad. The `playwright` npm
  package isn't installed globally, but browser builds are cached in
  `~/Library/Caches/ms-playwright/`. `npm install playwright` in a scratch
  dir, and if its pinned browser build doesn't match the cache, pass the
  cached binary via `chromium.launch({executablePath})` instead of
  downloading (`.../chromium_headless_shell-*/chrome-headless-shell-mac-arm64/chrome-headless-shell`).

## Gotchas

- Dev pages hold a livereload SSE connection open forever — Playwright's
  `waitUntil: 'networkidle'` never fires; use `'load'` and wait for a
  selector.
- Hiccup writes attributes alphabetically (`href` before `rel` before
  `title`) — grep/assert in that order.
- The site defaults dark via `color-scheme`; headless Chromium renders the
  light theme unless you emulate `prefers-color-scheme: dark`.
