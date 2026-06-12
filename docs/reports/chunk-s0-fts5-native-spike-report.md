# Chunk S0 — FTS5 auxiliary-surface native spike report

**Date:** 2026-06-12
**Scope:** Phase 2 (Search), chunk S0 — NativeSpike check 4 (`sqlite-fts5-match`, name kept)
extended from a bare MATCH probe to the full search.db surface, run inside the native image.

The extended check opens a **temp-file** SQLite database (not `:memory:`) via raw JDBC
(`java.sql.DriverManager` over the already-allowlisted xerial driver — the ADR-0004 access path,
zero new dependencies) and proved, both on the JVM and inside the GraalVM native binary
(`plainbase spike`, 8/8): `PRAGMA journal_mode=WAL` honored and `busy_timeout` set and read back;
FTS5 virtual-table DDL with `tokenize='unicode61 remove_diacritics 1'`; `bm25()` column weights
**actually flipping the result ordering** (title-weighted query ranks the title-hit document
first, body-weighted query ranks the repeated-body-hit document first); `snippet()` and
`highlight()` with `char(1)`/`char(2)` sentinel delimiters round-tripping cleanly (sentinels
parsed back out, highlight output reconstructs the stored body exactly); and prefix matching
(`"deplo"*` finds both documents). **Trigram leg verdict: PASS** — a `tokenize='trigram'` table
exhibits substring MATCH semantics (`bernet` finds `kubernetes deployment notes`) and the exact
CJK rescue case holds: the query `ガイド` finds the document containing `日本語ガイド` (the case
unicode61 cannot satisfy). Native output:
`PASS sqlite-fts5-match … trigram=PASS (ガイド found in 日本語ガイド; bernet found in kubernetes)`.
S2's trigram side-index task is therefore **unblocked** (gated on this PASS). No fallback-ladder
branch was taken: no auxiliary function was missing, FTS5 is present, the trigram leg passed.
ADR-0004 (raw JDBC for the derived-state search.db) was authored in this chunk and CLAUDE.md's
persistence rule amended to match; `verifyDependencyAllowlist` is green with zero allowlist diff.
