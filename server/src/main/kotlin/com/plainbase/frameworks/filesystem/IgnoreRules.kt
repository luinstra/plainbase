package com.plainbase.frameworks.filesystem

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher

/**
 * Decides which directory entries a [com.plainbase.domain.content.ContentStore] scan skips.
 *
 * Always ignored:
 *  - `.git` (the Git layer's own directory — never content).
 *  - any **dot-prefixed** entry (`.DS_Store`, `.gitignore`, `.obsidian/`, …): dotfiles are
 *    tooling state, not published docs. This also covers `.git` but it is named explicitly so
 *    the rule reads intentionally.
 *
 * Additionally ignored: any entry whose **content-root-relative path** matches one of the
 * configured `content.ignore` glob patterns (e.g. `drafts/**`, `**/*.tmp`). Globs use the
 * platform default filesystem's `glob:` syntax and match against the `/`-joined relative path.
 */
class IgnoreRules(
    private val ignoreGlobs: List<String> = emptyList(),
) {
    private val matchers: List<PathMatcher> =
        ignoreGlobs.map { FileSystems.getDefault().getPathMatcher("glob:$it") }

    /**
     * True iff a child named [name], located at content-relative path [relativePath]
     * (`/`-joined, no leading slash), should be excluded from the scan.
     */
    fun isIgnored(name: String, relativePath: String): Boolean {
        if (name.startsWith(".")) {
            logger.debug { "Ignoring dot-prefixed entry: $relativePath" }
            return true
        }
        if (matchers.isNotEmpty()) {
            val candidate = Path.of(relativePath)
            if (matchers.any { it.matches(candidate) }) {
                logger.debug { "Ignoring entry matching content.ignore glob: $relativePath" }
                return true
            }
        }
        return false
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
