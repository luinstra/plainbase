package com.plainbase.frameworks.filesystem

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionWatchSink
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.Watchable
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DiscussionWatcherTest : FunSpec({
    test("routing table") {
        val id = DiscussionId.require("01900000-0000-7000-8000-000000000101")

        DiscussionRouting.route(DiscussionWatchRole.APP, "discussions") shouldBe DiscussionRoute.Collection
        DiscussionRouting.route(DiscussionWatchRole.APP, id.value) shouldBe DiscussionRoute.Ignore
        DiscussionRouting.route(DiscussionWatchRole.APP, ".plainbase") shouldBe DiscussionRoute.Ignore
        DiscussionRouting.route(DiscussionWatchRole.COLLECTION, id.value) shouldBe DiscussionRoute.Discussion(id)
        DiscussionRouting.route(DiscussionWatchRole.COLLECTION, ".plainbase") shouldBe DiscussionRoute.Ignore
        DiscussionRouting.route(DiscussionWatchRole.COLLECTION, "prefix-${id.value}") shouldBe DiscussionRoute.Ignore
        DiscussionRouting.route(DiscussionWatchRole.COLLECTION, "${id.value}/child") shouldBe DiscussionRoute.Ignore
    }

    test("a folder created deleted or renamed in the collection raises discussion changed") {
        withScope("pb-discussion-events") { root ->
            val collection = prepareCollection(root)
            val sink = RecordingDiscussionSink()
            DiscussionWatcher.start(listOf(root), { sink }).use {
                val created = DiscussionId.require("01900000-0000-7000-8000-000000000102")
                Files.createDirectory(collection.resolve(created.value))
                awaitCondition { sink.discussions.count { it == created } == 1 }.shouldBeTrue()
                Files.delete(collection.resolve(created.value))
                awaitCondition { sink.discussions.count { it == created } == 2 }.shouldBeTrue()

                val movedIn = DiscussionId.require("01900000-0000-7000-8000-000000000103")
                val outside = Files.createDirectory(root.resolve("incoming-${movedIn.value}"))
                Files.move(outside, collection.resolve(movedIn.value), StandardCopyOption.ATOMIC_MOVE)
                awaitCondition { sink.discussions.count { it == movedIn } == 1 }.shouldBeTrue()
                Files.move(collection.resolve(movedIn.value), root.resolve("departed-${movedIn.value}"), StandardCopyOption.ATOMIC_MOVE)
                awaitCondition { sink.discussions.count { it == movedIn } == 2 }.shouldBeTrue()

                sink.discussions.count { it == created } shouldBe 2
                sink.discussions.count { it == movedIn } shouldBe 2
            }
        }
    }

    test("nested dot plainbase reaches neither content nor the sink") {
        withScope("pb-discussion-dot") { root ->
            val collection = prepareCollection(root)
            val contentSeen = ConcurrentLinkedQueue<TreePath>()
            val contentBarrier = CountDownLatch(1)
            val sink = RecordingDiscussionSink()
            val ordinary = DiscussionId.require("01900000-0000-7000-8000-000000000104")
            val nested = DiscussionId.require("01900000-0000-7000-8000-000000000105")
            Files.createDirectories(root.resolve("docs"))
            FileWatcher(root, IgnoreRules(), emptyList(), { path ->
                contentSeen += path
                if (path.value == "docs/after.md") contentBarrier.countDown()
            }).use { contentWatcher ->
                DiscussionWatcher.start(listOf(root), { sink }).use {
                    Files.createDirectory(collection.resolve(ordinary.value))
                    awaitCondition { sink.discussions.contains(ordinary) }.shouldBeTrue()

                    val nestedPath = Files.createDirectories(root.resolve("docs/.plainbase/discussions/${nested.value}"))
                    Files.writeString(nestedPath.resolve("note.md"), "nested marker data")
                    Files.createDirectories(root.resolve("docs"))
                    Files.writeString(root.resolve("docs/after.md"), "ordinary content sentinel")
                    contentBarrier.await(90, TimeUnit.SECONDS).shouldBeTrue()

                    sink.discussions.toList() shouldContainExactly listOf(ordinary)
                    contentSeen.none { it.value.contains(".plainbase") } shouldBe true
                }
                contentWatcher.workerForTest().isAlive shouldBe true
            }
        }
    }

    test("the content watcher is unchanged and never hears the collection") {
        withScope("pb-discussion-isolation") { root ->
            val collection = prepareCollection(root)
            val contentSeen = ConcurrentLinkedQueue<TreePath>()
            val contentLatch = CountDownLatch(1)
            val sink = RecordingDiscussionSink()
            val id = DiscussionId.require("01900000-0000-7000-8000-000000000106")
            Files.createDirectories(root.resolve("docs"))
            FileWatcher(root, IgnoreRules(), emptyList(), { path ->
                contentSeen += path
                if (path == TreePath.require("docs/sentinel.md")) contentLatch.countDown()
            }).use {
                DiscussionWatcher.start(listOf(root), { sink }).use {
                    Files.createDirectories(root.resolve("docs"))
                    Files.writeString(root.resolve("docs/sentinel.md"), "plain content sentinel")
                    contentLatch.await(90, TimeUnit.SECONDS).shouldBeTrue()
                    Files.createDirectory(collection.resolve(id.value))
                    awaitCondition { sink.discussions.contains(id) }.shouldBeTrue()

                    contentSeen.none { it.value.startsWith(".plainbase") } shouldBe true
                    contentSeen shouldContain TreePath.require("docs/sentinel.md")
                }
            }
        }
    }

    test("a symlinked plainbase is never registered or followed") {
        withScope("pb-discussion-link") { root ->
            val target = Files.createTempDirectory("pb-discussion-link-target")
            try {
                val targetCollection = prepareCollection(target)
                val app = root.resolve(".plainbase")
                try {
                    Files.createSymbolicLink(app, target.resolve(".plainbase"))
                } catch (_: IOException) {
                    return@withScope
                } catch (_: UnsupportedOperationException) {
                    return@withScope
                }
                val registered = ConcurrentLinkedQueue<Path>()
                val sink = RecordingDiscussionSink()
                DiscussionWatcher.start(
                    listOf(root),
                    { sink },
                    registerDirectory = { directory, service ->
                        registered.add(directory.toAbsolutePath().normalize())
                        registerDiscussionDirectory(directory, service)
                    },
                    rescanInterval = 100.milliseconds,
                ).use {
                    val id = DiscussionId.require("01900000-0000-7000-8000-000000000107")
                    Files.createDirectory(targetCollection.resolve(id.value))
                    Thread.sleep(250)

                    registered.none { it == app.toAbsolutePath().normalize() } shouldBe true
                    registered.none { it == app.resolve("discussions").toAbsolutePath().normalize() } shouldBe true
                    sink.discussions.toList().shouldBeEmpty()
                }
            } finally {
                target.toFile().deleteRecursively()
            }
        }
    }

    test("a discussion overflow rescans and never touches content") {
        withScope("pb-discussion-overflow-a") { rootA ->
            withScope("pb-discussion-overflow-b") { rootB ->
                prepareCollection(rootA)
                prepareCollection(rootB)
                val sinkA = RecordingDiscussionSink()
                val sinkB = RecordingDiscussionSink()
                val contentSeen = ConcurrentLinkedQueue<TreePath>()
                val contentLatch = CountDownLatch(1)
                val watchService = SignalWatchService()
                Files.createDirectories(rootA.resolve("docs"))
                FileWatcher(rootA, IgnoreRules(), emptyList(), { path ->
                    contentSeen += path
                    if (path == TreePath.require("docs/positive.md")) contentLatch.countDown()
                }).use {
                    DiscussionWatcher.start(
                        listOf(rootA, rootB),
                        { root -> if (root == rootA) sinkA else sinkB },
                        watchServiceFactory = { watchService },
                        registerDirectory = { directory, _ -> watchService.register(directory) },
                        rescanInterval = 5.seconds,
                    ).use {
                        val collectionPath = rootA.resolve(".plainbase/discussions").toAbsolutePath().normalize()
                        val key = watchService.keys.first { it.watchable() == collectionPath }
                        watchService.emitOverflow(key)
                        awaitCondition { sinkA.collectionChanges.get() >= 2 && sinkB.collectionChanges.get() >= 2 }.shouldBeTrue()

                        val afterOverflow = DiscussionId.require("01900000-0000-7000-8000-000000000199")
                        Files.createDirectory(rootA.resolve(".plainbase/discussions/${afterOverflow.value}"))
                        watchService.emitCreated(key, afterOverflow.value)
                        awaitCondition(3_000) { sinkA.discussions.contains(afterOverflow) }.shouldBeTrue()

                        Files.createDirectories(rootA.resolve("docs"))
                        Files.writeString(rootA.resolve("docs/positive.md"), "content still watched")
                        contentLatch.await(90, TimeUnit.SECONDS).shouldBeTrue()
                        contentSeen shouldContain TreePath.require("docs/positive.md")
                        contentSeen.none { it.value.startsWith(".plainbase") } shouldBe true
                    }
                }
            }
        }
    }

    test("replacing the collection keeps only its current watch key") {
        withScope("pb-discussion-collection-replace") { root ->
            val collection = prepareCollection(root)
            val watchService = SignalWatchService()
            val sink = RecordingDiscussionSink()
            val registered = ConcurrentLinkedQueue<Pair<Path, Any?>>()
            DiscussionWatcher.start(
                listOf(root),
                { sink },
                watchServiceFactory = { watchService },
                registerDirectory = { directory, _ ->
                    registered += directory.toAbsolutePath().normalize() to
                        Files.readAttributes(directory, BasicFileAttributes::class.java).fileKey()
                    watchService.register(directory)
                },
                rescanInterval = 100.milliseconds,
            ).use { watcher ->
                repeat(5) { index ->
                    val held = collection.resolveSibling("discussions-held-$index")
                    Files.move(collection, held)
                    Files.createDirectory(collection)
                    val fileKey = Files.readAttributes(collection, BasicFileAttributes::class.java).fileKey()

                    awaitCondition(3_000) {
                        registered.any { (path, key) -> path == collection.toAbsolutePath().normalize() && key == fileKey }
                    }.shouldBeTrue()
                    watcher.registrationCountsForTest() shouldBe (2 to 2)
                    watchService.keys.count { it.isValid } shouldBe 2
                    Files.delete(held)
                }
            }
        }
    }

    test("an attribute failure on one root keeps another root watched") {
        withScope("pb-discussion-attribute-a") { rootA ->
            withScope("pb-discussion-attribute-b") { rootB ->
                prepareCollection(rootA)
                val collectionB = prepareCollection(rootB)
                val sinkA = RecordingDiscussionSink()
                val sinkB = RecordingDiscussionSink()
                val watchService = ControlledWatchService(rootA.fileSystem.newWatchService())
                val denied = rootA.resolve(".plainbase").toAbsolutePath().normalize()
                DiscussionWatcher.start(
                    listOf(rootA, rootB),
                    { root -> if (root == rootA) sinkA else sinkB },
                    watchServiceFactory = { watchService },
                    registerDirectory = { directory, service ->
                        watchService.wrap(registerDiscussionDirectory(directory, watchService.delegate))
                    },
                    rescanInterval = 100.milliseconds,
                    readDirectoryAttributes = { path ->
                        if (path.toAbsolutePath().normalize() == denied) {
                            throw AccessDeniedException(path.toString())
                        }
                        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    },
                ).use {
                    val id = DiscussionId.require("01900000-0000-7000-8000-000000000198")
                    Files.createDirectory(collectionB.resolve(id.value))

                    awaitCondition(3_000) { sinkB.discussions.contains(id) }.shouldBeTrue()
                    sinkA.discussions.shouldBeEmpty()
                }
            }
        }
    }

    test("a registration IOException on one root keeps another root watched") {
        withScope("pb-discussion-register-denied") { rootA ->
            withScope("pb-discussion-register-healthy") { rootB ->
                Files.createDirectories(rootA.resolve(".plainbase"))
                val collectionB = prepareCollection(rootB)
                val sinkA = RecordingDiscussionSink()
                val sinkB = RecordingDiscussionSink()
                val watchService = rootA.fileSystem.newWatchService()
                val denied = rootA.resolve(".plainbase").toAbsolutePath().normalize()
                DiscussionWatcher.start(
                    listOf(rootA, rootB),
                    { root -> if (root == rootA) sinkA else sinkB },
                    watchServiceFactory = { watchService },
                    registerDirectory = { directory, service ->
                        if (directory.toAbsolutePath().normalize() == denied) {
                            throw AccessDeniedException(directory.toString())
                        }
                        directory.register(
                            service,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_DELETE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                        )
                    },
                    rescanInterval = 5.seconds,
                ).use {
                    val id = DiscussionId.require("01900000-0000-7000-8000-000000000201")
                    Files.createDirectory(collectionB.resolve(id.value))

                    awaitCondition(3_000) { sinkB.discussions.contains(id) }.shouldBeTrue()
                    sinkA.discussions.shouldBeEmpty()
                }
            }
        }
    }

    test("a foreign interrupt stops either worker branch and warns") {
        val logger = LoggerFactory.getLogger(DiscussionWatcher::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            withScope("pb-discussion-interrupt-disabled") { root ->
                val watcher = DiscussionWatcher.start(
                    listOf(root),
                    { RecordingDiscussionSink() },
                    watchServiceFactory = { throw IOException("watch provider unavailable") },
                    rescanInterval = 10.seconds,
                )
                try {
                    val worker = watcher.workerForTest()
                    worker.interrupt()
                    worker.join(2_000)
                    worker.isAlive shouldBe false
                } finally {
                    watcher.close()
                }
            }
            withScope("pb-discussion-interrupt-active") { root ->
                prepareCollection(root)
                val watcher = DiscussionWatcher.start(listOf(root), { RecordingDiscussionSink() }, rescanInterval = 10.seconds)
                try {
                    val worker = watcher.workerForTest()
                    worker.interrupt()
                    worker.join(2_000)
                    worker.isAlive shouldBe false
                } finally {
                    watcher.close()
                }
            }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }

        appender.list.count { it.level == Level.WARN && "worker exited" in it.formattedMessage } shouldBe 2
    }

    test("the first registration pass signals each root once") {
        withScope("pb-discussion-first-a") { rootA ->
            withScope("pb-discussion-first-b") { rootB ->
                prepareCollection(rootA)
                prepareCollection(rootB)
                val sinkA = RecordingDiscussionSink()
                val sinkB = RecordingDiscussionSink()
                val registered = ConcurrentLinkedQueue<Path>()
                DiscussionWatcher.start(
                    listOf(rootA, rootB),
                    { root -> if (root == rootA) sinkA else sinkB },
                    registerDirectory = { directory, service ->
                        registered.add(directory.toAbsolutePath().normalize())
                        registerDiscussionDirectory(directory, service)
                    },
                ).use {
                    sinkA.collectionChanges.get() shouldBe 1
                    sinkB.collectionChanges.get() shouldBe 1
                    registered.toSet() shouldBe setOf(
                        rootA.resolve(".plainbase").toAbsolutePath().normalize(),
                        rootA.resolve(".plainbase/discussions").toAbsolutePath().normalize(),
                        rootB.resolve(".plainbase").toAbsolutePath().normalize(),
                        rootB.resolve(".plainbase/discussions").toAbsolutePath().normalize(),
                    )
                }
            }
        }
    }

    test("the tick fires under event traffic and re-registers a replaced plainbase") {
        withScope("pb-discussion-tick") { root ->
            val collection = prepareCollection(root)
            val sink = RecordingDiscussionSink()
            val app = root.resolve(".plainbase")
            val registrations = ConcurrentLinkedQueue<Pair<Path, Any?>>()
            val tickAtStart = sink.collectionChanges.get()
            val running = AtomicBoolean(true)
            DiscussionWatcher.start(
                listOf(root),
                { sink },
                registerDirectory = { directory, service ->
                    val key = registerDiscussionDirectory(directory, service)
                    val fileKey = Files.readAttributes(directory, BasicFileAttributes::class.java).fileKey()
                    registrations += directory.toAbsolutePath().normalize() to fileKey
                    key
                },
                rescanInterval = 100.milliseconds,
            ).use {
                val traffic = Thread {
                    var index = 0
                    while (running.get()) {
                        val noise = collection.resolve("noise-$index")
                        runCatching { Files.createDirectory(noise) }
                        runCatching { Files.deleteIfExists(noise) }
                        index += 1
                    }
                }
                traffic.start()
                try {
                    awaitCondition(5_000) { sink.collectionChanges.get() >= tickAtStart + 2 }.shouldBeTrue()
                } finally {
                    running.set(false)
                    traffic.join(2_000)
                }

                val moved = root.resolve(".plainbase-held")
                val oldAppKey = Files.readAttributes(app, BasicFileAttributes::class.java).fileKey()
                Files.move(app, moved, StandardCopyOption.ATOMIC_MOVE)
                Files.createDirectories(app.resolve("discussions"))
                val newAppKey = Files.readAttributes(app, BasicFileAttributes::class.java).fileKey()
                (oldAppKey != newAppKey) shouldBe true

                awaitCondition(5_000) {
                    registrations.count { (path, key) -> path == app.toAbsolutePath().normalize() && key == newAppKey } >= 1
                }.shouldBeTrue()
                val afterReplacement = DiscussionId.require("01900000-0000-7000-8000-000000000108")
                Files.createDirectory(app.resolve("discussions/${afterReplacement.value}"))
                awaitCondition(5_000) { sink.discussions.contains(afterReplacement) }.shouldBeTrue()
            }
        }
    }

    test("a watcher that cannot start never fails content") {
        withScope("pb-discussion-blind") { root ->
            prepareCollection(root)
            val sink = RecordingDiscussionSink()
            val attempts = AtomicInteger()
            val contentLatch = CountDownLatch(1)
            Files.createDirectories(root.resolve("docs"))
            FileWatcher(root, IgnoreRules(), emptyList(), { path ->
                if (path == TreePath.require("docs/sentinel.md")) contentLatch.countDown()
            }).use {
                DiscussionWatcher.start(
                    listOf(root),
                    { sink },
                    watchServiceFactory = {
                        if (attempts.getAndIncrement() == 0) throw IOException("watch provider unavailable")
                        root.fileSystem.newWatchService()
                    },
                    rescanInterval = 100.milliseconds,
                ).use {
                    Files.createDirectories(root.resolve("docs"))
                    Files.writeString(root.resolve("docs/sentinel.md"), "content watcher remains live")
                    contentLatch.await(90, TimeUnit.SECONDS).shouldBeTrue()
                    awaitCondition(5_000) { attempts.get() >= 2 && sink.collectionChanges.get() >= 2 }.shouldBeTrue()
                }
            }
            DiscussionWatcher.start(listOf(root), { sink }, rescanInterval = Duration.ZERO).close()
            DiscussionWatcher.start(
                listOf(root),
                { throw IllegalStateException("sink factory failed") },
                rescanInterval = 100.milliseconds,
            ).close()
        }
    }

    test("two watches per scope root and none for other roots") {
        withScope("pb-discussion-scope") { scopeRoot ->
            withScope("pb-discussion-readonly") { readOnlyRoot ->
                prepareCollection(scopeRoot)
                Files.createDirectories(readOnlyRoot.resolve(".plainbase/discussions"))
                val registrations = ConcurrentLinkedQueue<Path>()
                val sink = RecordingDiscussionSink()
                DiscussionWatcher.start(
                    listOf(scopeRoot),
                    { sink },
                    registerDirectory = { directory, service ->
                        registrations.add(directory.toAbsolutePath().normalize())
                        registerDiscussionDirectory(directory, service)
                    },
                ).use {
                    registrations.toSet() shouldBe setOf(
                        scopeRoot.resolve(".plainbase").toAbsolutePath().normalize(),
                        scopeRoot.resolve(".plainbase/discussions").toAbsolutePath().normalize(),
                    )
                    registrations.none { it.startsWith(readOnlyRoot.toAbsolutePath().normalize()) } shouldBe true
                }
            }
        }
    }
})

