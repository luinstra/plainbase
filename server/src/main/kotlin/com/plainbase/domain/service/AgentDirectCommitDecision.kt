package com.plainbase.domain.service

import com.plainbase.domain.content.Nfc
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.root.RootName

/**
 * The PURE decision core for an agent's `PUT /api/v1/pages/{id}` write: does a COMMIT-capable agent's write to
 * [targetPath] land DIRECTLY (it falls inside `agentDirectCommit.globs`), or DEGRADE to a human-reviewed proposal?
 *
 * Domain-pure (no IO, no clock, no repo — covered by `DomainPurityTest`), and deliberately NOT a port: it is a total
 * function over its three inputs. [GuardedMutatingFacade] consults it on the agent DIRECT_PUT path only; the match
 * target is the SERVER-RESOLVED `current.path` the pipeline writes (never a client-supplied path), so "passes a glob
 * ⇔ the pipeline writes there" is true BY CONSTRUCTION (the [AgentWriteDecision.targetPath] identity).
 *
 * Fail-safe direction: every ambiguity resolves to [AgentWriteDecision.DegradeToProposal]. A wrong degrade is an
 * unnecessary human review (safe); a wrong direct-commit is an unreviewed privileged write (escalation).
 */
sealed interface AgentWriteDecision {

    /** The write to [targetPath] lands directly (a COMMIT agent, [targetPath] inside an `agentDirectCommit` glob). */
    data class DirectCommit(val targetPath: TreePath) : AgentWriteDecision

    /** The write to [targetPath] is filed as a proposal for human review (any non-COMMIT mode, or out-of-glob). */
    data class DegradeToProposal(val targetPath: TreePath) : AgentWriteDecision
}

/**
 * The decision: a [AgentMode.COMMIT] agent whose [targetPath] matches SOME [globs] entry declared for
 * [targetRoot] commits directly; every other case (`PROPOSE`/`READ_ONLY`, COMMIT out-of-glob, no glob for this
 * root, or empty [globs]) degrades to a proposal. Both arms carry [targetPath] so the facade can assert
 * reference identity against the WriteIntent's path on either branch.
 *
 * The root filter is what makes the per-root glob block an AUTHORIZATION boundary rather than a naming
 * convention: a pattern declared for main authorizes nothing in an extra root, and vice versa (ADR-0011 D6).
 */
fun agentWriteDecision(mode: AgentMode, globs: List<CommitGlob>, targetRoot: RootName, targetPath: TreePath): AgentWriteDecision =
    if (mode == AgentMode.COMMIT && globs.any { it.root == targetRoot && it.matches(targetPath) }) {
        AgentWriteDecision.DirectCommit(targetPath)
    } else {
        AgentWriteDecision.DegradeToProposal(targetPath)
    }

/**
 * A parsed, validated, NFC-normalized `agentDirectCommit.globs` pattern with a hand-rolled, case-SENSITIVE matcher
 * over [TreePath.segments].
 *
 * Hand-rolled, NOT `java.nio.file.PathMatcher` (the security crux): PathMatcher carries the OS-default FileSystem's
 * case + separator semantics (case-INSENSITIVE on macOS, `\` on Windows) — a platform-variant surface on a privilege
 * gate. Matching over the SAME `/`-separated NFC segments the content tree uses keeps the gate deterministic across
 * platforms and identical to the path the pipeline writes. No glob/path dependency (the allowlist gate forbids it).
 *
 * Wildcard grammar (case-sensitive throughout, post-NFC):
 *  - `*` matches zero-or-more non-`/` chars WITHIN one segment (never crosses a `/`);
 *  - `?` matches exactly one non-`/` char;
 *  - `**` matches ZERO OR MORE WHOLE segments — a `docs` prefix with a trailing `**` matches `docs` itself
 *    (zero segments), `docs/a.md` (one), and `docs/a/b.md` (two);
 *  - a plain segment matches a path segment iff byte-equal (post-NFC).
 *
 * **[root] comes from WHICH CONFIG KEY declared the pattern, never from the pattern text** (ADR-0011 D6, and
 * this is a security property, not a style choice). A colon is a legal `TreePath` segment character AND a legal
 * glob character - a directory literally named `archive:2024` indexes, serves, and is glob-matchable today - so a
 * `<root>:<pattern>` in-string grammar would silently RETARGET such an operator's existing pattern the day they
 * add a root named `archive`: revoking it in main, and granting it, unasked, inside the new root. That is a
 * privilege change produced by a version bump and nothing else. No printable separator is unambiguous against the
 * path charset, so the root arrives STRUCTURALLY instead: `auth.agentDirectCommit.globs` is main's list (unchanged
 * meaning, forever) and `auth.agentDirectCommit.roots.<name>` is the ONLY way to grant an extra root. A pattern is
 * never split on any character but the path separator.
 */
