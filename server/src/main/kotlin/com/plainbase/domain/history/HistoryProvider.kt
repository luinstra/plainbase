package com.plainbase.domain.history

import com.plainbase.domain.content.TreePath
import kotlin.time.Instant

/**
 * The optional Git-history port (ADR-0006, chunk W4): one new commit per save over the SAME directory
 * the content store serves. Pure domain — no framework imports (the [DomainPurityTest][com.plainbase
 * .DomainPurityTest] floor); the two adapters live in `frameworks/git/` ([GitCliHistoryProvider][com
 * .plainbase.frameworks.git.GitCliHistoryProvider] over the `git` system binary, [NoOpHistoryProvider]
 * [com.plainbase.frameworks.git.NoOpHistoryProvider] when Git is off).
 *
 * This is an INTERNAL signature, NOT a wire contract (ADR-0006 reversibility) — it may change as W5
 * (history/read surface) and W7 land. Commit message text is human-facing, never golden-frozen: tests
 * assert structure, never the exact string.
 */
interface HistoryProvider {

    /**
     * Commits the EXACT [bytes] (the W1 hook bytes — filter-free and disk-independent, never a disk
     * re-read) at [path] as ONE new commit, returning the recorded [Commit] (null only for the no-op
     * adapter). An external edit between the W1 CAS and this call cannot alter what is committed: the
     * blob is staged from these bytes via `hash-object --stdin`, not from the working tree.
     *
     * The commit captures the current HEAD tree plus EXACTLY this one path; a re-commit of bytes that
     * already equal HEAD's tree is a no-op (the existing HEAD [Commit] is returned, nothing new written),
     * which is what makes the W1 recovery re-commit idempotent. [author]/[committer] default to the
     * configured identity (Phase 3 has no principal; the split is real plumbing for Phase 4).
     */
    fun commit(path: TreePath, bytes: ByteArray, author: CommitIdentity? = null, committer: CommitIdentity? = null): Commit?

    /** The last commit that touched each of [paths], batched into one read (never one query per path). */
    fun lastCommits(paths: List<TreePath>): Map<TreePath, Commit>

    /** The commit history of [path], newest first, capped at [limit] when given. */
    fun log(path: TreePath, limit: Int? = null): List<Commit>

    /** The unified diff of [path] between commits [from] and [to]. */
    fun diff(from: String, to: String, path: TreePath): FileDiff

    /**
     * Fails fast (with an operator-actionable message) when this provider cannot operate — for the Git
     * adapter, when the `git` binary is absent. Run at startup BEFORE any commit can fire. The no-op
     * adapter is always ready.
     */
    fun gateCheck()
}

/** One commit's recorded identity + timestamps + message (read shape; W5 owns its evolution). */
data class Commit(
    val sha: String,
    val author: CommitIdentity,
    val committer: CommitIdentity,
    val authorTime: Instant,
    val committerTime: Instant,
    val message: String,
)

/** A Git identity — a name and an email. The author/committer split is settable per [HistoryProvider.commit]. */
data class CommitIdentity(val name: String, val email: String)