private class RecordingDiscussionSink : DiscussionWatchSink {
    val discussions = ConcurrentLinkedQueue<DiscussionId>()
    val collectionChanges = AtomicInteger()

    override fun discussionChanged(id: DiscussionId) {
        discussions += id
    }

    override fun collectionChanged() {
        collectionChanges.incrementAndGet()
    }
}

private fun prepareCollection(root: Path): Path = Files.createDirectories(root.resolve(".plainbase/discussions"))

private inline fun withScope(prefix: String, block: (Path) -> Unit) {
    val root = Files.createTempDirectory(prefix)
    try {
        block(root)
    } finally {
        root.toFile().deleteRecursively()
    }
}

private fun awaitCondition(timeoutMillis: Long = 90_000, condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (System.nanoTime() < deadline) {
        if (condition()) return true
        Thread.sleep(10)
    }
    return condition()
}

private fun registerDiscussionDirectory(directory: Path, service: WatchService): WatchKey =
    directory.register(
        service,
        StandardWatchEventKinds.ENTRY_CREATE,
        StandardWatchEventKinds.ENTRY_DELETE,
        StandardWatchEventKinds.ENTRY_MODIFY,
    )

private class SignalWatchService : WatchService {
    private val pending = LinkedBlockingQueue<SignalWatchKey>()
    val keys = ConcurrentLinkedQueue<SignalWatchKey>()

