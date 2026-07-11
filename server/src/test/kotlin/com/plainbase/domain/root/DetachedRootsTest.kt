package com.plainbase.domain.root

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The D15 verdict table (ADR-0011): Clean / partial-detached WARN / all-detached FATAL. */
class DetachedRootsTest : FunSpec({

    val main = RootName.MAIN
    val extra = RootName.require("extra")
    val gone = RootName.require("gone")

    test("empty id_map is Clean (fresh install, first boot)") {
        DetachedRoots.evaluate(boundRoots = emptySet(), configured = setOf(main)) shouldBe DetachedRoots.Verdict.Clean
    }

    test("every bound root configured is Clean") {
        DetachedRoots.evaluate(boundRoots = setOf(main, extra), configured = setOf(main, extra)) shouldBe DetachedRoots.Verdict.Clean
    }

    test("a proper nonempty difference is Detached (WARN), naming only the detached roots") {
        DetachedRoots.evaluate(boundRoots = setOf(main, gone), configured = setOf(main)) shouldBe
            DetachedRoots.Verdict.Detached(setOf(gone))
    }

    test("a nonempty id_map fully disjoint from the config is AllDetached (FATAL)") {
        DetachedRoots.evaluate(boundRoots = setOf(gone, extra), configured = setOf(main)) shouldBe
            DetachedRoots.Verdict.AllDetached(setOf(gone, extra))
    }

    test("the synthesized-config shape ({main} bound, {main} configured) is Clean - every migrated legacy DB") {
        DetachedRoots.evaluate(boundRoots = setOf(main), configured = setOf(main)) shouldBe DetachedRoots.Verdict.Clean
    }
})
