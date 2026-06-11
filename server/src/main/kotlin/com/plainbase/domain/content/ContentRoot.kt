package com.plainbase.domain.content

/**
 * The SINGLE traversal guard (chunk 1.5 Rule: no second containment check anywhere).
 *
 * [ContentRoot] performs root-anchored **lexical** resolution of a decoded path —
 * collapsing `.` and `..` segments without touching the filesystem — and rejects any
 * input whose resolution escapes the root. This is PB-LINK-1 step 4 (§A2) and the
 * render-time traversal guard, distilled to pure logic.
 *
 * Where [TreePath] refuses to even *hold* a `..`, [ContentRoot] is the place that
 * *accepts* paths containing `..`/`.` (which arise legitimately from relative links such
 * as `../infra/kubernetes.md`), collapses them, and decides containment. Encoded
 * traversal (`%2e%2e`) arrives here as literal `..` after [PercentCoding.decodeOnce] and
 * is contained identically to literal `..`.
 *
 * The content root is conceptual (the path is always tree-relative); no absolute
 * filesystem path is needed for the lexical guarantee, so this type is pure domain code.
 */
object ContentRoot {

    /** Outcome of a lexical resolution: either a contained [TreePath] or an escape. */
    sealed interface ResolveResult {
        data class Resolved(val path: TreePath) : ResolveResult

        /** The input resolved to the root itself (empty path) — valid but not a [TreePath]. */
        data object Root : ResolveResult

        /** The input escaped the content root (`..` above the root). PB-LINK-1 `outside_content_root`. */
        data object Outside : ResolveResult
    }

    /**
     * Resolves [target] lexically.
     *
     *  - A leading `/` resolves against the root, ignoring [baseDir].
     *  - Otherwise [target] resolves against [baseDir] (the directory of the current page),
     *    which must itself be a contained [TreePath] (or null for the root directory).
     *
     * `.` segments are dropped; `..` pops the last segment, and popping above the root is
     * an escape. Empty segments (from `//` or a trailing `/`) are dropped. The decoded,
     * NFC-normalized result is returned as a [TreePath], or [ResolveResult.Root] when it
     * collapses to the root, or [ResolveResult.Outside] on escape.
     *
     * [target] is expected to be already percent-decoded (via [PercentCoding]); each
     * surviving segment is NFC-normalized here so a link typed in NFD resolves to the
     * NFC-indexed file.
     */
    fun resolve(baseDir: TreePath?, target: String): ResolveResult {
        val stack = ArrayDeque<String>()

        val isAbsolute = target.startsWith("/")
        if (!isAbsolute && baseDir != null) {
            stack.addAll(baseDir.segments)
        }

        val body = if (isAbsolute) target.removePrefix("/") else target
        for (raw in body.split("/")) {
            when (raw) {
                "", "." -> Unit // collapse empty and current-dir segments
                ".." -> if (stack.isEmpty()) return ResolveResult.Outside else stack.removeLast()
                else -> stack.addLast(Nfc.normalize(raw))
            }
        }

        if (stack.isEmpty()) return ResolveResult.Root
        // Reconstruct via TreePath.of so the structural invariants hold for the result.
        val resolved = TreePath.of(stack.joinToString("/"))
            ?: return ResolveResult.Outside // defensive: should be unreachable post-collapse
        return ResolveResult.Resolved(resolved)
    }

    /** True iff [target] resolves to a path contained within the root (or the root itself). */
    fun contains(baseDir: TreePath?, target: String): Boolean = resolve(baseDir, target) !is ResolveResult.Outside
}
