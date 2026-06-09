# SQLDelight schema & migrations

- Schema: `.sq` files under `com/plainbase/frameworks/sqldelight/` (package-mirrored).
- Migrations: `.sqm` files in the same directory, named `<version>.sqm`
  (`1.sqm` migrates schema v1 -> v2, etc.). `DatabaseFactory.migrate` applies them
  using SQLite's `user_version` pragma.
- Generated API: `com.plainbase.frameworks.sqldelight.PlainbaseDb` (compile-time codegen,
  zero reflection — this is why SQLDelight and not Exposed, §3).
- The FTS5 virtual table lives in the same schema for Phase 0; the real search index
  moves to its own `DATA_DIR/search.db` (separate file, deliberately destroyable) in Phase 2.
