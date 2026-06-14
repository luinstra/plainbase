package com.plainbase.domain.content

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Acceptance (chunk 1, criterion 4 — parser half): the `_folder.yaml` line parser reads the
 * four known keys (title, order, slug, description), strips quotes, collapses a blank description
 * to null, and ignores unknown keys / malformed lines without throwing. Pure logic — JVM-only
 * Kotest, not @Tag("native").
 */
class FolderMetaTest : FunSpec({

    test("parses the four known keys") {
        val meta = FolderMeta.parse(
            """
            title: Guides
            order: 1
            slug: guide
            description: Folder summary
            """.trimIndent(),
        )
        meta shouldBe FolderMeta(title = "Guides", order = 1, slug = "guide", description = "Folder summary")
    }

    test("missing keys default to null") {
        val meta = FolderMeta.parse("title: API Reference\norder: 2\n")
        meta.title shouldBe "API Reference"
        meta.order shouldBe 2
        meta.slug.shouldBeNull()
    }

    test("strips matching surrounding quotes from values") {
        FolderMeta.parse("title: \"Quoted Title\"").title shouldBe "Quoted Title"
        FolderMeta.parse("slug: 'release-notes'").slug shouldBe "release-notes"
        FolderMeta.parse("description: \"Quoted desc\"").description shouldBe "Quoted desc"
        // A single embedded quote is not a matching pair: left intact.
        FolderMeta.parse("""title: it's fine""").title shouldBe "it's fine"
    }

    test("a blank description collapses to null") {
        FolderMeta.parse("description:   ").description.shouldBeNull()
        FolderMeta.parse("description: \"\"").description.shouldBeNull()
    }

    test("ignores unknown keys without throwing") {
        val meta = FolderMeta.parse("title: T\ncolor: blue\nhidden: true\n")
        meta shouldBe FolderMeta(title = "T", order = null, slug = null)
    }

    test("ignores blank lines and comments") {
        val meta = FolderMeta.parse("# heading comment\n\ntitle: T\n\n# trailing\n")
        meta.title shouldBe "T"
    }

    test("non-integer order is treated as absent") {
        FolderMeta.parse("order: not-a-number").order.shouldBeNull()
    }

    test("a slug round-trips through the model") {
        val parsed = FolderMeta.parse("title: API\norder: 2\nslug: api-ref\n")
        val rebuilt = FolderMeta(title = parsed.title, order = parsed.order, slug = parsed.slug)
        rebuilt shouldBe parsed
        rebuilt.slug shouldBe "api-ref"
    }
})
