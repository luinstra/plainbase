package com.plainbase.frameworks.objectstore

import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.BindingStatus
import com.plainbase.domain.root.RootBinding
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * **THE WRONG-BUCKET WIPE** (C3), and the latch that closes it.
 *
 * > A successful complete bucket LIST is positive proof of absence - **but only about the bucket it LISTED.**
 *
 * A wrong bucket name, a wrong prefix, or credentials scoped to a different EMPTY bucket produce a **successful empty
 * LIST**. No error, no timeout, no fault of any kind: a complete, authoritative view of the WRONG UNIVERSE, which
 * reads as "the corpus is gone" and deletes it. It is the bind-mount bug one level up, and these rows are it.
 *
 * The four cases here are DIFFERENT cases, and the whole design lives in the difference between them:
 *  - a wrong bucket with rows at risk reaps NOTHING, however many polls and restarts it gets;
 *  - a wrong bucket holding IDENTICAL PATHS reaps nothing either, because a LIST returns keys, not identities;
 *  - a FRESH install (nothing at risk) trusts its binding immediately and serves - a wrong binding there costs an
 *    EMPTY site, never a LOST one;
 *  - a DRAINED bucket under a TRUSTED binding reaps normally, because that is an ordinary delete and it must converge.
 */
class ObjectBindingLatchTest : FunSpec({

    val real = RootBinding("https://r2.example|handbook|")
    val typo = RootBinding("https://r2.example|handbok|") // one letter. That is the whole attack.

    val deploy = page("guides/deploy.md")
    val runbook = page("guides/runbook.md")
    val onboarding = page("onboarding.md")

    val deployId = PageId.require("01900000-0000-7000-9000-0000000000d1")
    val runbookId = PageId.require("01900000-0000-7000-9000-0000000000d2")
    val onboardingId = PageId.require("01900000-0000-7000-9000-0000000000d3")

    /** The real corpus: three MATERIALIZED pages (their ids live in their own bytes - the only witnessable kind). */
    fun handbook() = FakeObjectStore().apply {
        seedPage("guides/deploy.md", deployId)
        seedPage("guides/runbook.md", runbookId)
        seedPage("onboarding.md", onboardingId)
    }

    test("a fresh install (nothing at risk) TRUSTS its binding and serves - the SAFE case, and not the same as the others") {
        ObjectAbsenceWorld().use { world ->
            val snapshot = world.boot(handbook(), real).rebuild()

            withClue(
                "an empty at-risk snapshot is trivially satisfied, and that is SAFE: a proof authorizes only " +
                    "RETIREMENTS, and a root with no durable rows has nothing to retire",
            ) {
                world.topology.topology(RootName.MAIN).shouldNotBeNull().status shouldBe BindingStatus.TRUSTED
            }
            snapshot.pages.map { it.path } shouldContainExactlyInAnyOrder listOf(deploy, runbook, onboarding)
            world.idMap.bindings().map { it.id } shouldContainExactlyInAnyOrder listOf(deployId, runbookId, onboardingId)
        }
    }

    test("a WRONG (empty) bucket reaps NOTHING - not on first sight, not on the second poll, and not after a RESTART") {
        ObjectAbsenceWorld().use { world ->
            world.boot(handbook(), real).rebuild() // the real corpus, indexed, TRUSTED, three durable rows

            // The config now names a bucket that is real, reachable, healthy - and empty. Its LIST succeeds.
            val wrong = FakeObjectStore()
            world.boot(wrong, typo).rebuild()

            withClue("FIRST SIGHT of the new binding: the LIST is complete and it says NOTHING, because it is not ours") {
                world.idMap.bindings().map { it.id } shouldContainExactlyInAnyOrder listOf(deployId, runbookId, onboardingId)
                world.idMap.retiredBindings().shouldBeEmpty()
                world.limbo.count(RootName.MAIN) shouldBe 3
            }

            // THE SECOND POLL. This is where the first fix of this bug rebuilt it: the binding had been RECORDED, so
            // the next cycle saw it as unchanged and took the empty LIST as authority.
            world.store.pollOnce()
            world.builder().rebuild()
            withClue("the latch is a STATUS, not a memory of having seen the binding once") {
                world.idMap.retiredBindings().shouldBeEmpty()
                world.idMap.bindings().size shouldBe 3
            }

            // ...AND THE RESTART, which is the same trap one layer down: everything in memory is gone, and the only
            // thing that can still say "this binding was never verified" is the durable row.
            world.boot(wrong, typo).rebuild()
            withClue("UNRESOLVED survives the process that latched it, or it is not a latch") {
                world.topology.topology(RootName.MAIN).shouldNotBeNull().status shouldBe BindingStatus.UNRESOLVED
                world.idMap.retiredBindings().shouldBeEmpty()
                world.idMap.bindings().map { it.id } shouldContainExactlyInAnyOrder listOf(deployId, runbookId, onboardingId)
            }
            withClue("and the pages read 503, never 404: their absence is UNVERIFIED, which is not the same as gone") {
                world.limbo.count(RootName.MAIN) shouldBe 3
            }
        }
    }

    test("a wrong bucket with IDENTICAL PATHS but different content does NOT become TRUSTED, and displaces nothing") {
        ObjectAbsenceWorld().use { world ->
            world.boot(handbook(), real).rebuild()

            // A stale copy, a clone, a restore from somewhere else: the SAME three paths, carrying somebody else's
            // pages. A bucket LIST returns keys and etags - it does NOT return frontmatter ids - so a latch that
            // checked "did the LIST see every at-risk path" would promote this bucket to TRUSTED on the spot.
            val clone = FakeObjectStore().apply {
                seedPage("guides/deploy.md", PageId.require("01900000-0000-7000-9000-0000000000c1"))
                seedPage("guides/runbook.md", PageId.require("01900000-0000-7000-9000-0000000000c2"))
                seedPage("onboarding.md", PageId.require("01900000-0000-7000-9000-0000000000c3"))
            }
            world.boot(clone, typo).rebuild()

            withClue("identical paths are not identical pages: the at-risk set is witnessed by IDENTITY or not at all") {
                world.topology.topology(RootName.MAIN).shouldNotBeNull().status shouldBe BindingStatus.UNRESOLVED
            }
            withClue(
                "and the door BESIDE the latch: a decoy carrying different ids would DISPLACE the incumbents - " +
                    "no absence proof needed, because we READ the file - tombstoning every permalink it did not carry",
            ) {
                world.idMap.retiredBindings().shouldBeEmpty()
                world.idMap.bindings().map { it.id } shouldContainExactlyInAnyOrder listOf(deployId, runbookId, onboardingId)
            }
        }
    }

    test("a DRAINED bucket under a TRUSTED binding DOES reap - an ordinary delete has to converge") {
        ObjectAbsenceWorld().use { world ->
            val bucket = handbook()
            world.boot(bucket, real).rebuild() // TRUSTED, three rows

            // The SAME binding. The operator deleted two pages from the bucket we have verified is ours, and this is
            // the case the whole design has to keep working: proof of absence, from the authority itself.
            bucket.remove("guides/deploy.md")
            bucket.remove("onboarding.md")
            world.store.pollOnce()
            val snapshot = world.builder().rebuild()

            snapshot.pages.map { it.path } shouldContainExactlyInAnyOrder listOf(runbook)
            world.idMap.bindings().map { it.id } shouldContainExactlyInAnyOrder listOf(runbookId)
            withClue("a retired binding is TOMBSTONED, so /p/{id} stays a 410 rather than the 404 that kills a citation") {
                world.idMap.retiredBindings().map { it.id } shouldContainExactlyInAnyOrder listOf(deployId, onboardingId)
            }
            world.limbo.count(RootName.MAIN) shouldBe 0
        }
    }

    test("a create racing a paginated LIST is never reaped - the boundary is the rows BEFORE the first page") {
        ObjectAbsenceWorld().use { world ->
            val bucket = handbook().apply { pageSize = 1 } // LIST paginates: three pages, three round trips
            world.boot(bucket, real).rebuild() // TRUSTED

            // A page CREATED while the pagination is in flight. Its row lands in `id_map`; its object is not in a page
            // the LIST has already returned, and may not be in one it has yet to return either. Read the durable rows
            // AFTER the LIST and this row is "listed nowhere" - reaped by the very LIST that could not have seen it.
            val racing = page("guides/fresh.md")
            val racingId = PageId.require("01900000-0000-7000-9000-0000000000fa")
            bucket.onNetworkOp = {
                if (world.idMap.find(RootedPath(RootName.MAIN, racing)) == null) {
                    world.idMap.bind(RootedPath(RootName.MAIN, racing), racingId, materialized = true)
                }
            }

            world.store.pollOnce()
            world.builder().rebuild()

            withClue("rowsAtStart is read BEFORE the first LIST page, so a row created after it cannot be covered") {
                world.idMap.retiredBindings().shouldBeEmpty()
                world.idMap.find(RootedPath(RootName.MAIN, racing)).shouldNotBeNull()
            }
        }
    }

    test("an errored LIST page publishes NO generation and mints no proof - a partial manifest is not a smaller corpus") {
        ObjectAbsenceWorld().use { world ->
            val bucket = handbook().apply { pageSize = 1 }
            world.boot(bucket, real).rebuild() // TRUSTED, three rows, one good generation published

            // The bucket is drained AND the pagination breaks half way through it. Publishing what it got would be an
            // "authoritative" corpus of whatever the first page happened to hold - an incomplete manifest is not a
            // smaller corpus, it is an unknown one.
            bucket.remove("onboarding.md") // two objects left, so this poll's LIST is a TWO-page pagination
            bucket.failListCall = bucket.listCount + 2 // ...and its SECOND page never comes back

            world.store.pollOnce()
            world.builder().rebuild()

            withClue("the previous generation stands; nothing is proven gone by a listing that never finished") {
                world.idMap.retiredBindings().shouldBeEmpty()
                world.idMap.bindings().size shouldBe 3
            }

            // ...and once a LIST does run to completion, the SAME deletes converge. The refusal was about the evidence,
            // not about the deletion.
            world.store.pollOnce()
            world.builder().rebuild()
            world.idMap.retiredBindings().map { it.id } shouldContainExactlyInAnyOrder listOf(onboardingId)
        }
    }
})
