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

    test("unscanned-but-registered root: always an untouchable live owner (the D17 contest still happens)") {
        BindingVisibility.isLive(RootedPath(extra, page), scannedLive, scannedRoots, registered) shouldBe true
    }

    test("unregistered root: detached, supersedable (D2 - the boot WARN is its visibility)") {
        BindingVisibility.isLive(RootedPath(gone, page), scannedLive, scannedRoots, registered) shouldBe false
    }

    test("full-visibility pass (sources cover the registry): the rule collapses to the scanned-live check") {
        val allScanned = setOf(main, extra)
        BindingVisibility.isLive(RootedPath(extra, page), scannedLive, allScanned, registered) shouldBe false
        BindingVisibility.isLive(RootedPath(main, page), scannedLive, allScanned, registered) shouldBe true
    }
})
