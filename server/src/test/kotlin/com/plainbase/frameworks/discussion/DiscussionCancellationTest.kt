package com.plainbase.frameworks.discussion

import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionRows
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.discussion.EntriesRead
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.PageBytes
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.AnchorMatches
import com.plainbase.domain.service.DiscussionFullReads
import com.plainbase.domain.service.DiscussionReads
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class DiscussionCancellationTest : FunSpec({
    test("interrupted full reads stop detail and nested root listing without a failed summary") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion()
            val fileReads = AtomicInteger()
            val store = object : DiscussionStore by world.store {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    fileReads.incrementAndGet()
                    return world.store.read(root, id, only)
                }
            }
            val permits = DiscussionFullReads(store)
            val reads = DiscussionReads(world.rows, store, permits, world.sync, world.availability)
            world.sync.enter(DiscussionWorld.ROOT, "exercise file listing")

            withHeldReads(1, { block -> permits.withPermit(block) }) {
                interruptedCall { reads.detail(DiscussionWorld.ROOT, id) }
                interruptedCall { reads.rootDiscussions(DiscussionWorld.ROOT) }
            }

            fileReads.get() shouldBe 0
            world.rows.row(DiscussionWorld.ROOT, id)?.state shouldBe "ok"
        }
    }

    test("interrupted narrowed reads stop facts and the marker inside a page visit") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion()
            val fileReads = AtomicInteger()
            val store = object : DiscussionStore by world.store {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    fileReads.incrementAndGet()
                    return world.store.read(root, id, only)
                }
            }
            val permits = DiscussionFullReads(store)
            val reads = DiscussionReads(world.rows, store, permits, world.sync, world.availability)
            world.sync.enter(DiscussionWorld.ROOT, "exercise file listing")
            val page = indexedPage(world)

            withHeldReads(8, { block -> permits.withNarrowed(block) }) {
                interruptedCall { reads.facts(DiscussionWorld.ROOT, id) }
                interruptedCall { reads.pageDiscussions(DiscussionWorld.ROOT, page, PageIndex.EMPTY) }
            }

            fileReads.get() shouldBe 0
            world.rows.row(DiscussionWorld.ROOT, id)?.state shouldBe "ok"

            val markerEntered = CountDownLatch(1)
            val releaseMarker = CountDownLatch(1)
            val markerReads = AtomicInteger()
            val blockedStore = object : DiscussionStore by world.store {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    if (only == setOf(EntryName.Marker)) {
                        markerReads.incrementAndGet()
                        markerEntered.countDown()
                        check(releaseMarker.await(10, TimeUnit.SECONDS))
                    }
                    return world.store.read(root, id, only)
                }
            }
            val nestedReads = DiscussionReads(world.rows, blockedStore, DiscussionFullReads(blockedStore), world.sync, world.availability)
            try {
                interruptedCall(markerEntered) { nestedReads.pageDiscussions(DiscussionWorld.ROOT, page, PageIndex.EMPTY) }
            } finally {
                releaseMarker.countDown()
            }
            markerReads.get() shouldBe 1
            world.rows.row(DiscussionWorld.ROOT, id)?.state shouldBe "ok"
        }
    }

    test("interrupted JDBC reader acquisition stops indexed queries before file fallback") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion()
            val fileReads = AtomicInteger()
            val store = object : DiscussionStore by world.store {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    fileReads.incrementAndGet()
                    return world.store.read(root, id, only)
                }
            }
            val reads = DiscussionReads(world.rows, store, DiscussionFullReads(store), world.sync, world.availability)

            withHeldReads(DiscussionDb.READER_POOL_SIZE, { block -> world.db.read { block() } }) {
                interruptedCall { world.index.pageDiscussionCount(DiscussionWorld.ROOT, world.page.pageId) }
                world.sync.isUnsynced(DiscussionWorld.ROOT) shouldBe false
                interruptedCall { reads.rootDiscussions(DiscussionWorld.ROOT) }
                interruptedCall { reads.facts(DiscussionWorld.ROOT, id) }
                interruptedCall { reads.pageDiscussions(DiscussionWorld.ROOT, indexedPage(world), PageIndex.EMPTY) }
            }

            fileReads.get() shouldBe 0
            world.sync.isUnsynced(DiscussionWorld.ROOT) shouldBe false
            world.rows.row(DiscussionWorld.ROOT, id)?.state shouldBe "ok"
        }
    }

    test("interrupted anchor marker permit stops before cache work") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion()
            val row = requireNotNull(world.rows.row(DiscussionWorld.ROOT, id)).copy(anchorKind = "quote", anchorHash = "miss")
            val fileReads = AtomicInteger()
            val store = object : DiscussionStore by world.store {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    fileReads.incrementAndGet()
                    return world.store.read(root, id, only)
                }
            }
            val permits = DiscussionFullReads(store)
            val rows = object : DiscussionRows by world.rows {
                override fun cached(root: RootName, id: DiscussionId) = null
            }
            val matches = AnchorMatches(rows, store, permits, world.sync)

            withHeldReads(8, { block -> permits.withNarrowed(block) }) {
                interruptedCall {
                    matches.forPage(DiscussionWorld.ROOT, world.page.pageId, "changed", listOf(row)) {
                        PageBytes.of(world.pageBytes, emptyList())
                    }
                }
            }

            fileReads.get() shouldBe 0
            world.rows.cached(DiscussionWorld.ROOT, id).shouldBeNull()
            world.sync.isUnsynced(DiscussionWorld.ROOT) shouldBe false
        }
    }
})

internal fun withHeldReads(count: Int, acquire: (() -> Unit) -> Unit, block: () -> Unit) {
    val entered = CountDownLatch(count)
    val release = CountDownLatch(1)
    val holders = (1..count).map {
        thread(isDaemon = true) {
            acquire {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
    }
    try {
        entered.await(5, TimeUnit.SECONDS) shouldBe true
        block()
    } finally {
        release.countDown()
        holders.forEach { holder ->
            holder.join(5_000)
            holder.isAlive shouldBe false
        }
    }
}

internal fun interruptedCall(entered: CountDownLatch? = null, block: () -> Any?) {
    val failure = AtomicReference<Throwable?>()
    val result = AtomicReference<Any?>()
    val interrupted = AtomicBoolean()
    val worker = thread(isDaemon = true) {
        if (entered == null) Thread.currentThread().interrupt()
        try {
            result.set(block())
        } catch (caught: Throwable) {
            failure.set(caught)
        } finally {
            interrupted.set(Thread.currentThread().isInterrupted)
            Thread.interrupted()
        }
    }
    if (entered != null) {
        try {
            entered.await(5, TimeUnit.SECONDS) shouldBe true
        } finally {
            worker.interrupt()
        }
    }
    worker.join(5_000)
    if (worker.isAlive) {
        worker.interrupt()
        worker.join(5_000)
    }
    worker.isAlive shouldBe false
    result.get().shouldBeNull()
    failure.get().shouldBeInstanceOf<InterruptedException>()
    interrupted.get() shouldBe true
}

private fun indexedPage(world: DiscussionWorld) = IndexedPage(
    id = world.page.pageId,
    root = DiscussionWorld.ROOT,
    path = world.page.path,
    slug = "discussion",
    urlPath = world.page.path,
    title = "Discussion",
    frontmatter = Frontmatter.EMPTY,
    materialized = true,
    markdown = "page",
    contentHash = world.pageAnchor.contentHash,
    commit = null,
    html = "<p>page</p>",
    headings = emptyList(),
    links = emptyList(),
    sections = emptyList(),
)
