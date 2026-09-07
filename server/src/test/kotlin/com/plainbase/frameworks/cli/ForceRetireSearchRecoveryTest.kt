package com.plainbase.frameworks.cli

import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.search.PageSearchState
import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.service.SearchRetirementWorld
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files

/**
 * Offline force-retire recovery against the same durable app/search files used by the running two-root graph.
 * Each case stops the world before the command, so command-owned drivers never overlap the fixture's stores.
 */
class ForceRetireSearchRecoveryTest : FunSpec({

    test("force-retire absent victim survives startup recovery without a new retirement inference") {
        SearchRetirementWorld().use { world ->
            val victim = RootedPageId(RootName.PRIMARY, world.victimId)
            val expectedKeys = expectedKeys(world)
            val baseline = warm(world)
            val old = baseline.victimState

            Files.delete(world.victimPath)
            world.withStoresClosed {
                val output = CommandOutputFixture()
                AdminCommand.run(listOf("force-retire", "docs", world.victimId.value), world.commandConfig(), output.output) shouldBe 0
                output.stdout shouldBe forceRetireOutput(world)

                inspect(world, victim, VICTIM_TERM).assertRetired(victim, old)
            }

            world.builder.current.pages shouldBe emptyList()
            val startup = world.builder.rebuild()
            startup.pages.map { it.rooted }.toSet() shouldBe expectedKeys
            world.publicationRetirements.last() shouldBe emptySet()
            world.idMap.retiredUnboundIds() shouldContain victim
            world.provider.indexedState().keys shouldBe expectedKeys
            world.provider.indexedState().containsKey(victim) shouldBe false
            assertSearch(world.provider, VICTIM_TERM, 0L)
            assertSearch(world.provider, MAIN_ANCHOR_TERM, 1L)
            assertSearch(world.provider, HANDBOOK_ANCHOR_TERM, 1L)
            assertSearch(world.provider, HANDBOOK_VICTIM_TERM, 1L)
            assertSearch(world.provider, HANDBOOK_CONTROL_TERM, 1L)
            world.idMap.bindingInRoot(RootName.PRIMARY, world.victimId) shouldBe null
            world.idMap.retiredAt(RootName.PRIMARY, world.victimId) shouldNotBe null
        }
    }

    test("force-retire absent victim survives offline CLI reindex with the exact four-page corpus") {
        SearchRetirementWorld().use { world ->
            val victim = RootedPageId(RootName.PRIMARY, world.victimId)
            val expectedKeys = expectedKeys(world)
            val baseline = warm(world)
            val old = baseline.victimState
            Files.delete(world.victimPath)

            world.withStoresClosed {
                val retireOutput = CommandOutputFixture()
                AdminCommand.run(
                    listOf("force-retire", "docs", world.victimId.value),
                    world.commandConfig(),
                    retireOutput.output,
                ) shouldBe 0
                retireOutput.stdout shouldBe forceRetireOutput(world)
                inspect(world, victim, VICTIM_TERM).assertRetired(victim, old)

                val reindexOutput = CommandOutputFixture()
                ReindexCommand.run(emptyList(), world.commandConfig(), reindexOutput.output) shouldBe 0
                reindexOutput.stdout shouldBe
                    "reindex: rebuilt the search index for 4 page(s) across 2 roots: docs (1), handbook (3)\n"
            }

            world.builder.current.pages shouldBe emptyList()
            world.provider.indexedState().keys shouldBe expectedKeys
            world.provider.indexedState().containsKey(victim) shouldBe false
            assertSearch(world.provider, VICTIM_TERM, 0L)
            assertSearch(world.provider, MAIN_ANCHOR_TERM, 1L)
            assertSearch(world.provider, HANDBOOK_ANCHOR_TERM, 1L)
            assertSearch(world.provider, HANDBOOK_VICTIM_TERM, 1L)
            assertSearch(world.provider, HANDBOOK_CONTROL_TERM, 1L)
            world.idMap.bindingInRoot(RootName.PRIMARY, world.victimId) shouldBe null
            world.idMap.retiredAt(RootName.PRIMARY, world.victimId) shouldNotBe null
        }
    }

    test("force-retire present unchanged victim is reclaimed by the next startup pass at its own path") {
        SearchRetirementWorld().use { world ->
            val victim = RootedPageId(RootName.PRIMARY, world.victimId)
            val baseline = warm(world)
            val old = baseline.victimState
            val originalBytes = baseline.victimBytes

            world.withStoresClosed {
                val output = CommandOutputFixture()
                AdminCommand.run(listOf("force-retire", "docs", world.victimId.value), world.commandConfig(), output.output) shouldBe 0
                output.stdout shouldBe forceRetireOutput(world)
                inspect(world, victim, VICTIM_TERM).assertRetired(victim, old)
            }

            val rebuilt = world.builder.rebuild()
            rebuilt.pages.map { it.rooted }.toSet() shouldBe expectedKeys(world) + victim
            world.publicationRetirements.last() shouldBe emptySet()
            val after = inspect(world, victim, VICTIM_TERM)
            world.provider.indexedState().keys shouldBe expectedKeys(world) + victim
            after.bindingPath shouldBe "victim.md"
            after.tombstonePath shouldBe null
            after.raw shouldBe old
            after.termHits shouldBe 1L
            after.retiredIds shouldNotContain victim
            after.pointRetired shouldBe false
            assertSearch(world.provider, VICTIM_TERM, 1L)
            assertSearch(world.provider, MAIN_ANCHOR_TERM, 1L)
            assertSearch(world.provider, HANDBOOK_ANCHOR_TERM, 1L)
            assertSearch(world.provider, HANDBOOK_VICTIM_TERM, 1L)
            assertSearch(world.provider, HANDBOOK_CONTROL_TERM, 1L)
            Files.readAllBytes(world.victimPath) shouldBe originalBytes
            world.idMap.retiredUnboundIds() shouldNotContain victim
        }
    }

    test("force-retire present changed victim is reclaimed by offline CLI reindex") {
        SearchRetirementWorld().use { world ->
            val victim = RootedPageId(RootName.PRIMARY, world.victimId)
            val baseline = warm(world)
            val old = baseline.victimState
            val changedBytes = world.pageBytes(world.victimId, "Victim changed", CHANGED_TERM)
            val changedHash = world.contentHash(changedBytes)

            world.withStoresClosed {
                val retireOutput = CommandOutputFixture()
                AdminCommand.run(
                    listOf("force-retire", "docs", world.victimId.value),
                    world.commandConfig(),
                    retireOutput.output,
                ) shouldBe 0
                retireOutput.stdout shouldBe forceRetireOutput(world)
                inspect(world, victim, VICTIM_TERM).assertRetired(victim, old)

                Files.write(world.victimPath, changedBytes)
                val beforeReindex = inspect(world, victim, VICTIM_TERM)
                beforeReindex.raw shouldBe old
                beforeReindex.termHits shouldBe 1L

                val reindexOutput = CommandOutputFixture()
                ReindexCommand.run(emptyList(), world.commandConfig(), reindexOutput.output) shouldBe 0
                reindexOutput.stdout shouldBe
                    "reindex: rebuilt the search index for 5 page(s) across 2 roots: docs (2), handbook (3)\n"
            }

            world.provider.indexedState().keys shouldBe expectedKeys(world) + victim
            val after = inspect(world, victim, CHANGED_TERM)
            after.bindingPath shouldBe "victim.md"
            after.tombstonePath shouldBe null
            after.raw?.contentHash shouldBe changedHash
            after.raw?.path?.value shouldBe "victim.md"
            after.termHits shouldBe 1L
            after.retiredIds shouldNotContain victim
            after.pointRetired shouldBe false
            assertSearch(world.provider, VICTIM_TERM, 0L)
            assertSearch(world.provider, CHANGED_TERM, 1L)
            world.idMap.retiredUnboundIds() shouldNotContain victim
        }
    }
})

