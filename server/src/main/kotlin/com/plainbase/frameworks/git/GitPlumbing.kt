package com.plainbase.frameworks.git

import com.plainbase.domain.history.CommitIdentity
import kotlin.time.Instant

/**
 * The ADR-0006 single git-plumbing chokepoint (C5 MUST-FIX): the literal `hash-object`/`update-index`/
 * `write-tree`/`commit-tree`/`update-ref` primitives, shared VERBATIM by [GitCliHistoryProvider.commit]
 * (one path per save) and [GitBundleDr]'s boot reconcile (N paths after a bundle restore) - so the two
 * call sites can never drift apart; the Amendment-2 byte-fidelity golden (`GitBundleDrNativeTest`)
 * proves the reconcile path inherits the exact same filter-free byte-fidelity as the per-save path.
 * Never porcelain `git add`/`git commit`. Every function operates over a caller-seeded temp index
 * (`GIT_INDEX_FILE` in [indexEnv]) - this object owns no state and spawns no process outside [exec].
 */
internal object GitPlumbing {

    /** Seeds a temp index from `read-tree <baseSha>`, or `read-tree --empty` when [baseSha] is null (unborn HEAD). */
    fun seedIndex(exec: GitExecutor, indexEnv: Map<String, String>, baseSha: String?) {
        val seed = if (baseSha == null) listOf("read-tree", "--empty") else listOf("read-tree", baseSha)
        exec.run(seed, indexEnv).orThrowPlumbing("seed temp index")
    }

    /**
     * Stages [bytes] at [repoRelativePath], filter-free: `hash-object --no-filters -w --stdin` (no
     * `--path`, so no attribute lookup fires at all) then `update-index --add --cacheinfo`. Returns the
     * blob SHA.
     */
    fun stageBlob(exec: GitExecutor, indexEnv: Map<String, String>, repoRelativePath: String, bytes: ByteArray): String {
        val blobResult = exec.run(listOf("hash-object", "--no-filters", "-w", "--stdin"), indexEnv, stdin = bytes)
        blobResult.orThrowPlumbing("hash-object")
        val blobSha = GitExecutor.parseSha(blobResult.stdout) ?: error("hash-object did not return a SHA: ${blobResult.stderr}")
        exec.run(listOf("update-index", "--add", "--cacheinfo", "100644,$blobSha,$repoRelativePath"), indexEnv)
            .orThrowPlumbing("update-index --add")
        return blobSha
    }

    /**
     * Removes [repoRelativePath] from the temp index (`update-index --force-remove`) - an index-only op,
     * never a working-tree delete. [repoRelativePath] comes from [GitBundleDr]'s `ls-tree` walk over a
     * bucket-shipped bundle tree (HOSTILE input: `fetch.fsckObjects` does not reject a validly-tracked
     * path that happens to start with `-`, e.g. `--stdin`), so a literal `--` end-of-options separator is
     * REQUIRED before it — without it, such a path is reinterpreted as a flag (argument injection).
     */
    fun removeFromIndex(exec: GitExecutor, indexEnv: Map<String, String>, repoRelativePath: String) {
        exec.run(listOf("update-index", "--force-remove", "--", repoRelativePath), indexEnv)
            .orThrowPlumbing("update-index --force-remove")
    }

    /** `write-tree` over the temp index; the resulting tree SHA. */
    fun writeTree(exec: GitExecutor, indexEnv: Map<String, String>): String {
        val result = exec.run(listOf("write-tree"), indexEnv)
        result.orThrowPlumbing("write-tree")
        return GitExecutor.parseSha(result.stdout) ?: error("write-tree did not return a SHA: ${result.stderr}")
    }

    /** `commit-tree [-p parentSha] -m message` under [identityEnv]; the new commit SHA. `parentSha` null => a root commit. */
    fun commitTree(
        exec: GitExecutor,
        indexEnv: Map<String, String>,
        treeSha: String,
        parentSha: String?,
        identityEnv: Map<String, String>,
        message: String,
    ): String {
        val args = buildList {
            add("commit-tree")
            add(treeSha)
            if (parentSha != null) {
                add("-p")
                add(parentSha)
            }
            add("-m")
            add(message)
        }
        val result = exec.run(args, indexEnv + identityEnv)
        result.orThrowPlumbing("commit-tree")
        return GitExecutor.parseSha(result.stdout) ?: error("commit-tree did not return a SHA: ${result.stderr}")
    }

    /** `update-ref` CAS: advances [branchRef] (or, when null, detached `HEAD` via `--no-deref`) from [oldValue] to [newCommit]. */
    fun updateRef(exec: GitExecutor, branchRef: String?, newCommit: String, oldValue: String): GitResult =
        if (branchRef != null) {
            exec.run(listOf("update-ref", branchRef, newCommit, oldValue))
        } else {
            exec.run(listOf("update-ref", "--no-deref", "HEAD", newCommit, oldValue))
        }

    /** The `GIT_AUTHOR_*`/`GIT_COMMITTER_*` env pins for [commitTree], both stamped at the same [now]. */
    fun identityEnv(author: CommitIdentity, committer: CommitIdentity, now: Instant): Map<String, String> {
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

    private fun GitResult.orThrowPlumbing(step: String) {
        if (!ok) throw GitCommandException(step, exitCode, stderr)
    }
}
