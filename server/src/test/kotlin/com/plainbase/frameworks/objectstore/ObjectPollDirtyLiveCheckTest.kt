package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.TreePath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull

/**
 * O1: `pollOnce` must consult the dirty journal LIVE at the apply/delete decision (under `applyLock`), not a
 * once-per-poll snapshot. A write-ahead dirty mark added DURING the poll cycle - after the LIST, before the
 * decision - must still protect the path from being deleted/overwritten by this cycle's now-stale view (R3).
 *
 * The test marks the path dirty from the fake's `onNetworkOp` hook, which fires synchronously on the poll's
 * LIST - i.e. AFTER any top-of-poll snapshot but BEFORE the delete decision. With a snapshot the mark is
 * missed and the path is deleted; with the live check it is seen and the delete is skipped.
 */
class ObjectPollDirtyLiveCheckTest : FunSpec({

    test("a dirty mark added mid-poll (after the LIST, before the delete decision) protects the path from deletion") {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("mid-poll-dirty.md")
            // The path is in the mirror + recorded state, but the bucket LISTs empty -> it is a delete candidate.
            hybrid.mirror.write(path, "bytes".toByteArray())
            hybrid.state.recordConfirmed(path, "\"e0\"")
            hybrid.state.persist()
            hybrid.mirror.scan()

            // Mark the path dirty DURING the poll's LIST (onNetworkOp fires on the fake's list() call, after any
            // top-of-poll snapshot the OLD code took, before the delete loop runs).
            hybrid.fake.onNetworkOp = { hybrid.dirtyPaths.add(path) }

            hybrid.store.pollOnce()

            // Live R3 check: the delete loop re-queries dirtyPaths() and sees the just-added mark -> skips.
            hybrid.mirror.read(path).shouldNotBeNull() // never deleted
            hybrid.state.etagOf(path).shouldNotBeNull() // state never invalidated
        }
    }

    test("MINOR-1: the poll guard consults the per-path isDirty predicate per candidate") {
        val probed = mutableListOf<TreePath>()
        val recordThenNotDirty = { path: TreePath ->
            probed += path
            false
        }
        HybridFixture(isDirty = recordThenNotDirty).use { hybrid ->
            val path = TreePath.require("guarded.md")
            hybrid.mirror.write(path, "bytes".toByteArray())
            hybrid.state.recordConfirmed(path, "\"e0\"")
            hybrid.state.persist()
            hybrid.mirror.scan()

            hybrid.store.pollOnce() // delete candidate (bucket LISTs empty): the guard must call isDirty(path)

            probed shouldContain path // the per-path predicate WAS consulted (not a full-set rebuild)
            hybrid.state.etagOf(path).shouldBeNull() // isDirty=false -> the delete proceeded normally
        }
    }
})