private const val VICTIM_TERM = "lost-delivery-victim-term"
private const val CHANGED_TERM = "force-retire-changed-victim-term"
private const val MAIN_ANCHOR_TERM = "main-anchor-term"
private const val HANDBOOK_ANCHOR_TERM = "handbook-anchor-term"
private const val HANDBOOK_VICTIM_TERM = "handbook-victim-term"
private const val HANDBOOK_CONTROL_TERM = "handbook-control-term"

private data class WarmBaseline(
    val victimState: PageSearchState,
    val victimBytes: ByteArray,
)

private fun warm(world: SearchRetirementWorld): WarmBaseline {
    world.observeRoots()
    val victim = RootedPageId(RootName.PRIMARY, world.victimId)
    val expected = expectedKeys(world) + victim
    world.builder.rebuild().pages.map { it.rooted }.toSet() shouldBe expected
    val raw = world.provider.indexedState()
    raw.keys shouldBe expected
    val victimState = raw.getValue(victim)
    val victimBytes = Files.readAllBytes(world.victimPath)
    victimState.contentHash shouldBe world.contentHash(victimBytes)
    victimState.path.value shouldBe "victim.md"
    world.idMap.bindingInRoot(RootName.PRIMARY, world.victimId)?.path?.path?.value shouldBe "victim.md"
    world.idMap.retiredAt(RootName.PRIMARY, world.victimId) shouldBe null
    world.idMap.retiredUnboundIds() shouldBe emptySet()
    world.idMap.isRetiredUnbound(victim) shouldBe false
    assertSearch(world.provider, VICTIM_TERM, 1L)
    assertSearch(world.provider, MAIN_ANCHOR_TERM, 1L)
    assertSearch(world.provider, HANDBOOK_ANCHOR_TERM, 1L)
    assertSearch(world.provider, HANDBOOK_VICTIM_TERM, 1L)
    assertSearch(world.provider, HANDBOOK_CONTROL_TERM, 1L)
    return WarmBaseline(victimState, victimBytes)
}

