# 4. Raw JDBC for the derived-state search.db

- **Status:** Accepted
- **Date:** 2026-06-12
- **Deciders:** luinstra
- **Context:** Phase 2 (Search) chunk S0 — the native-gate spike for the FTS5 auxiliary surface
  (plan §B5); locks the access path for `DATA_DIR/search.db` before S2 builds the
  `Fts5SearchProvider` on it. NativeSpike check 4 (`sqlite-fts5-match`) is the working proof.

## Context

Phase 2 introduces a second SQLite database, `DATA_DIR/search.db`, holding the FTS5 section
index. It is **derived state**: rebuildable from the published page snapshot at any time,
deletable with zero data loss, schema-versioned by drop-and-recreate — it never migrates. The
app database is SQLDelight-managed, and CLAUDE.md's dependency discipline said "Persistence is
SQLDelight only", which forces the question of how search.db gets opened and queried.

SQLDelight earns its keep on the app DB through typed row mapping and `.sqm` migration
discipline — neither of which search.db has. And the search workload sits outside the SQLDelight
dialect's comfort: FTS5 virtual-table DDL (`CREATE VIRTUAL TABLE … USING fts5(…)` with tokenizer
options), `MATCH`, auxiliary functions (`bm25()` with column weights, `snippet()`, `highlight()`
with `char(1)` sentinel arguments), and the generation-swap query shapes cannot be expressed
cleanly as `.sq` files. The alternative of a second driver or any new dependency is banned by the
native-image dependency discipline.

## Decision

**`search.db` is accessed via raw JDBC (`java.sql.DriverManager` and friends) over the
already-allowlisted xerial sqlite-jdbc driver. The app database remains SQLDelight-only.**

- No new dependency, no allowlist change: the same driver JAR serves both databases; only the
  query layer differs.
- All search.db SQL lives in one adapter class (S2's `SearchDb` / `Fts5SearchProvider` under
  `frameworks/`), opened with `PRAGMA journal_mode=WAL` and `busy_timeout` set — the exact
  plumbing NativeSpike check 4 verifies against a temp-file database inside the native image.
- Schema versioning by `search_meta.schema_version`: on mismatch, drop everything, recreate,
  full rebuild. Derived state has no migrations, by privilege — which is precisely why it does
  not need SQLDelight.
- CLAUDE.md's dependency-discipline wording is amended in the same change: app DB is
  SQLDelight-only; search.db is raw JDBC over the same driver per this ADR. The rule text and
  this ADR may never disagree.

## Consequences

**Positive**

- The full FTS5 surface (virtual tables, MATCH, bm25 weights, snippet/highlight sentinels,
  trigram tokenizer) is usable directly, with working native-image proof in the 8/8 spike gate.
- Zero dependency-surface growth for the native bet; one class owns every search.db statement.

**Trade-offs**

- Raw JDBC means hand-written row mapping and resource handling (`use {}` discipline) for the
  search adapter — acceptable because search.db has no domain types to map and a single owner.
- Two access idioms in the codebase. The boundary is bright: SQLDelight for durable app state,
  raw JDBC only for this one derived, deletable database.

**Reversibility**

- High: search.db is deletable and migration-free, so the access path can be swapped (or the
  whole file dropped for an external engine, Appendix G) without data or contract consequences.
