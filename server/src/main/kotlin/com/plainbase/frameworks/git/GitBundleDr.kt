package com.plainbase.frameworks.git

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.service.RebuildScheduler
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.scheduling.ExecutorAlarm
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.time.Clock

/**
 * C5's git-over-mirror disaster-recovery orchestrator (object mode + `git.enabled=true` ONLY): a
 * bucket-shipped `.git` bundle (`<prefix>/.plainbase/history.bundle`) is the DR artifact a lost/wiped
 * `DATA_DIR` restores from, so a fresh boot recovers commit-grained history, not just content.
 *
 * Three operations, called from `serve()`'s boot sequence strictly around the object-mode
 * [ObjectContentStore.hydrate] call:
 *  - [restore] (BEFORE hydrate): the completeness-gate + FORK-2 sentinel truth table decides whether a
 *    real bundle-restore is owed, and performs it (init -> fetch -> retarget -> reset) when so.
 *  - [reconcileBootCommit] (AFTER hydrate, only when [restore] found work owed): ONE plumbing commit
 *    capturing exactly the authority-vs-bundle-tip divergence (HOLE A), via the SAME [GitPlumbing]
 *    chokepoint [GitCliHistoryProvider.commit] uses.
 *  - [onCommit] (per-save, off the write-pipeline monitor): the debounced ship cadence that keeps the
 *    bucket bundle fresh.
 *
 * [locks] are SHARED with the object-mode [GitCliHistoryProvider] (`historyModule`'s `single<GitRepoLocks>`):
 * `repoWrite` excludes a commit's ref mutation from this class's `bundle create`/reconcile ref mutation
 * (HOLE B); `ship` single-flights the whole ship operation so an older ship can never land after a newer
 * one, and a graceful-shutdown flush racing an in-flight cadence ship is serialized the same way.
 *
 * FORK 1 (strict hydrate) is enforced by the CALLER (`Application.kt` passes `strict = restored.isRestored`
 * to `hydrate()`), not here - this class only decides WHETHER a restore/reconcile is owed and performs the
 * git-side halves.
 */
