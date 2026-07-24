package com.plainbase.domain.service

import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.StoreRead
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.Stage
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.ktor.livePathOf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * **The C1 consumer matrix, one row per consumer** - and the rule they all share:
 *
 * > `AbsenceUnknown` is never allowed to become a FACT. It ends every path in "come back later", never in "it's gone".
 *
 * The wire half lives in `AbsenceUnverifiedWireTest`; these are the consumers that have no wire to answer on - the
 * ones that would instead have DESTROYED something (a recovery record, a corpus's identity) or improvised against a
 * view they could not verify. The `IndexBuilder`/limbo rows live in `AbsenceAuthorityTest` (C0), which is where the
 * durable-state assertions belong.
 */
class AbsenceClassifierTest : FunSpec({

    val doc = TreePath.require("doc.md")
    val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
    val bound = RootedPath(RootName.MAIN, doc)

    // ---- the rule itself, in the one place it exists -----------------------------------------------------

    test("the classifier decides on the INDEX and on nothing else: bound -> AbsenceUnknown, unbound -> ConfirmedAbsent") {
        world { world ->
            val absence = AbsenceClassifier(world.idMap)

            withClue("no bytes, and the index has never heard of this page: nothing is in doubt - the honest 404") {
                absence.classify(bound, StoreRead.NoBytes) shouldBe ContentRead.ConfirmedAbsent
            }

            world.idMap.bind(bound, id, materialized = false)

            withClue("the SAME observation, and now the index binds it: 503. The store's answer did not change - ours did") {
                absence.classify(bound, StoreRead.NoBytes) shouldBe ContentRead.AbsenceUnknown
                absence.absenceAt(bound) shouldBe ContentRead.AbsenceUnknown
            }
            withClue("a downed root outranks both - nothing may be concluded about a tree we cannot look at") {
                absence.classify(bound, StoreRead.RootDown) shouldBe ContentRead.RootDown
            }
            withClue("and bytes are bytes") {
                absence.classify(bound, StoreRead.Bytes("x".toByteArray())) shouldBe ContentRead.Bytes("x".toByteArray())
            }
        }
    }

    // ---- WritePipeline.reconcileDirtyPages: an interrupted save's ONLY recovery record --------------------

    test("reconcile KEEPS an interrupted save's recovery row on an unverified absence - it is USER CONTENT") {
        world { world ->
            Files.writeString(world.tree.resolve("doc.md"), "---\ntitle: Doc\n---\n\n# Doc\n")
            world.builder.rebuild()
            val pipeline = world.pipeline()

            // A save was interrupted: the journal holds the ONLY record of what the user was writing. Then the page's
            // file is not there. Under the pre-C0 rule that absence CLEARED the row - the worst instance of ledger
            // A1, because a dirty row is not derived state and does not come back.
            world.dirtyPages.mark(id, bound, expectedHash = "deadbeef", stage = Stage.WRITING)
            Files.delete(world.tree.resolve("doc.md"))

            pipeline.reconcileDirtyPages()

            withClue("nothing but an absence PROOF may destroy it, and in C0/C1 nothing mints one") {
                world.dirtyPages.get(RootedPageId(bound.root, id)).shouldNotBeNull()
            }
        }
    }

    // ---- AdoptionPass: never adopt against a view you cannot verify --------------------------------------

    test("adopt ABORTS on an unverified absence - it writes ids into the operator's own files, and it will not guess") {
        world { world ->
            Files.writeString(world.tree.resolve("doc.md"), "---\ntitle: Doc\n---\n\n# Doc\n")
            world.idMap.bind(bound, id, materialized = false)

            // The store LISTS the page (the walk saw it) and then cannot produce its bytes - a tree falling apart
            // under the scan. Adopt resolves identity GLOBALLY across every root at once and then materializes ids
            // into files, so a corpus that is not the corpus is not something it may improvise over.
            val vanishing = object : ContentStore by world.store {
                override fun readClassified(path: TreePath): StoreRead = StoreRead.NoBytes
            }
            val pass = AdoptionPass(
                sources = listOf(AdoptionPass.Source(RootName.MAIN, vanishing)),
                idMap = world.idMap,
                identity = PageIdentityService(UuidV7IdProvider()),
                patcher = FrontmatterPatcher(),
                rootLoss = RootLossClassifier(world.availability),
                citations = CitationFactory(),
                rootRank = { 0 },
                registeredRoots = setOf(RootName.MAIN),
            )

            val abort = shouldThrow<AbsenceUnverified> { pass.run(AdoptionPass.Mode.RECORD) { _, _ -> } }
            abort.root shouldBe RootName.MAIN
            withClue("and it destroyed nothing on the way out - the binding it could not read is exactly as it was") {
                world.idMap.livePathOf(id) shouldBe bound
            }
        }
    }
})

/**
 * A real tree + a real app DB - the two things the classifier's verdict is a function of, and ONE of each: the
 * pipeline, the builder and the classifier must all read the SAME `id_map`, or the test proves nothing about the
 * rule they are supposed to share.
 */
private class ClassifierWorld(val tree: Path) : AutoCloseable {

    val store = LocalContentStore(tree)
    private val harness = IndexHarness(tree, contentStore = store)

    val idMap get() = harness.idMap
    val builder get() = harness.builder
    val dirtyPages get() = harness.dirtyPages
    val availability get() = harness.availability

    fun pipeline(): WritePipeline = harness.writePipeline()

    override fun close() = harness.close()
}

private fun world(block: (ClassifierWorld) -> Unit) {
    val tree = Files.createTempDirectory("plainbase-absence-c1")
    try {
        ClassifierWorld(tree).use(block)
    } finally {
        tree.toFile().deleteRecursively()
    }
}