private fun assertSearch(provider: SearchProvider, term: String, total: Long) {
    val result = provider.search(SearchQuery(term, limit = 20, offset = 0))
    result.total shouldBe total
    if (total == 0L) {
        result.hits shouldBe emptyList()
    } else {
        result.hits.isNotEmpty() shouldBe true
    }
}

private fun expectedKeys(world: SearchRetirementWorld): Set<RootedPageId> = setOf(
    RootedPageId(RootName.PRIMARY, world.mainAnchorId),
    RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookAnchorId),
    RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookVictimId),
    RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookControlId),
)

private fun forceRetireOutput(world: SearchRetirementWorld): String =
    "force-retired ${world.victimId.value} in root 'docs' (last at victim.md); " +
        "/p/docs/${world.victimId.value} now answers 410 for a snapshot-absent page. If the file is still present, " +
        "the next pass reclaims the id at its own (root, path).\n"

private data class OfflineObservation(
    val bindingPath: String?,
    val tombstonePath: String?,
    val raw: PageSearchState?,
    val termHits: Long,
    val retiredIds: Set<RootedPageId>,
    val pointRetired: Boolean,
) {
    fun assertRetired(victim: RootedPageId, old: PageSearchState) {
        bindingPath shouldBe null
        tombstonePath shouldBe "victim.md"
        raw shouldBe old
        termHits shouldBe 1L
        retiredIds shouldContain victim
        pointRetired shouldBe true
    }
}

private fun inspect(world: SearchRetirementWorld, victim: RootedPageId, term: String): OfflineObservation =
    DatabaseFactory.createDriver(world.appDatabasePath).use { driver ->
        val idMap = SqlDelightIdMapRepository(DatabaseFactory.createDatabase(driver))
        SearchDb(world.searchDatabasePath).use { searchDb ->
            val provider = Fts5SearchProvider(searchDb)
            OfflineObservation(
                bindingPath = idMap.bindingInRoot(victim.root, victim.id)?.path?.path?.value,
                tombstonePath = idMap.retiredAt(victim.root, victim.id)?.path?.path?.value,
                raw = provider.indexedState()[victim],
                termHits = provider.search(SearchQuery(term, limit = 20, offset = 0)).total,
                retiredIds = idMap.retiredUnboundIds(),
                pointRetired = idMap.isRetiredUnbound(victim),
            )
        }
    }
