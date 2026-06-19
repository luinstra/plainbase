package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * W3b behavioral named tests for `POST /api/v1/preview` — the PRIVATE / non-contractual read-only
 * render of a submitted Markdown buffer. All run over [writeRestTest] (a temp COPY of the fixture
 * tree); preview persists nothing, but the asset tests share the harness, so one seam for the chunk.
 */
class PreviewRouteTest : FunSpec({

    fun markdown(): ContentType = ContentType.parse("text/markdown")
    suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    suspend fun HttpResponse.errorJson(): JsonObject = obj().getValue("error").jsonObject

    // 1. Renders + sanitizes: a heading gets a PB-SLUG-1 id; a raw <script> renders as ESCAPED text.
    test("preview renders the body, allocates heading ids, and escapes raw HTML (§C3 sanitization)") {
        writeRestTest(Fixtures.demoDocs) { _ ->
            val resp = client.post("/api/v1/preview") {
                contentType(markdown())
                setBody("# Hi\n\n<script>alert(1)</script>\n")
            }
            resp.status shouldBe HttpStatusCode.OK
            val body = resp.obj()
            val html = body.getValue("html").jsonPrimitive.content
            html shouldContain "id=\"hi\""
            html shouldContain "&lt;script&gt;"
            html shouldNotContain "<script>"

            val heading = body.getValue("headings").jsonArray.single().jsonObject
            heading.getValue("id").jsonPrimitive.content shouldBe "hi"
            heading.getValue("level").jsonPrimitive.content shouldBe "1"
            heading.getValue("text").jsonPrimitive.content shouldBe "Hi"
        }
    }

    // 2. Single-renderer parity for index-INDEPENDENT content: preview html == GET …/html after a PUT
    // of the same bytes (both go through the same rendererFactory). Not a claim for content with links.
    test("preview html is byte-identical to GET …/html for index-independent content (single-renderer)") {
        val pageId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
        val original = "---\nid: $pageId\ntitle: Parity\n---\n\n# Parity\n\nplain body, no links.\n"
        val tree = Files.createTempDirectory("plainbase-preview-parity")
        try {
            Files.write(tree.resolve("parity.md"), original.toByteArray())
            val seed = { idMap: com.plainbase.domain.repository.IdMapRepository ->
                idMap.bind(
                    com.plainbase.domain.content.TreePath.require("parity.md"),
                    com.plainbase.domain.page.PageId.require(pageId),
                    materialized = true,
                )
            }
            writeRestTest(tree, seed) { _ ->
                // The PUT-then-GET path renders through the index; the preview renders the SAME bytes live.
                val getHtml = client.get("/api/v1/pages/$pageId/html").obj().getValue("html").jsonPrimitive.content
                val previewHtml = client.post("/api/v1/preview") {
                    contentType(markdown())
                    // The ?path= matches the page's path so relative-href resolution is identical.
                    setBody(original)
                }.obj().getValue("html").jsonPrimitive.content
                previewHtml shouldBe getHtml
            }
        } finally {
            tree.toFile().deleteRecursively()
        }
    }

    // 3. Link resolution against the CURRENT snapshot (PB-LINK-1): a link to an EXISTING page resolves
    // to its /docs URL; a link to a non-existent page renders inert (data-pb-link-error).
    test("preview rewrites a link to an existing page and marks a link to a missing page inert") {
        val pageId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
        val seed = { idMap: com.plainbase.domain.repository.IdMapRepository ->
            idMap.bind(
                com.plainbase.domain.content.TreePath.require("guides/deploy-guide.md"),
                com.plainbase.domain.page.PageId.require(pageId),
                materialized = false,
            )
        }
        writeRestTest(Fixtures.demoDocs, seed) { _ ->
            val resp = client.post("/api/v1/preview?path=guides/preview.md") {
                contentType(markdown())
                setBody("[live](deploy-guide.md)\n\n[dead](does-not-exist.md)\n")
            }
            resp.status shouldBe HttpStatusCode.OK
            val html = resp.obj().getValue("html").jsonPrimitive.content
            html shouldContain "/docs/guides/deploy-guide"
            html shouldContain "data-pb-link-error"
        }
    }

    // 3b. The ?path= is read EAGER-decoded (codex-review fix): a standard client encodes a nested path's
    // `/` separators as `%2F` (URLSearchParams: `path=guides%2Fx.md`). previewPath() reads Ktor's eager-
    // decoded queryParameters (which turns %2F→/) and parses via TreePath.of, so a RELATIVE link resolves
    // against the buffer's FOLDER. The OLD strict-decoder path rejected %2F and fell back to the synthetic
    // ROOT, resolving the link from the content root (wrong URL). Assert the folder-relative URL, not root.
    test("a %2F-encoded ?path= resolves a relative link against the buffer's folder (eager-decode fix)") {
        writeRestTest(Fixtures.demoDocs) { _ ->
            val resp = client.post("/api/v1/preview?path=guides%2Fpreview.md") {
                contentType(markdown())
                setBody("[sibling](getting-started.md)\n")
            }
            resp.status shouldBe HttpStatusCode.OK
            val html = resp.obj().getValue("html").jsonPrimitive.content
            // Resolved against guides/ (the buffer's folder), NOT the content root.
            html shouldContain "\"/docs/guides/getting-started\""
            html shouldNotContain "data-pb-link-error"
        }
    }

    // 4. Preview persists NOTHING: the on-disk file set and a target page's bytes are unchanged, and a
    // follow-up GET returns the OLD content (the snapshot was never swapped).
    test("preview is read-only: no file written, no snapshot swap, the page still reads its old bytes") {
        val pageId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
        val seed = { idMap: com.plainbase.domain.repository.IdMapRepository ->
            idMap.bind(
                com.plainbase.domain.content.TreePath.require("guides/deploy-guide.md"),
                com.plainbase.domain.page.PageId.require(pageId),
                materialized = false,
            )
        }
        writeRestTest(Fixtures.demoDocs, seed) { harness ->
            val before = countFiles(harness.root)
            val originalBytes = harness.diskBytes("guides/deploy-guide.md")
            val originalGet = client.get("/api/v1/pages/$pageId").obj()

            client.post("/api/v1/preview") {
                contentType(markdown())
                setBody("# Totally different buffer\n\nnot saved.\n")
            }.status shouldBe HttpStatusCode.OK

            countFiles(harness.root) shouldBe before
            harness.diskBytes("guides/deploy-guide.md") shouldBe originalBytes
            val afterGet = client.get("/api/v1/pages/$pageId").obj()
            afterGet.getValue("content_hash").jsonPrimitive.content shouldBe originalGet.getValue("content_hash").jsonPrimitive.content
            afterGet.getValue("markdown").jsonPrimitive.content shouldBe originalGet.getValue("markdown").jsonPrimitive.content
        }
    }

    // 5. Body cap → 413, both a lying Content-Length and a no-Content-Length stream; nothing rendered.
    test("an over-cap preview body is 413 body_too_large (lying length and streamed)") {
        writeRestTest(Fixtures.demoDocs) { harness ->
            val cap = harness.services.maxWriteBodyBytes
            val oversize = ByteArray((cap + 1).toInt()) { 'a'.code.toByte() }

            val lying = client.post("/api/v1/preview") {
                setBody(LyingLengthMarkdownContent(oversize, advertisedLength = 100L))
            }
            lying.status shouldBe HttpStatusCode.PayloadTooLarge
            lying.errorJson().getValue("code").jsonPrimitive.content shouldBe "body_too_large"
            lying.errorJson().getValue("max_bytes").jsonPrimitive.content.toLong() shouldBe cap

            val chunked = client.post("/api/v1/preview") {
                contentType(markdown())
                setBody(ByteReadChannel(oversize))
            }
            chunked.status shouldBe HttpStatusCode.PayloadTooLarge
        }
    }

    // 6. Media type: non-text/markdown is 415.
    test("a preview without text/markdown is 415 unsupported_media_type") {
        writeRestTest(Fixtures.demoDocs) { _ ->
            val json = client.post("/api/v1/preview") {
                contentType(ContentType.Application.Json)
                setBody("""{"x":1}""")
            }
            json.status shouldBe HttpStatusCode.UnsupportedMediaType
            json.errorJson().getValue("code").jsonPrimitive.content shouldBe "unsupported_media_type"

            val none = client.post("/api/v1/preview") { setBody("# nope\n") }
            none.status shouldBe HttpStatusCode.UnsupportedMediaType
        }
    }

    // 7. Preview is NOT in the forever suite: no golden/rest/preview-*.json is registered.
    test("no preview golden exists in the forever suite (preview is private/non-contractual)") {
        val goldenDir = Fixtures.demoDocs.parent.parent.resolve("server/src/test/resources/golden/rest")
        if (Files.isDirectory(goldenDir)) {
            val previewGoldens = Files.list(goldenDir).use { stream ->
                stream.filter { it.fileName.toString().startsWith("preview-") || it.fileName.toString().startsWith("asset-") }.count()
            }
            previewGoldens shouldBe 0L
        }
    }
})

/** Counts the regular files under [root] (the "nothing written" invariant). */
private fun countFiles(root: Path): Int =
    Files.walk(root).use { stream -> stream.filter { Files.isRegularFile(it) }.count().toInt() }

/** A body that advertises [advertisedLength] but streams all of [bytes] — the streamed read is the authority. */
private class LyingLengthMarkdownContent(
    private val bytes: ByteArray,
    private val advertisedLength: Long,
) : OutgoingContent.WriteChannelContent() {
    override val contentType: ContentType get() = ContentType.parse("text/markdown")
    override val contentLength: Long get() = advertisedLength
    override suspend fun writeTo(channel: ByteWriteChannel) {
        channel.writeFully(bytes)
    }
}
