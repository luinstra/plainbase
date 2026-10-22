package com.plainbase.frameworks.config

import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.CommitGlob
import com.plainbase.domain.service.PolicyMode
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path

/** Pure normalized-value derivations shared by config loading and runtime consumers. */
internal object ConfigValuePolicy {
    fun policyMode(config: PlainbaseConfig): PolicyMode = when {
        config.profile == DeploymentProfile.READER -> PolicyMode.READER
        config.auth.mode == AuthMode.OFF -> PolicyMode.OPEN
        else -> PolicyMode.ENFORCED
    }

    /** Resolves the app-owned data directory; it is intentionally independent of file configuration. */
    fun dataDirFrom(env: Map<String, String>): Path =
        Path.of(env["DATA_DIR"] ?: "./data").toAbsolutePath().normalize()

    /** Pure theme diagnostics retain the key and rejection category, never the rejected value. */
    fun themeWarnings(config: PlainbaseConfig): List<String> = config.theme.rejections.map { rejection ->
        val reason = when (rejection.reason) {
            ThemeRejectionReason.NOT_A_BLOCK ->
                "must be a block such as theme { light = \"<name>.css\" } (found ${rejection.valueType})"
            ThemeRejectionReason.NULL -> "is null (remove the key to use the bundled theme)"
            ThemeRejectionReason.NOT_A_STRING -> "is not a string (found ${rejection.valueType})"
            ThemeRejectionReason.BLANK -> "is blank"
            ThemeRejectionReason.NOT_A_LEAF ->
                "is not a DATA_DIR theme name: optionally themes/, then 1 to 64 ASCII letters, digits, '_' or '-' " +
                    "starting with a letter or digit, then .css, .yaml or .yml"
            ThemeRejectionReason.OVERRIDE_NAME ->
                "uses a name reserved for the fixed DATA_DIR/custom.css override and cannot be selected"
        }
        val fallback = when (rejection.key) {
            "theme" -> "light and dark themes"
            "theme.light" -> "light theme"
            else -> "dark theme"
        }
        "${rejection.key} $reason; serving the bundled $fallback"
    }

    /** Parses the already-validated direct-commit glob strings with their declaring root. */
    fun agentDirectCommitGlobs(config: PlainbaseConfig): List<CommitGlob> =
        config.auth.agentDirectCommitGlobs.map { CommitGlob.parse(it, RootName.PRIMARY) } +
            config.auth.agentDirectCommitGlobsByRoot.flatMap { (root, globs) ->
                globs.map { CommitGlob.parse(it, root) }
            }

    /** Pure storage diagnostics; filesystem inspection and logging stay with their consumers. */
    fun storageWarnings(config: PlainbaseConfig): List<String> = buildList {
        if (config.storage.backend == StorageBackend.LOCAL && config.storage.ignoredObjectKeys.isNotEmpty()) {
            add(
                "storage.backend=local ignores the configured object-storage key(s): " +
                    "${config.storage.ignoredObjectKeys.joinToString(", ")} (set storage.backend=object to use them)",
            )
        }
        if (config.storage.backend == StorageBackend.OBJECT && config.contentDirSource != ConfigSource.DEFAULT) {
            add(
                "storage.backend=object ignores CONTENT_DIR (explicitly set via ${config.contentDirSource.name.lowercase()}): " +
                    "the bucket is the authority and the local mirror lives inside DATA_DIR",
            )
        }
    }

    /** Managed extras alone retain CONTENT_DIR as primary; only a declared primary makes that value ignored. */
    fun ignoredContentDirWarning(config: PlainbaseConfig): String? =
        if (config.roots.primaryDeclared && config.contentDirSource != ConfigSource.DEFAULT) {
            "roots {} is configured: the explicitly set CONTENT_DIR/contentDir (via " +
                "${config.contentDirSource.name.lowercase()}) is ignored - primary's path comes from roots.docs.path"
        } else {
            null
        }

    /**
     * Pure warnings for direct-commit globs attached to roots that deny page writes. Walk from `roots.list` because
     * primary globs live outside the by-root map, so this includes them.
     */
    fun editableGlobWarnings(config: PlainbaseConfig): List<String> = buildList {
        val globbedRoots = buildSet {
            if (config.auth.agentDirectCommitGlobs.isNotEmpty()) add(RootName.PRIMARY)
            config.auth.agentDirectCommitGlobsByRoot.forEach { (root, globs) ->
                if (globs.isNotEmpty()) add(root)
            }
        }
        config.roots.list
            .filter { !it.editable && it.name in globbedRoots }
            .forEach { root ->
                add(
                    "auth.agentDirectCommit declares direct-commit globs for root '${root.name.value}', but " +
                        "roots.${root.name.value} is editable = false - the globs can never authorize anything there, " +
                        "because the root refuses page writes outright. Set editable = true, or drop the globs.",
                )
            }
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
