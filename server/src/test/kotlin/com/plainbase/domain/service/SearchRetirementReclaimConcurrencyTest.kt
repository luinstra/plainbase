package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.principal.createGrantForTests
import com.plainbase.domain.repository.Stage
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.search.SearchQuery
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** File-backed builder-monitor ordering and same-path reclaim controls. */
class SearchRetirementReclaimConcurrencyTest : FunSpec({

    test("same-path create waits behind a gated old explicit swap and then indexes successfully") {
        SearchRetirementWorld().use { world ->
            world.observeRoots()
            world.builder.rebuild()
            Files.delete(world.victimPath)
            world.failNextDelete()
            world.builder.rebuild()

            val victim = RootedPageId(RootName.PRIMARY, world.victimId)
            world.idMap.bindingInRoot(RootName.PRIMARY, world.victimId) shouldBe null
            world.idMap.retiredAt(RootName.PRIMARY, world.victimId) shouldNotBe null
            world.builder.current.pageAt(victim) shouldBe null
            world.provider.indexedState().containsKey(victim) shouldBe true

            val gate = world.armNextRebuildGate()
            val oldRebuildError = AtomicReference<Throwable?>(null)
            val oldRebuild = Thread {
                runCatching { world.builder.rebuildSearchIndex() }.onFailure(oldRebuildError::set)
            }
            val newBytes = world.pageBytes(world.victimId, "Reclaimed victim", "monitor-new-term")
            val bindReturned = CountDownLatch(1)
            val releaseHistoryHook = CountDownLatch(1)
            val creatorOutcome = AtomicReference<WriteOutcome?>(null)
            val creatorError = AtomicReference<Throwable?>(null)
            val pipeline = world.pipeline(
                historyHook = WriteHistoryHook { _, _, _, _, _ ->
                    bindReturned.countDown()
                    check(releaseHistoryHook.await(10, TimeUnit.SECONDS)) { "timed out waiting to release create hook" }
                    null
                },
            )
            val creator = Thread {
                runCatching {
                    pipeline.create(
                        createGrantForTests(),
                        CreateIntent(
                            pageId = world.victimId,
                            root = RootName.PRIMARY,
                            path = TreePath.require("victim.md"),
                            bytes = newBytes,
                        ),
                    )
                }.onSuccess(creatorOutcome::set).onFailure(creatorError::set)
            }
            val workers = listOf(oldRebuild, creator)

            try {
                oldRebuild.start()
                gate.entered.await(10, TimeUnit.SECONDS) shouldBe true
                creator.start()
                bindReturned.await(10, TimeUnit.SECONDS) shouldBe true

                // WritePipeline.create ignores BindOutcome, so the hook alone is not proof: inspect the committed
                // repository state while the old builder monitor is still parked in the provider decorator.
                world.idMap.bindingInRoot(RootName.PRIMARY, world.victimId)?.path?.path?.value shouldBe "victim.md"
                world.idMap.retiredAt(RootName.PRIMARY, world.victimId) shouldBe null

                releaseHistoryHook.countDown()
                awaitBlocked(creator) shouldBe true
                gate.release.countDown()
                gate.completed.await(10, TimeUnit.SECONDS) shouldBe true
            } finally {
                releaseHistoryHook.countDown()
                gate.release.countDown()
                workers.forEach { it.join(10_000) }
                workers.forEach { it.isAlive shouldBe false }
            }

            oldRebuildError.get() shouldBe null
            creatorError.get() shouldBe null
            val written = creatorOutcome.get() as? WriteOutcome.Written
            written shouldNotBe null
            written!!.newHash shouldBe world.contentHash(newBytes)

            Files.readAllBytes(world.victimPath).contentEquals(newBytes) shouldBe true
            val holderPage = world.builder.current.pageAt(victim)
            holderPage shouldNotBe null
            holderPage!!.contentHash shouldBe world.contentHash(newBytes)
            world.provider.indexedState()[victim]?.contentHash shouldBe world.contentHash(newBytes)
            val newTermCount = world.provider.search(
                SearchQuery("monitor-new-term", limit = 20, offset = 0),
            ).total
            newTermCount shouldBe 1L
            val oldTermCount = world.provider.search(
                SearchQuery("lost-delivery-victim-term", limit = 20, offset = 0),
            ).total
            oldTermCount shouldBe 0L
            world.dirtyPages.all().none { it.pageId == world.victimId } shouldBe true
            world.idMap.retiredAt(RootName.PRIMARY, world.victimId) shouldBe null
            world.providerEventsSnapshot.first() shouldBe "old-swap-complete"
            world.providerEventsSnapshot.drop(1) shouldContain "creator-index"
        }
    }

    test("a creator reclaim before whole-set capture leaves the retained old row until creator publication") {
        SearchRetirementWorld().use { world ->
            world.observeRoots()
            world.builder.rebuild()
            val victim = RootedPageId(RootName.PRIMARY, world.victimId)
            world.establishRetiredVictimWithStaleSearch()
            val oldState = world.provider.indexedState()[victim]
            oldState shouldNotBe null
            val oldHash = oldState!!.contentHash
            val oldPath = oldState.path
            world.provider.search(
                SearchQuery("lost-delivery-victim-term", limit = 20, offset = 0),
            ).total shouldBe 1L
            world.resetRaceObservability()
            world.provider.recordMonitorEvents = true

            val queryGate = world.armRetirementQueryGate()
            val bindReturned = CountDownLatch(1)
            val releaseHistory = CountDownLatch(1)
            val creatorOutcome = AtomicReference<WriteOutcome?>(null)
            val creatorError = AtomicReference<Throwable?>(null)
            val oldRebuildError = AtomicReference<Throwable?>(null)
            val newBytes = world.pageBytes(world.victimId, "Before capture", "before-capture-term")
            val pipeline = world.pipeline(
                historyHook = WriteHistoryHook { _, _, _, _, _ ->
                    bindReturned.countDown()
                    check(releaseHistory.await(10, TimeUnit.SECONDS)) { "timed out waiting for before-capture release" }
                    null
                },
            )
            val creator = Thread {
                runCatching {
                    pipeline.create(
                        createGrantForTests(),
                        CreateIntent(world.victimId, RootName.PRIMARY, TreePath.require("victim.md"), newBytes),
                    )
                }.onSuccess(creatorOutcome::set).onFailure(creatorError::set)
            }
            val oldRebuild = Thread {
                runCatching { world.builder.rebuildSearchIndex() }.onFailure(oldRebuildError::set)
            }
            val workers = listOf(creator, oldRebuild)

            try {
                creator.start()
                bindReturned.await(10, TimeUnit.SECONDS) shouldBe true
                world.idMap.bindingInRoot(RootName.PRIMARY, world.victimId) shouldNotBe null
                world.idMap.retiredAt(RootName.PRIMARY, world.victimId) shouldBe null

                oldRebuild.start()
                queryGate.entered.await(10, TimeUnit.SECONDS) shouldBe true
                queryGate.captured shouldBe emptySet()
                world.provider.indexedState().containsKey(victim) shouldBe true

                queryGate.release.countDown()
                queryGate.completed.await(10, TimeUnit.SECONDS) shouldBe true
                oldRebuild.join(10_000)
                oldRebuild.isAlive shouldBe false
                val retainedOld = world.provider.indexedState()[victim]
                retainedOld shouldNotBe null
                retainedOld!!.contentHash shouldBe oldHash
                retainedOld.path shouldBe oldPath
                world.provider.search(
                    SearchQuery("lost-delivery-victim-term", limit = 20, offset = 0),
                ).total shouldBe 1L
                world.provider.search(
                SearchQuery("before-capture-term", limit = 20, offset = 0),
                ).total shouldBe 0L

                releaseHistory.countDown()
            } finally {
                releaseHistory.countDown()
                queryGate.release.countDown()
                workers.forEach { it.join(10_000) }
                workers.forEach { it.isAlive shouldBe false }
            }

            oldRebuildError.get() shouldBe null
            creatorError.get() shouldBe null
            creatorOutcome.get() shouldBe WriteOutcome.Written(world.contentHash(newBytes), null)
            world.providerEventsSnapshot.first() shouldBe "old-swap-complete"
            val indexedVictim = world.provider.indexedState()[victim]
            indexedVictim!!.contentHash shouldBe world.contentHash(newBytes)
            world.provider.search(SearchQuery("before-capture-term", limit = 20, offset = 0)).total shouldBe 1L
            val oldTermCount = world.provider.search(
                SearchQuery("lost-delivery-victim-term", limit = 20, offset = 0),
            ).total
            oldTermCount shouldBe 0L
        }
    }

    test("a post-capture creator fails both index attempts and dirty reconciliation restores the reclaimed page") {
        SearchRetirementWorld().use { world ->
            world.observeRoots()
            world.builder.rebuild()
            val victim = RootedPageId(RootName.PRIMARY, world.victimId)
            world.establishRetiredVictimWithStaleSearch()
            world.resetRaceObservability()
            world.provider.recordMonitorEvents = true

            val queryGate = world.armRetirementQueryGate()
            val bindReturned = CountDownLatch(1)
            val releaseHistory = CountDownLatch(1)
            val creatorOutcome = AtomicReference<WriteOutcome?>(null)
            val creatorError = AtomicReference<Throwable?>(null)
            val oldRebuildError = AtomicReference<Throwable?>(null)
            val newBytes = world.pageBytes(world.victimId, "After capture", "after-capture-term")
            val pipeline = world.pipeline(
                historyHook = WriteHistoryHook { _, _, _, _, _ ->
                    bindReturned.countDown()
                    check(releaseHistory.await(10, TimeUnit.SECONDS)) { "timed out waiting for post-capture release" }
                    null
                },
            )
            val creator = Thread {
                runCatching {
                    pipeline.create(
                        createGrantForTests(),
                        CreateIntent(world.victimId, RootName.PRIMARY, TreePath.require("victim.md"), newBytes),
                    )
                }.onSuccess(creatorOutcome::set).onFailure(creatorError::set)
            }
            val oldRebuild = Thread {
                runCatching { world.builder.rebuildSearchIndex() }.onFailure(oldRebuildError::set)
            }
            val workers = listOf(creator, oldRebuild)

            try {
                oldRebuild.start()
                queryGate.entered.await(10, TimeUnit.SECONDS) shouldBe true
                queryGate.captured shouldBe setOf(victim)

                creator.start()
                bindReturned.await(10, TimeUnit.SECONDS) shouldBe true
                world.idMap.bindingInRoot(RootName.PRIMARY, world.victimId) shouldNotBe null
                world.idMap.retiredAt(RootName.PRIMARY, world.victimId) shouldBe null
                releaseHistory.countDown()
                awaitBlocked(creator) shouldBe true

                world.provider.failNextIndexes(2)
                queryGate.release.countDown()
                queryGate.completed.await(10, TimeUnit.SECONDS) shouldBe true
            } finally {
                releaseHistory.countDown()
                queryGate.release.countDown()
                workers.forEach { it.join(10_000) }
                workers.forEach { it.isAlive shouldBe false }
            }

            oldRebuildError.get() shouldBe null
            creatorError.get() shouldBe null
            val failed = creatorOutcome.get() as? WriteOutcome.WrittenButUnindexed
            failed shouldNotBe null
            world.provider.indexCalls shouldBe 2
            val dirty = world.dirtyPages.get(victim)
            dirty shouldNotBe null
            dirty!!.path shouldBe RootedPath(RootName.PRIMARY, TreePath.require("victim.md"))
            dirty.stage shouldBe Stage.WRITING
            dirty.expectedHash shouldBe world.contentHash(newBytes)
            Files.readAllBytes(world.victimPath).contentEquals(newBytes) shouldBe true
            world.builder.current.pageAt(victim)!!.contentHash shouldBe world.contentHash(newBytes)
            world.provider.indexedState().containsKey(victim) shouldBe false
            world.providerEventsSnapshot.take(5) shouldBe listOf(
                "old-swap-complete",
                "index-attempt",
                "index-failure",
                "index-attempt",
                "index-failure",
            )

            world.pipeline().reconcileDirtyPages()

            world.provider.indexedState()[victim]!!.contentHash shouldBe world.contentHash(newBytes)
            val newTermCount = world.provider.search(
                SearchQuery("after-capture-term", limit = 20, offset = 0),
            ).total
            newTermCount shouldBe 1L
            val oldTermCount = world.provider.search(
                SearchQuery("lost-delivery-victim-term", limit = 20, offset = 0),
            ).total
            oldTermCount shouldBe 0L
            world.dirtyPages.get(victim) shouldBe null
            world.idMap.retiredAt(RootName.PRIMARY, world.victimId) shouldBe null
        }
    }
})

private fun awaitBlocked(thread: Thread): Boolean {
    // This control relies on IndexBuilder's JVM intrinsic monitor: BLOCKED means the creator reached that monitor.
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (System.nanoTime() < deadline) {
        when (thread.state) {
            Thread.State.BLOCKED -> return true
            Thread.State.TERMINATED -> return false
            else -> Thread.yield()
        }
    }
    return false
}
