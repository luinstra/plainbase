@file:OptIn(ExperimentalAtomicApi::class)

package com.plainbase.frameworks.filesystem

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.TreePath
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
     * Invoked at most ONCE when this watcher can no longer converge its tree, from either detector: the worker
     * exiting NON-gracefully (a `WatchService` fault, not a close or an interrupt), or REGISTRATION COVERAGE
     * being lost ([WatchCoverageLost]). Without it either failure is SILENT: the server keeps serving, looks
     * healthy, and simply stops converging on this tree forever. The one-per-event catch below is a different
     * thing entirely - it absorbs a bad event and keeps the loop alive.
     *
     * Strictly the WATCHER-broke-while-the-root-is-FINE condition, which is why it is not [onRootLost]: the two
     * carry different causes on the health wire (`watcher_failed` vs `vanished`) and prescribe different operator
     * actions (restart the server vs bring the disk back), so a root that GOES AWAY must never be reported as a
     * thread that died.
     */
    private val onFailure: (Throwable) -> Unit = {},
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
) : AutoCloseable {

    private val root: Path = root.toAbsolutePath().normalize()

    /** Only exclusions STRICTLY inside the root apply (see class doc): at-or-above-root ones can never receive app writes here. */
    private val excludedDirs: List<Path> = excluded
        .map { it.toAbsolutePath().normalize() }
        .filter { it != this.root && it.startsWith(this.root) }

    private val watchService = this.root.fileSystem.newWatchService()
    private val keys = ConcurrentHashMap<WatchKey, Path>()

    /** [onFailure]'s at-most-once contract, over BOTH detectors (a worker death, lost coverage). */
    private val failed = AtomicBoolean(false)
    private val worker: Thread

    init {
        excludedDirs.forEach { dir ->
            logger.warn {
                "$dir is nested inside the content root ${this.root}: excluded from the watch — " +
                    "changes under it never trigger rebuilds (DATA_DIR-in-CONTENT_DIR policy, §B1)"
            }
        }
        val uncovered = registerTree(this.root)
        worker = thread(name = "plainbase-file-watcher", isDaemon = true) { processEvents() }
        logger.info { "watching ${this.root} (${keys.size} directories)" }
        // Reported AFTER the worker starts, so what coverage we DO have is live either way - but reported, not
        // merely logged: a watcher that registered part of its tree and then answered "watching" is a server
        // that serves this root's stale bytes as fresh, forever, with no event, no rebuild and no 503.
        failIfUncovered(uncovered)
    }

    override fun close() {
        watchService.close() // wakes the worker's take() with ClosedWatchServiceException
        worker.join(ContentStore.WATCH_CLOSE_BOUND_MILLIS) // the port's close bound, which the shutdown budget counts
    }

    /** Registers [start]'s subtree, and FAILS this watcher if any of it could not be covered ([failIfUncovered]). */
    private fun register(start: Path) = failIfUncovered(registerTree(start))

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
     * Lost coverage FAILS this watcher, exactly like a dead worker - because it IS one, from where the operator
     * stands: the tree stops converging, silently, behind a server that keeps answering 200. The inotify watch
     * limit and a permission-denied subtree were previously a WARN in a log nobody reads, and nothing else in the
     * system can notice them (the idle tick only asks whether the root still EXISTS, and it does).
     *
     * Per-root by construction - one watcher, one root, one signal - so a sibling root's watcher runs on.
     */
    private fun failIfUncovered(uncovered: List<Path>) {
        if (uncovered.isEmpty()) return
        fail(WatchCoverageLost(root, uncovered))
    }

    /** [onFailure], at most once, whichever detector got there first. */
    private fun fail(cause: Throwable) {
        if (failed.compareAndSet(expectedValue = false, newValue = true)) onFailure(cause)
    }

    /**
     * The worker loop, wrapped so an UNEXPECTED death is reported rather than silent. The two graceful exits
     * (a close, an interrupt) return from [pollLoop] normally and notify nothing; anything else is the fault
     * [onFailure] exists for. `Exception`, not `Throwable`: a JVM `Error` (OOM/SOE) must still fail loudly.
     */
    private fun processEvents() {
        try {
            pollLoop()
        } catch (e: Exception) {
            logger.error(e) { "the watch worker for $root died; changes under it will NOT converge until a restart" }
            fail(e)
        }
    }

    private fun pollLoop() {
        while (true) {
            val key = try {
                // NOT take(): a poll TIMEOUT is the root's heartbeat (see the class doc), and it is also what
                // keeps a watcher whose last key died from blocking forever with nothing left to wake it.
                watchService.poll(livenessInterval.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            } catch (_: ClosedWatchServiceException) {
                return
            } catch (_: InterruptedException) {
                return
            }
            if (key == null) {
                if (rootLost()) return // an idle interval - the one tick that catches a root nobody is writing to
                continue
            }
            val dir = keys[key]
            for (event in key.pollEvents()) {
                try {
                    deliver(dir, event)
                } catch (e: Exception) {
                    logger.error(e) { "watch event handling failed; the next rebuild converges regardless" }
                }
            }
            if (key.reset()) continue
            keys.remove(key)
            // A deleted SUBdirectory's key cancels itself; the delete event in its PARENT already scheduled the
            // rebuild that drops its entries. The ROOT's key is the different story: nothing above it is watched,
            // so its death is the only signal there is.
            if (dir != root) continue
            if (rootLost()) return
            // The root's key died but its path is still traversable - the root directory was REPLACED (rename-in,
            // a remount over it). Watch coverage for the whole tree went with the old key, which no rebuild can
            // repair, so re-register and converge: the OVERFLOW recovery, for the same reason.
            logger.warn { "the watch key for $root was invalidated but the path is still there: re-registering the tree" }
            register(root)
            onChange(ContentStore.OVERFLOW)
        }
    }

    /**
     * Probes the root and, if it is GONE, publishes the loss ONCE - answering whether the worker should stop.
     * It should: D5 unavailability is sticky until a restart, so a surviving loop could only re-detect the same
     * loss every interval, and there is no tree left to watch.
     */
    private fun rootLost(): Boolean {
        if (rootIsAlive()) return false
        logger.error {
            "the content root $root is gone (unmounted, renamed, or deleted); marking it unavailable - it will " +
                "serve 503 until the path is restored and the server restarted"
        }
        onRootLost()
        return true
    }

    private fun deliver(dir: Path?, event: WatchEvent<*>) {
        if (event.kind() == StandardWatchEventKinds.OVERFLOW || dir == null) {
            logger.debug { "watch event queue overflow under ${dir ?: root}: re-registering the tree and scheduling a full pass" }
            // The dropped events may include directory CREATEs whose subtrees were therefore never
            // registered — the scheduled full pass converges index state but cannot restore watch
            // coverage, so re-walk the registrations first (idempotent; overflow is rare enough
            // that the walk's cost is irrelevant). See the class doc.
            register(root)
            onChange(ContentStore.OVERFLOW)
            return
        }
        val child = dir.resolve(event.context() as Path)
        if (isExcluded(child)) return
        val relative = relativeOf(child)
        if (ignoreRules.isIgnored(child.fileName.toString(), relative)) return
        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
            register(child) // a created directory is registered on sight (§B1)
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
         * Registration-failure classification — and the ONE place that decides whether coverage was LOST
         * (the return). A path that VANISHED mid-walk is the harmless deletion race — its delete event in the
         * parent's key already schedules the convergence pass — so it stays at DEBUG and costs no coverage
         * (false). Anything else (the inotify watch limit on large trees, permissions) leaves a silently
         * un-watched subtree behind a healthy-looking server, so it WARNs, naming the directory and the
         * consequence, and answers TRUE: the watcher then FAILS this root ([failIfUncovered]) rather than
         * reporting a coverage it does not have. Internal, not private: the failure modes are not cheaply
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
 * Part of the tree could not be registered: edits under [uncovered] will never schedule a rebuild, so this root
 * is live but only PARTLY watched — which the server cannot repair on its own and no other detector can even see.
 * Carried to the watcher's `onFailure` exactly like a worker death, because for the operator it is the same
 * outage (`watcher_failed`: restart the server; the disk is fine).
 */
class WatchCoverageLost(root: Path, val uncovered: List<Path>) : IOException(
    "watch coverage lost under $root: could not register ${uncovered.take(3).joinToString()}" +
        (if (uncovered.size > 3) " (+${uncovered.size - 3} more)" else "") +
        " - edits under them would never trigger a rebuild",
)

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
