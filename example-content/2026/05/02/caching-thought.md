;;;
{:type :post
 :tags [:web :performance]
 ;; a cross-post: the canonical home of this content is elsewhere, so the
 ;; page points its rel=canonical there and credits it visibly
 :canonical-url "https://oldblog.example.org/2026/caching"}
;;;

Untitled entries are fine — the date is the identity. A thought worth writing
down: if content only changes when you publish, then *every* page except
search is cacheable, and a CDN makes a tiny dynamic site behave like a
static one under load.
