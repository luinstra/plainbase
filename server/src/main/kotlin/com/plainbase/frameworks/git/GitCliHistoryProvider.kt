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

    override val enabled: Boolean = true

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

    /**
     * The newest commit touching each requested path, batched into as few `git log` walks as the argv
     * budget allows (never one query per path): each walk runs `--name-only` over a BATCH of paths,
     * newest→oldest along first-parent history, recording the first commit seen to touch each
     * still-unresolved path in that batch and stopping once the batch is resolved. A path with no commit
     * is simply absent. The keys are the requested [TreePath]s; the on-disk repo path (r6b,
     * raw-name-preserving) bridges a `git log` filename to the [TreePath] the caller asked for.
     * Unborn HEAD / empty repo → empty.
     *
     * Why BATCH and not one walk: `IndexBuilder.rebuild` passes the WHOLE corpus, so a single
     * `git log … -- <every page path>` puts every path on argv and a large content tree blows the OS
     * `ARG_MAX` (`Argument list too long` → Git-mode startup/rescan dies). Git 2.54.x `git log` does NOT
     * accept `--pathspec-from-file` (empirically verified — `fatal: unrecognized argument`), so the
     * pathspecs cannot move to stdin; instead each invocation caps its total pathspec bytes at
     * [MAX_PATHSPEC_BYTES_PER_BATCH], far under any ARG_MAX. The result is O(ceil(N / batch)) processes,
     * independent of per-PAGE count for normal trees (a few thousand `.md` paths fit in one batch); the
     * "never one query per path" intent stands. Each batch's paths are disjoint, so per-batch walks
     * resolve independently and the merged map is exactly the single-walk result.
     */
    override fun lastCommits(paths: List<TreePath>): Map<TreePath, Commit> {
        if (paths.isEmpty()) return emptyMap()
        val byRepoPath = paths.associateBy(repoPath)
        val resolved = LinkedHashMap<TreePath, Commit>()
        for (batch in batchPathspecs(byRepoPath.keys)) {
            val args = listOf("log", "-z") + FIRST_PARENT + listOf("--name-only", "--format=$LOG_FORMAT", "--") + batch
            val result = exec.run(args)
            if (!result.ok) return emptyMapOrThrow(result, "lastCommits")
            var pendingInBatch = batch.size
            for ((commit, files) in parseLogWithNames(result.stdoutText)) {
                for (file in files) {
                    val touched = byRepoPath[file] ?: continue
                    if (touched !in resolved) {
                        resolved[touched] = commit
                        pendingInBatch--
                    }
                }
                // Parse-only early exit: git has ALREADY produced the full walk (GitExecutor buffers all of
                // stdout before we parse), so this stops iterating parsed records — it does NOT truncate git's
                // walk. The full read is intentional (D-3): an old, infrequently-touched page's last commit may
                // be deep in history, so the walk cannot be safely capped without missing it.
                if (pendingInBatch == 0) break
            }
        }
        return resolved
    }

    /**
     * Splits repo-relative paths into batches whose total pathspec bytes stay under
     * [MAX_PATHSPEC_BYTES_PER_BATCH], so no single `git log … -- <paths>` invocation overflows the OS
     * `ARG_MAX`. A path longer than the budget on its own gets its own (over-budget) batch rather than
     * being silently dropped — git itself, not us, enforces the hard ceiling for that pathological case.
     */
    private fun batchPathspecs(repoPaths: Collection<String>): List<List<String>> {
        val batches = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var currentBytes = 0
        for (path in repoPaths) {
            val bytes = path.toByteArray().size + 1 // +1 for the inter-arg NUL the OS counts per argv entry
            if (current.isNotEmpty() && currentBytes + bytes > MAX_PATHSPEC_BYTES_PER_BATCH) {
                batches += current
                current = mutableListOf()
                currentBytes = 0
            }
            current += path
            currentBytes += bytes
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    /** The commit history of [path], newest first (along first-parent history), capped at [limit] when given. */
    override fun log(path: TreePath, limit: Int?): List<Commit> {
        val args = buildList {
            add("log")
            add("-z")
            addAll(FIRST_PARENT)
            limit?.let { add("--max-count=$it") }
            add("--format=$LOG_FORMAT")
            add("--")
            add(repoPath(path))
        }
        val result = exec.run(args)
        if (!result.ok) return emptyListOrThrow(result, "log")
        return chunkFields(result.stdoutText).map(::commitFrom)
    }

    override fun diff(from: String, to: String, path: TreePath): FileDiff {
        // `--no-ext-diff --no-textconv` (before the refs) disarm a repo-local `diff.external`/custom diff
        // driver/`textconv` from `.git/config` or `.gitattributes` — otherwise hitting the diff read-path
        // would run that helper as the server user (the read-path analog of W4's hostile-`.gitattributes`
        // commit paranoia: arbitrary command execution from attacker-controlled repo config).
        val result = exec.run(listOf("diff", "--no-ext-diff", "--no-textconv", from, to, "--", repoPath(path)))
        // A bad/unknown ref is a CLIENT error the route maps to 404 (W5 P2): distinguish it from an
        // OPERATIONAL failure (timeout, corrupt/inaccessible repo, unsupported flag) by git's bad-object
        // stderr signature, and let every OTHER non-zero exit propagate as a GitCommandException (→ 500 via
        // the route's default error path). Collapsing operational failures to 404 would hide them as "no diff".
        if (!result.ok && isUnknownRevision(result)) {
            throw UnknownRevisionException("diff $from..$to", result.exitCode, result.stderr)
        }
        result.orThrow("diff $from..$to")
        return FileDiff(from = from, to = to, path = path, unifiedDiff = result.stdoutText)
    }

    /**
     * Readies the content-root repo at startup (W5 P1): a NESTED `git init` at [workTree] when it has no
     * own `.git`, never advancing an ancestor checkout (the [ensureRepo] `Files.exists(workTree/.git)`
     * guard). Called AFTER the data-dir lock (P1-3) and BEFORE the first rebuild's `lastCommits` read, so
     * a forced-on content root never aborts serve (plain dir → operational failure) nor reads the wrong
     * ancestor repo. Idempotent — [commit] still calls [ensureRepo] too (harmless belt-and-suspenders).
     */
    override fun prepare() = ensureRepo()

    override fun gateCheck() {
        val version = exec.run(listOf("--version"))
        if (!version.ok) {
            throw GitUnavailableException(
                "Git mode is on but the `git` binary is unavailable (${version.stderr.ifBlank { "exit ${version.exitCode}" }}). " +
                    "Install git and ensure it is on PATH, or set PLAINBASE_GIT_ENABLED=false to run without history.",
            )
        }
        // The read path (`log`/`lastCommits`) passes `--diff-merges=first-parent`, which git only learned in
        // 2.31.0; on an older-but-present git every read exits non-zero and Git-mode startup (`rebuild` →
        // `lastCommits`) aborts serve AFTER this gate passes. Validate the version floor here so it fails LOUD
        // and actionable at the gate instead. An UNPARSEABLE `git version` line passes with a logged warning
        // rather than false-failing a perfectly modern git whose banner we did not anticipate.
        val parsed = parseGitVersion(version.stdoutText)
        if (parsed == null) {
            logger.warn { "could not parse git version from '${version.stdoutText.trim()}'; skipping the version-floor check" }
        } else {
            val (major, minor) = parsed
            if (major < MIN_GIT_MAJOR || (major == MIN_GIT_MAJOR && minor < MIN_GIT_MINOR)) {
                throw GitUnavailableException(
                    "Git mode requires git >= $MIN_GIT_MAJOR.$MIN_GIT_MINOR (for --diff-merges=first-parent, used by " +
                        "Plainbase history reads); found $major.$minor. Upgrade git, or set PLAINBASE_GIT_ENABLED=false " +
                        "to run without history.",
                )
            }
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
        return commitFrom(result.stdoutText.removeSuffix("\n").split(Char(0)))
    }

    /**
     * Builds a [Commit] from its NUL-separated field tuple (`$SHOW_FORMAT`/`$LOG_FORMAT` order): the message
     * (`%B`, last) keeps any embedded byte verbatim — only its single trailing newline is trimmed. NUL is
     * the one byte git forbids in a commit object, so it can never appear inside a field and the framing is
     * collision-proof (the §6 robustness choice over a printable record separator).
     */
    private fun commitFrom(fields: List<String>): Commit = Commit(
        sha = fields[0],
        author = CommitIdentity(name = fields[1], email = fields[2]),
        committer = CommitIdentity(name = fields[4], email = fields[5]),
        authorTime = Instant.fromEpochSeconds(fields[3].toLong()),
        committerTime = Instant.fromEpochSeconds(fields[6].toLong()),
        message = fields[7].removeSuffix("\n"),
    )

    /**
     * Tokenizes a `git log -z --format=$LOG_FORMAT` stream (no `--name-only`) into per-commit field tuples.
     * `-z` NUL-terminates each record AND `%x00` separates fields, so the whole stream is NUL-delimited
     * tokens that chunk cleanly into [COMMIT_FIELD_COUNT]-field records. The trailing empty token (after the
     * final record's terminator) leaves a short final chunk, which is dropped. Empty history → no records.
     */
    private fun chunkFields(stdout: String): List<List<String>> =
        stdout.split(Char(0)).chunked(COMMIT_FIELD_COUNT).filter { it.size == COMMIT_FIELD_COUNT }

    /**
     * Parses a `git log -z --name-only` stream into (commit, touched-files) pairs. Under `-z`, each record
     * is its [COMMIT_FIELD_COUNT] NUL-separated fields, then a SINGLE separator `\n` (the format/name-list
     * boundary git emits once per record), then the touched filenames as further NUL-separated tokens. So
     * ONLY the FIRST filename token carries that leading `\n`; every later filename token is the raw path.
     * A record ends where the next full object-id token begins, so we read the fields, then consume filename
     * tokens until the next SHA — robust to a body that itself contains control bytes (NUL excepted, which
     * git forbids).
     *
     * The separator `\n` is stripped from the FIRST filename token ONLY — never with a blanket
     * `removePrefix` on every token, which would corrupt the legitimate leading `\n` of a control-char
     * filename (in-surface per the freeze-surface policy) that is NOT the first file of a multi-file commit.
     *
     * Empties are NOT filtered at the split (revision BLOCKING 2): an empty message (`--allow-empty-message`)
     * or a blank identity field emits an empty token; dropping it at the root would shift every later field
     * and misattribute commits. The fixed [COMMIT_FIELD_COUNT] stride keeps every metadata field intact;
     * empty tokens are skipped ONLY in the inner filename walk (a real filename is never empty). A content
     * path always contains `/` or `.` (never bare hex), so [SHA_TOKEN] can never mistake a filename for the
     * next record boundary — see [SHA_TOKEN].
     */
    private fun parseLogWithNames(stdout: String): List<Pair<Commit, List<String>>> {
        val tokens = stdout.split(Char(0))
        val records = mutableListOf<Pair<Commit, List<String>>>()
        var i = 0
        while (i + COMMIT_FIELD_COUNT <= tokens.size && SHA_TOKEN.matches(tokens[i])) {
            val commit = commitFrom(tokens.subList(i, i + COMMIT_FIELD_COUNT))
            i += COMMIT_FIELD_COUNT
            val files = mutableListOf<String>()
            var firstFile = true
            while (i < tokens.size && !SHA_TOKEN.matches(tokens[i])) {
                // The format/name-list separator `\n` prefixes the FIRST filename token only; later tokens
                // are raw, so a filename legitimately starting with `\n` keeps it (strip the separator, not
                // the name's own leading byte).
                val name = if (firstFile) tokens[i].removePrefix("\n") else tokens[i]
                name.takeIf(String::isNotEmpty)?.let {
                    files += it
                    firstFile = false
                }
                i++
            }
            records += commit to files
        }
        return records
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

    /**
     * Classifies a failed read-path `git log`: an unborn HEAD / empty repo is the one BENIGN non-zero exit
     * ("no history yet" → empty), but a timeout, corrupt/inaccessible repo, or an unsupported flag is an
     * OPERATIONAL failure that must surface (the W4 fail-loud idiom — [orThrow]/[GitCommandException]),
     * never collapse to empty. Collapsing the latter would persist a false `commit = null` for a page that
     * genuinely has history (`IndexBuilder.reindex` does `log(path, 1).firstOrNull()?.sha`) and report a
     * false empty on `/history`. A born repo asked for a never-committed path exits 0 with empty stdout, so
     * it never reaches here; the only non-zero "no history" is the unborn case, recognized by its stderr.
     */
    private fun emptyListOrThrow(result: GitResult, step: String): List<Commit> {
        if (isUnbornHead(result)) return emptyList()
        result.orThrow(step)
        return emptyList()
    }

    private fun emptyMapOrThrow(result: GitResult, step: String): Map<TreePath, Commit> {
        if (isUnbornHead(result)) return emptyMap()
        result.orThrow(step)
        return emptyMap()
    }

    private fun commitMessage(repoRelativePath: String): String = "Update $repoRelativePath"

    /**
     * True only for the unborn-HEAD / empty-repo failure (LC_ALL=C is pinned in [GitExecutor], so the
     * English signature is stable): modern git says "does not have any commits yet"; older git / a missing
     * HEAD says "bad default revision 'HEAD'" or "unknown revision … HEAD". Anything else (timeout, corrupt
     * repo, unknown flag, permission) is operational and must throw.
     */
    private fun isUnbornHead(result: GitResult): Boolean = UNBORN_HEAD_STDERR.containsMatchIn(result.stderr)

    /**
     * True for a `git diff` failure caused by an UNRESOLVABLE `from`/`to` ref — the client error the route
     * maps to 404 ("no such commit"), distinct from an operational failure (W5 P2). Stable under the pinned
     * LC_ALL=C: git reports a bad ref as "fatal: bad object <sha>", "unknown revision or path not in the
     * working tree", "bad revision <ref>", or "ambiguous argument '<ref>': unknown revision". Anything else
     * (timeout, corrupt repo, permission, unsupported flag) is operational and must surface as 500.
     */
    private fun isUnknownRevision(result: GitResult): Boolean = UNKNOWN_REVISION_STDERR.containsMatchIn(result.stderr)

    /**
     * Extracts (major, minor) from a `git --version` banner, tolerant of vendor suffixes:
     * `git version 2.54.0` → (2, 54); `git version 2.39.5 (Apple Git-154)` → (2, 39); `git version 2.25.1`
     * → (2, 25). Anything that does not match the `git version <major>.<minor>` shape → null (the caller
     * then PASSES with a warning rather than false-failing an unexpected-but-modern banner).
     */
    private fun parseGitVersion(banner: String): Pair<Int, Int>? {
        val match = GIT_VERSION_LINE.find(banner) ?: return null
        val (major, minor) = match.destructured
        return major.toIntOrNull()?.let { maj -> minor.toIntOrNull()?.let { min -> maj to min } }
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        // NUL-separated fields, `%B` (message) last. NUL is the one byte git forbids in a commit object, so
        // a name/email/message can carry any other byte and still parse unambiguously. `show -s` reads one
        // commit; `log` reads many under `-z`, which reuses NUL as the record terminator too (chunkFields /
        // parseLogWithNames) — collision-proof, no printable record separator the body could mimic.
        private const val SHOW_FORMAT = "%H%x00%an%x00%ae%x00%at%x00%cn%x00%ce%x00%ct%x00%B"
        private const val LOG_FORMAT = SHOW_FORMAT

        /** First-parent history flags applied to `log` and `lastCommits` alike (W5 debate MUST-FIX 3 +
         *  revision BLOCKING 3). `--first-parent` is the TRAVERSAL flag: it walks only the first parent of
         *  each merge, so a side-branch-only commit is never reported as a path's last touch. `--diff-merges=
         *  first-parent` is the DISPLAY flag: a merge that resolves the path against its first parent still
         *  shows (and thus attributes) the change. Together they realize the documented semantic: "the last
         *  commit that touched the file along first-parent history". One without the other is a half-measure
         *  (traversal alone hides merge-resolved changes; display alone leaks side-branch commits). */
        private val FIRST_PARENT = listOf("--first-parent", "--diff-merges=first-parent")

        // The git version floor for the read path: `--diff-merges=first-parent` (in [FIRST_PARENT]) is only
        // a valid value since git 2.31.0 — that release taught `--diff-merges` the named convenience values
        // (`first-parent`, `m`, `c`, `cc`, `on`, `off`); 2.30 accepted only `off`/`none`/`on`. Source: git
        // 2.31.0 release notes (Documentation/RelNotes/2.31.0.txt, "the --diff-merges option learned ...").
        // An older-but-present git (Ubuntu 20.04 → 2.25, Debian 11 → 2.30) would fail every read, so the gate
        // rejects it loudly. The other read flags (`--first-parent`, `-z`, `--name-only`, `--max-count`,
        // `--no-ext-diff`, `--no-textconv`, GIT_LITERAL_PATHSPECS) predate 2.31, so 2.31 is the binding floor.
        private const val MIN_GIT_MAJOR = 2
        private const val MIN_GIT_MINOR = 31

        // `git version <major>.<minor>...` — anchored at the banner head, vendor suffixes (`(Apple Git-154)`)
        // and the patch component ignored. Tolerant: an unrecognized banner yields no match (gate then PASSES
        // with a warning rather than false-failing).
        private val GIT_VERSION_LINE = Regex("""git version (\d+)\.(\d+)""")

        private const val COMMIT_FIELD_COUNT = 8

        // Per-`git log` pathspec-byte budget for [lastCommits] batching (P2). 128 KiB is far under the
        // smallest realistic OS `ARG_MAX` (macOS ~1 MiB, Linux ~2 MiB) once env + the rest of argv are
        // accounted for, yet large enough that normal trees (thousands of ~40-byte `.md` paths) fit in one
        // invocation — so the common case is still ONE process, with batching kicking in only for huge trees.
        private const val MAX_PATHSPEC_BYTES_PER_BATCH = 128 * 1024

        // A full object id (40-hex SHA-1 or 64-hex SHA-256) — marks the start of the next record when walking
        // the interleaved `--name-only -z` token stream. INVARIANT: a content path always contains `/` or `.`
        // (page paths are `.md`, all queried paths are content paths), so it can never be bare hex and is never
        // misread as a record boundary. A future generalization to arbitrary (extensionless top-level) paths
        // would need a stronger record delimiter than "next bare object-id token".
        private val SHA_TOKEN = Regex("[0-9a-f]{40}([0-9a-f]{24})?")

        // The git stderr signatures for an unborn HEAD / empty repo across versions — the one BENIGN
        // non-zero read exit (maps to empty). Stable under the pinned LC_ALL=C (GitExecutor): modern git
        // emits "does not have any commits yet"; older git / a missing-ref HEAD emits "bad default
        // revision 'HEAD'" or "unknown revision or path not in the working tree" for HEAD.
        private val UNBORN_HEAD_STDERR =
            Regex("""does not have any commits yet|bad default revision 'HEAD'|ambiguous argument 'HEAD': unknown revision""")

        // The git stderr signatures for an UNRESOLVABLE diff ref (W5 P2) — a client 404, not an operational
        // 500. Stable under LC_ALL=C across versions: a bad object id, an unknown revision, a bad revision, or
        // an ambiguous-argument unknown-revision. Anything else (timeout, corrupt repo, unsupported flag) is
        // operational and propagates as a GitCommandException.
        private val UNKNOWN_REVISION_STDERR =
            Regex("""fatal: bad object|unknown revision or path not in the working tree|fatal: bad revision|unknown revision""")
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
open class GitCommandException(step: String, exitCode: Int, stderr: String) :
    RuntimeException("git $step failed (exit $exitCode): ${stderr.ifBlank { "<no stderr>" }}")

/**
 * A `git diff` over a ref that does not resolve to a commit (W5 P2) — a CLIENT error the diff route maps to
 * 404 `not_found`, distinct from an operational [GitCommandException] (which propagates → 500). Subtypes
 * [GitCommandException] so the W1 commit-path catch and the provider-level `shouldThrow<GitCommandException>`
 * read tests stay correct; the route catches THIS narrower type for its 404.
 */
class UnknownRevisionException(step: String, exitCode: Int, stderr: String) : GitCommandException(step, exitCode, stderr)

/** The actionable startup gate-check failure when Git mode is on but the binary is missing. */
class GitUnavailableException(message: String) : RuntimeException(message)
