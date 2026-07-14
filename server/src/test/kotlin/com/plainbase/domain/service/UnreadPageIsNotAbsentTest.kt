package com.plainbase.domain.service

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.StoreRead
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * **AN UNKNOWN ABSENCE IS NEVER A FACT - including one that arrives through the READ.**
 *
 * A scan is a WALK followed by a READ of each thing it walked, and `SourceScan.complete` describes only the WALK
 * ([IndexBuilder.SourceScan.complete]: *"Did the backend see the WHOLE tree?"*). So a page the walk ENUMERATED and
 * the read could not produce bytes for is dropped from the pass's drafts - and it is therefore missing from the
 * WITNESS map of a scan that still calls itself COMPLETE.
 *
 * The epoch mints its proof from exactly that difference ([com.plainbase.domain.root.ObservationEpoch.scanned]:
 * `in epoch.witnessed && !in witnessed`), so the page it could not read reads as a page that is GONE. Two written
 * contracts say it must not:
 *
 *  - [AbsenceClassifier]: *"A page whose binding this returns `AbsenceUnknown` for is in LIMBO. It is not a
 *    deletion, and it must never become one by accident."*
 *  - `IndexBuilder`'s own log line on that arm: *"its durable row goes to LIMBO - **nothing is deleted for it**."*
 *
 * The read failing is not evidence. `AbsenceUnknown` is the carrier for *"the bytes are not there and we cannot
 * prove they are gone"*, and the one thing this whole design forbids is letting that become *"it is gone"*.
 */
class UnreadPageIsNotAbsentTest : FunSpec({

    val extra = RootName.require("extra")
    val rollback = TreePath.require("notes/rollback.md")

    test("a page the WALK saw and the READ could not produce is LIMBO, never a reap - an unknown is not a fact") {
        withAbsenceTrees { mainDir, extraDir ->
            val pinned = requireNotNull(PageId.of("018f4c1e-8b7a-7c3d-9e2f-0a0b0c0d0e0f"))
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", "---\nid: ${pinned.value}\n---\n\n# Rollback\n\nbody\n")

            AbsenceWorld(mainDir, extraDir).use { world ->
                world.observe("main", "extra")
                val store = UnreadablePage(world.extraStore(extraDir), rollback)
                val builder = world.builder(mainDir, store, world.indexer)

                // The OPENING scan reads it normally: the epoch WITNESSES the page.
                builder.rebuild().byPath.getValue(RootedPath(extra, rollback)).id shouldBe pinned

                // Now the read of that ONE page comes back with no bytes, while the WALK still enumerates it - the
                // shape of an ordinary mid-pass race (an `rm`, a `git checkout` of another branch, a sync tool's
                // delete-then-write) landing between the walk and the read. The scan is still COMPLETE: completeness
                // is a property of the walk, and the walk saw the whole tree.
                //
                // Note the file is STILL ON DISK here. Nothing about this page is gone. We simply failed to read it.
                store.armed = true
                builder.rebuild()

                withClue("the classifier calls this AbsenceUnknown, and an AbsenceUnknown must NEVER become a deletion") {
                    world.idMap.retired(pinned).shouldBeNull()
                    world.idMap.pathOf(pinned).shouldNotBeNull() shouldBe RootedPath(extra, rollback)
                }
                withClue("LIMBO is where it goes - 503, self-healing, exactly what the log line promises") {
                    world.limbo.count(extra) shouldBe 1
                }
            }
        }
    }
})

/**
 * The WALK enumerates [vanished]; the READ of it produces no bytes. That is not a fault injected into the store -
 * it is the ordinary race the store already classifies, arriving deterministically: the walk's file list is a
 * SNAPSHOT, and a page can leave between the snapshot and the read of it.
 */
private class UnreadablePage(private val delegate: ContentStore, private val vanished: TreePath) : ContentStore by delegate {

    var armed = false

    override fun readClassified(path: TreePath): StoreRead =
        if (armed && path == vanished) StoreRead.NoBytes else delegate.readClassified(path)
}
