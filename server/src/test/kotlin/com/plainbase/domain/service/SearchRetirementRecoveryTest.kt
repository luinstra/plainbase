package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.repository.DirtyPage
import com.plainbase.domain.repository.Stage
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.search.SearchQuery
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** File-backed recovery scenarios for lost search delivery, restart rebuild, carry, and explicit reconciliation. */
class SearchRetirementRecoveryTest : FunSpec({

    test("lost delivery survives close and reopen, then startup rebuild removes the stale retired row") {
        SearchRetirementWorld().use { world ->
            world.observeRoots()
            world.builder.rebuild()
            val victim = RootedPageId(RootName.PRIMARY, world.victimId)
            val warmKeys = setOf(
                victim,
                RootedPageId(RootName.PRIMARY, world.mainAnchorId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookAnchorId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookVictimId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookControlId),
            )
            world.provider.indexedState().keys shouldBe warmKeys
            listOf(
                "lost-delivery-victim-term",
                "main-anchor-term",
                "handbook-anchor-term",
                "handbook-victim-term",
                "handbook-control-term",
            ).forEach { term ->
                val result = world.provider.search(SearchQuery(term, limit = 20, offset = 0))
                result.total shouldBe 1L
                result.hits.isNotEmpty() shouldBe true
            }
            val previousBuilder = world.builder

            world.provider.search(SearchQuery("lost-delivery-victim-term", limit = 20, offset = 0)).total shouldBe 1L
            Files.delete(world.victimPath)
            world.failNextDelete()
            world.builder.rebuild()
            world.idMap.bindingInRoot(RootName.PRIMARY, world.victimId) shouldBe null
            world.idMap.retiredAt(RootName.PRIMARY, world.victimId) shouldNotBe null
            world.provider.indexedState().containsKey(victim) shouldBe true

            world.reopen()
            world.builder shouldNotBe previousBuilder
            world.builder.current.pages shouldBe emptyList()
            world.idMap.retiredAt(RootName.PRIMARY, world.victimId) shouldNotBe null
            world.provider.indexedState().containsKey(victim) shouldBe true
            world.provider.search(SearchQuery("lost-delivery-victim-term", limit = 20, offset = 0)).total shouldBe 1L
            world.provider.search(SearchQuery("lost-delivery-victim-term", limit = 20, offset = 0)).hits.isNotEmpty() shouldBe true

            val startup = world.builder.rebuild()
            val expectedKeys = setOf(
                RootedPageId(RootName.PRIMARY, world.mainAnchorId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookAnchorId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookVictimId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookControlId),
            )
            startup.pages.map { it.rooted }.toSet() shouldBe expectedKeys
            startup.pages.map { it.rooted } shouldNotContain victim
            world.publicationRetirements.last() shouldBe emptySet()
            world.provider.indexedState().containsKey(victim) shouldBe false
            world.provider.indexedState().keys shouldBe expectedKeys
            val terms = listOf(
                "lost-delivery-victim-term" to 0L,
                "main-anchor-term" to 1L,
                "handbook-anchor-term" to 1L,
                "handbook-victim-term" to 1L,
                "handbook-control-term" to 1L,
            )
            terms.forEach { (term, total) ->
                val result = world.provider.search(SearchQuery(term, limit = 20, offset = 0))
                result.total shouldBe total
                if (total == 0L) {
                    result.hits shouldBe emptyList()
                } else {
                    result.hits.isNotEmpty() shouldBe true
                }
            }
        }
    }

    test("lost search delivery is recovered by the next real rescan") {
        SearchRetirementWorld().use { world ->
            world.observeRoots()
            world.builder.rebuild()
            val victim = RootedPageId(RootName.PRIMARY, world.victimId)

            world.idMap.bindingInRoot(RootName.PRIMARY, world.victimId)?.path?.path?.value shouldBe "victim.md"
            world.provider.indexedState().containsKey(victim) shouldBe true
            world.provider.search(SearchQuery("lost-delivery-victim-term", limit = 20, offset = 0)).total shouldBeGreaterThan 0L
            Files.delete(world.victimPath)
            world.failNextDelete()

            world.builder.rebuild()

            world.deleteFailureCount shouldBe 1
            world.idMap.bindingInRoot(RootName.PRIMARY, world.victimId) shouldBe null
            world.idMap.retiredAt(RootName.PRIMARY, world.victimId) shouldNotBe null
            world.provider.indexedState().containsKey(victim) shouldBe true
            world.provider.search(SearchQuery("lost-delivery-victim-term", limit = 20, offset = 0)).total shouldBeGreaterThan 0L

            // The failed delivery leaves durable retirement in place; the next ordinary rescan must use that
            // current authority rather than trusting only the snapshot it just published.
            world.builder.rebuild()
            world.provider.indexedState().containsKey(victim) shouldBe false
            world.provider.search(SearchQuery("lost-delivery-victim-term", limit = 20, offset = 0)).total shouldBe 0L
            world.provider.indexedState().keys shouldContain RootedPageId(RootName.PRIMARY, world.mainAnchorId)
            world.provider.indexedState().keys shouldContain RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookAnchorId)
        }
    }

    test("lost delivery followed by two failed publications needs explicit-only recovery") {
        SearchRetirementWorld().use { world ->
            world.observeRoots()
            world.builder.rebuild()
            val victim = RootedPageId(RootName.PRIMARY, world.victimId)

            world.idMap.bindingInRoot(RootName.PRIMARY, world.victimId)?.path?.path?.value shouldBe "victim.md"
            world.provider.indexedState().containsKey(victim) shouldBe true
            world.provider.search(SearchQuery("lost-delivery-victim-term", limit = 20, offset = 0)).total shouldBeGreaterThan 0L
            Files.delete(world.victimPath)
            world.failNextSearchPublications(2)
            world.builder.rebuild()
            world.updateMainAnchor()
            world.builder.rebuild()

            world.publicationRetirements shouldContain setOf(victim)
            world.publicationRetirements.last() shouldBe emptySet()
            world.searchPublicationInvocationCount shouldBe 3
            world.searchPublicationFailureCount shouldBe 2
            world.searchPublicationFailuresRemaining shouldBe 0
            world.idMap.bindingInRoot(RootName.PRIMARY, world.victimId) shouldBe null
            world.idMap.retiredAt(RootName.PRIMARY, world.victimId) shouldNotBe null
            world.provider.indexedState().containsKey(victim) shouldBe true
            world.provider.search(SearchQuery("lost-delivery-victim-term", limit = 20, offset = 0)).total shouldBeGreaterThan 0L
            world.searchPublicationFailuresRemaining shouldBe 0

            // An intervening empty publication must not erase durable retirement before the explicit generation
            // rebuild gets a chance to reconcile the engine.
            world.builder.rebuildSearchIndex()
            world.provider.indexedState().containsKey(victim) shouldBe false
            world.provider.search(SearchQuery("lost-delivery-victim-term", limit = 20, offset = 0)).total shouldBe 0L
            world.searchPublicationInvocationCount shouldBe 3
        }
    }

    test("retirement followed by failed render is recovered by immediate explicit rebuild") {
        SearchRetirementWorld().use { world ->
            world.observeRoots()
            val before = world.builder.rebuild()
            val victim = RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookVictimId)
            val control = RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookControlId)
            val checkpointsBeforeRetirement = world.checkpoints.load()

            before.pageAt(victim) shouldNotBe null
            before.pageAt(control) shouldNotBe null
            world.provider.indexedState().containsKey(victim) shouldBe true
            world.provider.search(SearchQuery("handbook-victim-term", limit = 20, offset = 0)).total shouldBeGreaterThan 0L
            Files.delete(world.handbookVictimPath)
            world.failNextRender()

            val failure = runCatching { world.builder.rebuild() }.exceptionOrNull()

            failure shouldNotBe null
            failure?.message shouldBe "renderer unavailable for RED oracle at docs-anchor.md"
            world.rendererFailureCount shouldBe 1
            world.idMap.bindingInRoot(SearchRetirementWorld.HANDBOOK, world.handbookVictimId) shouldBe null
            world.idMap.retiredAt(SearchRetirementWorld.HANDBOOK, world.handbookVictimId) shouldNotBe null
            (world.builder.current === before) shouldBe true
            checkpointsBeforeRetirement.containsKey(victim) shouldBe true
            world.checkpoints.load().containsKey(victim) shouldBe false

            val holderBeforeExplicit = world.builder.current
            val checkpointsBeforeExplicit = world.checkpoints.load()
            val returned = world.builder.rebuildSearchIndex()
            val raw = world.provider.indexedState()
            val observation = ExplicitRecoveryObservation(
                victimRaw = raw.containsKey(victim),
                victimTerms = world.provider.search(SearchQuery("handbook-victim-term", limit = 20, offset = 0)).total,
                controlRaw = raw.containsKey(control),
                controlTerms = world.provider.search(SearchQuery("handbook-control-term", limit = 20, offset = 0)).total,
                returnedPages = returned,
                holderUnchanged = world.builder.current === holderBeforeExplicit,
                checkpointsUnchanged = world.checkpoints.load() == checkpointsBeforeExplicit,
            )
            assertSoftly {
                observation shouldBe ExplicitRecoveryObservation(
                    victimRaw = false,
                    victimTerms = 0L,
                    controlRaw = true,
                    controlTerms = 1L,
                    returnedPages = before.pages.size - 1,
                    holderUnchanged = true,
                    checkpointsUnchanged = true,
                )
            }
        }
    }

    test("a failed render without retirement carries an unavailable root and self-heals a missing engine row") {
        SearchRetirementWorld().use { world ->
            world.observeRoots()
            world.builder.rebuild()
            val victim = RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookVictimId)
            val control = RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookControlId)

            world.provider.search(SearchQuery("handbook-victim-term", limit = 20, offset = 0)).total shouldBe 1L
            world.provider.search(SearchQuery("handbook-control-term", limit = 20, offset = 0)).total shouldBe 1L
            world.failNextRender()
            val failure = runCatching { world.builder.rebuild() }.exceptionOrNull()
            failure?.message shouldBe "renderer unavailable for RED oracle at docs-anchor.md"
            world.idMap.retiredAt(SearchRetirementWorld.HANDBOOK, world.handbookVictimId) shouldBe null
            world.idMap.bindingInRoot(SearchRetirementWorld.HANDBOOK, world.handbookVictimId) shouldNotBe null
            world.idMap.retiredUnboundIds() shouldBe emptySet()

            world.availability.markUnavailable(SearchRetirementWorld.HANDBOOK, UnavailableCause.VANISHED)
            val carried = world.builder.rebuild()
            carried.section(SearchRetirementWorld.HANDBOOK).pages.map { it.rooted }.toSet() shouldContain victim
            carried.section(SearchRetirementWorld.HANDBOOK).pages.map { it.rooted }.toSet() shouldContain control
            world.provider.indexedState().containsKey(victim) shouldBe true
            world.provider.indexedState().containsKey(control) shouldBe true

            world.provider.delete(listOf(victim))
            world.provider.indexedState().containsKey(victim) shouldBe false
            world.provider.search(SearchQuery("handbook-victim-term", limit = 20, offset = 0)).total shouldBe 0L
            world.updateMainAnchor()
            world.builder.rebuild()

            world.idMap.retiredUnboundIds() shouldBe emptySet()
            world.provider.indexedState().containsKey(victim) shouldBe true
            world.provider.indexedState().containsKey(control) shouldBe true
            world.provider.search(SearchQuery("handbook-victim-term", limit = 20, offset = 0)).total shouldBe 1L
            world.provider.search(SearchQuery("handbook-control-term", limit = 20, offset = 0)).total shouldBe 1L
        }
    }

    test("a successful retirement removes the stale row during the same publication sync") {
        SearchRetirementWorld().use { world ->
            world.observeRoots()
            world.builder.rebuild()
            val victim = RootedPageId(RootName.PRIMARY, world.victimId)

            Files.delete(world.victimPath)
            world.builder.rebuild()
            world.publicationRetirements.last() shouldBe setOf(victim)
            world.idMap.retiredUnboundIds() shouldBe setOf(victim)
            world.idMap.bindingInRoot(RootName.PRIMARY, world.victimId) shouldBe null

            world.provider.indexedState().containsKey(victim) shouldBe false
            world.provider.search(SearchQuery("lost-delivery-victim-term", limit = 20, offset = 0)).total shouldBe 0L
            world.provider.indexedState().keys shouldBe setOf(
                RootedPageId(RootName.PRIMARY, world.mainAnchorId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookAnchorId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookVictimId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookControlId),
            )
            world.provider.search(SearchQuery("main-anchor-term", limit = 20, offset = 0)).total shouldBe 1L
        }
    }

    test("retirement failure then unavailable-root carry incrementally removes only the victim") {
        SearchRetirementWorld().use { world ->
            world.observeRoots()
            val before = world.builder.rebuild()
            val victim = RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookVictimId)
            val control = RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookControlId)
            Files.delete(world.handbookVictimPath)
            world.failNextRender()
            val failure = runCatching { world.builder.rebuild() }.exceptionOrNull()

            failure shouldNotBe null
            failure?.message shouldBe "renderer unavailable for RED oracle at docs-anchor.md"
            world.rendererFailureCount shouldBe 1
            world.idMap.retiredAt(SearchRetirementWorld.HANDBOOK, world.handbookVictimId) shouldNotBe null
            world.idMap.bindingInRoot(SearchRetirementWorld.HANDBOOK, world.handbookVictimId) shouldBe null
            before.pageAt(victim) shouldNotBe null
            before.pageAt(control) shouldNotBe null
            (world.builder.current === before) shouldBe true
            world.availability.markUnavailable(SearchRetirementWorld.HANDBOOK, UnavailableCause.VANISHED)
            world.updateMainAnchor()

            val carried = world.builder.rebuild()
            val carriedIds = carried.section(SearchRetirementWorld.HANDBOOK).pages.map { it.rooted }.toSet()
            val raw = world.provider.indexedState()
            val observation = CarryRecoveryObservation(
                holderContainsVictim = victim in carriedIds,
                holderContainsControl = control in carriedIds,
                victimRaw = raw.containsKey(victim),
                victimTerms = world.provider.search(SearchQuery("handbook-victim-term", limit = 20, offset = 0)).total,
                controlRaw = raw.containsKey(control),
                controlTerms = world.provider.search(SearchQuery("handbook-control-term", limit = 20, offset = 0)).total,
            )
            assertSoftly {
                observation shouldBe CarryRecoveryObservation(
                    holderContainsVictim = true,
                    holderContainsControl = true,
                    victimRaw = false,
                    victimTerms = 0L,
                    controlRaw = true,
                    controlTerms = 1L,
                )
            }
        }
    }

    test("retirement failure and carried section recover through explicit rebuild after search outage") {
        SearchRetirementWorld().use { world ->
            world.observeRoots()
            val before = world.builder.rebuild()
            val victim = RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookVictimId)
            val control = RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookControlId)
            Files.delete(world.handbookVictimPath)
            world.failNextRender()
            val failure = runCatching { world.builder.rebuild() }.exceptionOrNull()

            failure shouldNotBe null
            failure?.message shouldBe "renderer unavailable for RED oracle at docs-anchor.md"
            world.rendererFailureCount shouldBe 1
            world.idMap.retiredAt(SearchRetirementWorld.HANDBOOK, world.handbookVictimId) shouldNotBe null
            world.idMap.bindingInRoot(SearchRetirementWorld.HANDBOOK, world.handbookVictimId) shouldBe null
            before.pageAt(victim) shouldNotBe null
            before.pageAt(control) shouldNotBe null
            (world.builder.current === before) shouldBe true
            world.availability.markUnavailable(SearchRetirementWorld.HANDBOOK, UnavailableCause.VANISHED)
            world.failNextSearchPublications(1)
            world.updateMainAnchor()
            val carried = world.builder.rebuild()
            val carriedIds = carried.section(SearchRetirementWorld.HANDBOOK).pages.map { it.rooted }.toSet()

            carriedIds shouldBe setOf(victim, control, RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookAnchorId))
            world.publicationRetirements.last() shouldBe emptySet()
            world.idMap.retiredUnboundIds() shouldBe setOf(victim)
            world.searchPublicationFailureCount shouldBe 1
            world.searchPublicationFailuresRemaining shouldBe 0
            world.provider.indexedState().containsKey(victim) shouldBe true
            world.provider.search(SearchQuery("handbook-victim-term", limit = 20, offset = 0)).total shouldBeGreaterThan 0L

            val holderBeforeExplicit = world.builder.current
            val checkpointsBeforeExplicit = world.checkpoints.load()
            val returned = world.builder.rebuildSearchIndex()
            val raw = world.provider.indexedState()
            val observation = ExplicitRecoveryObservation(
                victimRaw = raw.containsKey(victim),
                victimTerms = world.provider.search(SearchQuery("handbook-victim-term", limit = 20, offset = 0)).total,
                controlRaw = raw.containsKey(control),
                controlTerms = world.provider.search(SearchQuery("handbook-control-term", limit = 20, offset = 0)).total,
                returnedPages = returned,
                holderUnchanged = world.builder.current === holderBeforeExplicit,
                checkpointsUnchanged = world.checkpoints.load() == checkpointsBeforeExplicit,
            )
            assertSoftly {
                observation shouldBe ExplicitRecoveryObservation(
                    victimRaw = false,
                    victimTerms = 0L,
                    controlRaw = true,
                    controlTerms = 1L,
                    returnedPages = before.pages.size - 1,
                    holderUnchanged = true,
                    checkpointsUnchanged = true,
                )
            }
            world.idMap.retiredUnboundIds() shouldBe setOf(victim)
        }
    }

    test("partial retired deletion commits one page, rolls back the late page, and retries durably") {
        SearchRetirementWorld().use { world ->
            val docsBody = "甲乙丙丁戊己"
            val handbookBody = "天地玄黃宇宙"
            Files.write(
                world.victimPath,
                world.pageBytes(world.victimId, "Victim", "lost-delivery-victim-term $docsBody"),
            )
            Files.write(
                world.handbookVictimPath,
                world.pageBytes(world.handbookVictimId, "Handbook victim", "handbook-victim-term $handbookBody"),
            )
            world.observeRoots()
            world.builder.rebuild()
            val docsVictim = RootedPageId(RootName.PRIMARY, world.victimId)
            val handbookVictim = RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookVictimId)
            val initial = world.provider.indexedState()
            val controlKeys = setOf(
                RootedPageId(RootName.PRIMARY, world.mainAnchorId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookAnchorId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookControlId),
            )
            initial.keys shouldBe setOf(docsVictim, handbookVictim) + controlKeys
            assertRecoverySearch(world, "lost-delivery-victim-term", 1L)
            assertRecoverySearch(world, "handbook-victim-term", 1L)
            assertRecoverySearch(world, "乙丙丁", 1L)
            assertRecoverySearch(world, "地玄黃", 1L)

            val controlDirty = DirtyPage(
                pageId = world.handbookControlId,
                path = RootedPath(SearchRetirementWorld.HANDBOOK, TreePath.require("control.md")),
                expectedHash = "sha256:partial-delete-control",
                stage = Stage.WRITING,
            )
            world.dirtyPages.mark(controlDirty.pageId, controlDirty.path, controlDirty.expectedHash, controlDirty.stage)
            Files.delete(world.victimPath)
            Files.delete(world.handbookVictimPath)
            world.retire(RootName.PRIMARY, world.victimId)
            world.retire(SearchRetirementWorld.HANDBOOK, world.handbookVictimId)
            val retired = setOf(docsVictim, handbookVictim)
            world.idMap.retiredUnboundIds() shouldBe retired
            val metadataAfterProof = world.applicationMetadata()
            metadataAfterProof.retired shouldBe retired
            metadataAfterProof.retiredBindings.map { RootedPageId(it.path.root, it.id) }.toSet() shouldBe retired
            metadataAfterProof.bindings.filter { RootedPageId(it.path.root, it.id) in controlKeys }
                .map { RootedPageId(it.path.root, it.id) }.toSet() shouldBe controlKeys
            metadataAfterProof.checkpoints.filterKeys { it in controlKeys }.keys shouldBe controlKeys
            metadataAfterProof.dirtyPages.filter { it.pageId == controlDirty.pageId } shouldBe listOf(controlDirty)

            val audit = world.withSearchWriter { connection ->
                try {
                    val expectedGeneration = createPartialDeleteTriggers(connection, retired)
                    val failure = runCatching { world.builder.rebuild() }.exceptionOrNull()
                    failure shouldBe null
                    readPartialDeleteAudit(connection).also { audit ->
                        audit.rows.size shouldBe 1
                        audit.rows.single().generation shouldBe expectedGeneration
                    }
                } finally {
                    dropPartialDeleteTriggers(connection)
                }
            }
            audit.rows.size shouldBe 1
            world.delegatedDeleteExceptions.size shouldBe 1
            world.delegatedDeleteExceptions.single().message shouldBe
                "[SQLITE_CONSTRAINT_TRIGGER] A RAISE function within a trigger fired, causing the SQL statement to abort " +
                "(chunk3 late search_page delete failure)"

            val firstRetired = audit.rows.single().rooted
            val afterFailure = world.provider.indexedState()
            afterFailure.keys shouldBe initial.keys - firstRetired
            afterFailure.keys.size shouldBe 4
            val survivor = retired - firstRetired
            survivor.forEach { rooted -> afterFailure[rooted] shouldBe initial.getValue(rooted) }
            setOf(
                RootedPageId(RootName.PRIMARY, world.mainAnchorId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookAnchorId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookControlId),
            ).forEach { rooted -> afterFailure[rooted] shouldBe initial.getValue(rooted) }
            assertRecoverySearch(world, "lost-delivery-victim-term", if (docsVictim in survivor) 1L else 0L)
            assertRecoverySearch(world, "handbook-victim-term", if (handbookVictim in survivor) 1L else 0L)
            assertRecoverySearch(world, "乙丙丁", if (docsVictim in survivor) 1L else 0L)
            assertRecoverySearch(world, "地玄黃", if (handbookVictim in survivor) 1L else 0L)
            assertRecoverySearch(world, "main-anchor-term", 1L)
            assertRecoverySearch(world, "handbook-anchor-term", 1L)
            assertRecoverySearch(world, "handbook-control-term", 1L)
            val metadataAfterFailure = world.applicationMetadata()
            metadataAfterFailure.retiredBindings shouldBe metadataAfterProof.retiredBindings
            metadataAfterFailure.bindings.filter { RootedPageId(it.path.root, it.id) in controlKeys } shouldBe
                metadataAfterProof.bindings.filter { RootedPageId(it.path.root, it.id) in controlKeys }
            metadataAfterFailure.checkpoints.filterKeys { it in controlKeys } shouldBe
                metadataAfterProof.checkpoints.filterKeys { it in controlKeys }
            metadataAfterFailure.dirtyPages.filter { it.pageId == controlDirty.pageId } shouldBe listOf(controlDirty)

            world.builder.rebuild()

            world.publicationRetirements.last() shouldBe emptySet()
            world.provider.indexedState().keys shouldBe initial.keys - retired
            world.provider.indexedState().keys.size shouldBe 3
            setOf(
                RootedPageId(RootName.PRIMARY, world.mainAnchorId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookAnchorId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookControlId),
            ).forEach { rooted -> world.provider.indexedState()[rooted] shouldBe initial.getValue(rooted) }
            assertRecoverySearch(world, "lost-delivery-victim-term", 0L)
            assertRecoverySearch(world, "handbook-victim-term", 0L)
            assertRecoverySearch(world, "乙丙丁", 0L)
            assertRecoverySearch(world, "地玄黃", 0L)
            assertRecoverySearch(world, "main-anchor-term", 1L)
            assertRecoverySearch(world, "handbook-anchor-term", 1L)
            assertRecoverySearch(world, "handbook-control-term", 1L)
            val metadataAfterRetry = world.applicationMetadata()
            metadataAfterRetry.retired shouldBe metadataAfterFailure.retired
            metadataAfterRetry.retiredBindings shouldBe metadataAfterFailure.retiredBindings
            metadataAfterRetry.bindings.filter { RootedPageId(it.path.root, it.id) in controlKeys } shouldBe
                metadataAfterFailure.bindings.filter { RootedPageId(it.path.root, it.id) in controlKeys }
            metadataAfterRetry.checkpoints.filterKeys { it in controlKeys } shouldBe
                metadataAfterFailure.checkpoints.filterKeys { it in controlKeys }
            metadataAfterRetry.dirtyPages.filter { it.pageId == controlDirty.pageId } shouldBe listOf(controlDirty)
        }
    }

    test("restored old search rows survive failed delivery, then explicit search-only cleanup") {
        SearchRetirementWorld().use { world ->
            world.observeRoots()
            world.builder.rebuild()
            val victim = RootedPageId(RootName.PRIMARY, world.victimId)
            val oldState = world.provider.indexedState().getValue(victim)
            val expectedInitialKeys = setOf(
                victim,
                RootedPageId(RootName.PRIMARY, world.mainAnchorId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookAnchorId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookVictimId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookControlId),
            )
            world.provider.indexedState().keys shouldBe expectedInitialKeys
            val oldBytes = Files.readAllBytes(world.victimPath)
            val backup = world.dataDir.resolve("search-old.db")

            world.withStoresClosed {
                clearEmptySearchSidecars(world.searchDatabasePath)
                Files.copy(world.searchDatabasePath, backup, StandardCopyOption.REPLACE_EXISTING)
            }
            Files.delete(world.victimPath)
            world.retire(RootName.PRIMARY, world.victimId)
            world.builder.rebuild()
            world.provider.indexedState().containsKey(victim) shouldBe false
            val metadataBeforeRestore = world.applicationMetadata()
            val expectedCleanKeys = setOf(
                RootedPageId(RootName.PRIMARY, world.mainAnchorId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookAnchorId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookVictimId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookControlId),
            )
            world.provider.indexedState().keys shouldBe expectedCleanKeys

            world.withStoresClosed {
                clearEmptySearchSidecars(world.searchDatabasePath)
                Files.copy(backup, world.searchDatabasePath, StandardCopyOption.REPLACE_EXISTING)
            }

            // No scan has happened after the physical restore: durable app authority is retired while the old
            // search row, its path, hash, and bytes' content are visibly back in the derived store.
            world.idMap.retiredAt(RootName.PRIMARY, world.victimId) shouldNotBe null
            world.idMap.bindingInRoot(RootName.PRIMARY, world.victimId) shouldBe null
            world.applicationMetadata() shouldBe metadataBeforeRestore
            world.idMap.retiredAt(RootName.PRIMARY, world.victimId)?.path shouldBe
                RootedPath(RootName.PRIMARY, TreePath.require("victim.md"))
            world.provider.indexedState().keys shouldBe expectedInitialKeys
            world.provider.indexedState()[victim] shouldBe oldState
            world.contentHash(oldBytes) shouldBe oldState.contentHash
            assertRecoverySearch(world, "lost-delivery-victim-term", 1L)

            world.failNextSearchPublications(1)
            val failure = runCatching { world.builder.rebuild() }.exceptionOrNull()
            failure shouldBe null
            val holderAfterFailedDelivery = world.builder.current
            holderAfterFailedDelivery.pageAt(victim) shouldBe null
            world.searchPublicationFailureCount shouldBe 1
            world.provider.indexedState().containsKey(victim) shouldBe true
            world.provider.indexedState().keys shouldBe setOf(
                victim,
                RootedPageId(RootName.PRIMARY, world.mainAnchorId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookAnchorId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookVictimId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookControlId),
            )
            val metadataAfterFailedDelivery = world.applicationMetadata()
            assertRecoverySearch(world, "lost-delivery-victim-term", 1L)

            val expectedHolderKeys = setOf(
                RootedPageId(RootName.PRIMARY, world.mainAnchorId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookAnchorId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookVictimId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookControlId),
            )
            holderAfterFailedDelivery.pages.map { it.rooted }.toSet() shouldBe expectedHolderKeys
            val metadataBeforeExplicit = world.applicationMetadata()
            world.builder.rebuildSearchIndex() shouldBe 4
            (world.builder.current === holderAfterFailedDelivery) shouldBe true
            world.provider.indexedState().containsKey(victim) shouldBe false
            world.provider.indexedState().keys shouldBe expectedHolderKeys
            world.idMap.retiredAt(RootName.PRIMARY, world.victimId)?.path shouldBe
                RootedPath(RootName.PRIMARY, TreePath.require("victim.md"))
            assertRecoverySearch(world, "lost-delivery-victim-term", 0L)
            assertRecoverySearch(world, "main-anchor-term", 1L)
            assertRecoverySearch(world, "handbook-anchor-term", 1L)
            assertRecoverySearch(world, "handbook-victim-term", 1L)
            assertRecoverySearch(world, "handbook-control-term", 1L)
            world.applicationMetadata() shouldBe metadataAfterFailedDelivery
            world.applicationMetadata() shouldBe metadataBeforeExplicit
        }
    }

    test("a detached explicit root removes its retired victim while carrying its unretired rows") {
        SearchRetirementWorld().use { world ->
            world.observeRoots()
            world.builder.rebuild()
            val retired = RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookVictimId)
            val docsVictim = RootedPageId(RootName.PRIMARY, world.victimId)
            Files.delete(world.handbookVictimPath)
            world.retire(SearchRetirementWorld.HANDBOOK, world.handbookVictimId)
            world.failNextDelete()
            world.builder.rebuild()
            world.provider.indexedState().containsKey(retired) shouldBe true
            world.idMap.bindingInRoot(SearchRetirementWorld.HANDBOOK, world.handbookControlId) shouldNotBe null
            world.idMap.retiredAt(SearchRetirementWorld.HANDBOOK, world.handbookControlId) shouldBe null

            world.reopen(setOf(RootName.PRIMARY))
            world.resetScanObservability()
            val docsOnly = world.builder.rebuild()
            docsOnly.pages.map { it.root }.toSet() shouldBe setOf(RootName.PRIMARY)
            docsOnly.sections.map { it.root }.toSet() shouldBe setOf(RootName.PRIMARY)
            world.scannedRootsSnapshot shouldBe listOf(RootName.PRIMARY)
            world.sourceRoots shouldBe setOf(RootName.PRIMARY)
            val expectedKeys = setOf(
                docsVictim,
                RootedPageId(RootName.PRIMARY, world.mainAnchorId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookAnchorId),
                RootedPageId(SearchRetirementWorld.HANDBOOK, world.handbookControlId),
            )
            world.commandConfig(setOf(RootName.PRIMARY)).roots.list.map { it.name } shouldBe listOf(RootName.PRIMARY)
            world.provider.indexedState().keys shouldBe expectedKeys
            world.idMap.bindingInRoot(SearchRetirementWorld.HANDBOOK, world.handbookVictimId) shouldBe null
            world.idMap.retiredUnboundIds() shouldBe setOf(retired)
            assertRecoverySearch(world, "lost-delivery-victim-term", 1L)
            assertRecoverySearch(world, "handbook-anchor-term", 1L)
            assertRecoverySearch(world, "handbook-control-term", 1L)
            assertRecoverySearch(world, "handbook-victim-term", 0L)
            val metadataBeforeExplicit = world.applicationMetadata()
            world.builder.rebuildSearchIndex() shouldBe 2
            world.provider.indexedState().keys shouldBe expectedKeys
            assertRecoverySearch(world, "lost-delivery-victim-term", 1L)
            assertRecoverySearch(world, "handbook-anchor-term", 1L)
            assertRecoverySearch(world, "handbook-control-term", 1L)
            assertRecoverySearch(world, "handbook-victim-term", 0L)
            world.applicationMetadata() shouldBe metadataBeforeExplicit
        }
    }
})

