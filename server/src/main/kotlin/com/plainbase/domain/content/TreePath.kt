package com.plainbase.domain.content

/**
 * A content-root-relative path: NFC-normalized, `/`-separated, validated.
 *
 * A [TreePath] **structurally cannot** express traversal or absoluteness — there is no
 * valid instance whose [segments] contain `..`, `.`, an empty segment, or that begins
 * with `/`. Construction of any such input fails ([of] returns null / [require] throws),
 * so no valid [TreePath] can ever escape the content tree by itself. (Lexical resolution
 * against a [ContentRoot] is the separate, second guard.)
 *
 * The canonical text form ([value]) is the NFC, `/`-joined segments with no leading
 * slash — the form used for citations and `path` fields (§A4).
 */
class TreePath private constructor(
    /** The NFC-normalized path segments, root-first; never empty, never contains `..`/`.`/``. */
    val segments: List<String>,
) {

    /** The canonical text form: NFC segments joined by `/`, no leading slash. */
    val value: String get() = segments.joinToString("/")

    /** The final segment (the file or directory name). */
    val name: String get() = segments.last()

    /** The parent path, or null when this path is a single top-level segment. */
    val parent: TreePath? get() = if (segments.size <= 1) null else TreePath(segments.dropLast(1))

    /** Appends [child] (a single validated segment) to this path. */
    fun resolveChild(child: String): TreePath {
        require(isValidSegment(child)) { "invalid path segment: '$child'" }
        return TreePath(segments + Nfc.normalize(child))
    }

    override fun equals(other: Any?): Boolean = other is TreePath && other.segments == segments

    override fun hashCode(): Int = segments.hashCode()

    override fun toString(): String = value

    companion object {

        /**
         * Builds a [TreePath] from a raw, already-decoded relative path string, or returns
         * null if it is absolute, empty, or contains any `.`/`..`/empty segment.
         *
         * Each segment is NFC-normalized (the boundary rule). A leading `/` is rejected
         * rather than silently stripped — absoluteness is a structural error here, not a
         * normalization concern.
         */
        fun of(raw: String): TreePath? {
            if (raw.isEmpty()) return null
            if (raw.startsWith("/")) return null
            val parts = raw.split("/")
            if (parts.any { !isValidSegment(it) }) return null
            return TreePath(parts.map { Nfc.normalize(it) })
        }

        /** Like [of] but throws [IllegalArgumentException] on invalid input. */
        fun require(raw: String): TreePath =
            requireNotNull(of(raw)) { "not a valid content-relative path: '$raw'" }

        /** Resolves a single validated [name] under [parent], or as a top-level path when [parent] is null. */
        fun childOf(parent: TreePath?, name: String): TreePath =
            parent?.resolveChild(name) ?: require(name)

        /** A segment is valid iff it is non-empty and is neither `.` nor `..` (and has no `/`). */
        private fun isValidSegment(segment: String): Boolean =
            segment.isNotEmpty() && segment != "." && segment != ".." && !segment.contains('/')
    }
}