    @Volatile
    private var closed = false

    fun register(path: Path): WatchKey = SignalWatchKey(path, this).also(keys::add)

    fun emitOverflow(key: SignalWatchKey) = emit(key, StandardWatchEventKinds.OVERFLOW, null)

    fun emitCreated(key: SignalWatchKey, name: String) =
        emit(key, StandardWatchEventKinds.ENTRY_CREATE, Path.of(name))

    private fun emit(key: SignalWatchKey, kind: WatchEvent.Kind<*>, context: Path?) {
        if (key.add(SignalWatchEvent(kind, context))) pending.offer(key)
    }

    fun enqueue(key: SignalWatchKey) {
        pending.offer(key)
    }

    override fun poll(): WatchKey? {
        if (closed) throw ClosedWatchServiceException()
        return pending.poll()
    }

    override fun poll(timeout: Long, unit: TimeUnit): WatchKey? {
        if (closed) throw ClosedWatchServiceException()
        return pending.poll(timeout, unit)
    }

    override fun take(): WatchKey {
        if (closed) throw ClosedWatchServiceException()
        return pending.take()
    }

    override fun close() {
        closed = true
        pending.clear()
    }
}

private class SignalWatchKey(private val path: Path, private val service: SignalWatchService) : WatchKey {
    private val events = ConcurrentLinkedQueue<WatchEvent<*>>()
    private var valid = true
    private var signalled = false

