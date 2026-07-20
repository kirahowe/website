;;;
{:type :link
 :title "nextjournal/markdown"
 :link-url "https://github.com/nextjournal/markdown"
 :tags [:clojure :libraries]}
;;;

A markdown parser that emits data — the AST converts straight to hiccup.
This is what renders every entry on this site, and it ships built into
babashka, so it costs nothing to depend on.

> Markdown is a nice syntax for humans, but a lousy data structure. Parse
> it to a tree and suddenly it's programmable.
