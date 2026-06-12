# SQLDelight schema & migrations

- Schema: `.sq` files under `com/plainbase/frameworks/sqldelight/` (package-mirrored).
- Migrations: `.sqm` files in the same directory, named `<version>.sqm`
  (`1.sqm` migrates schema v1 -> v2, etc.). `DatabaseFactory.migrate` applies them
  using SQLite's `user_version` pragma.
- Generated API: `com.plainbase.frameworks.sqldelight.PlainbaseDb` (compile-time codegen,
  zero reflection — this is why SQLDelight and not Exposed, §3).
- Schema baselines: `schema/<version>.db`, regenerated via
  `./gradlew :server:generateMainPlainbaseDbSchema` whenever a migration lands
  (`verifyMigrations` checks every baseline + migration chain at build time).
- The search index lives in its own `DATA_DIR/search.db` (separate file, deliberately
  destroyable, raw JDBC per ADR-0004 — never SQLDelight). The Phase-0 FTS5 spike table
  was dropped by `2.sqm`.
