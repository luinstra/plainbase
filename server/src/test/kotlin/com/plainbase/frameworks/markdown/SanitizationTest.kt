package com.plainbase.frameworks.markdown

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.service.FixtureIndexStub
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Acceptance criterion 3 — sanitization (§C3). Two independent guarantees:
 *  - **ESCAPE_HTML:** raw HTML in Markdown renders as visible literal text, never as live markup —
 *    every emitted tag derives from the AST, so injected `<script>`/`<img onerror=…>` are escaped.
 *  - **Scheme ALLOWLIST (not blocklist):** `javascript:` and `data:` render inert, and so does an
 *    `ftp:` link — proving the policy is allow-listed (http/https/mailto/protocol-relative only),
 *    not a hand-maintained blocklist that an unforeseen scheme could slip past.
 *
 * "Inert" = the navigable attribute (`href`) is dropped and `data-pb-link-error="blocked_scheme"`
 * tags the element; nothing the browser will follow remains.
 */
class SanitizationTest : FunSpec({

    val renderer = FlexmarkRenderer(FixtureIndexStub(Fixtures.demoDocs))
    val sourcePath = TreePath.require("guides/deploy-guide.md")

    fun render(markdown: String) = renderer.render(sourcePath, markdown.toByteArray()).html

    test("raw <script> in Markdown is escaped to visible text, not live markup") {
        val html = render("Hello <script>alert(1)</script> world")
        html shouldContain "&lt;script&gt;"
        html shouldNotContain "<script>"
    }

    test("raw <img onerror=…> in Markdown is escaped to visible text") {
        val html = render("""An image: <img src=x onerror="alert(1)">""")
        html shouldContain "&lt;img"
        html shouldNotContain "<img src=x"
        html shouldNotContain "onerror=\"alert"
    }

    test("javascript: link renders inert (blocked_scheme, no href)") {
        val html = render("[click](javascript:alert(1))")
        html shouldContain """data-pb-link-error="blocked_scheme""""
        html shouldNotContain "href="
    }

    test("data: link renders inert (blocked_scheme, no href)") {
        val html = render("[x](data:text/html,<script>alert(1)</script>)")
        html shouldContain """data-pb-link-error="blocked_scheme""""
        html shouldNotContain "href="
    }

    test("ftp: link renders inert — allowlist, not blocklist (a non-dangerous scheme is still blocked)") {
        val page = renderer.render(sourcePath, "[spec](ftp://files.example.com/spec.md)".toByteArray())
        page.links.size shouldBe 1
        page.html shouldContain """data-pb-link-error="blocked_scheme""""
        page.html shouldNotContain "href="
    }

    // The bypass: link-bearing nodes flexmark emits that are NOT inline [text](url)/![alt](url). Each
    // reached render with its own default href because SUPPRESSED_LINKS is cleared; all must now route
    // through the §A2 allowlist and render inert for a blocked scheme.

    test("core autolink <javascript:…> is routed through the allowlist and rendered inert") {
        val html = render("See <javascript:alert(1)> here")
        html shouldContain """data-pb-link-error="blocked_scheme""""
        html shouldNotContain "href=\"javascript:"
    }

    test("reference-style javascript: link renders inert (no live href)") {
        val html = render("[click][x]\n\n[x]: javascript:alert(1)")
        html shouldContain """data-pb-link-error="blocked_scheme""""
        html shouldNotContain "href=\"javascript:"
    }

    test("reference-style data: link renders inert (no live href)") {
        val html = render("[click][x]\n\n[x]: data:text/html,<script>alert(1)</script>")
        html shouldContain """data-pb-link-error="blocked_scheme""""
        html shouldNotContain "href=\"data:"
    }

    test("reference-style image with javascript: target renders inert (no live src)") {
        val html = render("![a][x]\n\n[x]: javascript:alert(1)")
        html shouldContain """data-pb-link-error="blocked_scheme""""
        html shouldNotContain "src=\"javascript:"
    }

    test("inline Markdown image with javascript: target drops src (inert)") {
        val html = render("![alt](javascript:alert(1))")
        html shouldContain """data-pb-link-error="blocked_scheme""""
        html shouldNotContain "src=\"javascript:"
    }

    // Correctness regression guard: routing must classify, not blanket-strip. A mailto autolink is on
    // the allowlist and must stay a LIVE link — layer 1 cannot over-strip legit schemes.
    test("mailto: autolink stays a live link (allowlist permits mailto)") {
        val html = render("Contact <user@example.com> now")
        html shouldContain "href=\"mailto:user@example.com\""
        html shouldNotContain "data-pb-link-error"
    }

    // Fix #2: an UNDEFINED reference (`[TODO]`, `[1]`, `[x]`) is prose, not a link — flexmark renders
    // it as literal text (no `<a>`), so it must NOT enter linkOutcomes as a phantom Broken(MALFORMED)
    // that the chunk-8 link checker would flag. Only DEFINED refs route.
    test("undefined reference `[TODO]` is literal text, not a routed (broken) link") {
        val page = renderer.render(sourcePath, "see [TODO] later".toByteArray())
        page.links.shouldBeEmpty()
        page.html shouldContain "[TODO]"
        page.html shouldNotContain "data-pb-link-error"
    }

    // Cheap pin: a quoted URL on an ALLOWED scheme keeps its href but the embedded quote is
    // attribute-escaped — there is no break-out of the `href="…"` attribute.
    test("allowed-url with an embedded quote is href-escaped, no attribute break-out") {
        val html = render("""[x](http://a/"onmouseover=y)""")
        html shouldContain "href="
        html shouldNotContain """"onmouseover=y""" // the raw quote never survives unescaped into markup
        html shouldContain "&quot;onmouseover=y"
    }

    // Cheap pin: an http(s) angle-bracket autolink stays a LIVE link (allowlist).
    test("http(s) autolink <https://example.com> stays live") {
        val html = render("See <https://example.com> here")
        html shouldContain "href=\"https://example.com\""
        html shouldNotContain "data-pb-link-error"
    }
})
