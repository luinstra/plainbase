package com.plainbase.domain.service

import com.plainbase.domain.content.CasResult
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.Nfc
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.FrontmatterBlock
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.frameworks.filesystem.Fixtures
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.queryLong
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.time.Clock

/**
 * AdoptionPass acceptance tests (the chunk 4b master criteria, component level): the zero-write
 * proof, dry-run exactness, materialization + idempotence with the single-insertion byte-diff, the
 * pre-write intent log and its mid-batch-abort reconstruction, the §5.2 duplicate policy
 * end-to-end, and binary-at-rest over the adoption-seeded id_map.
 *
 * Every test runs against a FRESH COPY of the committed fixture tree in a temp dir — the committed
 * fixtures are never written to — over an in-memory SQLite id_map. Accept/refuse expectations are
 * derived from the patcher itself (its behavior is owned by the §A3 goldens; these tests pin
 * AdoptionPass's ROUTING of each patcher outcome, so fixture frontmatter can evolve freely).
 */
class AdoptionPassTest : FunSpec({

    test("zero-write proof: default adoption assigns ids to all pages and the directory checksum is identical") {
        withHarness { h ->
            val before = checksum(h.root)
            val report = h.pass().run(AdoptionPass.Mode.RECORD)
            checksum(h.root) shouldBe before

            val pages = mdPages(h.root).map { Nfc.normalize(it) }
            report.pages shouldHaveSize pages.size
            h.idMap.bindings() shouldHaveSize pages.size
            h.idMap.bindings().map { it.path.path.value }.shouldContainExactlyInAnyOrder(pages)
            // Read-only first index: nothing is materialized, every id is map-only.
            h.idMap.bindings().none { it.materialized }.shouldBeTrue()
            // Binary at rest over the adoption-seeded table (direct SQL, below the adapter).
            h.driver.queryLong("SELECT count(*) FROM id_map WHERE length(id) != 16") shouldBe 0L
        }
    }

    test("dry-run lists exactly the unmaterialized pages and the would-refuse pages, and writes nothing") {
        withHarness { h ->
            addRefusalPage(h.root)
            val before = checksum(h.root)
            val expected = patcherOutcomes(h.root)

            val report = h.pass().run(AdoptionPass.Mode.PREVIEW).report(RootName.MAIN)

            // Writes NOTHING: neither tree bytes nor db rows.
            checksum(h.root) shouldBe before
            h.idMap.bindings().shouldBeEmpty()
            h.idMap.issues().shouldBeEmpty()

            // EXACTLY the pages a MATERIALIZE run would patch / refuse.
            report.pages(AdoptionPass.Disposition.WOULD_MATERIALIZE).map { it.path.value }
                .shouldContainExactlyInAnyOrder(expected.accepted)
            report.pages(AdoptionPass.Disposition.REFUSED).map { it.path.value }
                .shouldContainExactlyInAnyOrder(expected.refused)

            // The would-refuse listing carries the rule-naming reason (§A3 measurement input).
            val refused = report.pages(AdoptionPass.Disposition.REFUSED).single { it.path.value == REFUSAL_PAGE }
            val reason = refused.issues.filterIsInstance<IdentityIssue.PatchRefused>().single()
            reason.message shouldContain "plain unquoted scalars"
        }
    }

    test("materialize: 100% of accepted pages patched (single-insertion byte-diff), second run = zero writes") {
        withHarness { h ->
            addRefusalPage(h.root)
            val originals = mdPages(h.root).associate { Nfc.normalize(it) to Files.readAllBytes(h.root.resolve(it)) }
            val expected = patcherOutcomes(h.root)

            // Run 1, with the intent log and the writes recorded into ONE event stream so the
            // intent-before-write pairing is asserted positionally.
            val events = mutableListOf<Pair<String, TreePath>>()
            val recording = RecordingStore(LocalContentStore(h.root)) { path -> events.add("write" to path) }
            val report = h.pass(recording).run(AdoptionPass.Mode.MATERIALIZE) { page, _ -> events.add("intent" to page.path) }
                .report(RootName.MAIN)

            // Every write is immediately preceded by its own intent entry.
            events.size shouldBe 2 * expected.accepted.size
            events.chunked(2).forEach { (intent, write) ->
                intent.first shouldBe "intent"
                write.first shouldBe "write"
                write.second shouldBe intent.second
            }

            // 100% of accepted pages materialized; each differs from its original by exactly the
            // patcher's single-point insertion carrying the assigned id.
            val materialized = report.pages(AdoptionPass.Disposition.MATERIALIZED)
            materialized.map { it.path.value }.shouldContainExactlyInAnyOrder(expected.accepted)
            materialized.forEach { page ->
                val original = originals.getValue(page.path.value)
                val patched = Files.readAllBytes(resolveByNfc(h.root, page.path.value))
                assertSingleIdInsertion(original, patched, page.id)
                h.idMap.find(RootedPath(RootName.MAIN, page.path)).shouldNotBeNull().materialized shouldBe true
            }

            // Refused pages are untouched, keep their map identity unmaterialized, and the crafted
            // page's issue is persisted with the rule-naming message.
            report.pages(AdoptionPass.Disposition.REFUSED).map { it.path.value }
                .shouldContainExactlyInAnyOrder(expected.refused)
            expected.refused.forEach { rel ->
                Files.readAllBytes(resolveByNfc(h.root, rel)) shouldBe originals.getValue(rel)
                h.idMap.find(RootedPath(RootName.MAIN, TreePath.require(rel))).shouldNotBeNull().materialized shouldBe false
            }
            val refusal = h.idMap.issues().filterIsInstance<IdentityIssue.PatchRefused>()
                .single { it.path.value == REFUSAL_PAGE }
            refusal.message shouldContain "plain unquoted scalars"

            // Idempotence: a second MATERIALIZE run performs ZERO ContentStore writes and leaves
            // the tree byte-identical; every previously patched page now reads back as FRONTMATTER.
            val afterFirst = checksum(h.root)
            val counting = RecordingStore(LocalContentStore(h.root)) {}
            val second = h.pass(counting).run(AdoptionPass.Mode.MATERIALIZE).report(RootName.MAIN)
            counting.writes shouldBe 0
            checksum(h.root) shouldBe afterFirst
            second.pages(AdoptionPass.Disposition.MATERIALIZED).shouldBeEmpty()
            second.pages(AdoptionPass.Disposition.ALREADY_MATERIALIZED) shouldHaveSize materialized.size
            second.pages(AdoptionPass.Disposition.ALREADY_MATERIALIZED)
                .all { it.source == PageIdentityService.Source.FRONTMATTER }.shouldBeTrue()
        }
    }

    test("duplicate-id fixture: the previously-bound path keeps the id, the copy is reassigned and the issue persisted") {
        withHarness { h ->
            h.pass().run(AdoptionPass.Mode.MATERIALIZE)
            val keptPath = TreePath.require("notes/no-frontmatter.md")
            val keptId = h.idMap.find(RootedPath(RootName.MAIN, keptPath)).shouldNotBeNull().id

            // Copy the materialized page inside the temp tree — the §5.2 copied-file scenario.
            val copyPath = TreePath.require("notes/no-frontmatter-copy.md")
            Files.copy(h.root.resolve(keptPath.value), h.root.resolve(copyPath.value))

            val report = h.pass().run(AdoptionPass.Mode.MATERIALIZE)
            val copy = report.pages.single { it.path == copyPath }
            copy.source shouldBe PageIdentityService.Source.MINTED
            copy.id shouldNotBe keptId
            // The copy's file still carries the OTHER page's id line -> AlreadyPresent -> never
            // overwritten, never marked materialized (§5.2: the fresh id is not materialized).
            copy.disposition shouldBe AdoptionPass.Disposition.MAPPED
            h.idMap.find(RootedPath(RootName.MAIN, copyPath)).shouldNotBeNull().materialized shouldBe false
            h.idMap.pathOf(keptId) shouldBe RootedPath(RootName.MAIN, keptPath)

            val issue = h.idMap.issues().filterIsInstance<IdentityIssue.DuplicateId>().single()
            issue shouldBe IdentityIssue.DuplicateId(id = keptId, root = RootName.MAIN, keptPath = keptPath, reassignedPath = copyPath)

            // Rescan stability: the copy keeps its minted id on the next run (now from id_map), so
            // its /p/{id} permalink is stable while the conflict persists.
            val third = h.pass().run(AdoptionPass.Mode.MATERIALIZE)
            val copyAgain = third.pages.single { it.path == copyPath }
            copyAgain.id shouldBe copy.id
            copyAgain.source shouldBe PageIdentityService.Source.ID_MAP
            // The deduped issue list still holds exactly one duplicate row.
            h.idMap.issues().filterIsInstance<IdentityIssue.DuplicateId>() shouldHaveSize 1
        }
    }

    test("intent log: a simulated mid-batch abort leaves a log from which completed/pending is reconstructable") {
        withHarness { h ->
            val real = LocalContentStore(h.root)
            val aborting = AbortingStore(real, failOnWrite = 4)
            val intents = mutableListOf<Pair<RootedPath, PageId>>()

            // A live root that fails a write is a genuine fault, NOT a disappearance: the classifier re-probes,
            // finds the tree right where it left it, and rethrows the IOException unchanged rather than laundering
            // a bug into "the disk is gone" (which would leave the operator restoring a path that never moved).
            shouldThrow<IOException> {
                h.pass(aborting).run(AdoptionPass.Mode.MATERIALIZE) { page, id -> intents.add(page to id) }
            }

            // 3 writes landed; the 4th was intent-logged and then aborted before the write — so the
            // completed/pending split falls out of the log plus the files' current bytes alone.
            intents shouldHaveSize 4
            val (completed, pending) = intents.partition { (page, id) ->
                String(Files.readAllBytes(resolveByNfc(h.root, page.path.value)), Charsets.UTF_8).contains("id: ${id.value}")
            }
            completed shouldHaveSize 3
            pending shouldHaveSize 1
            pending.single() shouldBe intents.last()

            // Idempotence makes re-running the reconciliation: the pending page materializes now.
            h.pass().run(AdoptionPass.Mode.MATERIALIZE)
            h.idMap.find(pending.single().first).shouldNotBeNull().materialized shouldBe true
        }
    }

    test("a page already carrying a valid frontmatter id is honored and bound as materialized") {
        withHarness { h ->
            val v4 = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
            Files.writeString(h.root.resolve("notes/already-identified.md"), "---\nid: $v4\ntitle: x\n---\nbody\n")

            val report = h.pass().run(AdoptionPass.Mode.RECORD)
            val page = report.pages.single { it.path.value == "notes/already-identified.md" }
            page.id shouldBe PageId.require(v4)
            page.source shouldBe PageIdentityService.Source.FRONTMATTER
            page.disposition shouldBe AdoptionPass.Disposition.ALREADY_MATERIALIZED
            h.idMap.find(RootedPath(RootName.MAIN, page.path)).shouldNotBeNull().materialized shouldBe true
        }
    }

    // ---- ONE global plan over EVERY root: what makes the D17 rank contest decidable at all ----------
    //
    // The pass used to run once per root, sequentially, which made every not-yet-reached root look
    // UNSCANNED - hence untouchable (D16) - so a rank winner could not take the id it outranked: it
    // minted a fresh one and wrote THAT into the page's frontmatter, durably, contrary to the policy.
    // Rank can only decide a contest both sides turn up to, so now the plan scans them all first.
    // Seating discipline as ever: no rank-0-main assumption - each case is driven from both seatings.

    test("cross-root rank: the HIGHER-ranked root TAKES the contested id from the live lower-ranked owner (no fresh mint)") {
        withTwoRoots { h, extra ->
            // The lower-ranked root owns the id today, and its page is right there on disk.
            Files.writeString(extra.resolve("shared.md"), "---\nid: ${CONTESTED.value}\n---\nbody\n")
            h.idMap.bind(EXTRA_PAGE, CONTESTED, materialized = true)
            Files.writeString(h.root.resolve("notes/claimant.md"), "---\nid: ${CONTESTED.value}\ntitle: x\n---\nbody\n")
            val registry = RootRegistry.of(listOf(localRoot("main", h.root), localRoot("extra", extra))) // main outranks

            val plan = h.pass(registry = registry, extras = listOf(source(extra))).run(AdoptionPass.Mode.RECORD)

            // Rank decides, previously-bound does NOT (D17) - and the winner keeps the id it already carries
            // in its frontmatter, so nothing is minted and nothing is rewritten.
            val winner = plan.pages.single { it.path.value == "notes/claimant.md" }
            winner.id shouldBe CONTESTED
            winner.source shouldBe PageIdentityService.Source.FRONTMATTER
            h.idMap.pathOf(CONTESTED) shouldBe RootedPath(RootName.MAIN, winner.path)

            // The beaten owner reassigns like any duplicate loser, and says so.
            val beaten = plan.report(EXTRA).pages.single()
            beaten.id shouldNotBe CONTESTED
            beaten.source shouldBe PageIdentityService.Source.MINTED
            beaten.issues.filterIsInstance<IdentityIssue.CrossRootDuplicateId>().single() shouldBe
                IdentityIssue.CrossRootDuplicateId(CONTESTED, kept = RootedPath(RootName.MAIN, winner.path), reassigned = EXTRA_PAGE)
        }
    }

    test("cross-root rank: an extra seated AHEAD of main wins the same contest - main's page is the one that reassigns") {
        withTwoRoots { h, extra ->
            Files.writeString(extra.resolve("shared.md"), "---\nid: ${CONTESTED.value}\n---\nbody\n")
            Files.writeString(h.root.resolve("notes/claimant.md"), "---\nid: ${CONTESTED.value}\ntitle: x\n---\nbody\n")
            val registry = RootRegistry.of(listOf(localRoot("extra", extra), localRoot("main", h.root))) // extra outranks

            val plan = h.pass(registry = registry, extras = listOf(source(extra))).run(AdoptionPass.Mode.RECORD)

            plan.report(EXTRA).pages.single().id shouldBe CONTESTED
            h.idMap.pathOf(CONTESTED) shouldBe EXTRA_PAGE
            val claimant = plan.pages.single { it.path.value == "notes/claimant.md" }
            claimant.id shouldNotBe CONTESTED
            claimant.issues.filterIsInstance<IdentityIssue.CrossRootDuplicateId>().single().kept shouldBe EXTRA_PAGE
        }
    }

    test("cross-root rank: the loser's own id_map row is NOT a way to keep the id - it is contested like any other claim") {
        withTwoRoots { h, extra ->
            // The loser carries NO frontmatter id: its only claim on the contested id is the id_map row. Under a
            // pass that binds inline that row is deleted by the winner's bind before the loser ever reads it; a
            // pass that plans BEFORE it binds still sees it, and honoring it would put ONE id on TWO pages - and
            // then bind the loser LAST, sweeping the winner's row and handing the id right back. So it reassigns.
            Files.writeString(extra.resolve("shared.md"), "---\ntitle: Shared\n---\nbody\n")
            h.idMap.bind(EXTRA_PAGE, CONTESTED, materialized = false)
            Files.writeString(h.root.resolve("notes/claimant.md"), "---\nid: ${CONTESTED.value}\ntitle: x\n---\nbody\n")
            val registry = RootRegistry.of(listOf(localRoot("main", h.root), localRoot("extra", extra)))

            val plan = h.pass(registry = registry, extras = listOf(source(extra))).run(AdoptionPass.Mode.RECORD)

            val winner = plan.pages.single { it.path.value == "notes/claimant.md" }
            winner.id shouldBe CONTESTED
            val beaten = plan.report(EXTRA).pages.single()
            beaten.id shouldNotBe CONTESTED
            beaten.source shouldBe PageIdentityService.Source.MINTED
            beaten.issues.filterIsInstance<IdentityIssue.CrossRootDuplicateId>().single().kept shouldBe
                RootedPath(RootName.MAIN, winner.path)
            // The decisive assertion: the winner's binding SURVIVED the loser's bind. Without the contest on the
            // mapped id, the plan holds one id twice and the last bind wins - a silent, durable identity swap.
            h.idMap.pathOf(CONTESTED) shouldBe RootedPath(RootName.MAIN, winner.path)
            h.idMap.bindings().map { it.id }.toSet() shouldHaveSize h.idMap.bindings().size
        }
    }

    test("the dry run IS the write: PREVIEW's planned bytes are exactly the bytes MATERIALIZE puts on disk") {
        withTwoRoots { h, extra ->
            addRefusalPage(h.root)
            Files.writeString(extra.resolve("onboarding.md"), "---\ntitle: Onboarding\n---\nbody\n")
            val registry = RootRegistry.of(listOf(localRoot("main", h.root), localRoot("extra", extra)))
            fun pass() = h.pass(registry = registry, extras = listOf(source(extra)))
            // RECORD first, so both runs below resolve the SAME ids from the id_map: a freshly minted id
            // differs per run by design, and it is the PATCH, not the mint, that this pins.
            pass().run(AdoptionPass.Mode.RECORD)

            val preview = pass().plan(AdoptionPass.Mode.PREVIEW)
            val previewed = preview.pages
                .filter { it.disposition == AdoptionPass.Disposition.WOULD_MATERIALIZE }
                .associate { page ->
                    val target = RootedPath(page.root, page.path)
                    target to requireNotNull(preview.bytesFor(target))
                }
            previewed.keys shouldContain EXTRA_ONBOARDING // the preview covers the extra root too

            val before = checksum(h.root) to checksum(extra)
            pass().run(AdoptionPass.Mode.MATERIALIZE)

            // EXACTLY the pages the preview named were touched - no others, in either root...
            val changed = changed(RootName.MAIN, before.first, checksum(h.root)) + changed(EXTRA, before.second, checksum(extra))
            changed shouldContainExactlyInAnyOrder previewed.keys.toList()
            // ...and each one holds, byte for byte, the bytes the preview showed. Same plan object, same bytes:
            // a preview that can disagree with its own write is worse than no preview at all.
            previewed.forEach { (page, bytes) ->
                val tree = if (page.root == RootName.MAIN) h.root else extra
                Files.readAllBytes(resolveByNfc(tree, page.path.value)) shouldBe bytes
            }
        }
    }

    test("a root that vanishes BETWEEN the plan and the write aborts the run: no file mutated, no partial binding") {
        withTwoRoots { h, extra ->
            Files.writeString(extra.resolve("onboarding.md"), "---\ntitle: Onboarding\n---\nbody\n")
            val registry = RootRegistry.of(listOf(localRoot("main", h.root), localRoot("extra", extra)))
            val pass = h.pass(registry = registry, extras = listOf(source(extra)))
            val before = checksum(h.root)

            val plan = pass.plan(AdoptionPass.Mode.MATERIALIZE) // read-only: every root was there for this
            extra.toFile().deleteRecursively() // ...and now one is not - the unmounted-disk shape, mid-run

            shouldThrow<RootUnavailable> { pass.apply(plan) }

            // apply() re-probes EVERY root before its FIRST write, so the abort costs nothing: main - which the
            // old sequential loop would already have half-adopted by now - is byte-identical, and not one binding
            // was recorded. Adopt deletes nothing and is idempotent, so restoring the path and re-running is free.
            checksum(h.root) shouldBe before
            h.idMap.bindings().shouldBeEmpty()
            h.idMap.issues().shouldBeEmpty()
        }
    }

    test("a root that vanishes DURING the write loop aborts - it is never RESURRECTED as a partial skeleton tree") {
        withTwoRoots { h, extra ->
            Files.writeString(extra.resolve("onboarding.md"), "---\ntitle: Onboarding\n---\nbody\n")
            val registry = RootRegistry.of(listOf(localRoot("main", h.root), localRoot("extra", extra)))
            // The window apply()'s pre-loop re-probe CANNOT close: every root was there when it looked, and one of
            // them goes away while the writes are already running. [VanishingStore] takes the disk out from under
            // the extra root at the instant its first page is written.
            val vanishing = VanishingStore(LocalContentStore(extra, rootName = EXTRA), extra)
            val pass = h.pass(registry = registry, extras = listOf(AdoptionPass.Source(EXTRA, vanishing)))

            shouldThrow<RootUnavailable> { pass.run(AdoptionPass.Mode.MATERIALIZE) }

            // THE assertion, and it is about the DISK, not the wire. A create-or-replace write makes missing parents,
            // so it would have re-made the deleted directory in order to land the page it was holding - leaving a
            // partial skeleton of the operator's tree (one file where a whole root used to be) at whatever that path
            // now resolves to, quite possibly a different mount entirely, and then reported SUCCESS. The CAS cannot:
            // it replaces a file it already resolved and creates nothing, so the root stays gone and the run stops.
            extra.toFile().exists() shouldBe false
        }
    }

    test("a page EDITED under the plan is not clobbered with the bytes the plan derived from the stale read") {
        withHarness { h ->
            val page = TreePath.require("notes/no-frontmatter.md")
            // ONE pass for both phases, as `run` (and so `AdoptCommand`) does: the plan's authority to replace a file
            // is the scan that produced it, and apply() CASes against exactly that read.
            val pass = h.pass()
            val plan = pass.plan(AdoptionPass.Mode.MATERIALIZE) // planned against the bytes as they are NOW...
            val edited = "---\ntitle: Edited underneath\n---\nsomeone else's work\n"
            Files.writeString(h.root.resolve(page.value), edited) // ...and someone edits the page before the write

            // The planned bytes are a patch of a file that no longer exists in that form. Writing them would silently
            // revert the edit, so the CAS precondition fails and the run stops instead of improvising.
            val stale = shouldThrow<PlanStale> { pass.apply(plan) }
            stale.page.path shouldBe page

            Files.readString(h.root.resolve(page.value)) shouldBe edited
        }
    }

    // ---- D16, the class-level partial-visibility guard --------------------------------------------
    //
    // A REGISTERED root with no source. `AdoptCommand` never builds this (it refuses to run unless it can
    // see every configured root), so the guard is reachable only from here - which is exactly why the test
    // stays: it is what stops a future caller from quietly re-introducing the cross-root steal.

    test("a configured root this pass has no source for is untouchable - even when the claimant OUTRANKS it") {
        withHarness { h ->
            val foreign = RootedPath(RootName.require("extra"), TreePath.require("mirror/page.md"))
            h.idMap.bind(foreign, CONTESTED, materialized = true)
            Files.writeString(h.root.resolve("notes/claimant.md"), "---\nid: ${CONTESTED.value}\ntitle: x\n---\nbody\n")
            val registry = RootRegistry.of(listOf(localRoot("main", h.root), localRoot("extra", h.root)))

            val plan = h.pass(registry = registry).run(AdoptionPass.Mode.RECORD) // ...and no source for 'extra'

            // Rank says main wins. Visibility says main does not get to decide: a pass that cannot see extra's
            // disk cannot know the page is still there, so taking the id would delete a durable binding on a
            // guess. The claimant reassigns and records instead; a pass that CAN see both trees settles it.
            val claimant = plan.pages.single { it.path.value == "notes/claimant.md" }
            claimant.id shouldNotBe CONTESTED
            h.idMap.pathOf(CONTESTED) shouldBe foreign // the foreign row survives the pass
            val expected = IdentityIssue.CrossRootDuplicateId(
                CONTESTED,
                kept = foreign,
                reassigned = RootedPath(RootName.MAIN, claimant.path),
            )
            claimant.issues.filterIsInstance<IdentityIssue.CrossRootDuplicateId>().single() shouldBe expected
            h.idMap.issues().filterIsInstance<IdentityIssue.CrossRootDuplicateId>().single() shouldBe expected
        }
    }

    test("PREVIEW: the contest is REPORTED but nothing is persisted - no bind, no row, no issue") {
        withHarness { h ->
            val foreign = RootedPath(RootName.require("extra"), TreePath.require("mirror/page.md"))
            h.idMap.bind(foreign, CONTESTED, materialized = true)
            Files.writeString(h.root.resolve("notes/claimant.md"), "---\nid: ${CONTESTED.value}\ntitle: x\n---\nbody\n")
            val registry = RootRegistry.of(listOf(localRoot("main", h.root), localRoot("extra", h.root)))

            val plan = h.pass(registry = registry).run(AdoptionPass.Mode.PREVIEW)

            // The report tells the operator exactly what a RECORD run would do (the reassignment + why)...
            plan.pages.single { it.path.value == "notes/claimant.md" }
                .issues.filterIsInstance<IdentityIssue.CrossRootDuplicateId>().single().kept shouldBe foreign
            // ...and writes none of it: preview binds nothing and records nothing.
            h.idMap.pathOf(CONTESTED) shouldBe foreign
            h.idMap.issues().filterIsInstance<IdentityIssue.CrossRootDuplicateId>().shouldBeEmpty()
        }
    }

    test("a binding under an UNREGISTERED root is superseded by the live main claim with NO issue (detached, D2)") {
        withHarness { h ->
            val detached = RootedPath(RootName.require("gone"), TreePath.require("mirror/page.md"))
            h.idMap.bind(detached, CONTESTED, materialized = true)
            Files.writeString(h.root.resolve("notes/claimant.md"), "---\nid: ${CONTESTED.value}\ntitle: x\n---\nbody\n")

            val plan = h.pass().run(AdoptionPass.Mode.RECORD) // main-only registry: 'gone' is detached

            val claimant = plan.pages.single { it.path.value == "notes/claimant.md" }
            claimant.id shouldBe CONTESTED
            h.idMap.pathOf(CONTESTED) shouldBe RootedPath(RootName.MAIN, claimant.path)
            h.idMap.issues().filterIsInstance<IdentityIssue.CrossRootDuplicateId>().shouldBeEmpty() // the boot WARN is its visibility
        }
    }
})

