package com.plainbase.domain.root

import com.plainbase.domain.page.PageId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * RootedPageId — the seam type that owns page identity under multi-root, and the single home of the
 * permalink string (§A4's durability layer) now that it no longer hangs off the bare [PageId].
 *
 * The permalink STRING is ROOT-QUALIFIED (per-root identity, C5): `/p/{root}/{id}`, so the owning root
 * SHAPES the string and two roots holding one id emit DIFFERENT permalinks.
 */
class RootedPageIdTest : FunSpec({

    test("permalink is the rooted /p/{root}/{id} form") {
        val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
        RootedPageId(RootName.PRIMARY, id).permalink shouldBe "/p/docs/${id.value}"
        Permalink.of(RootName.PRIMARY, id) shouldBe "/p/docs/${id.value}"
    }

    test("two roots holding the same id emit DIFFERENT permalinks") {
        val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
        val notes = RootName.require("notes")
        RootedPageId(notes, id).permalink shouldBe "/p/notes/${id.value}"
        RootedPageId(notes, id).permalink shouldNotBe RootedPageId(RootName.PRIMARY, id).permalink
    }
})
