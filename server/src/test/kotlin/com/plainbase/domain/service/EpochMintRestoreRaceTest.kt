package com.plainbase.domain.service

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.ScanResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.repository.IdBinding
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.Stage
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.ktor.livePathOf
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull

/**
 * **Revoke-before-stamp, the MID-MINT interleave (C5 Commit C).** R30 pins the bind landing between a proof's mint and
 * its apply. This pins the harder window one layer earlier: a restore's re-bind landing between the moment the EPOCH
 * mint reads its NEGATIVE EVIDENCE and the moment it captures the binding_epoch stamp.
 *
 * A pass reads its negative evidence in stages - the scan's witnessed/unread first, then `durable` (the id_map rows the
 * proof is ABOUT) inside each mint - and then stamps. Every stage the stamp is taken AFTER is a window: a restore's
 * re-bind of the covered key landing in it advances binding_epoch to E+1, the late stamp captures E+1 too, and at apply
 * the two-token compare re-reads E+1, MATCHES, and reaps the freshly re-created binding plus its `dirty_page`
 * USER-CONTENT recovery row - the exact durable loss Commit C exists to close. **The shipped fix captures the stamp at
 * the very top of `IndexBuilder.rebuild()`, above the scan, the git HEAD bracket and `durable` alike**, so any re-bind
 * lands strictly after it and the compare MISMATCHES.
 *
 * The two rows below pin the two stages, and it takes two DIFFERENT back-outs to tell them apart:
 *  - **durable-read window** - revert `ObservationEpoch.scanned` to a mint-time `retirements.bindingEpoch(root)`
 *    self-read. That is later than BOTH stages, so BOTH rows go RED.
 *  - **scan-end window** - move the `IndexBuilder` capture below the scan (still above `durable`). Only the SECOND row
 *    goes red; the durable-read row stays green. This is the one that earns the second row its separate existence, and
 *    the reason a single back-out is not proof: the first bug hid the second for a whole round.
 *
 * Either way the observation token never moves here (no break, no restart), so only the binding-epoch half can catch
 * the re-bind - and captured after the evidence, it cannot.
 */
class EpochMintRestoreRaceTest : FunSpec({

    val extra = RootName.require("extra")
    val rollback = RootedPath(extra, TreePath.require("notes/rollback.md"))

    test("a restore's re-bind at the EPOCH mint's durable read does NOT reap the re-created binding or its dirty_page") {
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                world.observe("docs", "extra")
                val id = world.builder(mainDir, LocalContentStore(extraDir), world.indexer)
                    .rebuild().byPath.getValue(rollback).id // the OPENING scan witnesses it

                // An interrupted save left a dirty_page recovery row (USER CONTENT) belonging to this page.
                world.dirtyPages.mark(id, rollback, "sha256:recovery", Stage.WRITING)

                // The page is deleted under the running server, so the next CONFIRMATION scan mints an EPOCH proof over it.
                extraDir.resolve("notes/rollback.md").toFile().delete()

                // THE RACE: a concurrent restore re-binds (extra, rollback, id) at the exact instant the EPOCH mint
                // snapshots its durable evidence - advancing binding_epoch. The proof must lose the two-token compare;
                // it only does so if its stamp was captured BEFORE this re-bind, i.e. before the durable read.
                val racing = ReBindAtDurableRead(world.idMap) { world.idMap.bind(rollback, id, materialized = true) }
                world.builder(mainDir, LocalContentStore(extraDir), world.indexer, idMap = racing).rebuild()

                withClue("the re-created binding survives: a proof minted before the restore's re-bind cannot reap it") {
                    world.idMap.livePathOf(id).shouldNotBeNull()
                    world.idMap.retiredAt(extra, id).shouldBeNull()
                }
                withClue("its dirty_page recovery row (USER CONTENT) survives: a stale reap would have destroyed it") {
                    world.dirtyPages.get(RootedPageId(extra, id)).shouldNotBeNull()
                }
            }
        }
    }

    test("a restore's re-bind in the (scan-end -> stamp) window does NOT reap the re-created binding or its dirty_page") {
        withAbsenceTrees { mainDir, extraDir ->
            writePage(mainDir, "guides/deploy.md", "# Deploy\n\nbody\n")
            writePage(extraDir, "notes/rollback.md", "# Rollback\n\nbody\n")
            AbsenceWorld(mainDir, extraDir).use { world ->
                world.observe("docs", "extra")
                val id = world.builder(mainDir, LocalContentStore(extraDir), world.indexer)
                    .rebuild().byPath.getValue(rollback).id // the OPENING scan witnesses it

                // An interrupted save left a dirty_page recovery row (USER CONTENT) belonging to this page.
                world.dirtyPages.mark(id, rollback, "sha256:recovery", Stage.WRITING)

                // The page is deleted under the running server, so the next CONFIRMATION scan mints an EPOCH proof over it.
                extraDir.resolve("notes/rollback.md").toFile().delete()

                // THE RACE, one layer up from the durable-read test: a concurrent restore re-binds (extra, rollback, id)
                // AS THE SCAN HANDS BACK - strictly after the scan's witnessed/unread are fixed (the file is already gone
                // from disk), and, if the stamp is captured after the scan, before it. That advances binding_epoch to E+1,
                // the late stamp captures E+1, the apply's two-token compare MATCHES, and the freshly re-created binding
                // plus its dirty_page row are reaped. The proof must lose that compare; it only does if its stamp was
                // captured BEFORE the scan, so this re-bind lands strictly after the stamp (E) and the compare re-reads E+1.
                val racing = ReBindAtScanEnd(LocalContentStore(extraDir)) { world.idMap.bind(rollback, id, materialized = true) }
                world.builder(mainDir, racing, world.indexer).rebuild()

                withClue("the re-created binding survives: a proof stamped before the scan cannot reap a bind that landed after it") {
                    world.idMap.livePathOf(id).shouldNotBeNull()
                    world.idMap.retiredAt(extra, id).shouldBeNull()
                }
                withClue("its dirty_page recovery row (USER CONTENT) survives: a stale reap would have destroyed it") {
                    world.dirtyPages.get(RootedPageId(extra, id)).shouldNotBeNull()
                }
            }
        }
    }
})

/**
 * An [IdMapRepository] that fires [onFirstRead] the FIRST time `bindings()` is read - the seam the EPOCH mint takes its
 * negative evidence from - AFTER snapshotting the durable rows. It models a concurrent restore's re-bind landing in the
 * evidence-then-stamp gap: the snapshot the mint sees is unchanged, but the root's binding_epoch has moved on.
 */
private class ReBindAtDurableRead(
    private val delegate: IdMapRepository,
    private val onFirstRead: () -> Unit,
) : IdMapRepository by delegate {
    private var fired = false

    override fun bindings(): List<IdBinding> {
        val snapshot = delegate.bindings()
        if (!fired) {
            fired = true
            onFirstRead()
        }
        return snapshot
    }
}

/**
 * A [ContentStore] that fires [onScanEnd] the FIRST time `scan()` hands back - the moment a rebuild's WITNESSED/UNREAD
 * evidence for this root is fixed. It models a concurrent restore's re-bind landing in the (scan-end -> stamp) window:
 * the pass's positive evidence is already settled, but the root's binding_epoch moves on before a stamp captured any
 * later than the scan can read it.
 */
private class ReBindAtScanEnd(
    private val delegate: ContentStore,
    private val onScanEnd: () -> Unit,
) : ContentStore by delegate {
    private var fired = false

    override fun scan(): ScanResult {
        val result = delegate.scan()
        if (!fired) {
            fired = true
            onScanEnd()
        }
        return result
    }
}
