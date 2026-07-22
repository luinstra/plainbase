package com.plainbase.domain.root

import com.plainbase.domain.page.PageId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * RootedPageId — the seam type that owns page identity under multi-root, and the single home of the
 * permalink string (§A4's durability layer) now that it no longer hangs off the bare [PageId].
 *
 * The permalink STRING is unchanged from the pre-seam `PageId.permalink`: bare `/p/{id}`. The root is
 * carried but does not yet shape the string (the root-qualified form is a later change), so this golden
 * pins the byte-identical relocation.
 */
class RootedPageIdTest : FunSpec({

    test("permalink is the bare /p/{id} form, byte-identical to the pre-seam PageId permalink") {
        val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
        RootedPageId(RootName.MAIN, id).permalink shouldBe "/p/${id.value}"
        Permalink.of(id) shouldBe "/p/${id.value}"
    }

    test("the permalink is the same string regardless of the qualifying root (unchanged in this chunk)") {
        val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
        val notes = RootName.require("notes")
        RootedPageId(notes, id).permalink shouldBe RootedPageId(RootName.MAIN, id).permalink
    }
})
