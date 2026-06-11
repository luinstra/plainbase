package com.plainbase.domain.content

import com.plainbase.domain.content.ContentRoot.ResolveResult
import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Acceptance: ContentRoot is the single traversal guard — any input whose lexical
 * resolution escapes the root is rejected. Mirrors PB-LINK-1 §A2 step 4.
 *
 * Pure logic — migrated to Kotest (JVM-only, NOT @Tag("native")). Non-ASCII path literals use
 * \u escapes so an editor cannot silently re-normalize them and weaken the NFC assertion.
 */
class ContentRootTest : FunSpec({

    fun resolvedPath(baseDir: TreePath?, target: String): String =
        when (val r = ContentRoot.resolve(baseDir, target)) {
            is ResolveResult.Resolved -> r.path.value
            ResolveResult.Root -> ""
            ResolveResult.Outside -> fail("expected contained resolution, got Outside for '$target'")
        }

    val guides = TreePath.require("guides")

    // ---- relative resolution against a base directory (PB-LINK-1 rows 1, 2) ----

    test("relative dot-dot resolves into a sibling directory") {
        // From guides/, "../infra/kubernetes.md" -> "infra/kubernetes.md".
        resolvedPath(guides, "../infra/kubernetes.md") shouldBe "infra/kubernetes.md"
    }

    test("relative same-dir resolves") {
        resolvedPath(guides, "setup.md") shouldBe "guides/setup.md"
        resolvedPath(guides, "./setup.md") shouldBe "guides/setup.md"
    }

    // ---- absolute-from-root (PB-LINK-1 row 4) ----

    test("leading slash resolves against the root, ignoring baseDir") {
        resolvedPath(guides, "/api/rest.md") shouldBe "api/rest.md"
    }

    // ---- escape detection (PB-LINK-1 row 10) ----

    test("dot-dot above the root escapes") {
        // From guides/, "../../outside.txt" pops guides then escapes the root.
        ContentRoot.resolve(guides, "../../outside.txt") shouldBe ResolveResult.Outside
        ContentRoot.contains(guides, "../../outside.txt") shouldBe false
    }

    test("dot-dot from root escapes immediately") {
        ContentRoot.resolve(null, "../x") shouldBe ResolveResult.Outside
    }

    test("absolute dot-dot escape is caught") {
        ContentRoot.resolve(guides, "/../outside.txt") shouldBe ResolveResult.Outside
    }

    // ---- encoded traversal is contained IDENTICALLY to literal (rows 26, 27) ----

    test("row 27 - decoded literal dot-dot escapes just like raw dot-dot") {
        // PercentCoding.decodeOnce("%2e%2e/%2e%2e/outside.txt") -> "../../outside.txt"
        // which then escapes from guides/.
        val decoded = "../../outside.txt"
        ContentRoot.resolve(guides, decoded) shouldBe ResolveResult.Outside
    }

    test("row 26 - double-decoded literal directory name does NOT traverse") {
        // decodeOnce("%252e%252e/secret.md") -> "%2e%2e/secret.md": "%2e%2e" is an ordinary
        // segment name, never a traversal — it stays contained.
        resolvedPath(TreePath.require("notes"), "%2e%2e/secret.md") shouldBe "notes/%2e%2e/secret.md"
    }

    // ---- collapse semantics ----

    test("resolution to the root yields Root") {
        // From guides/, "../" pops to the root directory.
        ContentRoot.resolve(guides, "../") shouldBe ResolveResult.Root
        ContentRoot.resolve(null, "") shouldBe ResolveResult.Root
    }

    test("interior dot-dot collapses lexically") {
        resolvedPath(null, "a/b/../c.md") shouldBe "a/c.md"
        resolvedPath(null, "a/b/c/../..") shouldBe "a"
    }

    test("trailing and double slashes are collapsed") {
        resolvedPath(null, "a//b/") shouldBe "a/b"
    }

    test("contains is true for a contained relative path") {
        ContentRoot.contains(guides, "../infra/x.md") shouldBe true
        ContentRoot.contains(null, "a/b.md") shouldBe true
    }

    test("resolution NFC-normalizes surviving segments") {
        // NFD input segment (r, e, U+0301) is NFC-folded to precomposed on the way out \u2014
        // deleting the Nfc.normalize call in ContentRoot.resolve would fail this.
        resolvedPath(null, "notes/re" + '\u0301' + "union.md") shouldBe "notes/r\u00e9union.md"
    }
})
