@file:OptIn(ExperimentalAtomicApi::class)

package com.plainbase.frameworks.filesystem

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.content.WatchCoverage
import com.plainbase.domain.root.BreakCause
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The `java.nio.file.WatchService` event source behind [LocalContentStore.watch] (§B1): every
 * non-ignored directory under [root] registered recursively, a created directory registered on
 * sight, events filtered through the scan's own [IgnoreRules] (so `.git` churn never schedules a
 * rebuild) and through [excluded] subtrees — DATA_DIR when it is nested STRICTLY inside
 * CONTENT_DIR, warned about at startup, so the app's own writes (search.db, plainbase.db) cannot
 * re-trigger the watcher. An exclusion at or above the root is ignored, not applied — applying it
 * would exclude every content path, a silently dead watcher. Ignoring it is safe in both remaining
 * shapes: under a STRICT ancestor (CONTENT_DIR nested inside DATA_DIR, a perfectly normal config)
 * the app's writes land outside the watched tree, and the equal-dirs case is rejected at the
 * config boundary ([com.plainbase.frameworks.config.PlainbaseConfig.requireContentDir]) before a
 * watcher ever exists.
 *
 * Events are delivered, not interpreted: each one becomes a single [onChange] call and the
 * serialized rebuild converges everything (§B2). That is why the delivery races inherent to
 * WatchService are harmless for INDEX state — a file created inside a brand-new directory before
 * its registration produces no event of its own, but the directory-creation event already
 * scheduled the full pass that indexes it. WATCH coverage is the separate concern a full pass
 * cannot repair: an `OVERFLOW` (the JDK drops events past its per-key queue bound, realistic
 * during a big `git checkout`) may have swallowed directory-creation events outright, leaving
 * subtrees unwatched forever. The overflow branch therefore re-walks the registration tree —
 * idempotent, since registering an already-watched directory returns its existing key — before
 * forwarding [ContentStore.OVERFLOW].
 *
 * **A subtree the OS will not register at all** (the inotify watch limit, a `chmod 000` directory) is the other
 * half of that concern, and it is a CONVERGENCE fact, not an availability one: the root is there and serves every
 * byte, so it is reported as [WatchCoverage.PARTIAL] ([onCoverage]) and the tree keeps converging the slow way -
 * the worker retries the registration on a coarse cadence and drives the same synthetic [ContentStore.OVERFLOW]
 * pass meanwhile, so an edit under the unwatched subtree lands LATE rather than NEVER, and a raised limit or a
 * fixed permission returns the tree to [WatchCoverage.WHOLE] with no restart.
 *
 * **The ROOT ITSELF is watched, and that is what BOUNDS the D5 detection lag.** Every other root-loss
 * detector in the system is driven by TRAFFIC — a write's probe, a rebuild's probe — so a root nobody
 * writes to has none, and an unmount or a root rename produces no child event to notice either (it may
 * produce no event at all, and where it does, the JDK merely INVALIDATES the key). An idle root would
 * therefore keep serving carried-forward bytes as `available: true` forever, which is not a lag but a
 * broken invariant. So the worker POLLS with a [livenessInterval] timeout instead of blocking in `take()`:
 * every tick with no event re-probes the root ([rootIsAlive]), and the root's OWN key going invalid is
 * treated as the same condition. Root gone → [onRootLost] (mark + converge, see [LocalContentStore.watch])
 * and the worker STOPS, because D5 unavailability is sticky until a restart and there is nothing left to
 * watch. Detection is therefore bounded by [livenessInterval] for EVERY available root, traffic or not —
 * the timeout is the guarantee and key-invalidation is only the fast path on platforms that signal it.
 *
 * Platform note (§B1): Linux is inotify (milliseconds); macOS is the JDK's polling implementation
 * (multi-second) — the 5 s latency criterion binds Linux, the deployment platform.
 */
