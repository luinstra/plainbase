package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.ktor.routes.assetContentType
import com.plainbase.frameworks.ktor.routes.assetNeedsSandbox
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.http.ContentType

/**
 * C1a item 2: the served-MIME inert-allowlist that keys the per-asset sandbox CSP. The named home for the
 * [assetNeedsSandbox] unit assertions (NOT folded into the route test): scriptable served types (svg, js,
 * mjs, pdf) are sandboxed; the known-inert types are not — including `text/plain; charset=UTF-8` (the
 * charset [assetContentType] appends to text types must still match the param-less `text/plain`). The
 * inert set is derived deliberately from the extension map, so a hypothetical FUTURE scriptable map entry
 * that nobody adds to the inert set is sandboxed by default (inert-unless-proved-safe).
 */
class AssetSandboxTypeTest : FunSpec({

    test("scriptable served types are sandboxed") {
        for (name in listOf("x.svg", "x.js", "x.mjs", "x.pdf")) {
            assetNeedsSandbox(assetContentType(name)) shouldBe true
        }
    }

    test("known-inert served types are NOT sandboxed (incl. the charset-appended text types)") {
        // text/* types arrive from assetContentType WITH a `; charset=UTF-8` param; the allowlist compares
        // without parameters, so they must still match the param-less inert entry.
        for (name in listOf(
            "x.png", "x.jpg", "x.jpeg", "x.gif", "x.webp", "x.ico",
            "x.css", "x.txt", "x.csv", "x.json", "x.yaml", "x.yml",
            "x.woff", "x.woff2",
            "x.unknown-extension", // → application/octet-stream (inert; an uploaded evil.html lands here too)
        )) {
            assetNeedsSandbox(assetContentType(name)) shouldBe false
        }
    }

    test("an uploaded evil.html resolves to octet-stream and is therefore inert (no sandbox)") {
        assetContentType("evil.html").withoutParameters() shouldBe ContentType.Application.OctetStream
        assetNeedsSandbox(assetContentType("evil.html")) shouldBe false
    }

    test("a future scriptable type absent from the inert allowlist defaults to sandbox") {
        // The regression guard: anything not deliberately listed inert is sandboxed by default.
        assetNeedsSandbox(ContentType.parse("application/wasm")) shouldBe true
        assetNeedsSandbox(ContentType.Application.Xml) shouldBe true
    }
})
