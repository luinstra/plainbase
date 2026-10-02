package com.plainbase.frameworks.filesystem

import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionWatchSink
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@Tag("native")
class DiscussionWatcherNativeTest {
    @Test
    fun collectionEventsReachTheSinkAndNeverTheContentCallback() {
        withRoot { root ->
            val contentEvents = ConcurrentLinkedQueue<String>()
            val contentSignal = CountDownLatch(1)
            val collectionSignals = AtomicInteger()
            val observed = ConcurrentLinkedQueue<DiscussionId>()
            val id = discussionId(1)
            Files.createDirectories(root.resolve(".plainbase"))
            Files.createDirectories(root.resolve("docs"))
            val contentWatcher = FileWatcher(root, IgnoreRules(), emptyList(), { path ->
                contentEvents += path.value
                if (path.value.startsWith(".plainbase")) contentSignal.countDown()
            })
            try {
                val sink = recordingSink(collectionSignals, observed)
                DiscussionWatcher.start(listOf(root), { sink }, rescanInterval = 200.milliseconds).use {
                    val collection = root.resolve(".plainbase/discussions")
                    Files.createDirectory(collection)
                    val collectionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (collectionSignals.get() < 2 && System.nanoTime() < collectionDeadline) Thread.sleep(10)
                    assertTrue(collectionSignals.get() >= 2, "the APP key should announce the collection registration")
                    Files.createDirectory(collection.resolve(id.value))
                    val discussionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (id !in observed && System.nanoTime() < discussionDeadline) Thread.sleep(10)
                    assertEquals(listOf(id), observed.toList())
                    assertFalse(contentSignal.await(100, TimeUnit.MILLISECONDS))
                    assertTrue(contentEvents.none { it.startsWith(".plainbase") })
                }
            } finally {
                contentWatcher.close()
            }
        }
    }

    @Test
    fun folderCreateInTheCollectionRaisesDiscussionChanged() {
        withRoot { root ->
            val collection = Files.createDirectories(root.resolve(".plainbase/discussions"))
            val id = discussionId(2)
            val observed = ConcurrentLinkedQueue<DiscussionId>()
            DiscussionWatcher.start(listOf(root), { sink(observed) }, rescanInterval = 200.milliseconds).use {
                Files.createDirectory(collection.resolve(id.value))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (observed.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
                assertEquals(listOf(id), observed.toList())
            }
        }
    }

    @Test
    fun symlinkedCollectionIsNeverRegistered() {
        withRoot { root ->
            val outside = Files.createTempDirectory("pb-discussion-native-outside")
            try {
                Files.createDirectories(root.resolve(".plainbase"))
                val outsideCollection = Files.createDirectories(outside.resolve("discussions"))
                val id = discussionId(3)
                val observed = ConcurrentLinkedQueue<DiscussionId>()
                Files.createSymbolicLink(root.resolve(".plainbase/discussions"), outsideCollection)
                DiscussionWatcher.start(listOf(root), { sink(observed) }, rescanInterval = 200.milliseconds).use {
                    Files.createDirectory(outsideCollection.resolve(id.value))
                    Thread.sleep(500)
                    assertTrue(observed.isEmpty(), "a symlinked collection must not forward external events")
                }
            } finally {
                outside.toFile().deleteRecursively()
            }
        }
    }
}

private fun recordingSink(
    collectionSignals: AtomicInteger,
    observed: ConcurrentLinkedQueue<DiscussionId>,
) = object : DiscussionWatchSink {
    override fun discussionChanged(id: DiscussionId) {
        observed += id
    }

    override fun collectionChanged() {
        collectionSignals.incrementAndGet()
    }
}

private fun sink(observed: ConcurrentLinkedQueue<DiscussionId>) = object : DiscussionWatchSink {
    override fun discussionChanged(id: DiscussionId) {
        observed += id
    }

    override fun collectionChanged() = Unit
}

private fun discussionId(value: Int) = DiscussionId.require("01900000-0000-7000-8000-${value.toString(16).padStart(12, '0')}")

private inline fun withRoot(block: (Path) -> Unit) {
    val root = Files.createTempDirectory("pb-discussion-watcher-native")
    try {
        block(root)
    } finally {
        root.toFile().deleteRecursively()
    }
}