class CommitGlob private constructor(private val segments: List<String>, val root: RootName) {

    /** True iff this glob matches [path]'s segment list under the [CommitGlob] grammar. */
    fun matches(path: TreePath): Boolean = matchSegments(0, path.segments, 0)

    /**
     * Two-pointer segment match with `**` consuming zero-or-more WHOLE segments. Recursing on each `**` split point
     * keeps the fail-safe invariant honest: a path matches ONLY when some split makes the whole pattern consume the
     * whole path, so any structural uncertainty falls through to `false` (→ degrade).
     */
    private fun matchSegments(patternIndex: Int, path: List<String>, pathIndex: Int): Boolean {
        var pi = patternIndex
        var si = pathIndex
        while (pi < segments.size) {
            val pattern = segments[pi]
            if (pattern == DOUBLE_STAR) {
                // `**` absorbs zero-or-more whole segments: try every split of the remaining path.
                for (consumed in si..path.size) {
                    if (matchSegments(pi + 1, path, consumed)) return true
                }
                return false
            }
            if (si >= path.size || !segmentMatches(pattern, path[si])) return false
            pi++
            si++
        }
        return si == path.size
    }

    companion object {
        private const val DOUBLE_STAR = "**"

        /** Within-ONE-segment wildcard match (`*` = zero+ chars, `?` = one char, else literal); no `/` ever appears here. */
        private fun segmentMatches(pattern: String, text: String): Boolean = matchChars(pattern, 0, text, 0)

        private fun matchChars(pattern: String, patternIndex: Int, text: String, textIndex: Int): Boolean {
            var pi = patternIndex
            var ti = textIndex
            var matched: Boolean? = null
            while (pi < pattern.length && matched == null) {
                when (pattern[pi]) {
                    '*' -> {
                        matched = (ti..text.length).any { index -> matchChars(pattern, pi + 1, text, index) }
                    }
                    '?' -> {
                        when {
                            ti >= text.length -> matched = false
                            else -> {
                                pi++
                                ti++
                            }
                        }
                    }
                    else -> {
                        when {
                            ti >= text.length || text[ti] != pattern[pi] -> matched = false
                            else -> {
                                pi++
                                ti++
                            }
                        }
                    }
                }
            }
            return matched ?: (ti == text.length)
        }

        /**
         * Parses [raw] into a [CommitGlob], throwing [IllegalArgumentException] (naming the bad pattern) on a
         * malformed input — the `requireParseableCidrs` fail-fast idiom, called at config load. A single leading `/`
         * is stripped (operator convenience; absoluteness is meaningless here); a single trailing `/` (empty trailing
         * segment) is dropped; an interior empty segment, a `.`/`..` segment, a blank pattern, or a pattern that
         * normalizes to zero segments is REJECTED. Each surviving segment is NFC-normalized (so an operator's NFD-form
         * glob matches the always-NFC [TreePath.value]); the wildcard metacharacters stay literal in the segment.
         *
         * [root] is the root the DECLARING config key names, defaulted to [RootName.MAIN] - so the grammar, the
         * matcher and every pre-C4 call site are byte-identical, and an existing `globs` list keeps meaning
         * exactly what it means today.
         */
        fun parse(raw: String, root: RootName = RootName.MAIN): CommitGlob {
            require(raw.isNotBlank()) { "agentDirectCommit glob must not be blank: '$raw'" }
            val parts = raw.removePrefix("/").split("/").toMutableList()
            if (parts.size > 1 && parts.last().isEmpty()) parts.removeAt(parts.lastIndex) // a single trailing '/'
            require(parts.isNotEmpty() && parts.none { it.isEmpty() }) {
                "agentDirectCommit glob has an empty segment: '$raw'"
            }
            require(parts.none { it == "." || it == ".." }) {
                "agentDirectCommit glob must not contain a '.' or '..' segment: '$raw'"
            }
            return CommitGlob(parts.map(Nfc::normalize), root)
        }
    }
}