private val CONTESTED = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
private val EXTRA = RootName.require("extra")
private val EXTRA_PAGE = RootedPath(EXTRA, TreePath.require("shared.md"))
private val EXTRA_ONBOARDING = RootedPath(EXTRA, TreePath.require("onboarding.md"))

private fun source(tree: Path) = AdoptionPass.Source(EXTRA, LocalContentStore(tree, rootName = EXTRA))

/** [withHarness] plus a REAL second root - a second tree the pass genuinely scans, not just a registry name. */
private fun withTwoRoots(block: (Harness, Path) -> Unit) {
    withHarness { h ->
        val extra = Files.createTempDirectory("pb-adopt-extra")
        try {
            block(h, extra)
        } finally {
            extra.toFile().deleteRecursively()
        }
    }
}

/** The crafted would-refuse page (quoted key — the §A3 case-9 class) added to the temp tree. */
private const val REFUSAL_PAGE = "notes/refuse-me.md"

private fun addRefusalPage(root: Path) {
    Files.writeString(root.resolve(REFUSAL_PAGE), "---\n'quoted': key\n---\nbody\n")
}

/** A fresh fixture copy + in-memory id_map, torn down afterwards. */
private fun withHarness(block: (Harness) -> Unit) {
    val tmp = Files.createTempDirectory("pb-adopt")
    try {
        Files.walk(Fixtures.demoDocs).use { stream ->
            stream.forEach { src ->
                val dest = tmp.resolve(Fixtures.demoDocs.relativize(src).toString())
                if (Files.isDirectory(src)) Files.createDirectories(dest) else Files.copy(src, dest)
            }
        }
        DatabaseFactory.createInMemoryDriver().use { driver ->
            block(Harness(tmp, driver))
        }
    } finally {
        Files.walk(tmp).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}

private class Harness(val root: Path, val driver: app.cash.sqldelight.db.SqlDriver) {
    val idMap = SqlDelightIdMapRepository(DatabaseFactory.createDatabase(driver))

    /**
     * [registry] seats the configured roots in D7 order; main's tree is always a source, [extras] adds the others.
     * A registered root with NO source is the partial-visibility case the D16 rule protects — which `AdoptCommand`
     * never constructs (it refuses to run unless it can see every root), so it is reachable only from here.
     */
    fun pass(
        store: ContentStore = LocalContentStore(root),
        registry: RootRegistry = RootRegistry.of(listOf(localRoot("main", root))),
        extras: List<AdoptionPass.Source> = emptyList(),
    ): AdoptionPass =
        AdoptionPass(
            sources = listOf(AdoptionPass.Source(RootName.MAIN, store)) + extras,
            idMap = idMap,
            identity = PageIdentityService(UuidV7IdProvider(), registry::rank),
            patcher = FrontmatterPatcher(),
            rootLoss = RootLossClassifier(RootAvailability(Clock.System)),
            citations = CitationFactory(),
            rootRank = registry::rank,
            registeredRoots = registry.roots.map { it.name }.toSet(),
        )
}

/**
 * Counts (and reports) delegated writes — the zero-writes-on-second-run and pairing probe.
 *
 * It intercepts [compareAndSwapWrite], not [write]: adopt materializes through the CAS precisely so it can never
 * create a page (or a root) it did not scan, and a probe on the method adopt no longer calls would see nothing.
 */
private class RecordingStore(
    private val delegate: ContentStore,
    private val onWrite: (TreePath) -> Unit,
) : ContentStore by delegate {
    var writes = 0
        private set

    override fun compareAndSwapWrite(path: TreePath, baseHash: String, bytes: ByteArray, hasher: (ByteArray) -> String): CasResult {
        writes++
        onWrite(path)
        return delegate.compareAndSwapWrite(path, baseHash, bytes, hasher)
    }
}

/**
 * Deletes [tree] the instant the pass attempts its first file write.
 *
 * It hooks BOTH write surfaces on purpose, so the test it drives is a real DIFFERENTIAL rather than a
 * description of the current implementation: an adoption pass that went back to the create-or-replace [write]
 * would lose the root at exactly the same instant, and would then be caught rebuilding it.
 */
private class VanishingStore(
    private val delegate: ContentStore,
    private val tree: Path,
) : ContentStore by delegate {

    override fun compareAndSwapWrite(path: TreePath, baseHash: String, bytes: ByteArray, hasher: (ByteArray) -> String): CasResult {
        vanish()
        return delegate.compareAndSwapWrite(path, baseHash, bytes, hasher)
    }

    override fun write(path: TreePath, bytes: ByteArray) {
        vanish()
        delegate.write(path, bytes)
    }

    private fun vanish() {
        tree.toFile().deleteRecursively()
    }
}

/** Fails the [failOnWrite]-th write — the simulated mid-batch abort. */
private class AbortingStore(
    private val delegate: ContentStore,
    private val failOnWrite: Int,
) : ContentStore by delegate {
    private var writes = 0

    override fun compareAndSwapWrite(path: TreePath, baseHash: String, bytes: ByteArray, hasher: (ByteArray) -> String): CasResult {
        if (++writes == failOnWrite) throw IOException("simulated mid-batch abort")
        return delegate.compareAndSwapWrite(path, baseHash, bytes, hasher)
    }
}

/** The tree's .md files as content-relative `/`-joined strings (raw on-disk form). */
private fun mdPages(root: Path): List<String> =
    Files.walk(root).use { stream ->
        stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".md") }
            .map { root.relativize(it).joinToString("/") }
            .toList()
    }

