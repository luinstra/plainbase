package com.plainbase.frameworks.config

import java.nio.file.Files
import java.nio.file.Path

/**
 * Application configuration.
 *
 * - `CONTENT_DIR` — canonical, user-owned content tree (Markdown + assets). §4 hard rule.
 * - `DATA_DIR`    — app-owned state (SQLite DB, plainbase.yaml, caches, search.db).
 *
 * Environment variables override defaults; `plainbase.yaml` in DATA_DIR is layered
 * in by later phases (env always wins).
 */
data class PlainbaseConfig(
    val contentDir: Path,
    val dataDir: Path,
    val host: String,
    val port: Int,
    /**
     * PB-WRITE-1 body cap: the maximum `PUT /api/v1/pages/{id}` request-body size in bytes; a body
     * exceeding it is rejected `413 body_too_large` (the response carries this authoritative number,
     * so clients never hardcode it). Default 1 MiB; raisable per deploy (raising is additive — the
     * frozen contract is the cap BEHAVIOR + the code + the `max_bytes` field, never the number).
     */
    val maxWriteBodyBytes: Long = DEFAULT_MAX_WRITE_BODY_BYTES,
    /**
     * W3b asset upload cap: the maximum `POST /api/v1/pages/{id}/assets` request-body size in bytes; a body
     * exceeding it is rejected `413 body_too_large` (the response carries this authoritative number). A
     * separate, LARGER cap than [maxWriteBodyBytes] — assets are binaries (screenshots, pdfs, fonts), so a
     * 1 MiB document cap is wrong for them. Default 10 MiB; raisable per deploy (raising is additive).
     */
    val maxAssetBytes: Long = DEFAULT_MAX_ASSET_BYTES,
) {
    /** Path of the app-state SQLite database (workflow + security state, never content). */
    val appDatabasePath: Path get() = dataDir.resolve("plainbase.db")

    /**
     * Path of the derived-state search database (§B5/ADR-0004): rebuildable from the published
     * snapshot at any time, deletable with zero data loss — always a separate file from
     * [appDatabasePath] (§4 hard rule).
     */
    val searchDatabasePath: Path get() = dataDir.resolve("search.db")

    /**
     * Startup guard: fails fast with an operator-actionable message when the configured
     * CONTENT_DIR is missing or not a directory. Without it the first scan dies on a bare
     * `NoSuchFileException` that names nothing the operator can act on; silently serving an
     * empty tree would be worse (§4 — the content tree is the product).
     *
     * Also rejects DATA_DIR == CONTENT_DIR: that config violates §4's user-owned/app-owned
     * separation, and concretely puts plainbase.db/search.db (plus their -wal/-journal siblings —
     * none of them dotfiles) INSIDE the watched content root, where every checkpoint write would
     * re-trigger the watcher: a silent, self-sustaining rebuild loop. Strict nesting either way
     * stays legal — the watcher excludes a strictly-nested DATA_DIR, and under a strict ancestor
     * the app's writes land outside the watched tree.
     */
    fun requireContentDir(): Path {
        require(Files.isDirectory(contentDir)) { "CONTENT_DIR does not exist or is not a directory: $contentDir" }
        require(dataDir.toAbsolutePath().normalize() != contentDir.toAbsolutePath().normalize()) {
            "DATA_DIR and CONTENT_DIR must be different directories (both are $contentDir): app-owned state " +
                "(plainbase.db, search.db) inside the user-owned content root would re-trigger the watcher " +
                "after every rebuild — a self-sustaining rebuild loop (§4 separation)"
        }
        return contentDir
    }

    companion object {
        const val VERSION: String = "0.1.0"

        const val DEFAULT_PORT: Int = 8080

        /** PB-WRITE-1 default body cap: 1 MiB. Raisable via `PLAINBASE_MAX_WRITE_BODY_BYTES` (raising is additive). */
        const val DEFAULT_MAX_WRITE_BODY_BYTES: Long = 1_048_576

        /** W3b default asset cap: 10 MiB. Raisable via `PLAINBASE_MAX_ASSET_BYTES` (raising is additive). */
        const val DEFAULT_MAX_ASSET_BYTES: Long = 10_485_760

        fun fromEnv(env: Map<String, String> = System.getenv()): PlainbaseConfig = PlainbaseConfig(
            contentDir = Path.of(env["CONTENT_DIR"] ?: "./content").toAbsolutePath().normalize(),
            dataDir = Path.of(env["DATA_DIR"] ?: "./data").toAbsolutePath().normalize(),
            host = env["PLAINBASE_HOST"] ?: "0.0.0.0",
            port = env["PLAINBASE_PORT"]?.toIntOrNull() ?: DEFAULT_PORT,
            maxWriteBodyBytes = env["PLAINBASE_MAX_WRITE_BODY_BYTES"]?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_MAX_WRITE_BODY_BYTES,
            maxAssetBytes = env["PLAINBASE_MAX_ASSET_BYTES"]?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_MAX_ASSET_BYTES,
        )
    }
}
