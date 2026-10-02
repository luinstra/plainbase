package com.plainbase.frameworks.discussion

import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.discussion.EntriesRead
import com.plainbase.domain.discussion.EntryListing
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.RawEntry
import com.plainbase.domain.discussion.Stamp
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.DetailPage
import com.plainbase.domain.service.DiscussionFacts
import com.plainbase.domain.service.DiscussionFullReads
import com.plainbase.domain.service.DiscussionReads
import com.plainbase.domain.service.DiscussionReparser
import com.plainbase.domain.service.ReparseOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DiscussionReadPermitLifetimeTest : FunSpec({
    test("a detail read waits while the reparser holds raw entries through its final stamp") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion()
            val stampReached = CountDownLatch(1)
            val releaseStamp = CountDownLatch(1)
            val detailEntered = CountDownLatch(1)
            val stamps = AtomicInteger()
            val reads = AtomicInteger()
            val store = object : DiscussionStore by world.store {
                override fun stamp(root: RootName, id: DiscussionId): Stamp? {
                    if (stamps.incrementAndGet() == 2) {
                        stampReached.countDown()
                        check(releaseStamp.await(10, TimeUnit.SECONDS))
                    }
                    return world.store.stamp(root, id)
                }

                override fun listEntries(root: RootName, id: DiscussionId): EntryListing {
                    reads.incrementAndGet()
                    detailEntered.countDown()
                    return world.store.listEntries(root, id)
                }
            }
            val limiter = DiscussionFullReads(store)
            val reparser = DiscussionReparser(setOf(DiscussionWorld.ROOT), world.rows, store, limiter)
            val detail = DiscussionReads(world.rows, store, limiter, world.sync, world.availability)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val reparse = pool.submit<ReparseOutcome> { reparser.reparseOne(DiscussionWorld.ROOT, id) }
                stampReached.await(5, TimeUnit.SECONDS) shouldBe true
                val requested = CountDownLatch(1)
                val read = pool.submit<DetailPage> {
                    requested.countDown()
                    detail.detail(DiscussionWorld.ROOT, id)
                }
                requested.await(5, TimeUnit.SECONDS) shouldBe true
                detailEntered.await(200, TimeUnit.MILLISECONDS) shouldBe false
                reads.get() shouldBe 0
                releaseStamp.countDown()
                reparse.get(10, TimeUnit.SECONDS).shouldBeInstanceOf<ReparseOutcome.Applied>().state shouldBe "ok"
                read.get(10, TimeUnit.SECONDS).shouldBeInstanceOf<DetailPage.Content>()
                detailEntered.await(5, TimeUnit.SECONDS) shouldBe true
            } finally {
                releaseStamp.countDown()
                pool.shutdownNow()
                pool.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
    }

    test("a ninth narrowed facts read waits until a raw marker is consumed") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion()
            world.sync.enter(DiscussionWorld.ROOT, "read from files")
            val consuming = CountDownLatch(8)
            val releaseConsumption = CountDownLatch(1)
            val ninthRead = CountDownLatch(1)
            val calls = AtomicInteger()
            val store = object : DiscussionStore by world.store {
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    val result = world.store.read(root, id, only)
                    if (only != setOf(EntryName.Marker)) return result
                    if (calls.incrementAndGet() == 9) ninthRead.countDown()
                    val present = result as EntriesRead.Present
                    val gated = object : AbstractList<RawEntry>() {
                        override val size: Int get() = present.entries.size

                        override fun get(index: Int): RawEntry {
                            consuming.countDown()
                            check(releaseConsumption.await(10, TimeUnit.SECONDS))
                            return present.entries[index]
                        }
                    }
                    return EntriesRead.Present(gated, present.commentCount)
                }
            }
            val reads = DiscussionReads(world.rows, store, DiscussionFullReads(store), world.sync, world.availability)
            val pool = Executors.newFixedThreadPool(9)
            val ready = CountDownLatch(9)
            val start = CountDownLatch(1)
            try {
                val tasks = (1..9).map {
                    pool.submit<DiscussionFacts> {
                        ready.countDown()
                        check(start.await(5, TimeUnit.SECONDS))
                        reads.facts(DiscussionWorld.ROOT, id)
                    }
                }
                ready.await(5, TimeUnit.SECONDS) shouldBe true
                start.countDown()
                consuming.await(5, TimeUnit.SECONDS) shouldBe true
                ninthRead.await(200, TimeUnit.MILLISECONDS) shouldBe false
                calls.get() shouldBe 8
                releaseConsumption.countDown()
                tasks.forEach { it.get(10, TimeUnit.SECONDS).shouldBeInstanceOf<DiscussionFacts.Known>().state shouldBe "ok" }
                calls.get() shouldBe 9
            } finally {
                start.countDown()
                releaseConsumption.countDown()
                pool.shutdownNow()
                pool.awaitTermination(10, TimeUnit.SECONDS)
            }
        }
    }
})
