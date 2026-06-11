package com.plainbase.domain.content

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Acceptance: TreePath structurally cannot express `..` or be absolute.
 *
 * Pure logic — migrated to Kotest (JVM-only, NOT @Tag("native")). Non-ASCII path literals use
 * \u escapes so an editor cannot silently re-normalize them and weaken the NFC assertions.
 */
class TreePathTest : FunSpec({

    test("valid relative path constructs") {
        val p = TreePath.of("guides/setup.md")
        p.shouldNotBeNull()
        p.value shouldBe "guides/setup.md"
        p.segments shouldBe listOf("guides", "setup.md")
        p.name shouldBe "setup.md"
    }

    test("dot-dot segment cannot be constructed") {
        TreePath.of("../x").shouldBeNull()
        TreePath.of("guides/../secret.md").shouldBeNull()
        TreePath.of("..").shouldBeNull()
    }

    test("single-dot segment cannot be constructed") {
        TreePath.of("./x").shouldBeNull()
        TreePath.of("a/./b").shouldBeNull()
        TreePath.of(".").shouldBeNull()
    }

    test("absolute path cannot be constructed") {
        TreePath.of("/api/rest.md").shouldBeNull()
        TreePath.of("/").shouldBeNull()
    }

    test("empty path cannot be constructed") {
        TreePath.of("").shouldBeNull()
    }

    test("empty interior segment cannot be constructed") {
        TreePath.of("a//b").shouldBeNull()
        TreePath.of("a/").shouldBeNull()
    }

    test("require throws on invalid input") {
        shouldThrow<IllegalArgumentException> { TreePath.require("../escape") }
    }

    test("segments are NFC-normalized at construction") {
        // NFD input (r, e, U+0301 COMBINING ACUTE) folds to precomposed NFC U+00E9 at construction —
        // deleting the Nfc.normalize call in TreePath.of would fail this.
        val p = TreePath.of("notes/re\u0301union.md")
        p.shouldNotBeNull()
        p.value shouldBe "notes/r\u00e9union.md" // precomposed NFC, not the NFD input
        p.name shouldBe "r\u00e9union.md"
    }

    test("parent and resolveChild compose") {
        val p = TreePath.require("a/b/c.md")
        p.parent?.value shouldBe "a/b"
        p.parent?.parent?.value shouldBe "a"
        p.parent?.parent?.parent.shouldBeNull()

        val child = TreePath.require("a/b").resolveChild("c.md")
        child.value shouldBe "a/b/c.md"
    }

    test("resolveChild rejects traversal segments") {
        val p = TreePath.require("a")
        shouldThrow<IllegalArgumentException> { p.resolveChild("..") }
        shouldThrow<IllegalArgumentException> { p.resolveChild(".") }
        shouldThrow<IllegalArgumentException> { p.resolveChild("b/c") }
    }

    test("equality is value-based and NFC-insensitive to input form") {
        // Left side NFD, right side NFC \u2014 equal ONLY because both normalize to NFC.
        TreePath.of("notes/re\u0301union.md") shouldBe TreePath.of("notes/r\u00e9union.md")
        TreePath.of("notes/re\u0301union.md")?.hashCode() shouldBe
            TreePath.of("notes/r\u00e9union.md")?.hashCode()
    }
})
