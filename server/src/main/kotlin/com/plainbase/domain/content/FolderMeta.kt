package com.plainbase.domain.content

import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Parsed `_folder.yaml` metadata for a content folder (§A4).
 *
 * Three keys are known and honored:
 *  - [title] — the human display title for the folder in the nav tree.
 *  - [order] — an explicit sort key among siblings (lower sorts first); null = order by title.
 *  - [slug]  — a URL-segment override for this folder's canonical URL path; null = derive
 *              the segment from the folder name via the slugger (chunk 5).
 *
 * Unknown keys are ignored **with a warning log** — `_folder.yaml` is a deliberately tiny,
 * three-key file, not a general YAML document; an unknown key is almost always a typo or a
 * future feature, and silently dropping it would hide both.
 */
data class FolderMeta(
    val title: String? = null,
    val order: Int? = null,
    val slug: String? = null,
) {
    companion object {
        private val logger = KotlinLogging.logger {}

        private val KNOWN_KEYS = setOf("title", "order", "slug")

        /**
         * Parses the three known keys from a `_folder.yaml` body with a deliberately tiny
         * line grammar — NOT a YAML engine (the native-image bet bans Jackson/SnakeYAML and
         * `_folder.yaml` only ever carries flat `key: value` scalars).
         *
         * Grammar: each non-blank, non-comment line is `key: value`. The key is the text
         * before the first `:`; the value is the remainder, trimmed, with one optional layer
         * of matching surrounding quotes stripped. Blank lines and `#` comment lines are
         * skipped. A malformed `order` (non-integer) is treated as absent and warned. Unknown
         * keys are ignored with a warning. [source] names the file for log context.
         */
        fun parse(body: String, source: String = "_folder.yaml"): FolderMeta {
            var title: String? = null
            var order: Int? = null
            var slug: String? = null

            for (rawLine in body.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val colon = line.indexOf(':')
                if (colon < 0) {
                    logger.warn { "Ignoring malformed line in $source (no ':'): '$line'" }
                    continue
                }
                val key = line.substring(0, colon).trim()
                val value = stripQuotes(line.substring(colon + 1).trim())
                if (key !in KNOWN_KEYS) {
                    logger.warn { "Ignoring unknown key '$key' in $source (known keys: title, order, slug)" }
                    continue
                }
                when (key) {
                    "title" -> title = value
                    "slug" -> slug = value
                    "order" -> {
                        val parsed = value.toIntOrNull()
                        if (parsed == null) {
                            logger.warn { "Ignoring non-integer 'order' in $source: '$value'" }
                        } else {
                            order = parsed
                        }
                    }
                }
            }
            return FolderMeta(title = title, order = order, slug = slug)
        }

        /** Strips one layer of matching surrounding single or double quotes, if present. */
        private fun stripQuotes(value: String): String {
            if (value.length >= 2) {
                val first = value.first()
                val last = value.last()
                if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                    return value.substring(1, value.length - 1)
                }
            }
            return value
        }
    }
}
