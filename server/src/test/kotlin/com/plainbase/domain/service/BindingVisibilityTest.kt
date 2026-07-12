package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The D16 classification table, tested DIRECTLY (ADR-0011): this is where the rule's truth table
 * lives; the pass-level cases in IndexBuilderMultiRootTest/AdoptionPassTest prove each pass
 * actually routes through it.
 */
class BindingVisibilityTest : FunSpec({

    val main = RootName.MAIN
    val extra = RootName.require("extra")
    val gone = RootName.require("gone")
    val page = TreePath.require("guides/a.md")

    val scannedLive = setOf(RootedPath(main, page))
    val scannedRoots = setOf(main)
    val registered = setOf(main, extra)

    test("scanned root, path on disk: live") {
        BindingVisibility.isLive(RootedPath(main, page), scannedLive, scannedRoots, registered) shouldBe true
    }

    test("scanned root, path gone from disk: a moved file, supersedable") {
        BindingVisibility.isLive(RootedPath(main, TreePath.require("moved.md")), scannedLive, scannedRoots, registered) shouldBe false
    }

    test("unscanned-but-registered root: always an untouchable live owner") {
        BindingVisibility.isLive(RootedPath(extra, page), scannedLive, scannedRoots, registered) shouldBe true
    }

    test("unregistered root: detached, not an owner at all (D2 - the boot WARN is its visibility)") {
        BindingVisibility.isLive(RootedPath(gone, page), scannedLive, scannedRoots, registered) shouldBe false
    }

    test("full-visibility pass (sources cover the registry): the rule collapses to the scanned-live check") {
        val allScanned = setOf(main, extra)
        BindingVisibility.isLive(RootedPath(extra, page), scannedLive, allScanned, registered) shouldBe false
        BindingVisibility.isLive(RootedPath(main, page), scannedLive, allScanned, registered) shouldBe true
    }

    // The OTHER half of D16: being a live owner and being takeable are different questions, and the D17
    // rank contest may only ask the second one about a root the pass actually looked at.
    test("only a SCANNED root's binding can lose the rank contest") {
        BindingVisibility.isSupersedable(RootedPath(main, page), scannedRoots) shouldBe true
    }

    test("an unscanned root's binding is NON-supersedable, however the two roots rank (D-C4-10)") {
        BindingVisibility.isSupersedable(RootedPath(extra, page), scannedRoots) shouldBe false
    }
})
