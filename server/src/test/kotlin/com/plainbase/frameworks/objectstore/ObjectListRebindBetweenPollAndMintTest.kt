package com.plainbase.frameworks.objectstore

import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.BindingLatch
import com.plainbase.domain.root.RootBinding
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull

/**
 * **The one interleaving `AbsenceInterleavingHarnessTest` structurally cannot reach: OBJECT_LIST's poll boundary.**
 *
 * That matrix injects events at reads inside `rebuild()`. OBJECT_LIST's negative evidence is not read there - it is
 * captured a whole poll cycle earlier, at the LIST's pagination boundary (`rowsAtStart`), and carried in the manifest.
 * So "capture every stamp before the earliest evidence read" has a genuinely different shape here, and this row exists
 * to check the half of it that is NOT satisfied by construction.
 *
 * How the two stamps differ for this source:
 *  - the BINDING half is sound by construction: the manifest's `bindingEpoch` is co-read WITH `rowsAtStart` at the
 *    pagination boundary, so stamp and evidence are the same instant. A later bind advances past it and loses the
 *    compare. Nothing to probe.
 *  - the OBSERVATION half is read at the top of `rebuild()` - i.e. AFTER the evidence it stamps. For a local root the
 *    stamp precedes the evidence; here it cannot. Its defence is the latch's TWO refusals, in implementation order:
 *    [BindingLatch.proven]'s `manifest.binding != latched.binding` comparison returns first, with the UNRESOLVED trust
 *    check behind it. This row pins that pair.
 *
 * **Why this specific event, and not a bare revoke.** The events that can revoke an object root's observation are
 * exactly: an identity rebind (`onIdentityRebind` -> `broke`), an availability loss, and a restart. A restart destroys
 * the in-memory manifest, so no stale proof survives to apply. An availability loss is caught source-agnostically by
 * `applyProofs`' `unavailableNow` standing gate. A BARE revoke is unreachable - nothing else calls `broke` for an
 * object root, and `ObjectContentStore` documents that it never invokes `onBreak` at all. That leaves the rebind as
 * the only realistic event in the (poll -> mint) window, which makes it the whole probe rather than one cell of many.
 *
 * A LIST taken against one bucket must authorize NOTHING once the root points somewhere else: copy and re-bind are
 * indistinguishable from the listing's point of view, and what it failed to see in the old universe says nothing about
 * the new one.
 *
 * **WHAT THIS MEASURED, which is not what was expected.** The property holds - and the CONTROL proves that is not
 * vacuous, because the identical delete under an unchanged binding does converge. Both refusal conditions are true:
 * [BindingLatch.proven] compares the manifest binding first, while observing a new binding also leaves the latch
 * UNRESOLVED so the following trust check would refuse if that comparison were backed out. The comparison is the belt
 * for a stale generation under a binding that has become trusted again, a state this row does not construct.
 *
 * The mid-lifetime [BindingLatch.observe] below is test-only. Its trusted-again world row is deliberately deferred,
 * with the owner's acceptance and no tracked issue, until whichever chunk first makes observe reachable mid-lifetime
 * through config reload or multi-root object backends. This KDoc is the durable record. Today observe is boot-only, and
 * the `trustCalls shouldBe 0` RED in `SqlDelightRootTopologyRepositoryTest` pins comparison-before-trust ORDER, not the
 * trusted-again world.
 *
 * That distinction is the reason to run a probe instead of reasoning: the KDoc on the caller previously credited the
 * binding guard, and it was crediting the wrong half.
 */
class ObjectListRebindBetweenPollAndMintTest : FunSpec({

    val handbookBinding = RootBinding("https://r2.example|handbook|")
    val elsewhere = RootBinding("https://r2.example|archive|")

    val deployId = PageId.require("01900000-0000-7000-9000-0000000000e1")
    val runbookId = PageId.require("01900000-0000-7000-9000-0000000000e2")

    test("a re-bind landing between the LIST and the MINT reaps nothing - the listing described a different universe") {
        ObjectAbsenceWorld().use { world ->
            val bucket = FakeObjectStore().apply {
                seedPage("guides/deploy.md", deployId)
                seedPage("guides/runbook.md", runbookId)
            }
            world.boot(bucket, handbookBinding).rebuild() // TRUSTED, two durable rows

            // An ordinary delete in the bucket we are latched to: on its own this converges, and the CONTROL below is
            // what proves it does. The LIST that sees it is taken HERE, and it is this pass's whole negative evidence.
            bucket.remove("guides/deploy.md")
            world.store.pollOnce()

            // THE INTERLEAVE: one BindingLatch.observe call records the new binding and advances its freshness epoch
            // transactionally. It lands AFTER the listing was taken and BEFORE AbsencePass.capture reads the observation
            // stamp at the top of rebuild(), which is the window this source cannot close by ordering.
            BindingLatch(world.topology).observe(RootName.PRIMARY, elsewhere)

            world.builder().rebuild()

            withClue("a LIST of the OLD bucket must not retire a binding after the root moved to a new one") {
                world.idMap.retiredBindings().shouldBeEmpty()
                world.idMap.bindings().map { it.id } shouldContainExactlyInAnyOrder listOf(deployId, runbookId)
            }
        }
    }

    test("CONTROL - the SAME delete under an unchanged binding DOES converge, or the row above proves nothing") {
        ObjectAbsenceWorld().use { world ->
            val bucket = FakeObjectStore().apply {
                seedPage("guides/deploy.md", deployId)
                seedPage("guides/runbook.md", runbookId)
            }
            world.boot(bucket, handbookBinding).rebuild()

            bucket.remove("guides/deploy.md")
            world.store.pollOnce()
            world.builder().rebuild() // nothing re-binds this time

            withClue("an ordinary delete in a TRUSTED bucket is exactly what OBJECT_LIST exists to converge") {
                world.idMap.bindings().map { it.id } shouldContainExactlyInAnyOrder listOf(runbookId)
                world.idMap.retiredBindings().map { it.id } shouldContainExactlyInAnyOrder listOf(deployId)
            }
        }
    }

    test("an idempotent same-id re-bind after LIST cannot refresh the manifest epoch at mint") {
        ObjectAbsenceWorld().use { world ->
            val bucket = FakeObjectStore().apply {
                seedPage("guides/deploy.md", deployId)
                seedPage("guides/runbook.md", runbookId)
            }
            world.boot(bucket, handbookBinding).rebuild()

            bucket.remove("guides/deploy.md")
            world.store.pollOnce()

            val deployPath = RootedPath(RootName.PRIMARY, page("guides/deploy.md"))
            world.idMap.bind(deployPath, deployId, materialized = true)
            world.builder().rebuild()

            withClue("the pre-rebind LIST cannot retire the binding restored after its epoch was captured") {
                world.idMap.bindingInRoot(RootName.PRIMARY, deployId).shouldNotBeNull()
                world.idMap.retiredAt(RootName.PRIMARY, deployId).shouldBeNull()
            }
        }
    }
})
