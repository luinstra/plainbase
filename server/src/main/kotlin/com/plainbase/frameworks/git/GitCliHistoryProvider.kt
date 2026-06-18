package com.plainbase.frameworks.git

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.Commit
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.history.FileDiff
import com.plainbase.domain.history.HistoryProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The Git-on adapter (ADR-0006, chunk W4): ONE new commit per save over the same working tree the
 * content store serves, via pure plumbing on a per-operation temp index — never porcelain `git commit`,
 * never the live index. The recipe (POST round-3, empirically verified on Git 2.54.0) is the only
 * correct sequence; see the W4 addendum §3 D-3 for the full provenance.
 *
 * [exec] is the hermetic chokepoint; [workTree] is the content root the repo must be rooted EXACTLY at
 * (so init never advances an ancestor checkout when CONTENT_DIR sits inside one); [gitHome] (created
 * lazily at the first commit — P1-3) holds the isolated config home and the per-op temp indexes; [clock]
 * feeds reproducible commit timestamps; the optional [maintenance] dispatcher runs auto-GC OFF the W1
 * monitor (F3, defaulted so tests stub/observe).
 */
class GitCliHistoryProvider(
    private val exec: GitExecutor,
    private val workTree: Path,
    private val gitHome: Path,
    private val defaultAuthor: CommitIdentity,
    private val defaultCommitter: CommitIdentity,
    private val clock: Clock,
    // The repo-relative path to STAGE in git for a TreePath — raw-on-disk-name-preserving (r6b), so a
    // non-NFC on-disk file is committed at its real path, not the NFC phantom `path.value`. Injected as a
    // function (loose coupling, like `maintenance`) so the provider never depends on the whole ContentStore
    // port; wired to ContentStore::resolveRepoRelativePath. Defaults to TreePath.value for tests/no-store.
    private val repoPath: (TreePath) -> String = { it.value },
    private val maintenance: (() -> Unit)? = null,
) : HistoryProvider {

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    override fun commit(path: TreePath, bytes: ByteArray, author: CommitIdentity?, committer: CommitIdentity?): Commit {
        ensureRepo()

        // The repo-relative path to stage (r6b): the RAW on-disk name, not the NFC `path.value`, so the
        // committed git path matches the real file on a normalization-preserving filesystem. Resolved ONCE.
        val repoRelativePath = repoPath(path)

        // (2) Capture the HEAD SHA and its fully-qualified ref in ONE git invocation (r6a) so they cannot
        // desync: `rev-parse HEAD --symbolic-full-name HEAD` reads HEAD once and prints the object id on line
        // 1 and the symbolic full name on line 2 (`refs/heads/<branch>`, or literal `HEAD` when detached).
        // Two separate reads let an external `git checkout` to a same-tip branch slip between them, pairing
        // the original tip with the NEW branch — then the CAS would advance the wrong branch.
        val head = exec.run(listOf("rev-parse", "HEAD", "--symbolic-full-name", "HEAD"))
        val oldHead = if (head.ok) GitExecutor.parseSha(head.stdout) else null
        val unborn = oldHead == null
        // The atomic command FAILS entirely on an unborn HEAD (rev-parse HEAD errors before printing the
        // symbolic name), so capture the branch ref separately there — `symbolic-ref HEAD` works on an unborn
        // HEAD (no race to lose: there is no commit yet to desync from). When born, parse the ref from the
        // atomic result. Null ⇒ detached.
        val branchRef = if (unborn) {
            exec.run(listOf("symbolic-ref", "HEAD")).let { if (it.ok) it.stdoutText.trim().takeIf(String::isNotEmpty) else null }
        } else {
            parseHeadRef(head, oldHead)
        }

        val indexFile = gitHome.resolve("idx-" + java.util.UUID.randomUUID())
        val indexEnv = mapOf("GIT_INDEX_FILE" to indexFile.toString())
        try {
            // (3) Seed the temp index UNIFORMLY — never "skip read-tree" (a pre-created empty file is not
            // a valid index → update-index fails `index file smaller than expected`, F2). Seed from the
            // CAPTURED oldHead, never live `HEAD` (P1): if the user `git checkout`s another branch between
            // step 2 and here, a `read-tree HEAD` would import THAT branch's tree, then we'd commit it onto
            // the captured branch — history corruption. Pinning to oldHead keeps the whole op on one snapshot.
            val seed = if (unborn) listOf("read-tree", "--empty") else listOf("read-tree", oldHead!!)
            exec.run(seed, indexEnv).orThrow("seed temp index")

            // (4) Stage the EXACT hook bytes from stdin (filter-free; no --path, so no attribute lookup
            // fires at all — clean filters, LFS, working-tree-encoding are mooted by construction, P0-2).
            val blobResult = exec.run(listOf("hash-object", "--no-filters", "-w", "--stdin"), indexEnv, stdin = bytes)
            blobResult.orThrow("hash-object")
            val blobSha = GitExecutor.parseSha(blobResult.stdout) ?: error("hash-object did not return a SHA: ${blobResult.stderr}")

            exec.run(listOf("update-index", "--add", "--cacheinfo", "100644,$blobSha,$repoRelativePath"), indexEnv).orThrow("update-index")

            // (5) Write the tree; idempotency oracle — if it equals the CAPTURED base's tree (born only),
            // this save's bytes already are oldHead: no-op, return the existing commit (makes recovery
            // re-commits free). Compare against `${oldHead}^{tree}`, never live `HEAD^{tree}` (P1) — same
            // snapshot-pinning as the seed, so a mid-op branch switch cannot skew the compare.
            val treeResult = exec.run(listOf("write-tree"), indexEnv)
            treeResult.orThrow("write-tree")
            val newTree = GitExecutor.parseSha(treeResult.stdout) ?: error("write-tree did not return a SHA: ${treeResult.stderr}")
            if (!unborn) {
                val baseTree = exec.run(listOf("rev-parse", "$oldHead^{tree}")).let { if (it.ok) GitExecutor.parseSha(it.stdout) else null }
                if (baseTree == newTree) {
                    // A clean no-op only if HEAD is STILL the captured base (P2-3, mirrors the r6d/update-ref
                    // CAS philosophy): the tree compare is against the STALE oldHead, so if an external actor
                    // advanced HEAD after the capture (e.g. committed a different version of this path), a
                    // bare "no-op success" would let W1 clear the dirty row and SILENTLY DROP the user's save
                    // relative to the new tip. Re-read HEAD; if it moved, raise so the orThrow path leaves the
                    // page dirty and reconcile retries against the fresh tip (where the bytes are a real diff).
                    val currentHead = exec.run(listOf("rev-parse", "--verify", "HEAD")).let {
                        if (it.ok) GitExecutor.parseSha(it.stdout) else null
                    }
                    if (currentHead != oldHead) {
                        throw GitCommandException(
                            "no-op race",
                            -1,
                            "HEAD advanced from $oldHead to $currentHead during the save; not a clean no-op",
                        )
                    }
                    logger.debug { "commit of ${path.value} is a no-op (tree unchanged); returning existing HEAD" }
                    // Repair the live index here too (crash-then-reconcile hits this path: HEAD already has the
                    // bytes, so no new commit — but the live index still shows the page deleted/modified until
                    // we stage it). Same guards as the real-commit path; the expected HEAD is the captured base.
                    syncLiveIndex(branchRef, oldHead, blobSha, repoRelativePath)
                    return show(oldHead)
                }
            }

            // (6) commit-tree with identity env; the new SHA is commit-tree's OWN stdout (F7) — never a
            // post-update-ref rev-parse HEAD (a concurrent external commit would yield the wrong SHA).
            val now = clock.now()
            val identityEnv = identityEnv(author ?: defaultAuthor, committer ?: defaultCommitter, now)
            val commitArgs = buildList {
                add("commit-tree")
                add(newTree)
                if (!unborn) {
                    add("-p")
                    add(oldHead)
                }
                add("-m")
                add(commitMessage(repoRelativePath))
            }
            val commitResult = exec.run(commitArgs, indexEnv + identityEnv)
            commitResult.orThrow("commit-tree")
            val newCommit = GitExecutor.parseSha(commitResult.stdout) ?: error("commit-tree did not return a SHA: ${commitResult.stderr}")

            // (7) update-ref with an old-value CAS: a HEAD that moved out-of-band since step 2 fails here
            // (W1 leaves the page dirty) rather than overwriting a concurrent commit (P0-1 ref-CAS). The
            // target is the branch ref captured at step 2 (P2-1), not a fresh symbolic-ref read.
            updateRef(branchRef, newCommit, oldHead).orThrow("update-ref")

            // (7b) Porcelain-parity: the staging ran under the per-op temp index, so the LIVE .git/index is
            // untouched — leaving the saved path stale (absent on a fresh repo) means an external `git status`
            // shows a phantom deletion. Surgically stage ONLY the just-committed blob into the live index
            // (P2-3: NOT `read-tree HEAD`, which would wipe the user's unrelated staged work). The working
            // tree already matches (ContentStore wrote the bytes), so `git status` for the saved path is then
            // clean. Best-effort, NON-FATAL (the commit already landed); skipped if HEAD switched branches or
            // advanced past our commit (r6d) — the expected HEAD is the commit we just created.
            syncLiveIndex(branchRef, newCommit, blobSha, repoRelativePath)

            // (8) Hydrate from the SHA we created, keyed on that SHA (F7).
            val commit = show(newCommit)

            // (9) Best-effort auto-maintenance OFF the W1 monitor, non-fatal (F3): plumbing skips the
            // auto-GC porcelain runs, so loose objects would grow unbounded without this.
            dispatchMaintenance()
            return commit
        } finally {
            runCatching { indexFile.deleteRecursively() }
        }
    }

    override fun lastCommits(paths: List<TreePath>): Map<TreePath, Commit> = throw NotImplementedError("W5")

    override fun log(path: TreePath, limit: Int?): List<Commit> = throw NotImplementedError("W5")

    override fun diff(from: String, to: String, path: TreePath): FileDiff = throw NotImplementedError("W5")

    override fun gateCheck() {
        val version = exec.run(listOf("--version"))
        if (!version.ok) {
            throw GitUnavailableException(
                "Git mode is on but the `git` binary is unavailable (${version.stderr.ifBlank { "exit ${version.exitCode}" }}). " +
                    "Install git and ensure it is on PATH, or set PLAINBASE_GIT_ENABLED=false to run without history.",
            )
        }
        // The binary is present, but `--version` never opens the worktree. When a repo IS present, validate
        // ACCESS to it so an inaccessible repo (Docker uid-mismatch `fatal: detected dubious ownership`,
        // permissions, a corrupt `.git`) aborts at startup — BEFORE any save writes content and then errors
        // in the hook, leaving the page dirty. Skip when `.git` is absent (force-on; the repo is created at
        // the first commit, so there is nothing to validate yet).
        if (Files.exists(workTree.resolve(".git"))) {
            val access = exec.run(listOf("rev-parse", "--is-inside-work-tree"))
            if (!(access.ok && access.stdoutText.trim() == "true")) {
                throw GitUnavailableException(
                    "Git mode is on but the repository at $workTree is not accessible " +
                        "(${access.stderr.ifBlank { "exit ${access.exitCode}" }}). Plainbase runs git with a hermetic " +
                        "config (global/system config nulled), so `safe.directory` cannot fix this — instead change " +
                        "ownership so the server process's user owns $workTree (clears git's dubious-ownership check; the " +
                        "usual cause is a container/host UID mismatch), or set PLAINBASE_GIT_ENABLED=false to run without history.",
                )
            }
        }
    }

    /**
     * Ensures a repo rooted EXACTLY at [workTree] (D-8) and the git-home + temp-index parent are present
     * (lazy, first-commit — P1-3). The check is `Files.exists(workTree/.git)` — NOT `rev-parse
     * --is-inside-work-tree`: when CONTENT_DIR is a SUBDIRECTORY of an outer checkout, rev-parse walks up
     * to the ancestor `.git` and reports "true", so the plumbing would silently advance the surrounding
     * repo. `Files.exists` catches both a real `.git` dir and a `.git`-as-a-file linked worktree; its
     * absence means we `git init` a nested repo at CONTENT_DIR (`-C workTree` then uses CONTENT_DIR/.git).
     */
    private fun ensureRepo() {
        Files.createDirectories(gitHome)
        if (!Files.exists(workTree.resolve(".git"))) {
            exec.run(listOf("init")).orThrow("git init")
        }
    }

    /**
     * update-ref CAS: on a branch advance `<ref> <new> <old>`; the unborn create-form uses the zero oid.
     * [branchRef] is the FULLY-QUALIFIED ref (`refs/heads/<branch>`) captured ATOMICALLY with [oldHead] at
     * step 2 (r6a) — null for a detached HEAD. Never re-read here.
     *
     * For a detached HEAD we target `HEAD` with `--no-deref` (r6c): without it, `update-ref HEAD`
     * DEREFERENCES HEAD, so if an external checkout re-attached HEAD to a branch (same commit) since step 2,
     * we would advance THAT branch instead of the detached HEAD. `--no-deref` updates HEAD itself.
     */
    private fun updateRef(branchRef: String?, newCommit: String, oldHead: String?): GitResult {
        val oldValue = oldHead ?: zeroOid()
        return if (branchRef != null) {
            exec.run(listOf("update-ref", branchRef, newCommit, oldValue))
        } else {
            exec.run(listOf("update-ref", "--no-deref", "HEAD", newCommit, oldValue))
        }
    }

    /**
     * Parses the fully-qualified HEAD ref from a `rev-parse HEAD --symbolic-full-name HEAD` result (r6a):
     * the symbolic name is the LAST non-SHA, non-blank stdout line. `refs/heads/<branch>` → that ref;
     * literal `HEAD` (detached) or no symbolic line (unborn / command failed) → null (detached/HEAD target).
     */
    private fun parseHeadRef(head: GitResult, oldHead: String?): String? {
        if (!head.ok) return null
        val ref = head.stdoutText.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() && it != oldHead }
        return ref?.takeIf { it.startsWith("refs/") }
    }

    /** The all-zeros object id matching the repo's object format (r6e) — 40 zeros for sha1, 64 for sha256. */
    private fun zeroOid(): String {
        val format = exec.run(listOf("rev-parse", "--show-object-format")).let { if (it.ok) it.stdoutText.trim() else "" }
        return if (format == "sha256") "0".repeat(64) else "0".repeat(40)
    }

    /** Hydrates a [Commit] from a SHA via one `git show -s` read (the SHA is the key, F7). */
    private fun show(sha: String): Commit {
        val result = exec.run(listOf("show", "-s", "--format=$SHOW_FORMAT", sha))
        result.orThrow("show -s")
        // Fields are NUL-separated so any byte in a name/email/message parses unambiguously (LC_ALL=C,
        // quotePath=false). The delimiter is Char(0) — a NUL written as code, never a literal NUL byte in
        // the source (which would mark this .kt binary to git's diff/blame).
        val fields = result.stdoutText.removeSuffix("\n").split(Char(0))
        return Commit(
            sha = fields[0],
            author = CommitIdentity(name = fields[1], email = fields[2]),
            committer = CommitIdentity(name = fields[4], email = fields[5]),
            authorTime = Instant.fromEpochSeconds(fields[3].toLong()),
            committerTime = Instant.fromEpochSeconds(fields[6].toLong()),
            message = fields[7].removeSuffix("\n"),
        )
    }

    /**
     * Surgically stages the just-committed blob into the LIVE index after a successful commit (porcelain
     * parity, P1-A) — touching ONLY the saved path, so the user's unrelated staged work is left intact
     * (P2-3). Best-effort, NON-FATAL: the commit already landed.
     *
     * Two guards make the stage safe against external concurrency:
     *  - branch (P2): the live index belongs to whatever ref HEAD names NOW; if the user `git checkout`ed
     *    away from [capturedBranch] since step 2, staging here would land our blob on the WRONG branch.
     *  - HEAD identity (r6d): even on the SAME branch, another actor may have advanced HEAD past our commit
     *    after update-ref (or before the no-op return). Staging our (now-older) blob against a NEWER HEAD
     *    would write a stale revert of the path. So stage ONLY when HEAD still names exactly [expectedHead]
     *    (the commit whose tree this blob reflects).
     */
    private fun syncLiveIndex(capturedBranch: String?, expectedHead: String, blobSha: String, repoRelativePath: String) {
        val head = exec.run(listOf("rev-parse", "HEAD", "--symbolic-full-name", "HEAD"))
        val currentHead = if (head.ok) GitExecutor.parseSha(head.stdout) else null
        val currentBranch = parseHeadRef(head, currentHead)
        if (currentBranch != capturedBranch || currentHead != expectedHead) {
            logger.debug {
                "skipping live-index sync of $repoRelativePath: HEAD is $currentHead@$currentBranch, expected $expectedHead@$capturedBranch"
            }
            return
        }
        // Stage the SAME raw repo-relative path the commit recipe used (r6b), never the NFC TreePath.value.
        val result = exec.run(listOf("update-index", "--add", "--cacheinfo", "100644,$blobSha,$repoRelativePath"))
        if (!result.ok) logger.warn { "live-index sync of $repoRelativePath failed (commit already landed; non-fatal): ${result.stderr}" }
    }

    private fun dispatchMaintenance() {
        val task = maintenance ?: { runAutoMaintenance(exec) }
        try {
            task()
        } catch (e: Exception) {
            logger.warn(e) { "auto-maintenance failed; the save still committed (non-fatal)" }
        }
    }

    private fun identityEnv(author: CommitIdentity, committer: CommitIdentity, now: Instant): Map<String, String> {
        val date = "@${now.epochSeconds} +0000"
        return mapOf(
            "GIT_AUTHOR_NAME" to author.name,
            "GIT_AUTHOR_EMAIL" to author.email,
            "GIT_AUTHOR_DATE" to date,
            "GIT_COMMITTER_NAME" to committer.name,
            "GIT_COMMITTER_EMAIL" to committer.email,
            "GIT_COMMITTER_DATE" to date,
        )
    }

    private fun GitResult.orThrow(step: String) {
        if (!ok) throw GitCommandException(step, exitCode, stderr)
    }

    private fun commitMessage(repoRelativePath: String): String = "Update $repoRelativePath"

    companion object {
        private val logger = KotlinLogging.logger {}

        // NUL-separated so a name/email/message containing any whitespace or newline parses unambiguously.
        private const val SHOW_FORMAT = "%H%x00%an%x00%ae%x00%at%x00%cn%x00%ce%x00%ct%x00%B"
    }
}

/**
 * Best-effort git auto-maintenance: `maintenance run --auto` on modern git, falling back to `gc --auto`
 * on hosts predating `git maintenance` (< 2.30). Shared by the provider's default dispatcher and the
 * production off-thread wiring so the fallback is live in BOTH (P2-C). Best-effort; the caller owns
 * threading + non-fatal handling.
 */
internal fun runAutoMaintenance(exec: GitExecutor) {
    val auto = exec.run(listOf("maintenance", "run", "--auto", "--quiet"))
    if (!auto.ok) exec.run(listOf("gc", "--auto"))
}

/** A failed git plumbing step in the commit recipe — surfaces the step + exit + stderr to the W1 catch. */
class GitCommandException(step: String, exitCode: Int, stderr: String) :
    RuntimeException("git $step failed (exit $exitCode): ${stderr.ifBlank { "<no stderr>" }}")

/** The actionable startup gate-check failure when Git mode is on but the binary is missing. */
class GitUnavailableException(message: String) : RuntimeException(message)
