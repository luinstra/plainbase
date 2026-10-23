# Unreleased

- Server and offline CLIs now default state/config to `$HOME/.plainbase` directly. Explicit nonblank
  `DATA_DIR` (including container `/data`) still wins. Missing, blank, relative or invalid HOME needs
  explicit absolute DATA_DIR when using the default; empty/whitespace DATA_DIR now refuses.
- Recognizable implicit launch-CWD `./data` installations refuse a silent switch, including theme-only
  evidence and inspection failures. Keep using an explicit absolute DATA_DIR or follow the
  [offline migration runbook](../operating-plainbase.md#offline-migration-to-the-home-directory).
  No state or documents are migrated automatically. CONTENT_DIR and relative root paths still use
  launch CWD; use absolute content/root paths before changing it. Gradle `:server:run` follows the
  same default; use explicit disposable paths for development.
