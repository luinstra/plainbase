package com.plainbase.frameworks.filesystem

import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionWatchSink
import com.plainbase.frameworks.lifecycle.CompletionWait
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal enum class DiscussionWatchRole {
    APP,
    COLLECTION,
}

internal sealed interface DiscussionRoute {
    data object Ignore : DiscussionRoute
    data object Collection : DiscussionRoute
    data class Discussion(val id: DiscussionId) : DiscussionRoute
}

internal object DiscussionRouting {
    fun route(role: DiscussionWatchRole, childName: String): DiscussionRoute = when (role) {
        DiscussionWatchRole.APP -> if (childName == "discussions") DiscussionRoute.Collection else DiscussionRoute.Ignore
        DiscussionWatchRole.COLLECTION -> {
            val id = DiscussionId.of(childName)?.takeIf { it.value == childName }
            if (id == null) DiscussionRoute.Ignore else DiscussionRoute.Discussion(id)
        }
    }
}

@Suppress("TooGenericExceptionCaught", "LoopWithTooManyJumpStatements")
class DiscussionWatcher private constructor(
    roots: List<Path>,
    sinkFor: (Path) -> DiscussionWatchSink,
    private val watchServiceFactory: () -> WatchService,
    private val registerDirectory: (Path, WatchService) -> WatchKey,
    private val readDirectoryAttributes: (Path) -> BasicFileAttributes,
    private val rescanInterval: Duration,
) : AutoCloseable {
    private val roots = roots.map { RootWatch(it.toAbsolutePath().normalize(), sinkFor(it.toAbsolutePath().normalize())) }
    private val registrations = mutableMapOf<LogicalKey, Registration>()
    private val byKey = mutableMapOf<WatchKey, Registration>()
    private val lifecycleLock = Any()
    private val watchService = AtomicReference<WatchService?>(null)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var registrationCountsForTest = 0 to 0
    private val tickCountForTest = AtomicInteger()

    private val worker: Thread

    init {
        require(rescanInterval.isPositive())
        try {
            watchService.set(watchServiceFactory())
        } catch (failure: Exception) {
            warn(failure, "discussion watch service could not start")
        }
        this.roots.forEach { root ->
            registerSafely(root)
            signalCollection(root)
        }
        worker = thread(name = "plainbase-discussion-watcher", isDaemon = true) { processEvents() }
    }

    override fun close() {
        val service = synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            watchService.getAndSet(null)
        }
        runCatching { service?.close() }.onFailure { failure ->
            if (failure is Error) throw failure
            logger.warn(failure) { "closing the discussion watch service failed" }
        }
        worker.interrupt()
        CompletionWait.run {
            awaitForever(
                await = { millis -> worker.join(millis) },
                completed = { !worker.isAlive },
            )
            captureCurrentInterrupt()
        }
    }

    internal fun workerForTest(): Thread = worker

    internal fun registrationCountsForTest(): Pair<Int, Int> = registrationCountsForTest

    internal fun tickCountForTest(): Int = tickCountForTest.get()

    private fun processEvents() {
        try {
            var nextTick = System.nanoTime() + rescanInterval.inWholeNanoseconds
            while (!closed.get()) {
                val service = watchService.get()
                if (service == null) {
                    if (!waitUntil(nextTick)) return
                } else {
                    val remaining = (nextTick - System.nanoTime()).coerceAtLeast(0)
                    val key = try {
                        service.poll(remaining, TimeUnit.NANOSECONDS)
                    } catch (_: InterruptedException) {
                        if (!closed.get()) Thread.currentThread().interrupt()
                        return
                    } catch (_: ClosedWatchServiceException) {
                        if (closed.get()) return
                        disableService(IllegalStateException("discussion watch service closed"))
                        null
                    } catch (failure: Exception) {
                        disableService(failure)
                        null
                    }
                    if (key != null) processKey(key)
                }
                if (closed.get()) return
                if (System.nanoTime() >= nextTick) {
                    tick()
                    nextTick = System.nanoTime() + rescanInterval.inWholeNanoseconds
                }
            }
        } finally {
            if (!closed.get()) logger.warn { "discussion watcher worker exited while the watcher remained open" }
        }
    }

    private fun processKey(key: WatchKey) {
        val registration = byKey[key]
        if (registration == null || registrations[registration.logical]?.key != key) {
            forget(key)
            rescanAll()
            return
        }
        var overflow = false
        try {
            for (event in key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    overflow = true
                    break
                }
                val childName = (event.context() as? Path)?.fileName?.toString() ?: event.context()?.toString() ?: continue
                when (val route = DiscussionRouting.route(registration.logical.role, childName)) {
                    DiscussionRoute.Ignore -> Unit
                    DiscussionRoute.Collection -> {
                        registerSafely(registration.root)
                        signalCollection(registration.root)
                    }
                    is DiscussionRoute.Discussion -> runCatching {
                        registration.root.sink.discussionChanged(route.id)
                    }.onFailure { failure ->
                        if (failure is Error) throw failure
                        logger.warn(failure) { "discussion change signal failed for ${route.id.value}" }
                    }
                }
            }
            if (overflow) rescanAll()
        } finally {
            if (!key.reset()) {
                forget(registration)
                registerSafely(registration.root)
                signalCollection(registration.root)
            }
        }
    }

    private fun tick() {
        tickCountForTest.incrementAndGet()
        if (watchService.get() == null) {
            try {
                val service = watchServiceFactory()
                val closeCreated = synchronized(lifecycleLock) {
                    closed.get() || !watchService.compareAndSet(null, service)
                }
                if (closeCreated) service.close()
            } catch (failure: Exception) {
                warn(failure, "discussion watch service retry failed")
            }
        }
        roots.forEach { root ->
            registerSafely(root)
            signalCollection(root)
        }
    }

    private fun rescanAll() {
        roots.forEach { root ->
            registerSafely(root)
            signalCollection(root)
        }
    }

    private fun registerSafely(root: RootWatch) {
        val service = watchService.get() ?: return
        try {
            registerRoot(root, service)
        } catch (failure: RootAttributeFailure) {
            root.attributeFailure(failure)
        } catch (failure: Exception) {
            disableService(failure)
        }
    }

    private fun registerRoot(root: RootWatch, service: WatchService) {
        val app = root.path.resolve(".plainbase")
        val appAttributes = directoryAttributes(root, app) ?: run {
            forget(LogicalKey(root, DiscussionWatchRole.APP))
            forget(LogicalKey(root, DiscussionWatchRole.COLLECTION))
            return
        }
        registerOne(root, DiscussionWatchRole.APP, app, appAttributes, service)
        val collection = app.resolve("discussions")
        val collectionAttributes = directoryAttributes(root, collection) ?: run {
            forget(LogicalKey(root, DiscussionWatchRole.COLLECTION))
            return
        }
        val currentApp = directoryAttributes(root, app)
        if (currentApp?.fileKey() != appAttributes.fileKey()) return
        registerOne(root, DiscussionWatchRole.COLLECTION, collection, collectionAttributes, service)
    }

    private fun registerOne(
        root: RootWatch,
        role: DiscussionWatchRole,
        path: Path,
        attributes: BasicFileAttributes,
        service: WatchService,
    ) {
        val logical = LogicalKey(root, role)
        val existing = registrations[logical]
        if (existing != null && existing.fileKey == attributes.fileKey() && existing.key.isValid) return
        existing?.let(::forget)
        val key = try {
            registerDirectory(path, service)
        } catch (failure: IOException) {
            throw RootAttributeFailure(path, failure)
        }
        val registration = Registration(root, logical, path, attributes.fileKey(), key)
        registrations[logical] = registration
        byKey[key] = registration
        updateRegistrationCountsForTest()
    }

    private fun directoryAttributes(root: RootWatch, path: Path): BasicFileAttributes? = try {
        readDirectoryAttributes(path).also { root.attributeRecovered(path) }
            .takeIf { it.isDirectory && !it.isSymbolicLink }
    } catch (_: NoSuchFileException) {
        null
    } catch (failure: IOException) {
        throw RootAttributeFailure(path, failure)
    }

    private fun forget(logical: LogicalKey) {
        val registration = registrations.remove(logical) ?: return
        byKey.remove(registration.key)
        registration.key.cancel()
        updateRegistrationCountsForTest()
    }

    private fun forget(registration: Registration) {
        byKey.remove(registration.key)
        if (registrations[registration.logical] == registration) registrations.remove(registration.logical)
        registration.key.cancel()
        updateRegistrationCountsForTest()
    }

    private fun forget(key: WatchKey) {
        val registration = byKey.remove(key)
        if (registration != null && registrations[registration.logical] == registration) {
            registrations.remove(registration.logical)
        }
        key.cancel()
        updateRegistrationCountsForTest()
    }

    private fun updateRegistrationCountsForTest() {
        registrationCountsForTest = registrations.size to byKey.size
    }

    private fun disableService(failure: Exception) {
        warn(failure, "discussion watcher fell back to periodic registration")
        val service = watchService.getAndSet(null)
        registrations.values.forEach { it.key.cancel() }
        registrations.clear()
        byKey.clear()
        updateRegistrationCountsForTest()
        runCatching { service?.close() }.onFailure { closeFailure ->
            if (closeFailure is Error) throw closeFailure
            logger.warn(closeFailure) { "closing the failed discussion watch service failed" }
        }
    }

    private fun signalCollection(root: RootWatch) {
        runCatching(root.sink::collectionChanged).onFailure { failure ->
            if (failure is Error) throw failure
            logger.warn(failure) { "discussion collection signal failed for ${root.path}" }
        }
    }

    private fun waitUntil(deadline: Long): Boolean {
        val remaining = (deadline - System.nanoTime()).coerceAtLeast(0)
        if (remaining == 0L) return true
        try {
            TimeUnit.NANOSECONDS.sleep(remaining)
            return true
        } catch (_: InterruptedException) {
            if (!closed.get()) Thread.currentThread().interrupt()
            return false
        }
    }

    private fun warn(failure: Exception, message: String) {
        logger.warn(failure) { "$message; periodic scans will retry" }
    }

    private class RootWatch(val path: Path, val sink: DiscussionWatchSink) {
        private val attributeFailureStates = mutableMapOf<Path, String>()

        fun attributeFailure(failure: RootAttributeFailure) {
            val signature = "${failure.path}: ${failure.cause?.javaClass?.name}: ${failure.cause?.message}"
            if (attributeFailureStates[failure.path] == signature) return
            attributeFailureStates[failure.path] = signature
            logger.warn(failure.cause) { "discussion watcher could not inspect $signature; that root will retry on the next tick" }
        }

        fun attributeRecovered(path: Path) {
            val previous = attributeFailureStates.remove(path) ?: return
            logger.info { "discussion watcher resumed attribute checks after $previous" }
        }
    }

    private class RootAttributeFailure(val path: Path, cause: IOException) : Exception(cause)

    private data class LogicalKey(val root: RootWatch, val role: DiscussionWatchRole)

    private data class Registration(
        val root: RootWatch,
        val logical: LogicalKey,
        val path: Path,
        val fileKey: Any?,
        val key: WatchKey,
    )

    companion object {
        private val logger = KotlinLogging.logger {}
        private val noOpSink = object : DiscussionWatchSink {
            override fun discussionChanged(id: DiscussionId) = Unit
            override fun collectionChanged() = Unit
        }

        fun start(
            roots: List<Path>,
            sinkFor: (Path) -> DiscussionWatchSink,
            watchServiceFactory: () -> WatchService = { FileSystems.getDefault().newWatchService() },
            registerDirectory: (Path, WatchService) -> WatchKey = { directory, service ->
                directory.register(
                    service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                )
            },
            rescanInterval: Duration = 60.seconds,
            readDirectoryAttributes: (Path) -> BasicFileAttributes = { path ->
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            },
        ): DiscussionWatcher {
            val safeInterval = rescanInterval.takeIf { it.isPositive() } ?: 60.seconds
            val safeSinkFor: (Path) -> DiscussionWatchSink = { root ->
                try {
                    sinkFor(root)
                } catch (failure: Exception) {
                    logger.warn(failure) { "discussion watcher sink could not be created for $root" }
                    noOpSink
                }
            }
            return DiscussionWatcher(roots, safeSinkFor, watchServiceFactory, registerDirectory, readDirectoryAttributes, safeInterval)
        }
    }
}
