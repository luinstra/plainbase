package com.plainbase.frameworks.config

import com.plainbase.BuildInfo
import java.nio.file.Path

/**
 * Application configuration.
 *
 * Environment variables override defaults; `DATA_DIR/plainbase.conf` is layered in by [ConfigLoader.fromEnvAndFile].
 * Secrets stay in env, never the file. [ConfigLoader.fromEnv] is the env-only fast path used by the credential-free
 * `spike`; the server and DATA_DIR-sharing CLIs use [ConfigLoader.fromEnvAndFile] so file-configured decisions match
 * `serve`.
 */
data class PlainbaseConfig(
    val contentDir: Path,
    val dataDir: Path,
    val host: String,
    val port: Int,
    /**
     * PB-WRITE-1 maximum `PUT /api/v1/pages/{id}` request-body size in bytes. The response carries configured
     * `max_bytes`; the numeric default is configurable and not the frozen contract.
     */
    val maxWriteBodyBytes: Long = DEFAULT_MAX_WRITE_BODY_BYTES,
    /**
     * W3b uses a separate, larger cap for binary assets. Raising either cap preserves the wire contract;
     * responses carry configured `max_bytes`, not a frozen default.
     */
    val maxAssetBytes: Long = DEFAULT_MAX_ASSET_BYTES,
    /** Git-history layer config (ADR-0006): enablement tri-state plus commit identity. */
    val git: GitConfig = GitConfig(),
    /** Phase-4 auth substrate (ADR-0008): bind-guard and secure-context inputs. */
    val auth: AuthConfig = AuthConfig(),
    /** Storage-backend selection (Q9): local filesystem authority or an S3-compatible bucket. */
    val storage: StorageConfig = StorageConfig(),
    /** Source of [contentDir], used to warn when object mode ignores an explicitly set CONTENT_DIR. */
    val contentDirSource: ConfigSource = ConfigSource.DEFAULT,
    /**
     * Parsed `roots {}` topology, or the synthesized back-compat main root for a legacy config. The default runs only
     * at construction; `copy(contentDir = ...)` or `copy(storage = ...)` retains the prior topology.
     */
    val roots: RootsConfig = RootsConfig.synthesized(contentDir, storage),
) {
    /** Path of the app-owned SQLite database. */
    val appDatabasePath: Path get() = dataDir.resolve("plainbase.db")

    /** Path of the machine-managed roots file beside `plainbase.conf`. */
    val managedRootsPath: Path get() = dataDir.resolve(MANAGED_ROOTS_FILE)

    /** Path of the rebuildable derived-state search database. */
    val searchDatabasePath: Path get() = dataDir.resolve("search.db")

    /** The local primary root, or the legacy path needed by object-mode mirror and CLI seams. */
    fun mainContentRoot(): Path = roots.primary.localPath ?: contentDir

    companion object {
        // C5 item 8: self-report tracks the release tag from generated BuildInfo instead of a hardcoded literal.
        const val VERSION: String = BuildInfo.VERSION

        const val DEFAULT_PORT: Int = 8080

        /** The machine-managed roots file in DATA_DIR; CLI writes extras only, so an environment-selected primary stays unfrozen. */
        const val MANAGED_ROOTS_FILE: String = "roots.conf"

        /** Default bind host: loopback (§ADR-0008). */
        const val DEFAULT_HOST: String = "127.0.0.1"

        /** PB-WRITE-1 default body cap: 1 MiB; configurable, with `max_bytes` authoritative in 413 responses. */
        const val DEFAULT_MAX_WRITE_BODY_BYTES: Long = 1_048_576

        /** W3b default asset cap: 10 MiB; configurable rather than a frozen numeric contract. */
        const val DEFAULT_MAX_ASSET_BYTES: Long = 10_485_760

        /** Default Git author/committer identity. */
        const val DEFAULT_GIT_AUTHOR_NAME: String = "Plainbase"
        const val DEFAULT_GIT_AUTHOR_EMAIL: String = "plainbase@localhost"

        /** A4b default proxy identity header. */
        const val DEFAULT_PROXY_IDENTITY_HEADER: String = "X-Forwarded-User"

        /** Q9 default signing region. */
        const val DEFAULT_S3_REGION: String = "auto"

        /** Q9 default watch/reconcile poll interval in seconds. */
        const val DEFAULT_S3_POLL_SECONDS: Long = 60
    }
}
