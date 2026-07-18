# Spec work handoff — next phase

Written 2026-07-17, at the end of two elicitation sessions that produced the
three Allium spec modules in this directory. Read this to resume in a clean
context without re-deriving or re-litigating anything. Delete it when the
next phase is done.

## State

Three modules, all committed and validated:

- **`entries.allium`** — the shared Entry model (fields, `draft → queued →
  published` lifecycle, derived slug/display-title). Edits to what an entry
  *is* go here; both other modules import it.
- **`publishing.allium`** — the pipeline: draft creation, queueing (the
  `publish` property), lint pass, manual publish + queue flush, the
  autopublish launchd agent, the server's pull loop.
- **`content.allium`** — serving: canonical URLs, archives (404 when
  empty), black-box search contract, RSS feed shape, static pages, caching,
  uptime/freshness guarantees.

**Validate with all files in one invocation** (single-file checks can't
resolve the `use` imports):

```sh
allium check specs/*.allium     # one JSON doc per file, concatenated
allium analyse specs/*.allium
```

Known-benign output — do NOT "fix":
- `entries.allium` status warnings + deadlock findings when viewed
  standalone: its lifecycle rules live in publishing.allium, whose own
  report (0 diagnostics, 0 findings) is the meaningful one.
- `field.unused` infos for fields referenced only in derived values or
  `where` predicates (checker doesn't count those references).
- `missingSourceHint` warnings on Author/Visitor/HostEnvironment —
  deliberate external entities with no governing spec.

## The four spec→code gaps (the likely next phase)

Each is a deliberate decision by Kira (2026-07-17) where the spec is
stricter than the implementation. Also listed in the spec file headers.

1. **Noticeable publish failures** — `PublishFailureNotice` +
   `@guarantee FailuresAreNoticeable` (publishing.allium). Today a failed
   queued draft only prints a warning; under the agent that lands in
   `~/Library/Logs/website-autopublish.log` and nobody sees it.
   Code: `src/site/author.clj` `publish-queue!` / `queued-drafts`.
   Blocked on open question #1 (what form the notice takes).
2. **`invariant NoUrlCollisions`** — no two published entries share
   slug + date; `PublishBlockedByCollision` must block the publish. Today
   only exact-filename collisions are caught (`publish-draft!`'s
   target-exists check). The server side already backstops this:
   `build-index` throws on duplicate paths (`src/site/content.clj`).
   Code: `src/site/author.clj` `publish-draft!` — add a slug+date check
   against the index.
3. **Manual publish refuses on a broken tree** —
   `@guarantee NoPublishFromBrokenTree` now covers `bb publish <name>` too.
   Today it warns "skipping lints" and proceeds when `build-index` throws.
   Code: `src/site/author.clj` `publish` (the single-draft branch) — make
   it die like `publish-queue!` does.
4. **Nav hides empty types** — `@guarantee NavHidesEmptyTypes`
   (content.allium). Today the header renders a link for every configured
   type unconditionally. Code: `src/site/views/layout.clj` `header` — needs
   the index (it currently receives only config), show only types with ≥1
   published entry.

## Open questions (in publishing.allium)

1. What form must a noticeable unattended failure take (macOS
   notification? failure marker in `bb drafts`? something else)? — gates
   gap 1.
2. Is retry-forever acceptable for a failing queued draft once failures
   are noticeable, or should it eventually be de-queued?
3. When is a `PublishFailureNotice` cleared (entry publishes / de-queued /
   author acknowledges)?

Ask Kira before implementing gap 1; gaps 2–4 are unambiguous and can be
implemented directly from the specs.

## Decision log (do not re-litigate)

- Scope: publishing pipeline first, then content & serving. Excluded
  forever-ish: dev conveniences, `bb suggest-tags`. Frontmatter dialects
  and search scoring deliberately black-boxed.
- Three-module structure over one spec (Kira's explicit choice).
- No `:note` entry type exists or is planned; README was fixed instead.
- Failed queued drafts: fail noticeably, never block the rest of the queue.
- URL collisions: full invariant (block), not just a lint warning.
- Broken tree: manual publish must refuse, same as the flush.
- Nav: hide empty types (not an empty-state page, not keep-the-404).

## Suggested next moves

- Implement gaps 2–4 (small, spec-guided changes; `bb test` + `bb lint`
  for the code, `allium check specs/*.allium` stays green).
- Resolve open question 1 with Kira, then implement gap 1.
- `/weed` afterwards to confirm code and specs align (and catch drift the
  gap list doesn't cover).
- `/propagate` to generate tests from spec obligations, if wanted.
- Possible future elicitation: pages/attachments lifecycle (deliberately
  thin today).