/** The patcher's own accept/refuse split over the tree's current bytes, keyed by NFC path. */
private class PatcherOutcomes(val accepted: List<String>, val refused: List<String>)

private fun patcherOutcomes(root: Path): PatcherOutcomes {
    val probe = FrontmatterPatcher()
    val probeId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
    val outcomes = mdPages(root).associate { rel ->
        Nfc.normalize(rel) to probe.patch(Files.readAllBytes(root.resolve(rel)), probeId)
    }
    return PatcherOutcomes(
        accepted = outcomes.filterValues { it is FrontmatterPatcher.PatchResult.Patched }.keys.toList(),
        refused = outcomes.filterValues { it is FrontmatterPatcher.PatchResult.Refused }.keys.toList(),
    )
}

/** Resolves an NFC content-relative path against the raw (possibly NFD) on-disk tree. */
private fun resolveByNfc(root: Path, nfcValue: String): Path {
    val match = mdPages(root).singleOrNull { Nfc.normalize(it) == nfcValue }
    return root.resolve(match ?: nfcValue)
}

/** The pages whose bytes actually moved between two [checksum]s of [root]'s tree. */
private fun changed(root: RootName, before: Map<String, String>, after: Map<String, String>): List<RootedPath> =
    after.filterNot { (path, digest) -> before[path] == digest }.keys.map { RootedPath(root, TreePath.require(it)) }