class GitBundleDr(
    private val exec: GitExecutor,
    private val objectStore: ObjectContentStore,
    private val mirrorRoot: Path,
    /** `DATA_DIR/tmp` - the restore fetch source AND the ship-time bundle-create target share one path/name. */
    private val tmpDir: Path,
    /** FORK 2's durable marker (`DATA_DIR/restore-pending`) - a plain file, never a ref, so it survives a wholesale `.git` delete. */
    private val sentinelPath: Path,
    /** The server identity (author == committer) for the boot reconcile commit (FORK 3: never a human/agent identity). */
    private val identity: CommitIdentity,
    private val clock: Clock,
    /** The SAME raw-on-disk repo-relative path function `historyModule` binds for the object-mode [GitCliHistoryProvider]. */
    private val repoPath: (TreePath) -> String,
    /** Where this class's own per-op temp indexes live (the reconcile commit); created lazily. */
    private val gitHome: Path,
    private val locks: GitRepoLocks,
    private val alarm: RebuildScheduler.Alarm = ExecutorAlarm(threadName = "plainbase-bundle-dr-ship"),
) : AutoCloseable {

    private val cadenceLock = Any()
    private var pendingCommits = 0
    private var armed = false
    private var everShipped = false
    private var consecutiveShipFailures = 0
    private var closed = false

    /** The FORK-2/2b truth-table outcome: whether a real restore ran, and (when so) the bundle tip the reconcile diffs against. */
    data class Restored(val isRestored: Boolean, val tip: String?) {
        companion object {
            val NOT_RESTORED = Restored(isRestored = false, tip = null)
        }
    }

    // ---- 2a: the completeness gate + FORK-2 sentinel --------------------------------------------

    /**
     * The three-way outcome of [gitState]: a bare ok/not-ok boolean cannot distinguish a genuinely
     * incomplete/absent `.git` (safe to delete + re-restore, self-healing a crash-mid-fetch) from one
     * that git simply CANNOT READ in this environment (dubious ownership, permissions, timeout, or any
     * other non-definitive failure) - conflating the two would delete a complete-but-unreadable repo on
     * a guess (the BLOCKING data-loss hole this type exists to close).
     */
    internal enum class GitState { COMPLETE, DEFINITIVELY_INCOMPLETE, UNREADABLE }

    /**
     * `git rev-parse --verify HEAD^{commit}` classified via [classify] - NEVER `Files.exists(.git)` alone
     * (an incomplete/mid-fetch `.git` must not read as complete) and NEVER a bare ok/not-ok boolean (see
     * [GitState]).
     */
    internal fun gitState(): GitState {
        val result = exec.run(listOf("rev-parse", "--verify", "HEAD^{commit}"))
        return classify(result, gitDirExists = Files.exists(mirrorRoot.resolve(".git")))
    }

    internal fun sentinelPresent(): Boolean = Files.exists(sentinelPath)

    // ---- 2b: restore (BEFORE hydrate) ------------------------------------------------------------

    /**
     * The FORK-2 gate truth table (POST-lock, before [ObjectContentStore.hydrate]), keyed on [gitState]:
     *  1. [GitState.COMPLETE], no sentinel -> [Restored.NOT_RESTORED] (a warm restart; nothing owed, no GET).
     *  2. [GitState.COMPLETE], sentinel present -> a crash landed between a prior restore's `reset` and its
     *     reconcile: the mirror IS current, so reconcile is merely OWED against the current tip - no GET,
     *     no re-fetch (never touches the HOLE-C surface when the bundle is not even needed).
     *  3. [GitState.UNREADABLE] -> ABORT THE BOOT (BLOCKING fold): git cannot positively confirm the
     *     mirror is incomplete, so deleting it would risk destroying a complete, otherwise-healthy repo.
     *     Never reaches the GET or the delete path.
     *  4. [GitState.DEFINITIVELY_INCOMPLETE] -> a real restore is needed: [ObjectContentStore.getHistoryBundle]
     *     (HOLE C: a non-404 transport failure FAILS THE BOOT, reclassified via [ObjectContentStore.bootRefusal]
     *     into the same operator-actionable R16 refusal the hydrate/LIST self-check uses - never a raw
     *     exception; only a definitive 404 means "no bundle"). `null` -> fresh install / abandoned restore:
     *     clear a stale sentinel (else a 404-plus-incomplete-`.git` loops restoreOwed forever), delete any
     *     partial `.git`, NOT-RESTORED.
     *     Present -> write the sentinel BEFORE the fetch, delete any partial `.git` (crash-mid-fetch
     *     heal), then init -> `-c fetch.fsckObjects=true` fetch -> retarget -> `reset --mixed`, with the
     *     tmp bundle file cleaned up in a `finally`.
     */
    fun restore(): Restored {
        when (gitState()) {
            GitState.COMPLETE -> {
                if (!sentinelPresent()) return Restored.NOT_RESTORED
                val tip = parseShaOrThrow(listOf("rev-parse", "HEAD"), "rev-parse HEAD (complete .git, sentinel present)")
                return Restored(isRestored = true, tip = tip)
            }
            GitState.UNREADABLE -> throw unreadableMirrorException()
            GitState.DEFINITIVELY_INCOMPLETE -> Unit // fall through: a real restore is needed below
        }
        // HOLE C: a non-404 transport/credential/signature failure MUST still be fatal (never read as
        // "no bundle" -> fresh-init -> clobber) - only the MESSAGE changes here. This GET is the FIRST
        // network call on the git.enabled restore path (strictly before hydrate's own LIST self-check),
        // so a raw ConnectException/SSLException/ObjectStoreException would otherwise bypass the R16
        // classification entirely; reclassify via the SAME [ObjectContentStore.bootRefusal] the object
        // hydrate/LIST self-check uses, so the operator sees the identical actionable refusal either way.
        val bundle = try {
            objectStore.getHistoryBundle()
        } catch (e: Exception) {
            throw objectStore.bootRefusal(e)
        }
        if (bundle == null) {
            clearSentinel() // MUST-BIND 5: never loop restoreOwed on a stale sentinel + 404 + incomplete .git
            deletePartialGit()
            return Restored.NOT_RESTORED // prepare()/the first commit inits a fresh empty repo later
        }
        writeSentinel()
        deletePartialGit() // crash-mid-fetch heal: a partial .git from an earlier interrupted restore
        Files.createDirectories(mirrorRoot)
        Files.createDirectories(tmpDir)
        val tmpBundlePath = tmpDir.resolve(TMP_BUNDLE_FILENAME)
        try {
            Files.write(tmpBundlePath, bundle)
            runOrThrow(listOf("init"), "init")
            // CRUX 4: fsck the bundle AT THE SOURCE on this rare restore path; the leading `-c` is NOT
            // added to PINNED_CONFIG - the hot path (every other git call) never fscks.
            // `--update-head-ok`: a fresh `init` leaves HEAD symbolically pointing at `refs/heads/main`
            // (unborn) - a plain (non-bare) repo's own currently-checked-out branch - and git REFUSES to
            // fetch into the checked-out branch by default (`fatal: refusing to fetch into branch ...
            // checked out`), empirically verified (this is exactly what SP3's real-repo validation would
            // have caught; recorded here since SP3 itself was deferred to build time). `--update-head-ok`
            // is the documented, git-native escape for a caller (not a human) driving the fetch directly.
            runOrThrow(
                listOf("-c", "fetch.fsckObjects=true", "fetch", "--update-head-ok", tmpBundlePath.toString(), "refs/heads/*:refs/heads/*"),
                "fetch",
            )
            val branchRef = resolveBundleBranch(tmpBundlePath)
            runOrThrow(listOf("symbolic-ref", "HEAD", branchRef), "symbolic-ref")
            val tip = parseShaOrThrow(listOf("rev-parse", branchRef), "rev-parse $branchRef")
            runOrThrow(listOf("reset", "--mixed", tip), "reset --mixed") // index only - never touches worktree files
            return Restored(isRestored = true, tip = tip)
        } finally {
            runCatching { Files.deleteIfExists(tmpBundlePath) } // MUST-BIND 6: no disk leak, no stale-bundle hazard
        }
    }

    /** `git bundle list-heads <tmpBundlePath>` -> its first head ref; Plainbase bundles are `--all` under
     *  pinned `init.defaultBranch=main`, so `refs/heads/main` is both the typical value and the fallback. */
    private fun resolveBundleBranch(tmpBundlePath: Path): String {
        val result = exec.run(listOf("bundle", "list-heads", tmpBundlePath.toString()))
        if (!result.ok) throw GitCommandException("bundle list-heads", result.exitCode, result.stderr)
        val ref = result.stdoutText.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()?.split(WHITESPACE)?.getOrNull(1)
        return ref?.takeIf { it.startsWith("refs/") } ?: DEFAULT_BRANCH_REF
    }

    // ---- 2c: the boot reconcile commit (AFTER hydrate) --------------------------------------------

    /**
     * No-op when [restored] is [Restored.NOT_RESTORED] (BOUND-3: a warm restart or a no-bundle fresh
     * install never gets a boot commit). Otherwise ONE plumbing commit capturing exactly the
     * authority-vs-bundle-tip divergence, under [GitRepoLocks.repoWrite] (the SAME monitor
     * [GitCliHistoryProvider.commit] wraps), via the shared [GitPlumbing] chokepoint. Clears the FORK-2
     * sentinel once done (committed or no-op) - the reconcile obligation is discharged either way.
     */
    fun reconcileBootCommit(restored: Restored) {
        if (!restored.isRestored) return
        val tip = requireNotNull(restored.tip) { "reconcileBootCommit: RESTORED with a null tip" }
        val committed = synchronized(locks.repoWrite) { reconcileCommit(tip) }
        // Cluster-3b: a non-no-op reconcile bypasses the per-save `dispatchMaintenance` ship trigger
        // (this path never goes through GitCliHistoryProvider.commit), so arm the ship cadence here.
        if (committed) onCommit()
        clearSentinel()
    }

    @OptIn(ExperimentalPathApi::class)
    private fun reconcileCommit(tip: String): Boolean {
        Files.createDirectories(gitHome)
        val indexFile = gitHome.resolve("idx-" + UUID.randomUUID())
        val indexEnv = mapOf("GIT_INDEX_FILE" to indexFile.toString())
        try {
            GitPlumbing.seedIndex(exec, indexEnv, tip)
            val tipFiles = listTipFiles(tip) // HOLE F: -z NUL-parsed, never split on an embedded newline
            val authoritativeRepoPaths = mutableSetOf<String>()
            for (path in objectStore.authoritativeMirrorPaths()) {
                val repoRelativePath = repoPath(path)
                authoritativeRepoPaths += repoRelativePath
                val bytes = Files.readAllBytes(mirrorRoot.resolve(repoRelativePath))
                GitPlumbing.stageBlob(exec, indexEnv, repoRelativePath, bytes)
            }
            // HOLE A: the remove-set is keyed off the AUTHORITY (authoritativeMirrorPaths, post-strict-hydrate),
            // never raw disk-absence - a tip file absent from the authority is force-removed from the index.
            for (tipFile in tipFiles) {
                if (tipFile !in authoritativeRepoPaths) GitPlumbing.removeFromIndex(exec, indexEnv, tipFile)
            }
            val newTree = GitPlumbing.writeTree(exec, indexEnv)
            val baseTree = parseShaOrThrow(listOf("rev-parse", "$tip^{tree}"), "rev-parse $tip^{tree}")
            if (newTree == baseTree) {
                // BLOCKING #3: even a clean no-op can follow a PRIOR crash that landed the reconcile
                // commit (updateRef succeeded) but never resynced the live index (e.g. a retry via the
                // FORK-2 sentinel after that exact crash window) - resync against tip regardless, so a
                // stale live index never lingers.
                syncLiveIndexAfterReconcile(tip)
                return false // clean no-op: nothing diverged, skip the commit
            }
            val identityEnv = GitPlumbing.identityEnv(identity, identity, clock.now())
            val newCommit = GitPlumbing.commitTree(exec, indexEnv, newTree, tip, identityEnv, RECONCILE_MESSAGE)
            val result = GitPlumbing.updateRef(exec, currentBranchRef(), newCommit, tip)
            if (!result.ok) throw GitCommandException("update-ref", result.exitCode, result.stderr)
            // BLOCKING #3 (porcelain parity): the recipe staged into a TEMP GIT_INDEX_FILE, so the LIVE
            // `.git/index` is still at the OLD tip - `git status` would show phantom differences for
            // every reconciled path. Unlike commit()'s single-file `syncLiveIndex` (a surgical
            // `--cacheinfo` add of the one changed blob), reconcile's divergence can span an ARBITRARY
            // number of adds/removes, so the equivalent here is `reset --mixed` against the NEW commit -
            // the SAME index-only primitive `restore()` uses to seed the index after a fetch.
            syncLiveIndexAfterReconcile(newCommit)
            return true
        } finally {
            runCatching { indexFile.deleteRecursively() }
        }
    }

    /**
     * Resyncs the LIVE `.git/index` (never the per-op temp index) to [commit] via `reset --mixed` — an
     * index-only op, never touching worktree files (the worktree already matches, since the mirror IS
     * the authority). Best-effort, NON-FATAL (the commit, or lack thereof, already landed): a failure
     * here only leaves `git status` showing a stale porcelain view, never corrupts history.
     */
    private fun syncLiveIndexAfterReconcile(commit: String) {
        val result = exec.run(listOf("reset", "--mixed", commit))
        if (!result.ok) logger.warn { "live-index sync after reconcile failed (commit already landed; non-fatal): ${result.stderr}" }
    }

    /** `git ls-tree -r --name-only -z <tip>`, NUL-parsed (HOLE F - the `parseLogWithNames` framing class). */
    private fun listTipFiles(tip: String): List<String> {
        val result = exec.run(listOf("ls-tree", "-r", "--name-only", "-z", tip))
        if (!result.ok) throw GitCommandException("ls-tree", result.exitCode, result.stderr)
        return result.stdoutText.split(Char(0)).filter { it.isNotEmpty() }
    }

    private fun currentBranchRef(): String? =
        exec.run(listOf("symbolic-ref", "HEAD"))
            .let { if (it.ok) it.stdoutText.trim().takeIf(String::isNotEmpty) else null }
            ?.takeIf { it.startsWith("refs/") }

    // ---- 2d: the debounced bundle ship (HOLE B + Cluster-3a) ---------------------------------------

    /**
     * BLOCKING #2 fix: the in-memory-only HALF of the per-save cadence trigger - records a commit and
     * decides whether a ship is owed RIGHT NOW (the FIRST non-no-op commit of this process, so a fresh
     * instance killed early must not lose ALL history; or the [SHIP_COMMIT_THRESHOLD]-commit cadence),
     * arming the debounce [alarm] for [SHIP_MAX_LATENCY_MILLIS] otherwise. No git call, no network call -
     * cheap enough to call SYNCHRONOUSLY on the write-pipeline monitor's own thread (`historyModule`'s
     * per-save wiring does exactly that), so the ship OBLIGATION is decided deterministically BEFORE the
     * save returns success, rather than racing an async dispatch that a crash could pre-empt entirely.
     * The caller dispatches the actual [shipBestEffort] (the slow `bundle create` + network PUT) OFF that
     * thread when this returns true - never under [GitRepoLocks.repoWrite], never under the write-pipeline
     * monitor.
     */
    fun recordCommit(): Boolean = synchronized(cadenceLock) {
        if (closed) return@synchronized false
        pendingCommits++
        val firstEver = !everShipped && pendingCommits == 1
        if (firstEver || pendingCommits >= SHIP_COMMIT_THRESHOLD) {
            // MINOR (opus): a still-pending debounce alarm from an EARLIER commit would otherwise fire
            // later and re-ship redundantly (harmless, but wasteful). [alarm] has no cancel primitive, so
            // an already-scheduled timer still fires - but clearing the flag here lets the NEXT commit
            // (after this ship) re-arm its OWN fresh alarm rather than silently no-op'ing against a stale
            // `armed=true` left over from before this threshold-ship.
            armed = false
            true
        } else {
            if (!armed) {
                armed = true
                alarm.after(SHIP_MAX_LATENCY_MILLIS) { onAlarm() }
            }
            false
        }
    }

    /**
     * [recordCommit] then, when due, [shipBestEffort] on the SAME thread - a single-threaded,
     * synchronous convenience for the boot reconcile ([reconcileBootCommit], which is already off any
     * write-pipeline monitor) and for tests. The per-save `historyModule` wiring instead calls
     * [recordCommit] directly (synchronously, guaranteed before the save returns - BLOCKING #2) and
     * dispatches [shipBestEffort] itself, off-thread, only when it returns true.
     */
    fun onCommit() {
        if (recordCommit()) shipBestEffort()
    }

    private fun onAlarm() {
        synchronized(cadenceLock) { armed = false }
        shipBestEffort()
    }

    /** Best-effort ship (WARN, escalating to ERROR after [SHIP_FAILURE_ESCALATION_THRESHOLD] consecutive
     *  failures) - never throws, never kills a save/poll/shutdown. Callable off-thread once [recordCommit]
     *  (or the debounce alarm) has decided a ship is due. */
    internal fun shipBestEffort() {
        synchronized(cadenceLock) { pendingCommits = 0 }
        try {
            ship()
            synchronized(cadenceLock) {
                everShipped = true
                consecutiveShipFailures = 0
            }
        } catch (e: Exception) {
            val failures = synchronized(cadenceLock) { ++consecutiveShipFailures }
            if (failures >= SHIP_FAILURE_ESCALATION_THRESHOLD) {
                logger.error(e) {
                    "bundle ship failed $failures times in a row (${causeOf(e)}); the DR bundle is stale until a ship succeeds"
                }
            } else {
                logger.warn(e) { "bundle ship failed (${causeOf(e)}); the next commit or cadence tick retries" }
            }
            // MINOR (agy): make the "next commit or cadence tick retries" claim actually true even with no
            // further commits - [alarm] is ONE-SHOT, so a failed ship must arm its OWN retry tick rather
            // than rely on a future commit's recordCommit() to do so (a quiet instance would otherwise
            // stay permanently stale after one failed ship).
            synchronized(cadenceLock) {
                if (!closed && !armed) {
                    armed = true
                    alarm.after(SHIP_MAX_LATENCY_MILLIS) { onAlarm() }
                }
            }
        }
    }

    /**
     * `bundle create --all` under [GitRepoLocks.repoWrite] (mutually exclusive with a concurrent
     * commit's ref mutation, MUST-BIND 3 / Cluster-3a torn-bundle), then the network PUT OUTSIDE that
     * lock (M1: never hold a monitor across a network call) but still inside [GitRepoLocks.ship] (whole
     * ships single-flight, so a slow older ship can never land after a newer one - HOLE B). Only ships
     * wait on `locks.ship`; no save ever blocks on the network.
     */
    fun ship() {
        synchronized(locks.ship) {
            Files.createDirectories(tmpDir)
            val tmpBundlePath = tmpDir.resolve(TMP_BUNDLE_FILENAME)
            val bytes = synchronized(locks.repoWrite) {
                try {
                    val result = exec.run(listOf("bundle", "create", tmpBundlePath.toString(), "--all"))
                    if (!result.ok) throw GitCommandException("bundle create", result.exitCode, result.stderr)
                    Files.readAllBytes(tmpBundlePath)
                } finally {
                    runCatching { Files.deleteIfExists(tmpBundlePath) }
                }
            }
            // `PutCondition.None` makes the bucket a dumb last-writer; bundle monotonicity rests ENTIRELY
            // on single-instance (DataDirLock) + in-process single-flight (locks.ship). A SECOND
            // bundle-shipping code path (a CLI `plainbase backup`, future multi-writer) would silently
            // regress the DR artifact via out-of-order unconditional PUT.
            objectStore.putHistoryBundle(bytes)
        }
    }

    /** The graceful-shutdown flush: a final best-effort ship through the SAME `locks.ship` (HOLE B: never
     *  races an in-flight cadence ship), before the object-store transport closes. Never throws. */
    override fun close() {
        synchronized(cadenceLock) { closed = true }
        (alarm as? AutoCloseable)?.close()
        // MINOR (opus/agy): `bundle create --all` refuses to create an empty bundle on an unborn/no-commit
        // repo ("fatal: Refusing to create empty bundle") - skip the flush entirely rather than logging a
        // scary-but-harmless "graceful-shutdown bundle ship failed" WARN on every clean shutdown of a
        // fresh install that has not committed anything yet.
        if (gitState() != GitState.COMPLETE) return
        try {
            ship()
        } catch (e: Exception) {
            logger.warn(e) { "graceful-shutdown bundle ship failed (${causeOf(e)}); the DR bundle stays as of the last successful ship" }
        }
    }

    // ---- internals ----------------------------------------------------------------------------

    private fun writeSentinel() {
        sentinelPath.parent?.let { Files.createDirectories(it) }
        if (!Files.exists(sentinelPath)) Files.createFile(sentinelPath)
    }

    private fun clearSentinel() {
        Files.deleteIfExists(sentinelPath)
    }

    /**
     * Clears a `.git` this call has already classified [GitState.DEFINITIVELY_INCOMPLETE] (never called
     * on [GitState.UNREADABLE], which aborts the boot before reaching here). Belt-and-suspenders (review
     * fold): renames it ASIDE to a dot-prefixed `.git.pre-restore-<epoch-millis>-<uuid>` sibling rather
     * than an irreversible [deleteRecursively], so an operator has a last-ditch recovery window if this
     * self-heal path ever fires against a mirror that was not actually incomplete. Dot-prefixed, so
     * [com.plainbase.frameworks.filesystem.IgnoreRules] excludes it from every content scan the same way
     * it already excludes `.git` itself. Falls back to an outright delete only if the rename itself fails
     * (e.g. cross-device or permission trouble) - never leaves the incomplete `.git` in place to keep
     * being misread as complete-ish debris.
     *
     * Husk-accumulation fix: an [isUnbornAndEmpty] `.git` (no refs, no commits - a bare `git init` with
     * nothing ever fetched or committed into it) has NOTHING for the rename-aside to preserve, so it is
     * [deleteRecursively]'d OUTRIGHT instead. This matters on a git-enabled, never-committed object
     * instance: `prepare()`'s lazy `ensureRepo()` (`GitCliHistoryProvider`) `git init`s an empty repo on
     * EVERY boot that lacks one, so a crash-looping container that never lands a save would otherwise
     * rename that empty repo aside once per restart. A `.git` that DOES carry a ref/commit worth a
     * last-ditch look still takes the rename-aside path exactly as before, and the minted husks are now
     * bounded by [reapPreRestoreHusks] (a single trailing reap reached on every branch below).
     *
     * Invariant: after any restore that reaches this method (every [GitState.DEFINITIVELY_INCOMPLETE]
     * restore) and whose [mirrorRoot] exists, at most [HUSK_KEEP_COUNT] matched husks remain under
     * [mirrorRoot]; a restore whose mirror dir does not yet exist is a clean no-op (see
     * [reapPreRestoreHusks]).
     */
    @OptIn(ExperimentalPathApi::class)
    private fun deletePartialGit() {
        val gitDir = mirrorRoot.resolve(".git")
        when {
            !Files.exists(gitDir) -> Unit // absent: nothing to clear; any legacy husks are still reaped below
            isUnbornAndEmpty() -> {
                try {
                    gitDir.deleteRecursively()
                } catch (e: IOException) {
                    logger.warn(e) { "could not delete the unborn/empty .git before restore; leaving it in place" }
                }
            }
            else -> {
                val renamedAside = mirrorRoot.resolve("$PRE_RESTORE_HUSK_PREFIX${clock.now().toEpochMilliseconds()}-${UUID.randomUUID()}")
                try {
                    Files.move(gitDir, renamedAside)
                } catch (e: IOException) {
                    logger.warn(e) { "could not rename aside the incomplete .git before restore; deleting it instead" }
                    gitDir.deleteRecursively()
                }
            }
        }
        reapPreRestoreHusks()
    }

    /**
     * Bounds the `.git.pre-restore-*` husks the rename-aside branch mints: keeps the newest
     * [HUSK_KEEP_COUNT] by the epoch-millis embedded in each name and reaps the rest, best-effort (a
     * failed reap never fails a restore). Only ever removes what the rename-aside provably minted - a
     * name that does not match the `<epoch>-<uuid>` shape is SKIPPED, never deleted (the house
     * fail-closed posture). Runs boot-serial inside the `DataDirLock` region before any watcher/pipeline
     * thread exists (`Application.kt:135-151`), so it needs no lock. Returns early when [mirrorRoot] does
     * not yet exist: on a fresh / lost-DATA_DIR boot `deletePartialGit()` runs before the mirror dir is
     * created ([restore] at `:141`/`:145`, `Files.createDirectories(mirrorRoot)` at `:146`), and a
     * missing mirror can hold no husks - opening a directory stream on it would throw and abort the boot.
     */
    @OptIn(ExperimentalPathApi::class)
    private fun reapPreRestoreHusks() {
        if (!Files.exists(mirrorRoot)) return
        val husks = try {
            Files.newDirectoryStream(mirrorRoot, "$PRE_RESTORE_HUSK_PREFIX*").use { stream ->
                stream.mapNotNull { entry ->
                    val tail = entry.fileName.toString().removePrefix(PRE_RESTORE_HUSK_PREFIX)
                    // toLongOrNull: an over-long numeric name is SKIPPED, never reaped (fail-safe, no throw).
                    PRE_RESTORE_HUSK_NAME.matchEntire(tail)?.groupValues?.get(1)?.toLongOrNull()?.let { epoch -> epoch to entry }
                }
            }
        } catch (e: IOException) {
            logger.warn(e) { "could not list the mirror to reap pre-restore husks; skipping this reap" }
            return
        }
        husks.sortedWith(compareByDescending<Pair<Long, Path>> { it.first }.thenByDescending { it.second.fileName.toString() })
            .drop(HUSK_KEEP_COUNT)
            .forEach { (_, husk) ->
                try {
                    husk.deleteRecursively()
                } catch (e: IOException) {
                    logger.warn(e) { "could not reap the pre-restore husk $husk; leaving it in place" }
                }
            }
    }

    /**
     * True only when the `.git` at [mirrorRoot] holds NO ref and NO commit - genuinely nothing an operator
     * could ever recover from a rename-aside. `git show-ref` is the plumbing check: it exits non-zero with
     * empty stdout when the repo has no refs at all (a bare `init`, or a crash before any ref was written);
     * ANY ref present (even one whose target object is missing from an interrupted fetch) is treated as
     * "might be worth a last look" and still takes the preserve path below. Only called on a `.git` this
     * call has already classified [GitState.DEFINITIVELY_INCOMPLETE] (never [GitState.COMPLETE]), so a
     * repo with a resolvable `HEAD^{commit}` never reaches here.
     */
    private fun isUnbornAndEmpty(): Boolean {
        val result = exec.run(listOf("show-ref"))
        return !result.ok && result.stdoutText.isBlank()
    }

    /** The BLOCKING-fold abort: git cannot positively confirm the mirror's `.git` is incomplete, so
     *  [restore] refuses to guess and delete it (mirrors [GitUnavailableException]'s actionable-message
     *  idiom in [GitCliHistoryProvider]; caught by `Application.kt`'s existing `serve: ${e.message}` +
     *  exit(1) boot-gate path, same as every other startup gate). Never leaks stderr verbatim (no secrets
     *  in git's own diagnostic text here, but keep the message actionable, not a raw dump). */
    private fun unreadableMirrorException(): GitMirrorUnreadableException = GitMirrorUnreadableException(
        "the object-mode mirror .git at $mirrorRoot exists but git cannot read it - most likely a DATA_DIR " +
            "ownership/permission change (e.g. a container/host UID mismatch triggering git's dubious-ownership " +
            "refusal). Plainbase runs git with a hermetic config (global/system config nulled), so `safe.directory` " +
            "cannot fix this - instead change ownership so the server process's user owns $mirrorRoot, or otherwise " +
            "restore read access to it, then restart. Refusing to delete a repo it cannot positively confirm is " +
            "incomplete.",
    )

    private fun runOrThrow(args: List<String>, step: String) {
        val result = exec.run(args)
        if (!result.ok) throw GitCommandException(step, result.exitCode, result.stderr)
    }

    private fun parseShaOrThrow(args: List<String>, step: String): String {
        val result = exec.run(args)
        if (!result.ok) throw GitCommandException(step, result.exitCode, result.stderr)
        return GitExecutor.parseSha(result.stdout)
            ?: throw GitCommandException(step, result.exitCode, "no SHA parsed from: ${result.stdoutText}")
    }

    private fun causeOf(failure: Throwable): String = failure.message ?: failure::class.simpleName ?: "failure"

    companion object {
        private val logger = KotlinLogging.logger {}

        private const val TMP_BUNDLE_FILENAME = "history.bundle"
        private const val DEFAULT_BRANCH_REF = "refs/heads/main"
        private const val RECONCILE_MESSAGE = "reconcile: bucket state at boot"

        /** The single owner of the rename-aside husk name prefix: shared by the mint site
         *  ([deletePartialGit]) and the reap ([reapPreRestoreHusks]) so they cannot drift, and imported by
         *  `GitBundleDrShipTest.kt`'s `huskDirs()` helper. */
        internal const val PRE_RESTORE_HUSK_PREFIX = ".git.pre-restore-"

        /** How many `.git.pre-restore-*` husks the reap keeps (newest by embedded epoch-millis). */
        internal const val HUSK_KEEP_COUNT = 3

        /** The strict minted-husk tail shape `<epoch-millis>-<uuid>`; a non-matching name is skipped, never
         *  reaped (fail-safe - the reap only removes what the rename-aside provably minted). */
        private val PRE_RESTORE_HUSK_NAME = Regex("^(\\d+)-[0-9a-fA-F-]{36}$")

        private val WHITESPACE = Regex("\\s+")

        // git's stable (LC_ALL=C-pinned, per GitExecutor) dubious-ownership refusal signature - the exact
        // reason PINNED_CONFIG cannot suppress this check without a blanket `safe.directory=*` security
        // regression (it would suppress the ownership check globally, not just for this mirror).
        private val DUBIOUS_OWNERSHIP_STDERR = Regex("detected dubious ownership")

        // The two DEFINITIVE "no real repo here yet" signatures for `git rev-parse --verify HEAD^{commit}`
        // (empirically verified on Git 2.54.0, stable under the pinned LC_ALL=C): a genuinely absent/
        // non-repo `.git` ("fatal: not a git repository ..."), or an unborn HEAD / freshly-init'd empty
        // repo (`--verify` on an unresolvable rev fails "fatal: Needed a single revision"). Anything else
        // is NOT definitive and must classify GitState.UNREADABLE instead (fail closed).
        private val DEFINITIVELY_INCOMPLETE_STDERR = Regex("not a git repository|Needed a single revision")

        /**
         * The pure classification (no I/O - a testable function, not folded into [GitBundleDr.gitState]):
         * given a `rev-parse --verify HEAD^{commit}` [result] and whether `.git` exists on disk, decides
         * the [GitState]. Order:
         *  1. ok -> [GitState.COMPLETE].
         *  2. `.git` absent entirely -> [GitState.DEFINITIVELY_INCOMPLETE] (nothing to misread; a fresh
         *     install or an abandoned restore).
         *  3. stderr matches git's stable (LC_ALL=C-pinned) dubious-ownership refusal
         *     ("detected dubious ownership") -> [GitState.UNREADABLE]. This is the exact failure
         *     `PINNED_CONFIG` cannot suppress without a `safe.directory=*` security regression (it clears
         *     the environment and pins no `safe.directory`), so it must fail closed here instead.
         *  4. stderr matches a DEFINITIVE "no real repo here yet" signature (empirically verified against
         *     `--verify HEAD^{commit}`: "not a git repository" for a non-repo dir, "Needed a single
         *     revision" for an unborn HEAD / freshly-init'd empty repo) -> [GitState.DEFINITIVELY_INCOMPLETE].
         *  5. anything else (permission denied, a timeout/-1 exit, or any other non-definitive failure) ->
         *     [GitState.UNREADABLE] - fail CLOSED by default, never assume incomplete.
         */
        internal fun classify(result: GitResult, gitDirExists: Boolean): GitState {
            if (result.ok) return GitState.COMPLETE
            if (!gitDirExists) return GitState.DEFINITIVELY_INCOMPLETE
            if (DUBIOUS_OWNERSHIP_STDERR.containsMatchIn(result.stderr)) return GitState.UNREADABLE
            if (DEFINITIVELY_INCOMPLETE_STDERR.containsMatchIn(result.stderr)) return GitState.DEFINITIVELY_INCOMPLETE
            return GitState.UNREADABLE
        }

        // C5 Step 0 (SP3) frozen defaults - stand as named constants until a real-environment spike tunes
        // the numbers (SP3 deferred at build time; these are the addendum's own fallback values).
        internal const val SHIP_COMMIT_THRESHOLD = 20
        internal const val SHIP_MAX_LATENCY_MILLIS = 300_000L
        internal const val SHIP_FAILURE_ESCALATION_THRESHOLD = 5
    }
}

/**
 * The BLOCKING-fold boot-abort (review fold): [GitBundleDr.restore] throws this when [GitBundleDr.GitState]
 * classifies the mirror `.git` [GitBundleDr.GitState.UNREADABLE] - present, but git cannot positively
 * confirm it as either complete or incomplete in this environment (dubious ownership, permissions, or any
 * other non-definitive failure). Deleting on a guess could destroy a complete, otherwise-healthy repo;
 * this fails loud instead, the same actionable-message idiom as [GitUnavailableException].
 */
class GitMirrorUnreadableException(message: String) : RuntimeException(message)
