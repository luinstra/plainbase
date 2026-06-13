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
import kotlin.concurrent.thread

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
 * Platform note (§B1): Linux is inotify (milliseconds); macOS is the JDK's polling implementation
 * (multi-second) — the 5 s latency criterion binds Linux, the deployment platform.
 */
class FileWatcher(
    root: Path,
    private val ignoreRules: IgnoreRules,
    excluded: Collection<Path>,
    private val onChange: (TreePath) -> Unit,
) : AutoCloseable {

    private val root: Path = root.toAbsolutePath().normalize()

    /** Only exclusions STRICTLY inside the root apply (see class doc): at-or-above-root ones can never receive app writes here. */
    private val excludedDirs: List<Path> = excluded
        .map { it.toAbsolutePath().normalize() }
        .filter { it != this.root && it.startsWith(this.root) }

    private val watchService = this.root.fileSystem.newWatchService()
    private val keys = ConcurrentHashMap<WatchKey, Path>()
    private val worker: Thread

    init {
        excludedDirs.forEach { dir ->
            logger.warn {
                "$dir is nested inside the content root ${this.root}: excluded from the watch — " +
                    "changes under it never trigger rebuilds (DATA_DIR-in-CONTENT_DIR policy, §B1)"
            }
        }
        registerTree(this.root)
        worker = thread(name = "plainbase-file-watcher", isDaemon = true) { processEvents() }
        logger.info { "watching ${this.root} (${keys.size} directories)" }
    }

    override fun close() {
        watchService.close() // wakes the worker's take() with ClosedWatchServiceException
        worker.join(5_000)
    }

    /** Registers [start] and every non-ignored, non-excluded directory below it. Idempotent — a watched dir keeps its key. */
    private fun registerTree(start: Path) {
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
                            logRegistrationFailure(dir, e)
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                            logRegistrationFailure(file, exc) // an unvisitable DIRECTORY is lost watch coverage
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
            logRegistrationFailure(start, e)
        }
    }

    private fun processEvents() {
        while (true) {
            val key = try {
                watchService.take()
            } catch (_: ClosedWatchServiceException) {
                return
            } catch (_: InterruptedException) {
                return
            }
            val dir = keys[key]
            for (event in key.pollEvents()) {
                try {
                    deliver(dir, event)
                } catch (e: Exception) {
                    logger.error(e) { "watch event handling failed; the next rebuild converges regardless" }
                }
            }
            // A deleted directory's key cancels itself; the delete event in its PARENT already
            // scheduled the rebuild that drops its entries.
            if (!key.reset()) keys.remove(key)
        }
    }

    private fun deliver(dir: Path?, event: WatchEvent<*>) {
        if (event.kind() == StandardWatchEventKinds.OVERFLOW || dir == null) {
            logger.debug { "watch event queue overflow under ${dir ?: root}: re-registering the tree and scheduling a full pass" }
            // The dropped events may include directory CREATEs whose subtrees were therefore never
            // registered — the scheduled full pass converges index state but cannot restore watch
            // coverage, so re-walk the registrations first (idempotent; overflow is rare enough
            // that the walk's cost is irrelevant). See the class doc.
            registerTree(root)
            onChange(ContentStore.OVERFLOW)
            return
        }
        val child = dir.resolve(event.context() as Path)
        if (isExcluded(child)) return
        val relative = relativeOf(child)
        if (ignoreRules.isIgnored(child.fileName.toString(), relative)) return
        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
            registerTree(child) // a created directory is registered on sight (§B1)
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
         * Registration-failure visibility policy (review finding): a path that VANISHED mid-walk is
         * the harmless deletion race — its delete event in the parent's key already schedules the
         * convergence pass — and stays at DEBUG. Anything else (the inotify watch limit on large
         * trees, permissions) leaves a silently un-watched subtree behind a healthy-looking server,
         * so it WARNs, naming the directory and the consequence. Startup still proceeds either way:
         * the index itself works and rescan converges on demand — degraded watching beats failing
         * hard. Internal, not private: the failure modes are not cheaply fakeable through a real
         * `WatchService`, so the classification is unit-tested directly.
         */
        internal fun logRegistrationFailure(dir: Path, failure: IOException) {
            if (failure is NoSuchFileException) {
                logger.debug { "directory vanished during watch registration (deletion race): $dir" }
            } else {
                logger.warn(failure) {
                    "could not watch $dir: edits under it will NOT trigger rebuilds until a restart or a manual rescan"
                }
            }
        }
    }
}