    @Synchronized
    fun add(event: WatchEvent<*>): Boolean {
        if (!valid) return false
        events.offer(event)
        if (signalled) return false
        signalled = true
        return true
    }

    @Synchronized
    override fun isValid(): Boolean = valid

    override fun pollEvents(): MutableList<WatchEvent<*>> {
        val all = mutableListOf<WatchEvent<*>>()
        while (true) all += events.poll() ?: break
        return all
    }

    @Synchronized
    override fun reset(): Boolean {
        if (!valid) return false
        signalled = false
        if (events.isNotEmpty()) {
            signalled = true
            service.enqueue(this)
        }
        return true
    }

    @Synchronized
    override fun cancel() {
        valid = false
        events.clear()
    }

    override fun watchable(): Watchable = path
}

private class SignalWatchEvent(kind: WatchEvent.Kind<*>, private val path: Path?) : WatchEvent<Any?> {
    private val eventKind = kind

    @Suppress("UNCHECKED_CAST")
    override fun kind(): WatchEvent.Kind<Any?> = eventKind as WatchEvent.Kind<Any?>

    override fun count(): Int = 1

    override fun context(): Any? = path
}

private class ControlledWatchService(val delegate: WatchService) : WatchService {
    private val pending = LinkedBlockingQueue<WatchKey>()
    private val wrappers = ConcurrentHashMap<WatchKey, ControlledWatchKey>()
    val keys = ConcurrentLinkedQueue<ControlledWatchKey>()

