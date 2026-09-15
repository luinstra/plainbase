package com.plainbase.frameworks.config

import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.CommitGlob
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path

/** Pure normalized-value derivations shared by config loading and runtime consumers. */
internal object ConfigValuePolicy {
    /** Resolves the app-owned data directory; it is intentionally independent of file configuration. */
    fun dataDirFrom(env: Map<String, String>): Path =
        Path.of(env["DATA_DIR"] ?: "./data").toAbsolutePath().normalize()

    /** Parses the already-validated direct-commit glob strings with their declaring root. */
    fun agentDirectCommitGlobs(config: PlainbaseConfig): List<CommitGlob> =
        config.auth.agentDirectCommitGlobs.map { CommitGlob.parse(it, RootName.PRIMARY) } +
            config.auth.agentDirectCommitGlobsByRoot.flatMap { (root, globs) ->
                globs.map { CommitGlob.parse(it, root) }
            }

    /** True iff [value] is an absolute HTTP(S) URL with a host. */
    fun isAbsoluteHttpUrl(value: String): Boolean {
        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            return false
        }
        return (uri.scheme == "http" || uri.scheme == "https") && uri.host != null
    }

    /** True iff [value] has an HTTPS scheme. */
    fun isHttpsUrl(value: String): Boolean =
        try {
            URI(value).scheme == "https"
        } catch (_: URISyntaxException) {
            false
        }
}
