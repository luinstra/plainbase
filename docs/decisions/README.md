# Architecture Decision Records

Short, durable records of decisions with long-lived consequences — the *why* behind choices that
aren't obvious from the code and would otherwise be re-litigated. One file per decision, numbered,
append-only (supersede rather than rewrite).

Format: Status · Date · Deciders · Context · Decision · Consequences. Keep them tight.

| # | Decision | Status |
|---|----------|--------|
| [0001](0001-frontmatter-patcher-refuses-rather-than-parses-quoted-values.md) | The frontmatter patcher refuses ambiguous YAML rather than parsing it | Accepted |
| [0002](0002-slug-collisions-are-same-role-only.md) | Same-parent slug collisions are same-role only (page-vs-page, folder-vs-folder) | Accepted |
| [0003](0003-folder-landing-pages.md) | Folders gain client-rendered landing views at their URL prefix | Accepted |
| [0004](0004-raw-jdbc-for-derived-search-db.md) | Raw JDBC for the derived-state search.db (app DB stays SQLDelight-only) | Accepted |
| [0005](0005-two-stage-search-palette.md) | Two-stage search palette (jump-to first, full-text on demand) | Accepted |
| [0006](0006-git-via-system-binary-not-jgit.md) | Git history via the system `git` binary (not JGit), behind a hermetic executor | Accepted |
| [0007](0007-tree-node-metadata-editorial-dates-provisional-fields.md) | Tree-node metadata: editorial dates now, provisional wire fields, Git last-modified stays distinct | Accepted |
| [0008](0008-tls-terminates-at-an-external-reverse-proxy.md) | TLS terminates at an external reverse proxy (not in-process), with a fail-closed bind guard | Accepted |
| [0009](0009-hocon-config-file-not-yaml.md) | The config file is HOCON (`plainbase.conf`), not YAML, layered under env | Accepted |