private data class ExplicitRecoveryObservation(
    val victimRaw: Boolean,
    val victimTerms: Long,
    val controlRaw: Boolean,
    val controlTerms: Long,
    val returnedPages: Int,
    val holderUnchanged: Boolean,
    val checkpointsUnchanged: Boolean,
)

private data class CarryRecoveryObservation(
    val holderContainsVictim: Boolean,
    val holderContainsControl: Boolean,
    val victimRaw: Boolean,
    val victimTerms: Long,
    val controlRaw: Boolean,
    val controlTerms: Long,
)

private data class PartialDeleteAudit(val rows: List<PartialDeleteRecord>)

private data class PartialDeleteRecord(val generation: Long, val rooted: RootedPageId)

private fun assertRecoverySearch(world: SearchRetirementWorld, term: String, total: Long) {
    val result = world.provider.search(SearchQuery(term, limit = 20, offset = 0))
    result.total shouldBe total
    if (total == 0L) {
        result.hits shouldBe emptyList()
    } else {
        result.hits.isNotEmpty() shouldBe true
    }
}

private fun clearEmptySearchSidecars(searchDatabasePath: java.nio.file.Path) {
    val sidecars = listOf(
        searchDatabasePath.resolveSibling("${searchDatabasePath.fileName}-wal"),
        searchDatabasePath.resolveSibling("${searchDatabasePath.fileName}-shm"),
    )
    val wal = sidecars.first()
    if (Files.exists(wal)) {
        val size = Files.size(wal)
        check(size == 0L) { "refusing physical search restore while ${wal.fileName} contains $size bytes" }
    }
    sidecars.forEach(Files::deleteIfExists)
}

