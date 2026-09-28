package com.plainbase.domain.service

import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.discussion.EntriesRead
import com.plainbase.domain.root.RootName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DiscussionFullReadsTest : FunSpec({
    test("full reads never overlap") {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = mockk<DiscussionStore>()
        every { store.read(any(), any(), any()) } answers {
            val current = active.incrementAndGet()
            peak.accumulateAndGet(current, ::maxOf)
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            active.decrementAndGet()
            EntriesRead.Absent
        }
        val reads = DiscussionFullReads(store)
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val tasks = (1..2).map {
            pool.submit {
                check(start.await(5, TimeUnit.SECONDS))
                reads.withFullRead(ROOT, ID) { it }
            }
        }

        try {
            start.countDown()
            entered.await(5, TimeUnit.SECONDS) shouldBe true
        } finally {
            release.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            pool.shutdownNow()
        }

        peak.get() shouldBe 1
    }

    test("narrowed reads never exceed eight") {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val allAttempted = CountDownLatch(10)
        val allEntered = CountDownLatch(10)
        val release = CountDownLatch(1)
        val reads = DiscussionFullReads(mockk())
        val pool = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)
        val tasks = (1..10).map {
            pool.submit {
                check(start.await(5, TimeUnit.SECONDS))
                allAttempted.countDown()
                reads.withNarrowed {
                    val current = active.incrementAndGet()
                    peak.accumulateAndGet(current, ::maxOf)
                    allEntered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    active.decrementAndGet()
                }
            }
        }

        try {
            start.countDown()
            allAttempted.await(5, TimeUnit.SECONDS) shouldBe true
            allEntered.await(200, TimeUnit.MILLISECONDS) shouldBe false
            peak.get() shouldBe 8
        } finally {
            release.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            pool.shutdownNow()
        }
    }
})

private val ROOT = RootName.require("docs")
private val ID = DiscussionId.require("01900000-0000-7000-8000-000000000001")
