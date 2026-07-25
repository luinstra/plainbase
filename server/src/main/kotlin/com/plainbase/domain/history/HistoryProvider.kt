package com.plainbase.domain.history

import com.plainbase.domain.content.TreePath
import kotlin.time.Instant

/**
 * The optional Git-history port (ADR-0006): one new commit per save over the same content tree
 * the content store serves. Pure domain — no framework imports (the [DomainPurityTest][com.plainbase
 * .DomainPurityTest] floor); the two adapters live in `frameworks/git/` ([GitCliHistoryProvider][com
 * .plainbase.frameworks.git.GitCliHistoryProvider] over the `git` system binary, [NoOpHistoryProvider]
 * [com.plainbase.frameworks.git.NoOpHistoryProvider] when Git is off).
 *
 * This is an INTERNAL signature, NOT a wire contract (ADR-0006 reversibility) — it may change as the
 * history/read surface and later work land. Commit message text is human-facing, never golden-frozen: tests
 * assert structure, never the exact string.
 */
interface HistoryProvider {

    /**
     * Whether this provider is backed by a real Git repo (false for the no-op adapter). Surfaced to
     * clients as `git_enabled` so "Git off" is distinguishable from "Git on, no commits yet".
     */
    val enabled: Boolean

    /**
     * Commits the EXACT [bytes] (the write-pipeline hook bytes — filter-free and disk-independent, never a disk
     * re-read) at [path] as ONE new commit, returning the recorded [Commit] (null only for the no-op
     * adapter). An external edit between the write-pipeline CAS and this call cannot alter what is committed: the
     * blob is staged from these bytes via `hash-object --stdin`, not from the working tree.
     *
     * The commit captures the current HEAD tree plus EXACTLY this one path; a re-commit of bytes that
     * already equal HEAD's tree is a no-op (the existing HEAD [Commit] is returned, nothing new written),
     * which is what makes the write-pipeline recovery re-commit idempotent. [author]/[committer] default to the
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
     * Readies the backing store so reads and commits operate on the EXACT content root. Called ONCE at
     * startup, AFTER the data-dir lock is held and BEFORE the first rebuild. NoOp: no-op. Git:
     * ensures the content-root repo exists, creating a NESTED repo at CONTENT_DIR when it has no own
     * `.git` (never advancing an ancestor checkout) — idempotent.
     *
     * Why a distinct step from the lazy first-commit init: the startup `rebuild()` reads (`lastCommits`)
     * BEFORE any save commits, and `git -C workTree log` walks UP to an ancestor `.git` when CONTENT_DIR
     * has none — so a forced-on content root with no own repo would either abort serve (plain dir →
     * operational failure) or silently read the wrong ancestor repo. Creating the content-root repo here
     * — after the lock validates DATA_DIR (never touch it before the lock) — closes both holes.
     */
    fun prepare()

    /**
     * Fails fast (with an operator-actionable message) when this provider cannot operate — for the Git
     * adapter, when the `git` binary is absent. Run at startup BEFORE any commit can fire. The no-op
     * adapter is always ready.
     */
    fun gateCheck()

    /**
     * The repo's current HEAD as a full object id, or null (no repo, no commits yet, a SHALLOW repo, any
     * failure). The GIT absence oracle (C4) brackets a pass between two reads of this and diffs the range;
     * everything about it fails CLOSED, so an inconclusive answer is null, never a licence.
     */
    fun currentHead(): String?

    /** Whether [ancestor] is an ancestor of [descendant]. FALSE on ANY failure — an unknown sha, a non-zero exit. */
    fun isAncestor(ancestor: String, descendant: String): Boolean

    /**
     * The `.md` tree paths DELETED between commits [from] and [to] (renames NOT resolved — a rename is a `D`
     * of its old path, refuted later by the witness that read it under its new name), or null on ANY failure
     * (a diff we could not fully understand is not a smaller diff, and never "no deletions").
     */
    fun deletedIn(from: String, to: String): Set<TreePath>?
}

/** One commit's recorded identity + timestamps + message (read shape; the history layer owns its evolution). */
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

/**
 * A history BACKEND operation failed (ADR-0006's fail-loud rule) — the PORT's operational failure type.
 *
 * It lives here, in the domain, rather than in the git adapter for one concrete reason: a DOMAIN consumer has
 * to be able to CATCH it, and `IndexBuilder`'s root-loss classifier is exactly such a consumer. Every history
 * call is rooted at a work tree, so a work tree that has VANISHED makes the backend exit non-zero and raise
 * this — it is the carrier a lost root produces out of the history collaborator, the way an `IOException` is
 * the carrier it produces out of the content store. A classifier that could not see it would let a vanished
 * root escape unmarked and keep serving its carried content.
 *
 * The git adapter's `GitCommandException` is the concrete subtype, so every existing throw site, catch and
 * `shouldThrow<GitCommandException>` is untouched.
 */
open class HistoryCommandException(message: String) : RuntimeException(message)