private fun createPartialDeleteTriggers(
    connection: java.sql.Connection,
    victims: Set<RootedPageId>,
): Long {
    connection.createStatement().use { statement ->
        statement.execute(
            "CREATE TEMP TABLE chunk3_partial_eligible(" +
                "generation INTEGER NOT NULL, root TEXT NOT NULL, page_id BLOB NOT NULL, " +
                "PRIMARY KEY(generation, root, page_id))",
        )
        statement.execute(
            "CREATE TEMP TABLE chunk3_partial_audit(" +
                "audit_order INTEGER NOT NULL, generation INTEGER NOT NULL, " +
                "root TEXT NOT NULL, page_id BLOB NOT NULL, PRIMARY KEY(generation, root, page_id))",
        )
        val generation = connection.createStatement().use { active ->
            active.executeQuery("SELECT CAST(value AS INTEGER) FROM search_meta WHERE key = 'active_generation'").use { rows ->
                check(rows.next())
                rows.getLong(1)
            }
        }
        connection.prepareStatement(
            "INSERT INTO chunk3_partial_eligible(generation, root, page_id) VALUES (?, ?, ?)",
        ).use { insert ->
            victims.forEach { rooted ->
                insert.setLong(1, generation)
                insert.setString(2, rooted.root.value)
                insert.setBytes(3, rooted.id.toByteArray())
                insert.addBatch()
            }
            insert.executeBatch()
        }
        statement.execute(
            "CREATE TEMP TRIGGER chunk3_partial_audit_trigger AFTER DELETE ON main.search_page " +
                "WHEN EXISTS (SELECT 1 FROM chunk3_partial_eligible e WHERE e.generation = OLD.generation " +
                "AND e.root = OLD.root AND e.page_id = OLD.page_id) " +
                "BEGIN INSERT INTO chunk3_partial_audit(audit_order, generation, root, page_id) " +
                "SELECT COALESCE(MAX(audit_order), 0) + 1, OLD.generation, OLD.root, OLD.page_id " +
                "FROM chunk3_partial_audit; END",
        )
        statement.execute(
            "CREATE TEMP TRIGGER chunk3_partial_abort_trigger BEFORE DELETE ON main.search_page " +
                "WHEN EXISTS (SELECT 1 FROM chunk3_partial_eligible e WHERE e.generation = OLD.generation " +
                "AND e.root = OLD.root AND e.page_id = OLD.page_id) " +
                "AND (SELECT COUNT(*) FROM chunk3_partial_audit) >= 1 " +
                "BEGIN SELECT RAISE(ABORT, 'chunk3 late search_page delete failure'); END",
        )
        return generation
    }
}

private fun readPartialDeleteAudit(connection: java.sql.Connection): PartialDeleteAudit =
    connection.createStatement().use { statement ->
        statement.executeQuery("SELECT generation, root, page_id FROM chunk3_partial_audit ORDER BY audit_order").use { rows ->
            PartialDeleteAudit(
                buildList {
                    while (rows.next()) {
                        add(
                            PartialDeleteRecord(
                                generation = rows.getLong(1),
                                rooted = RootedPageId(
                                    RootName.require(rows.getString(2)),
                                    com.plainbase.domain.page.PageId.fromByteArray(rows.getBytes(3)),
                                ),
                            ),
                        )
                    }
                },
            )
        }
    }

private fun dropPartialDeleteTriggers(connection: java.sql.Connection) {
    connection.createStatement().use { statement ->
        statement.execute("DROP TRIGGER IF EXISTS chunk3_partial_abort_trigger")
        statement.execute("DROP TRIGGER IF EXISTS chunk3_partial_audit_trigger")
        statement.execute("DROP TABLE IF EXISTS chunk3_partial_audit")
        statement.execute("DROP TABLE IF EXISTS chunk3_partial_eligible")
    }
}