class FileWatcher(
    root: Path,
    private val ignoreRules: IgnoreRules,
    excluded: Collection<Path>,
    private val onChange: (TreePath) -> Unit,
    /**
     * Invoked at most ONCE when the WORKER dies - it exits NON-gracefully (a `WatchService` fault, not a close or
     * an interrupt) and this tree therefore stops converging on events forever. Without it that death is SILENT:
     * the server keeps serving, looks healthy, and quietly never sees another edit. The one-per-event catch below
     * is a different thing entirely - it absorbs a bad event and keeps the loop alive.
     *
     * Strictly the WATCHER-broke-while-the-root-is-FINE condition, which is why it is not [onRootLost]: the two
     * carry different causes on the health wire (`watcher_failed` vs `vanished`) and prescribe different operator
     * actions (restart the server vs bring the disk back), so a root that GOES AWAY must never be reported as a
     * thread that died.
     *
     * It is NOT the coverage detector (that is [onCoverage]): a subtree this watcher cannot register leaves a tree
     * that still converges, slowly, and belongs nowhere near a sticky failure.
     */
    private val onFailure: (Throwable) -> Unit = {},
    /**
     * Reports how much of the tree this watcher can actually SEE, in BOTH directions ([WatchCoverage]) - a
     * CONVERGENCE fact, never an availability one. A subtree the OS refuses to register (the inotify watch limit,
     * a permission-denied directory) leaves the root THERE and every byte of it readable; what it costs is EVENTS
     * under that subtree, so those edits converge on the periodic full pass this watcher drives ([retryCoverage])
     * instead of on their own event. Reporting it as a root FAULT would 503 a healthy root over a host-wide kernel
     * limit, stickily, until a restart that only re-registers, re-fails and re-marks.
     */
    private val onCoverage: (WatchCoverage) -> Unit = {},
    /**
     * Every GAP this watcher knows it has (C2) - and it already knew about all of them; it just never said so.
     *
     * The observation epoch turns "the last scan saw this page and this one does not" into a DELETE, which is sound
     * only while nothing was MISSED in between. So the epoch needs the one thing a watcher can honestly supply: not
     * the KIND of each event (see [onChange], which stays uninterpreted for exactly this reason) but the admission
     * that events were dropped, a subtree stopped being watched, a key died under a directory that is still there,
     * or the tree was swapped out from under the watches. Each one revokes the root's observation wholesale, because
     * a gap is not scoped to the paths we happened to notice it on.
     */
    private val onBreak: (BreakCause) -> Unit = {},
    /**
     * Is the watched tree still THERE? The store's own probe ([ContentStore.available]) when the production
     * wiring passes it, so the watcher and every other root-loss detector share ONE notion of gone. The default
     * is that same probe, bound here ([rootLivenessProbe]) - a watcher constructed without a store must not
     * detect LESS than one constructed with it.
     */
    private val rootIsAlive: () -> Boolean = boundRootProbe(root),
    /**
     * Invoked at most ONCE when the ROOT ITSELF is gone (see the class doc). The production wiring closes over the
     * D5 marker and the rebuild scheduler ([LocalContentStore.watch]) - publication is load-bearing, not
     * bookkeeping: an unmarked root keeps serving its carried-forward section as `available: true`.
     */
    private val onRootLost: () -> Unit = {},
    /** How long the worker blocks on one poll - and therefore the BOUND on root-loss detection for an idle root. */
    private val livenessInterval: Duration = LIVENESS_INTERVAL,
    /** How often a PARTIALLY-covered tree retries its registration and drives a converging pass ([retryCoverage]). */
    private val coverageRetryInterval: Duration = COVERAGE_RETRY_INTERVAL,
) : AutoCloseable {

    private val root: Path = root.toAbsolutePath().normalize()

    /** Only exclusions STRICTLY inside the root apply (see class doc): at-or-above-root ones can never receive app writes here. */
    private val excludedDirs: List<Path> = excluded
        .map { it.toAbsolutePath().normalize() }
        .filter { it != this.root && it.startsWith(this.root) }

    private val watchService = this.root.fileSystem.newWatchService()
    private val keys = ConcurrentHashMap<WatchKey, Path>()

    /** [onFailure]'s at-most-once contract, over its ONE detector: a worker that died. */
    private val failed = AtomicBoolean(false)

    /** The coverage this watcher last REPORTED - so only TRANSITIONS are published (see [reportCoverage]). */
    private val partial = AtomicBoolean(false)
    private val worker: Thread

    init {
        excludedDirs.forEach { dir ->
            logger.warn {
                "$dir is nested inside the content root ${this.root}: excluded from the watch — " +
                    "changes under it never trigger rebuilds (DATA_DIR-in-CONTENT_DIR policy, §B1)"
            }
        }
        // Reported BEFORE the worker starts, and the order is a CORRECTNESS constraint, not a preference (C2/B1).
        // The worker can re-register and re-report from its very first tick (an overflow, a retry), so a report
        // issued after it starts is racing one issued by it - and the CONSTRUCTING thread's `uncovered` is the
        // STALER of the two. A stale WHOLE landing on top of a fresh PARTIAL used to cost a slow rebuild; now it
        // poisons the OBSERVATION EPOCH, which would take delete authority over a tree it is not fully watching.
        // (The old order existed so a consumer never learned "partial" from a watcher not yet driving the recovery
        // pass. That is a latency argument against a correctness one, and it loses: the worker starts on the very
        // next line, and a PARTIAL that arrives a microsecond early costs nothing.)
        reportCoverage(registerTree(this.root))
        worker = thread(name = "plainbase-file-watcher", isDaemon = true) { processEvents() }
        logger.info { "watching ${this.root} (${keys.size} directories)" }
    }

    override fun close() {
        watchService.close() // wakes the worker's take() with ClosedWatchServiceException
        worker.join(ContentStore.WATCH_CLOSE_BOUND_MILLIS) // the port's close bound, which the shutdown budget counts
    }

    /**
     * Registers a NEW subtree (a directory created on sight). It can only ever LOSE coverage, never restore it:
     * what it walked is one branch, and the tree's other branches are not its to answer for - a `registerTree`
     * that came back clean HERE says nothing about the `chmod 000` directory two levels over.
     */
    private fun registerSubtree(start: Path) {
        val uncovered = registerTree(start)
        if (uncovered.isNotEmpty()) reportCoverage(uncovered)
    }

    /**
     * Re-walks the WHOLE tree (idempotent - a watched dir keeps its key) and reports the coverage it actually
     * achieved. The only call that can report [WatchCoverage.WHOLE], because it is the only one that looked
     * everywhere.
     */
    private fun registerWholeTree() = reportCoverage(registerTree(root))

    /**
     * Registers [start] and every non-ignored, non-excluded directory below it. Idempotent — a watched dir keeps
     * its key. Returns the directories whose WATCH COVERAGE was lost (the [logRegistrationFailure] classification);
     * an empty list means the tree is watched whole.
     */
    private fun registerTree(start: Path): List<Path> {
        val uncovered = mutableListOf<Path>()
        try {
            Files.walkFileTree(
                start,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (isExcluded(dir)) return FileVisitResult.SKIP_SUBTREE
                        if (dir != root && isIgnoredDir(dir)) return FileVisitResult.SKIP_SUBTREE
                        try {
                            keys[
                                dir.register(
                                    watchService,
                                    StandardWatchEventKinds.ENTRY_CREATE,
                                    StandardWatchEventKinds.ENTRY_DELETE,
                                    StandardWatchEventKinds.ENTRY_MODIFY,
                                ),
                            ] = dir
                        } catch (e: IOException) {
                            if (logRegistrationFailure(dir, e)) uncovered.add(dir)
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                            if (logRegistrationFailure(file, exc)) uncovered.add(file) // an unvisitable DIRECTORY is lost coverage
                        } else {
                            // A plain file's visit failure loses no coverage — its events come from
                            // the parent directory's key — so no dir-scoped WARN.
                            logger.debug(exc) { "watch registration walk could not visit $file" }
                        }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (e: IOException) {
            if (logRegistrationFailure(start, e)) uncovered.add(start)
        }
        return uncovered
    }

    /**
     * Lost coverage is REPORTED, never FAILED. The inotify watch limit is a host-wide kernel resource and a
     * permission-denied subtree is fixed in place: both leave a root that exists, reads correctly and serves every
     * byte it is asked for, so what they cost is CONVERGENCE SPEED, not availability. Answering them with
     * [onFailure] - sticky until a restart that would only re-register, re-fail and re-mark - would be a permanent,
     * restart-proof 503 the server inflicts on itself and cannot leave.
     *
     * BOTH transitions are reported, and only TRANSITIONS: the retry tick re-registers on a cadence, so a consumer
     * handed one report per tick would be told a condition it already knows. A raised limit or a fixed permission
     * clears the flag on the next retry, with no restart.
     *
     * Per-root by construction - one watcher, one root, one signal - so a sibling root is untouched.
     */
    private fun reportCoverage(uncovered: List<Path>) {
        val whole = uncovered.isEmpty()
        if (!partial.compareAndSet(expectedValue = whole, newValue = !whole)) return // no transition, nothing to say
        if (whole) {
            // INFO, not WARN: the condition is OVER, and the state an operator acts on lives on the health wire.
            logger.info { "watch coverage for $root is WHOLE again: its edits converge on their own events, as normal" }
        } else {
            logger.warn {
                "watch coverage for $root is PARTIAL: could not register ${bounded(uncovered)} - edits under them raise " +
                    "NO event, so this root converges on a full pass every $coverageRetryInterval until the registration " +
                    "succeeds (the tree is otherwise healthy and serves normally). Raise the inotify watch limit " +
                    "(fs.inotify.max_user_watches), or fix the directory permissions"
            }
        }
        onCoverage(if (whole) WatchCoverage.WHOLE else WatchCoverage.PARTIAL)
        // Losing coverage is also a BREAK (C2): edits under an unregistered subtree raise no event at all, so from
        // here on this watcher is sampling the tree rather than observing it, and an epoch cannot rest on a sample.
        // REGAINING it is not the mirror image - it does not re-open anything, because the pages that changed while
        // we were blind changed unseen. A fresh epoch has to be earned by a scan, like every other epoch.
        if (!whole) onBreak(BreakCause.COVERAGE_LOST)
    }

    /**
     * The PARTIAL-coverage recovery, driven on [coverageRetryInterval] by the worker loop: re-register the tree
     * (idempotent) and deliver the synthetic [ContentStore.OVERFLOW] - the SAME full-pass contract the overflow
     * branch already uses, so a consumer needs no new concept and an edit under an unwatched subtree lands LATE
     * rather than NEVER.
     *
     * The pass runs on the retry that CLEARS the flag too, and that is the point of doing it here rather than
     * conditionally: the edits made under the subtree while it was unwatched are exactly the ones no event will
     * ever arrive for, and this is the last pass that goes looking for them.
     */
    private fun retryCoverage() {
        registerWholeTree()
        onChange(ContentStore.OVERFLOW)
    }

    /** [onFailure], at most once - the worker can only die once, but `close()` racing a death must not double-report. */
    private fun fail(cause: Throwable) {
        if (!failed.compareAndSet(expectedValue = false, newValue = true)) return
        onBreak(BreakCause.WATCHER_DIED) // BEFORE the failure: the epoch must be dead before anything acts on the death
        onFailure(cause)
    }

    /**
     * The worker loop, wrapped so an UNEXPECTED death is reported rather than silent. The two graceful exits
     * (a close, an interrupt) return from [pollLoop] normally and notify nothing; anything else is the fault
     * [onFailure] exists for. `Exception`, not `Throwable`: a JVM `Error` (OOM/SOE) must still fail loudly.
     */
    private fun processEvents() {
        runCatching {
            pollLoop()
        }.onFailure { failure ->
            if (failure is Error) throw failure
            logger.error(failure) { "the watch worker for $root died; changes under it will NOT converge until a restart" }
            fail(failure)
        }
    }

    private fun pollLoop() {
        // The retry deadline is the WORKER's own, a plain local: nothing else reads it, and hanging the cadence on
        // a shared field would be a race to invent for no reason.
        var nextRetry = System.nanoTime() + coverageRetryInterval.inWholeNanoseconds
        while (true) {
            val key = runCatching {
                // NOT take(): a poll TIMEOUT is the root's heartbeat (see the class doc), and it is also what
                // keeps a watcher whose last key died from blocking forever with nothing left to wake it.
                watchService.poll(livenessInterval.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            }.getOrElse { failure ->
                when (failure) {
                    is ClosedWatchServiceException, is InterruptedException -> return
                    else -> throw failure
                }
            }
            // Coverage runs on its OWN, COARSE cadence - deliberately NOT hung on the liveness tick, which fires
            // every few seconds: the scheduler would coalesce the passes, but each pass it does run is O(corpus),
            // and a big corpus would rebuild itself into the ground for as long as one subtree stays unwatched.
            if (partial.load() && System.nanoTime() >= nextRetry) {
                nextRetry = System.nanoTime() + coverageRetryInterval.inWholeNanoseconds
                retryCoverage()
            }
            if (key == null) {
                if (rootLost()) return // an idle interval - the one tick that catches a root nobody is writing to
            } else if (processKey(key)) {
                return
            }
        }
    }

    /** Processes one key and reports whether the worker should stop. */
    private fun processKey(key: WatchKey): Boolean {
        val dir = keys[key]
        for (event in key.pollEvents()) {
            runCatching {
                deliver(dir, event)
            }.onFailure { failure ->
                if (failure is Error) throw failure
                logger.error(failure) { "watch event handling failed; the next rebuild converges regardless" }
            }
        }
        if (key.reset()) return false

        keys.remove(key)
        // A deleted SUBdirectory's key cancels itself; the delete event in its PARENT already scheduled the
        // rebuild that drops its entries. The ROOT's key is the different story: nothing above it is watched,
        // so its death is the only signal there is.
        return when {
            dir != root -> {
                if (cancellationIsAGap(dir)) onBreak(BreakCause.WATCH_KEY_CANCELLED)
                false
            }

            rootLost() -> true
            else -> {
                // The root's key died but its path is still traversable - the root directory was REPLACED (rename-in,
                // a remount over it). Watch coverage for the whole tree went with the old key, which no rebuild can
                // repair, so re-register and converge: the OVERFLOW recovery, for the same reason. And it is a BREAK
                // for a reason no rebuild can repair either: every page this epoch witnessed, it witnessed on the tree
                // that just went away.
                logger.warn { "the watch key for $root was invalidated but the path is still there: re-registering the tree" }
                onBreak(BreakCause.WATCH_KEY_CANCELLED)
                registerWholeTree()
                onChange(ContentStore.OVERFLOW)
                false
            }
        }
    }

    /**
     * Probes the root and, if it is GONE, publishes the loss ONCE - answering whether the worker should stop.
     * It should: D5 unavailability is sticky until a restart, so a surviving loop could only re-detect the same
     * loss every interval, and there is no tree left to watch.
     */
    private fun rootLost(): Boolean {
        if (rootIsAlive()) return false
        // Whatever happened under the root while it was away, we did not see it - and the worker stops here, so we
        // are not going to. The epoch dies with the tree, and it dies BEFORE the loss is published, so nothing that
        // reacts to the loss can still be holding delete authority over it.
        onBreak(BreakCause.ROOT_LOST)
        logger.error {
            "the content root $root is gone (unmounted, renamed, or deleted); marking it unavailable - it will " +
                "serve 503 until the path is restored and the server restarted"
        }
        onRootLost()
        return true
    }

    private fun deliver(dir: Path?, event: WatchEvent<*>) {
        if (event.kind() == StandardWatchEventKinds.OVERFLOW || dir == null) {
            logger.warn {
                "watch event queue overflow under ${dir ?: root}: an UNKNOWN set of events was dropped, so this root's " +
                    "observation has a hole in it. Re-registering the tree and scheduling a full pass"
            }
            // OVERFLOW is itself a BREAK, and it has to be its own signal (C2): the re-registration below can come
            // back WHOLE, and a WHOLE->WHOLE report publishes no transition at all - so before this, a dropped-event
            // storm could pass through the watcher leaving no trace anywhere. Past the queue bound, `rm -rf` and an
            // unmount are observationally IDENTICAL, and the epoch's answer to that is to refuse to guess: the tail
            // of the storm lands in limbo rather than being reaped.
            onBreak(BreakCause.OVERFLOW)
            // The dropped events may include directory CREATEs whose subtrees were therefore never
            // registered — the scheduled full pass converges index state but cannot restore watch
            // coverage, so re-walk the registrations first (idempotent; overflow is rare enough
            // that the walk's cost is irrelevant). See the class doc.
            registerWholeTree()
            onChange(ContentStore.OVERFLOW)
            return
        }
        val child = dir.resolve(event.context() as Path)
        if (isExcluded(child)) return
        val relative = relativeOf(child)
        if (ignoreRules.isIgnored(child.fileName.toString(), relative)) return
        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
            registerSubtree(child) // a created directory is registered on sight (§B1)
        }
        // TreePath.of NFC-normalizes (the boundary rule); a name it rejects cannot be content.
        val treePath = TreePath.of(relative) ?: return
        onChange(treePath)
    }

    /** Content-root-relative `/`-joined path of [path] (which is always under [root] here). */
    private fun relativeOf(path: Path): String =
        root.relativize(path.toAbsolutePath().normalize()).joinToString("/")

    private fun isExcluded(path: Path): Boolean {
        val normalized = path.toAbsolutePath().normalize()
        return excludedDirs.any { normalized.startsWith(it) }
    }

    private fun isIgnoredDir(dir: Path): Boolean = ignoreRules.isIgnored(dir.fileName.toString(), relativeOf(dir))

    companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * The root-liveness poll interval - the D5 detection bound for a root with no traffic. Deliberately the
         * same 5 s as the §B1 change-to-visible criterion: one number an operator has to remember, and a probe
         * this cheap (three `stat`s per root) has no reason to be rarer.
         */
        internal val LIVENESS_INTERVAL = 5.seconds

        /**
         * How often a PARTIALLY-covered tree re-registers and drives a converging full pass - the bound on how
         * late an edit under an unwatched subtree can land, and on how long a fixed permission (or a raised
         * inotify limit) stays reported as PARTIAL.
         *
         * Deliberately COARSE, and deliberately not the liveness tick: the pass is a whole-corpus rebuild, so on a
         * large corpus a per-tick pass would be a rebuild storm for as long as one directory stayed unreadable.
         * Five minutes is the trade the degraded state is meant to make - slow convergence beats both a lie and a
         * self-inflicted outage.
         */
        internal val COVERAGE_RETRY_INTERVAL = 5.minutes

        /**
         * A cancelled SUBdirectory key: is it a GAP in the observation, or a delete we watched happen? (C2)
         *
         * The directory is the whole answer. An ordinary `rm -rf subdir` delivers every child's ENTRY_DELETE on that
         * subdirectory's own key BEFORE the key dies, and leaves NO directory behind - so the deletes were OBSERVED,
         * the next scan confirms them, and the epoch may honestly reap them. A key that dies while its directory is
         * STILL THERE is the opposite: an unmounted submount (the mountpoint stays), a rename-flip that swapped the
         * subtree out from under the inode the watch was tracking, a remount. Those deliver NO child deletes at all,
         * so their pages would vanish from the next scan with nothing having been seen - which is exactly the
         * inference the whole design forbids.
         *
         * It errs toward BREAK, and deliberately: a delete-then-recreate that lands between the cancellation and this
         * `stat` reads as "still there" and costs a re-earned epoch. The other way round costs a corpus.
         */
        internal fun cancellationIsAGap(dir: Path?): Boolean {
            if (dir == null) return true // an unknown key is not one we can exonerate
            if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return false // the directory was DELETED, and we watched it go
            logger.warn {
                "the watch key for $dir died while the directory is still there (an unmounted submount, a rename-flip, a " +
                    "remount): its pages can change with NO event, so this root's observation is broken until a fresh scan"
            }
            return true
        }

        /** The first few uncovered directories: an inotify-limit failure can name thousands of them, and the WARN cannot. */
        private fun bounded(uncovered: List<Path>): String =
            uncovered.take(UNCOVERED_DIRECTORY_LIMIT).joinToString() +
                if (uncovered.size > UNCOVERED_DIRECTORY_LIMIT) {
                    " (+${uncovered.size - UNCOVERED_DIRECTORY_LIMIT} more)"
                } else {
                    ""
                }

        private const val UNCOVERED_DIRECTORY_LIMIT = 3

        /**
         * Registration-failure classification — and the ONE place that decides whether coverage was LOST
         * (the return). A path that VANISHED mid-walk is the harmless deletion race — its delete event in the
         * parent's key already schedules the convergence pass — so it stays at DEBUG and costs no coverage
         * (false). Anything else (the inotify watch limit on large trees, permissions) leaves a silently
         * un-watched subtree behind a healthy-looking server, so it WARNs, naming the directory and the
         * consequence, and answers TRUE: the watcher then reports [WatchCoverage.PARTIAL] ([reportCoverage])
         * rather than a coverage it does not have. Internal, not private: the failure modes are not cheaply
         * fakeable through a real `WatchService`, so the classification is unit-tested directly.
         */
        internal fun logRegistrationFailure(dir: Path, failure: IOException): Boolean {
            if (failure is NoSuchFileException) {
                logger.debug { "directory vanished during watch registration (deletion race): $dir" }
                return false
            }
            logger.warn(failure) {
                "could not watch $dir: edits under it will NOT trigger rebuilds until a restart or a manual rescan"
            }
            return true
        }
    }
}

/**
 * The default [FileWatcher.rootIsAlive]: the store's own runtime probe ([rootLivenessProbe]), bound to [root]
 * once, here — never re-derived per tick, since the identity half of the probe is the tree captured at capture
 * time. Production passes `LocalContentStore::available`, which is this same probe.
 */
private fun boundRootProbe(root: Path): () -> Boolean {
    val normalized = root.toAbsolutePath().normalize()
    val probe = rootLivenessProbe(normalized)
    return { probe(normalized) }
}
