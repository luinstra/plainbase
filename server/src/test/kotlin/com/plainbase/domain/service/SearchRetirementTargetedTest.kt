package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.principal.grantForTests
import com.plainbase.domain.repository.BindOutcome
import com.plainbase.domain.repository.Stage
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.search.SearchQuery
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files

/** Real file-backed targeted-write acceptance at the durable-retirement guard. */
class SearchRetirementTargetedTest : FunSpec({

    test("a stale authorized edit remains dirty until same-path reclaim, then reconciles") {
        SearchRetirementWorld().use { world ->
            world.observeRoots()
            val before = world.builder.rebuild()
            val victim = RootedPageId(RootName.PRIMARY, world.victimId)
            val old = requireNotNull(before.pageAt(victim))
            val oldBytes = Files.readAllBytes(world.victimPath)
            val newBytes = world.pageBytes(world.victimId, "Rewritten victim", "targeted-recovery-term")
            world.retire(RootName.PRIMARY, world.victimId) shouldBe setOf(victim)
            world.resetRaceObservability()

            val outcome = world.pipeline().write(
                grantForTests(),
                WriteIntent(world.victimId, RootName.PRIMARY, TreePath.require("victim.md"), old.contentHash, newBytes),
            )

            val failed = outcome as? WriteOutcome.WrittenButUnindexed
            failed shouldNotBe null
            world.provider.indexCalls shouldBe 0
            world.wholeRetirementReadCount shouldBe 0
            world.pointRetirementReadCount shouldBe 1
            Files.readAllBytes(world.victimPath).contentEquals(newBytes) shouldBe true
            world.builder.current.pageAt(victim)!!.contentHash shouldBe world.contentHash(newBytes)
            world.provider.indexedState()[victim]!!.contentHash shouldBe old.contentHash
            world.provider.search(SearchQuery("lost-delivery-victim-term", limit = 20, offset = 0)).total shouldBe 1L
            world.provider.search(SearchQuery("targeted-recovery-term", limit = 20, offset = 0)).total shouldBe 0L
            val dirty = world.dirtyPages.get(victim)!!
            dirty.path shouldBe RootedPath(RootName.PRIMARY, TreePath.require("victim.md"))
            dirty.expectedHash shouldBe world.contentHash(newBytes)
            dirty.stage shouldBe Stage.WRITING

            world.idMap.bind(RootedPath(RootName.PRIMARY, TreePath.require("victim.md")), world.victimId, materialized = true) shouldBe
                BindOutcome.Bound
            world.pipeline().reconcileDirtyPages()

            world.provider.indexedState()[victim]!!.contentHash shouldBe world.contentHash(newBytes)
            world.provider.search(SearchQuery("targeted-recovery-term", limit = 20, offset = 0)).total shouldBe 1L
            world.provider.search(SearchQuery("lost-delivery-victim-term", limit = 20, offset = 0)).total shouldBe 0L
            world.dirtyPages.get(victim) shouldBe null
            world.idMap.retiredAt(RootName.PRIMARY, world.victimId) shouldBe null
            oldBytes.contentEquals(Files.readAllBytes(world.victimPath)) shouldBe false
        }
    }

    test("a live targeted write surfaces a point-authority failure and recovers without a whole read") {
        SearchRetirementWorld().use { world ->
            world.observeRoots()
            val before = world.builder.rebuild()
            val victim = RootedPageId(RootName.PRIMARY, world.victimId)
            val old = requireNotNull(before.pageAt(victim))
            val newBytes = world.pageBytes(world.victimId, "Point failure victim", "point-recovery-term")
            world.resetRaceObservability()
            world.failNextPointQuery()

            val outcome = world.pipeline().write(
                grantForTests(),
                WriteIntent(world.victimId, RootName.PRIMARY, TreePath.require("victim.md"), old.contentHash, newBytes),
            )

            (outcome is WriteOutcome.WrittenButUnindexed) shouldBe true
            world.wholeRetirementReadCount shouldBe 0
            world.pointRetirementReadCount shouldBe 1
            world.provider.indexCalls shouldBe 0
            Files.readAllBytes(world.victimPath).contentEquals(newBytes) shouldBe true
            world.builder.current.pageAt(victim)!!.contentHash shouldBe world.contentHash(newBytes)
            world.provider.indexedState()[victim]!!.contentHash shouldBe old.contentHash
            val dirty = world.dirtyPages.get(victim)!!
            dirty.path shouldBe RootedPath(RootName.PRIMARY, TreePath.require("victim.md"))
            dirty.expectedHash shouldBe world.contentHash(newBytes)
            dirty.stage shouldBe Stage.WRITING

            world.pipeline().reconcileDirtyPages()

            world.provider.indexedState()[victim]!!.contentHash shouldBe world.contentHash(newBytes)
            world.provider.search(SearchQuery("point-recovery-term", limit = 20, offset = 0)).total shouldBe 1L
            world.dirtyPages.get(victim) shouldBe null
        }
    }

    test("a proof from the post-CAS hook clears the dirty row and targeted refusal does not recreate it") {
        SearchRetirementWorld().use { world ->
            world.observeRoots()
            val before = world.builder.rebuild()
            val victim = RootedPageId(RootName.PRIMARY, world.victimId)
            val old = requireNotNull(before.pageAt(victim))
            val newBytes = world.pageBytes(world.victimId, "Proof after CAS", "proof-hook-term")
            var hookRan = false
            var dirtySeenInHook = false
            var bytesSeenInHook = false
            var dirtyPathInHook: RootedPath? = null
            var dirtyHashInHook: String? = null
            var dirtyStageInHook: Stage? = null
            var applied = emptySet<RootedPageId>()
            world.resetRaceObservability()

            val outcome = world.pipeline(
                historyHook = WriteHistoryHook { _, _, _, _, _ ->
                    hookRan = true
                    val dirty = world.dirtyPages.get(victim)
                    dirtySeenInHook = dirty != null
                    dirtyPathInHook = dirty?.path
                    dirtyHashInHook = dirty?.expectedHash
                    dirtyStageInHook = dirty?.stage
                    bytesSeenInHook = Files.readAllBytes(world.victimPath).contentEquals(newBytes)
                    applied = world.retire(RootName.PRIMARY, world.victimId)
                    null
                },
            ).write(
                grantForTests(),
                WriteIntent(world.victimId, RootName.PRIMARY, TreePath.require("victim.md"), old.contentHash, newBytes),
            )

            (outcome is WriteOutcome.WrittenButUnindexed) shouldBe true
            hookRan shouldBe true
            dirtySeenInHook shouldBe true
            bytesSeenInHook shouldBe true
            dirtyPathInHook shouldBe RootedPath(RootName.PRIMARY, TreePath.require("victim.md"))
            dirtyHashInHook shouldBe world.contentHash(newBytes)
            dirtyStageInHook shouldBe Stage.WRITING
            applied shouldBe setOf(victim)
            world.dirtyPages.get(victim) shouldBe null
            world.idMap.bindingInRoot(RootName.PRIMARY, world.victimId) shouldBe null
            world.idMap.retiredAt(RootName.PRIMARY, world.victimId) shouldNotBe null
            world.provider.indexCalls shouldBe 0
            world.wholeRetirementReadCount shouldBe 0
            world.pointRetirementReadCount shouldBe 1
            Files.readAllBytes(world.victimPath).contentEquals(newBytes) shouldBe true
            world.builder.current.pageAt(victim)!!.contentHash shouldBe world.contentHash(newBytes)

            val refusal = runCatching {
                world.builder.reindex(RootedPath(RootName.PRIMARY, TreePath.require("victim.md")))
            }.exceptionOrNull()
            refusal shouldNotBe null
            world.dirtyPages.get(victim) shouldBe null
            world.provider.search(SearchQuery("lost-delivery-victim-term", limit = 20, offset = 0)).total shouldBe 1L
            world.provider.search(SearchQuery("proof-hook-term", limit = 20, offset = 0)).total shouldBe 0L
        }
    }
})
