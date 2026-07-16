package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.Commit
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.history.FileDiff
import com.plainbase.domain.history.HistoryProvider
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
import java.io.IOException

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

    test("an OBJECT-root MOVE preserves the id - the LIST proves absence by KEY, and a rename is what changes the key") {
        ObjectAbsenceWorld().use { world ->
            val bucket = handbook()
            world.boot(bucket, real).rebuild() // TRUSTED, three rows

            // The operator renames a key in the bucket we have VERIFIED is ours. The bytes are identical, and the id
            // is inside them - only the key moved. `BindingLatch.proven` excludes witnessed PATHS, so the old key's
            // binding lands in `gone` and the apply transaction would tombstone an id we are holding in our hands.
            //
            // This is the SAME defect as the local/EPOCH one ([com.plainbase.domain.service.OnlineMoveIdentityTest]),
            // in a second source - which is why the refutation is enforced ONCE, over every scan-derived proof, in
            // `IndexBuilder.refuted`. This row is what makes that a CLASS fix rather than a claim.
            bucket.remove("guides/deploy.md")
            bucket.seedPage("guides/deploy-v2.md", deployId)
            world.store.pollOnce()
            val snapshot = world.builder().rebuild()

            withClue("the page keeps its permalink: we FETCHED those bytes, and they say which page they are") {
                snapshot.pages.map { it.path } shouldContainExactlyInAnyOrder
                    listOf(page("guides/deploy-v2.md"), runbook, onboarding)
                world.idMap.bindings().map { it.id } shouldContainExactlyInAnyOrder listOf(deployId, runbookId, onboardingId)
            }
            withClue("and NOTHING is tombstoned - a renamed key must not turn /p/{id} into a 410") {
                world.idMap.retiredBindings().shouldBeEmpty()
            }
            world.limbo.count(RootName.MAIN) shouldBe 0
        }
    }

    test("an object RENAME whose GET fails reaps NOTHING - we cannot refute what we did not read") {
        ObjectAbsenceWorld().use { world ->
            val bucket = handbook()
            val client = GetFails(bucket, failFor = "guides/deploy-v2.md")
            world.boot(client, real).rebuild() // TRUSTED, three rows, and a mirror holding the whole generation

            // The rename - and this time the GET of the NEW key fails once. The poll drops it and "retries next
            // cycle" (ObjectContentStore.kt:482-489), so the generation NAMES a key the mirror does not hold.
            //
            // The LIST is still complete and still authoritative about the BUCKET: `guides/deploy.md` is genuinely
            // not there. But the REFUTATION is made of pages we READ, and on an object root we read the MIRROR - so
            // the id that would have refuted this absence is sitting in a key we never fetched. A LIST returns keys
            // and etags, never frontmatter ids, so the manifest cannot supply it either.
            //
            // Absence proven by the bucket, refutation withheld by the mirror. Minting here retires a page that MOVED.
            bucket.remove("guides/deploy.md")
            bucket.seedPage("guides/deploy-v2.md", deployId)
            client.armed = true
            world.store.pollOnce()
            world.builder().rebuild()

            withClue("a mirror that does not hold the whole generation has not shown us every page the bucket has") {
                world.idMap.retiredBindings().shouldBeEmpty()
            }
            withClue("the page is not gone, it is UNREAD - so it waits in limbo, and the next poll fetches it") {
                world.idMap.pathOf(deployId).shouldNotBeNull()
                world.limbo.count(RootName.MAIN) shouldBe 1
            }
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

    test("an object root's enabled mirror-git provider is never asked for the C4 absence-oracle members - the backend gate") {
        ObjectAbsenceWorld().use { world ->
            world.boot(handbook(), real) // a real corpus, TRUSTED and hydrated, over an OBJECT backend

            // An object root's history is git-over-the-MIRROR: enabled, but OUR derived repo, never "recorded human
            // intent" about the bucket (an offline bucket delete is not a mirror commit). The C4 mint's backend gate
            // excludes it by construction, so if the gate filtered on `enabled` alone this spy would be asked and blow
            // up. It is not: the rebuild completes, `lastCommits` (which every scan calls) aside, untouched.
            world.builder(history = ThrowingGitOracleSpy).rebuild()
        }
    }
})

/**
 * An ENABLED history that answers the scan's `lastCommits` inertly but BLOWS UP on the three C4 absence-oracle
 * members - so an object root reaching any of them is a backend-gate regression, caught as a throw.
 */
private object ThrowingGitOracleSpy : HistoryProvider {
    override val enabled = true
    override fun currentHead(): String? = error("an OBJECT root must never be asked for currentHead (C4 backend gate)")
    override fun isAncestor(ancestor: String, descendant: String): Boolean = error("an OBJECT root must never be asked isAncestor")
    override fun deletedIn(from: String, to: String): Set<TreePath>? = error("an OBJECT root must never be asked deletedIn")

    override fun commit(path: TreePath, bytes: ByteArray, author: CommitIdentity?, committer: CommitIdentity?): Commit? = null
    override fun lastCommits(paths: List<TreePath>): Map<TreePath, Commit> = emptyMap()
    override fun log(path: TreePath, limit: Int?): List<Commit> = emptyList()
    override fun diff(from: String, to: String, path: TreePath): FileDiff = FileDiff(from, to, path, "")
    override fun prepare() = Unit
    override fun gateCheck() = Unit
}

/**
 * The bucket answers every LIST honestly; the GET of ONE key fails, exactly as a transient 500 or a timeout does.
 * `pollOnce` catches it, drops the key, and retries next cycle - so the published generation NAMES a key that the
 * mirror does not hold, and the pass reads a tree with a hole in it that only the etag map knows about.
 */
private class GetFails(private val delegate: FakeObjectStore, private val failFor: String) : ObjectStoreClient by delegate {

    var armed = false

    override suspend fun get(key: String, maxBytes: Long?): FetchedObject? =
        if (armed && key == failFor) throw IOException("transient GET failure") else delegate.get(key, maxBytes)
}