/** NFC relative-path -> sha256 over every regular file: the directory-checksum primitive. */
private fun checksum(dir: Path): Map<String, String> =
    Files.walk(dir).use { stream ->
        stream.filter(Files::isRegularFile).toList().associate { file ->
            Nfc.normalize(dir.relativize(file).joinToString("/")) to
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }
        }
    }

/**
 * The byte-diff acceptance assertion: [patched] is [original] plus exactly ONE single-point
 * insertion — the `id:` line inside an existing block, or the complete minimal block when the
 * original had none (§A3 cases 1–4). Proven structurally (longest common prefix + suffix cover
 * every original byte) and textually (the inserted bytes are exactly the id line / block).
 */
private fun assertSingleIdInsertion(original: ByteArray, patched: ByteArray, id: PageId) {
    val inserted = patched.size - original.size
    inserted shouldBeGreaterThan 0

    var prefix = 0
    while (prefix < original.size && original[prefix] == patched[prefix]) prefix++
    var suffix = 0
    while (suffix < original.size - prefix && original[original.size - 1 - suffix] == patched[patched.size - 1 - suffix]) suffix++
    // Single-point insertion: every original byte survives, in order, as prefix + suffix.
    (prefix + suffix >= original.size).shouldBeTrue()

    val at = original.size - suffix
    val insertion = String(patched.copyOfRange(at, at + inserted), Charsets.UTF_8)
    val pattern = when (FrontmatterBlock.detect(original)) {
        is FrontmatterBlock.Detection.Present -> Regex("id: ${id.value}(\r\n|\r|\n)")
        is FrontmatterBlock.Detection.Absent -> Regex("---(\r\n|\r|\n)id: ${id.value}\\1---\\1")
    }
    pattern.matches(insertion).shouldBeTrue()
}