    fun wrap(key: WatchKey): WatchKey = wrappers.computeIfAbsent(key) { ControlledWatchKey(it).also(keys::add) }

    fun emit(key: ControlledWatchKey, kind: WatchEvent.Kind<*>) {
        key.add(kind)
        pending.offer(key)
    }

    override fun poll(): WatchKey? = pending.poll() ?: delegate.poll()?.let(::wrap)

    override fun poll(timeout: Long, unit: TimeUnit): WatchKey? {
        val synthetic = pending.poll(timeout, unit)
        return synthetic ?: delegate.poll(0, TimeUnit.MILLISECONDS)?.let(::wrap)
    }

    override fun take(): WatchKey = pending.take()

    override fun close() {
        pending.clear()
        delegate.close()
    }
}

private class ControlledWatchKey(private val delegate: WatchKey) : WatchKey {
    private val events = ConcurrentLinkedQueue<WatchEvent<*>>()

    fun add(kind: WatchEvent.Kind<*>) {
        events += object : WatchEvent<Any?> {
            override fun kind(): WatchEvent.Kind<Any?> = overflowKind(kind)
            override fun count(): Int = 1
            override fun context(): Any? = null
        }
    }

    override fun isValid(): Boolean = delegate.isValid

    override fun pollEvents(): MutableList<WatchEvent<*>> {
        val all = mutableListOf<WatchEvent<*>>()
        while (true) all += events.poll() ?: break
        all += delegate.pollEvents()
        return all
    }

    override fun reset(): Boolean = delegate.reset()

    override fun cancel() = delegate.cancel()

    override fun watchable(): Watchable = delegate.watchable()

    @Suppress("UNCHECKED_CAST")
    private fun overflowKind(kind: WatchEvent.Kind<*>): WatchEvent.Kind<Any?> = kind as WatchEvent.Kind<Any?>
}
