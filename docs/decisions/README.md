# Architecture Decision Records

Short, durable records of decisions with long-lived consequences — the *why* behind choices that
aren't obvious from the code and would otherwise be re-litigated. One file per decision, numbered,
append-only (supersede rather than rewrite).

Format: Status · Date · Deciders · Context · Decision · Consequences. Keep them tight.

| # | Decision | Status |
|---|----------|--------|
| [0001](0001-frontmatter-patcher-refuses-rather-than-parses-quoted-values.md) | The frontmatter patcher refuses ambiguous YAML rather than parsing it | Accepted |
| [0002](0002-slug-collisions-are-same-role-only.md) | Same-parent slug collisions are same-role only (page-vs-page, folder-vs-folder) | Accepted |
