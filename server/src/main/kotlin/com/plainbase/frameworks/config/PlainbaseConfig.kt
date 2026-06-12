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
     */
    fun requireContentDir(): Path {
        require(Files.isDirectory(contentDir)) { "CONTENT_DIR does not exist or is not a directory: $contentDir" }
        return contentDir
    }

    companion object {
        const val VERSION: String = "0.1.0"

        const val DEFAULT_PORT: Int = 8080

        fun fromEnv(env: Map<String, String> = System.getenv()): PlainbaseConfig = PlainbaseConfig(
            contentDir = Path.of(env["CONTENT_DIR"] ?: "./content").toAbsolutePath().normalize(),
            dataDir = Path.of(env["DATA_DIR"] ?: "./data").toAbsolutePath().normalize(),
            host = env["PLAINBASE_HOST"] ?: "0.0.0.0",
            port = env["PLAINBASE_PORT"]?.toIntOrNull() ?: DEFAULT_PORT,
        )
    }
}
