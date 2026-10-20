package com.plainbase.frameworks.ktor

import com.plainbase.domain.page.IndexedPage
import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.config.DeploymentProfile
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.application.pluginOrNull
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.testing.testApplication

class ReaderHeadWireTest : FunSpec({
    listOf(
        ReaderHeadProbe("root redirect", { "/" }, "HTTP/1.1 302 Found"),
        ReaderHeadProbe("root shell", { "/docs" }),
        ReaderHeadProbe("page shell", { requireNotNull(it.probePage().url) }),
        ReaderHeadProbe("health", { "/healthz" }),
        ReaderHeadProbe("index shell", { "/index.html" }),
        ReaderHeadProbe("favicon", { "/favicon.svg" }),
        ReaderHeadProbe("font", { "/fonts/ibm-plex-sans-400.woff2" }),
        ReaderHeadProbe("content asset", { "/assets/docs/infra/assets/diagram.svg" }),
        ReaderHeadProbe("permalink", { "/p/docs/${it.probePage().id.value}" }, "HTTP/1.1 302 Found"),
        ReaderHeadProbe("browse redirect", { "/browse/docs/${it.probePage().path.value}" }, "HTTP/1.1 302 Found"),
        ReaderHeadProbe("theme", { "/_plainbase/theme-light.css" }),
        ReaderHeadProbe("unmounted admin shell", { "/admin" }, "HTTP/1.1 404 Not Found"),
        ReaderHeadProbe("tree", { "/api/v1/tree" }),
        ReaderHeadProbe("search", { "/api/v1/search?q=deploy" }),
        ReaderHeadProbe("session", { "/api/v1/session" }),
        ReaderHeadProbe("page JSON", { "/api/v1/pages/${it.probePage().id.value}" }),
        ReaderHeadProbe("page HTML", { "/api/v1/pages/${it.probePage().id.value}/html" }),
        ReaderHeadProbe("page metadata", { "/api/v1/pages/${it.probePage().id.value}/metadata" }),
        ReaderHeadProbe("page by path", { "/api/v1/pages/by-path/docs/${requireNotNull(it.probePage().urlPath).value}" }),
    ).forEach { probe ->
        test("reader HEAD ${probe.label} matches GET metadata with no body before the next response") {
            readerWireServer { port, harness ->
                val path = probe.path(harness)
                val get = observeWire(port, "GET", path)
                val head = observeWire(port, "HEAD", path)
                withClue("$path GET=${get.head}; HEAD=${head.head}") {
                    val getNext = get.outcome.shouldBeInstanceOf<WireOutcome.NextResponse>()
                    val headNext = head.outcome.shouldBeInstanceOf<WireOutcome.NextResponse>()
                    assertSoftly {
                        get.head.statusLine shouldBe probe.getStatus
                        (getNext.leftover > 0 || get.head.statusLine.substringAfter(' ').take(3).toInt() in 300..399) shouldBe true
                        head.head.statusLine shouldBe get.head.statusLine
                        readerHeadHeaders.forEach { name ->
                            withClue(name) { head.head.header(name) shouldBe get.head.header(name) }
                        }
                        getNext.statusLine shouldBe "HTTP/1.1 200 OK"
                        headNext.statusLine shouldBe "HTTP/1.1 200 OK"
                        withClue("HEAD leftover") { headNext.leftover shouldBe 0 }
                    }
                }
            }
        }
    }

    listOf(
        "/api/v1/nope" to "HTTP/1.1 404 Not Found",
        "/api/v1/nope?q=%" to "HTTP/1.1 400 Bad Request",
        "/api/v1/pages/by-path/%GG" to "HTTP/1.1 400 Bad Request",
    ).forEach { (path, status) ->
        test("reader HEAD $path preserves the API error metadata and socket framing") {
            readerWireServer { port, _ ->
                val get = observeWire(port, "GET", path)
                val head = observeWire(port, "HEAD", path)
                val getNext = get.outcome.shouldBeInstanceOf<WireOutcome.NextResponse>()
                val headNext = head.outcome.shouldBeInstanceOf<WireOutcome.NextResponse>()
                assertSoftly {
                    get.head.statusLine shouldBe status
                    head.head.statusLine shouldBe status
                    get.head.header("Content-Type") shouldBe listOf("application/json")
                    get.head.header("Content-Length") shouldBe listOf(getNext.leftover.toString())
                    (getNext.leftover > 0) shouldBe true
                    listOf("Content-Type", "Content-Length").forEach { name ->
                        withClue(name) { head.head.header(name) shouldBe get.head.header(name) }
                    }
                    headNext.leftover shouldBe 0
                    headNext.statusLine shouldBe "HTTP/1.1 200 OK"
                    getNext.statusLine shouldBe "HTTP/1.1 200 OK"
                }
            }
        }
    }

    test("reader theme advertises GET and HEAD") {
        readerWireServer { port, _ ->
            val options = observeWire(port, "OPTIONS", "/_plainbase/theme-light.css")
            options.head.statusLine shouldBe "HTTP/1.1 405 Method Not Allowed"
            options.head.header("Allow") shouldBe listOf("GET, HEAD")
        }
    }

    listOf(DeploymentProfile.WORKSPACE, DeploymentProfile.READER).forEach { profile ->
        test("AutoHeadResponse scope for $profile") {
            RestHarness(Fixtures.demoDocs, authMode = AuthMode.OFF, profile = profile).use { harness ->
                testApplication {
                    application { plainbaseModule(harness.services) }
                    startApplication()
                    val plugin = application.pluginOrNull(AutoHeadResponse)
                    if (profile == DeploymentProfile.READER) plugin.shouldNotBeNull() else plugin.shouldBeNull()
                }
            }
        }
    }

    listOf(
        "/docs" to "HTTP/1.1 404 Not Found",
        "/healthz" to "HTTP/1.1 405 Method Not Allowed",
        "/api/v1/tree" to "HTTP/1.1 404 Not Found",
    ).forEach { (path, status) ->
        test("workspace HEAD $path keeps its observed wire response") {
            wireServer { port ->
                val head = observeWire(port, "HEAD", path)
                val headers = if (path == "/api/v1/tree") {
                    mapOf(
                        "content-length" to listOf("77"),
                        "content-type" to listOf("application/json"),
                        "connection" to listOf("keep-alive"),
                    )
                } else {
                    mapOf("content-length" to listOf("0"), "connection" to listOf("keep-alive"))
                }
                assertSoftly {
                    head.head.statusLine shouldBe status
                    head.head.headers.filterKeys { it != "date" } shouldBe headers
                    head.outcome shouldBe WireOutcome.NextResponse(0, "HTTP/1.1 200 OK")
                }
            }
        }
    }
})

private data class ReaderHeadProbe(
    val label: String,
    val path: (RestHarness) -> String,
    val getStatus: String = "HTTP/1.1 200 OK",
)

private val readerHeadHeaders = listOf(
    "Content-Type", "Content-Length", "Location", "Cache-Control", "Content-Security-Policy", "Vary", "ETag",
)

private fun RestHarness.probePage(): IndexedPage = builder.current.pages.single { it.path.value == "guides/deploy-guide.md" }

private suspend fun readerWireServer(block: suspend (Int, RestHarness) -> Unit) {
    lateinit var harness: RestHarness
    wireServer(harness = {
        RestHarness(Fixtures.demoDocs, authMode = AuthMode.OFF, profile = DeploymentProfile.READER).also { harness = it }
    }) { port -> block(port, harness) }
}
