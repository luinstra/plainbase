package com.plainbase.frameworks.ktor

import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.repository.Role
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.TestIdProvider
import com.plainbase.frameworks.filesystem.Fixtures
import com.plainbase.frameworks.ktor.routes.composeDocument
import com.plainbase.frameworks.markdown.FrontmatterReader
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock

class TypedPageCreateTest : FunSpec({
    val firstId = "01900000-0000-7000-8000-000000000001"
    val citations = CitationFactory()

    test("omitted and explicit null type retain exact legacy bytes including reserved filenames") {
        for (typeField in listOf("", ",\"type\":null")) {
            writeRestTest(Fixtures.demoDocs, idProvider = TestIdProvider()) { harness ->
                val response = client.post("/api/v1/pages") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"root":"docs","folder":"legacy","title":"Index","body":"body"$typeField}""")
                }
                response.status shouldBe HttpStatusCode.Created
                val expected = "---\nid: $firstId\ntitle: \"Index\"\n---\n\nbody".toByteArray()
                harness.diskBytes("legacy/index.md") shouldBe expected
                Json.parseToJsonElement(response.bodyAsText()).jsonObject["content_hash"]?.jsonPrimitive?.content shouldBe
                    citations.contentHash(expected)
            }
        }
    }

    test("omitted and null type preserve legacy noncharacters and separators through real HTTP creation") {
        val characters = listOf(
            Triple("\\uFFFE", '\uFFFE', "rawslug.md"),
            Triple("\\uFFFF", '\uFFFF', "rawslug.md"),
            Triple("\\u2028", '\u2028', "raw-slug.md"),
            Triple("\\u2029", '\u2029', "raw-slug.md"),
        )
        for (typeField in listOf("", ",\"type\":null")) {
            for ((escape, character, filename) in characters) {
                writeRestTest(Fixtures.demoDocs, idProvider = TestIdProvider()) { harness ->
                    val response = client.post("/api/v1/pages") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"root":"docs","folder":"legacy","title":"Legacy $escape","slug":"raw${escape}slug"""" +
                                ""","body":"body$escape"$typeField}""",
                        )
                    }
                    withClue(response.bodyAsText()) { response.status shouldBe HttpStatusCode.Created }
                    val expected = "---\nid: $firstId\ntitle: \"Legacy $character\"\n" +
                        "slug: \"raw${character}slug\"\n---\n\nbody$character"
                    harness.diskBytes("legacy/$filename") shouldBe expected.toByteArray()
                    Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("content_hash").jsonPrimitive.content shouldBe
                        citations.contentHash(expected.toByteArray())
                }
            }
        }
    }

    test("human typed creation rejects actual reserved title and slug filenames and accepts the custom-slug remedy") {
        val root = Files.createTempDirectory("typed-reserved-human")
        val search = Files.createTempDirectory("typed-reserved-search")
        try {
            SearchDb(search.resolve("search.db")).use { searchDb ->
                IndexHarness(root).use { harness ->
                    harness.builder.rebuild()
                    harness.roleRepository.upsert("builtin", "editor", Role.EDITOR, Clock.System.now())
                    val context = harness.testRouteContext(
                        searchProvider = Fts5SearchProvider(searchDb), idProvider = TestIdProvider(),
                        enforced = true, extract = fixedPrincipal(Principal.Human("builtin", "editor")),
                    )
                    testApplication {
                        application { plainbaseModule(context) }
                        for (name in listOf("Index", "LOG")) {
                            for (folder in listOf("", "nested")) {
                                for (request in listOf(
                                    typedRequest(title = name, slug = null, folder = folder),
                                    typedRequest(slug = name, folder = folder),
                                )) {
                                    val response = client.post("/api/v1/pages") {
                                        contentType(ContentType.Application.Json)
                                        setBody(request)
                                    }
                                    response.status shouldBe HttpStatusCode.BadRequest
                                    val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
                                    error.getValue("code").jsonPrimitive.content shouldBe "invalid_create_request"
                                    val message = error.getValue("message").jsonPrimitive.content
                                    message shouldContain "${name.lowercase()}.md"
                                    message shouldContain "non-reserved slug"
                                    message shouldNotContain "Edit URL"
                                    fileCount(root) shouldBe 0
                                    harness.dirtyPages.all().isEmpty() shouldBe true
                                    harness.proposalRepository.all().isEmpty() shouldBe true
                                }
                            }
                        }
                        for (name in listOf("Index", "LOG")) {
                            for (folder in listOf("", "nested")) {
                                val slug = "${name.lowercase()}-reference"
                                val response = client.post("/api/v1/pages") {
                                    contentType(ContentType.Application.Json)
                                    setBody(typedRequest(title = name, slug = slug, folder = folder))
                                }
                                withClue(response.bodyAsText()) { response.status shouldBe HttpStatusCode.Created }
                                val bytes = Files.readAllBytes(root.resolve(if (folder.isEmpty()) "$slug.md" else "$folder/$slug.md"))
                                independentHeaderType(bytes) shouldBe "Reference"
                                independentHeader(bytes).stringValue("title") shouldBe name
                                independentHeader(bytes).stringValue("slug") shouldBe slug
                            }
                        }
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
            search.toFile().deleteRecursively()
        }
    }

    test("typed punctuation Unicode and numeric-looking values are string scalars with verbatim bodies") {
        val title = "A: \"quote\" \\ café 😀"
        val slug = "typed-😀"
        val body = "# café 😀\n\n---\ntype: body, not metadata\n---\n\u2028\u2029\uFFFE\uFFFF"
        val types = listOf(
            "Reference" to "\"Reference\"",
            "42" to "\"42\"",
            "true" to "\"true\"",
            "Custom: \"type\" \\ 😀" to "\"Custom: \\\"type\\\" \\\\ 😀\"",
            "  Descriptive  " to "\"  Descriptive  \"",
            "Join\u200D Bidi\u202E" to "\"Join\u200D Bidi\u202E\"",
        )
        for ((type, quotedType) in types) {
            writeRestTest(Fixtures.demoDocs, idProvider = TestIdProvider()) { harness ->
                val response = client.post("/api/v1/pages") {
                    contentType(ContentType.Application.Json)
                    setBody(typedRequest(title = title, slug = slug, type = type, body = body))
                }
                withClue(response.bodyAsText()) { response.status shouldBe HttpStatusCode.Created }
                val bytes = harness.diskBytes("typed/typed-.md")
                val expected = "---\nid: $firstId\ntype: $quotedType\ntitle: \"A: \\\"quote\\\" \\\\ café 😀\"\n" +
                    "slug: \"typed-😀\"\n---\n\n$body"
                bytes shouldBe expected.toByteArray()
                independentHeaderType(bytes) shouldBe type
                independentHeader(bytes).stringValue("title") shouldBe title
                independentHeader(bytes).stringValue("slug") shouldBe slug
                FrontmatterReader().parse(bytes).scalar("type") shouldBe type
                val text = bytes.decodeToString(throwOnInvalidSequence = true)
                text.substringAfter("\n---\n\n") shouldBe body
                text.startsWith("---\nid: $firstId\ntype: ") shouldBe true
                citations.contentHash(bytes) shouldBe
                    Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("content_hash").jsonPrimitive.content
            }
        }
    }

    test("invalid typed inputs return the frozen 400 code and name the actual validation field without writes") {
        writeRestTest(Fixtures.demoDocs, idProvider = TestIdProvider()) { harness ->
            val before = fileCount(harness.root)
            for ((field, request) in invalidTypedRequests()) {
                val response = client.post("/api/v1/pages") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                withClue("field=$field request=$request response=${response.bodyAsText()}") {
                    response.status shouldBe HttpStatusCode.BadRequest
                    val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
                    error.getValue("code").jsonPrimitive.content shouldBe "invalid_create_request"
                    val message = error.getValue("message").jsonPrimitive.content
                    if (field != "decode") message shouldContain field
                    if (request.contains("\\uFFFE")) {
                        message shouldContain "ISO control characters"
                        message shouldContain "U+FFFE/U+FFFF"
                        message shouldContain "U+2028/U+2029"
                        message shouldContain "unpaired Unicode surrogates"
                    }
                    fileCount(harness.root) shouldBe before
                    harness.dirtyPages.all().isEmpty() shouldBe true
                }
            }
        }
    }

    test("invalid typed agent inputs cannot mint a page id or file a degraded proposal") {
        val root = Files.createTempDirectory("typed-invalid-agent")
        val search = Files.createTempDirectory("typed-invalid-search")
        try {
            SearchDb(search.resolve("search.db")).use { searchDb ->
                IndexHarness(root).use { harness ->
                    harness.builder.rebuild()
                    val ids = TestIdProvider()
                    val agent = Principal.Agent(harness.apiTokens.mint(label = "typed-agent", mode = AgentMode.COMMIT).id)
                    val context = harness.testRouteContext(
                        searchProvider = Fts5SearchProvider(searchDb), idProvider = ids,
                        enforced = true, extract = fixedPrincipal(agent), agentDirectCommitGlobs = emptyList(),
                    )
                    testApplication {
                        application { plainbaseModule(context) }
                        val reserved = listOf("Index", "LOG").flatMap { name ->
                            listOf("", "nested").flatMap { folder ->
                                listOf(typedRequest(title = name, slug = null, folder = folder), typedRequest(slug = name, folder = folder))
                            }
                        }
                        for (request in invalidTypedRequests().map { it.second } + reserved) {
                            val response = client.post("/api/v1/pages") {
                                contentType(ContentType.Application.Json)
                                setBody(request)
                            }
                            response.status shouldBe HttpStatusCode.BadRequest
                            Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
                                .getValue("code").jsonPrimitive.content shouldBe "invalid_create_request"
                        }
                    }
                    harness.proposalRepository.all().isEmpty() shouldBe true
                    harness.dirtyPages.all().isEmpty() shouldBe true
                    fileCount(root) shouldBe 0
                    ids.next() shouldBe PageId.require(firstId)
                }
            }
        } finally {
            root.toFile().deleteRecursively()
            search.toFile().deleteRecursively()
        }
    }

    test("escaped JSON surrogate pairs succeed and UTF-8 preserves astral metadata and body") {
        writeRestTest(Fixtures.demoDocs, idProvider = TestIdProvider()) { harness ->
            val response = client.post("/api/v1/pages") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"root":"docs","folder":"typed","title":"Pair \uD83D\uDE00","slug":"pair"""" +
                        ""","type":"Type \uD83D\uDE00","body":"\uD83D\uDE00\u2028\u2029"}""",
                )
            }
            response.status shouldBe HttpStatusCode.Created
            harness.diskBytes("typed/pair.md") shouldBe
                "---\nid: $firstId\ntype: \"Type 😀\"\ntitle: \"Pair 😀\"\nslug: \"pair\"\n---\n\n😀\u2028\u2029".toByteArray()
            independentHeaderType(harness.diskBytes("typed/pair.md")) shouldBe "Type 😀"
        }
    }

    test("typed composition is capped after the added line and accepts the exact byte boundary") {
        for (overflow in listOf(0, 1)) {
            writeRestTest(Fixtures.demoDocs, idProvider = TestIdProvider()) { harness ->
                val cap = harness.services.maxWriteBodyBytes.toInt()
                val header = "---\nid: $firstId\ntype: \"Reference\"\ntitle: \"Big\"\n---\n\n"
                val body = "x".repeat(cap - header.toByteArray().size + overflow)
                val request = typedRequest(title = "Big", slug = null, folder = "", body = body)
                (request.toByteArray().size <= cap) shouldBe true
                val before = fileCount(harness.root)
                val response = client.post("/api/v1/pages") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                if (overflow == 0) {
                    response.status shouldBe HttpStatusCode.Created
                    harness.diskBytes("big.md") shouldBe (header + body).toByteArray()
                } else {
                    response.status shouldBe HttpStatusCode.PayloadTooLarge
                    Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
                        .getValue("code").jsonPrimitive.content shouldBe "body_too_large"
                    fileCount(harness.root) shouldBe before
                }
            }
        }
    }

    test("independent oracle discriminates the legacy nonprintable output from valid typed composition") {
        for (character in listOf('\uFFFE', '\uFFFF')) {
            val legacy = composeDocument(firstId, "Bad $character", null, null)
            shouldThrowAny { independentHeader(legacy) }
            shouldThrowAny { composeDocument(firstId, "Bad $character", null, null, "Reference") }
        }
        independentHeaderType(composeDocument(firstId, "Good 😀", null, null, "Reference")) shouldBe "Reference"
    }
})

private fun typedRequest(
    title: String = "Safe", slug: String? = "safe", type: String = "Reference", body: String? = "body", folder: String = "typed",
): String = Json.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        put("root", "docs")
        if (folder.isNotEmpty()) put("folder", folder)
        put("title", title)
        if (slug != null) put("slug", slug)
        put("type", type)
        if (body != null) put("body", body)
    },
)

/** Lone surrogates use ASCII JSON escapes so they reach validation after strict UTF-8 decoding. */
private fun invalidTypedRequests(): List<Pair<String, String>> {
    val base = typedRequest()
    val invalid = mutableListOf<Pair<String, String>>()
    for ((field, original) in listOf("title" to "Safe", "slug" to "safe", "type" to "Reference")) {
        for (escape in listOf("\\u0000", "\\u0085", "\\uFFFE", "\\uFFFF", "\\u2028", "\\u2029", "\\uD800", "\\uDC00")) {
            invalid += field to base.replace("\"$field\":\"$original\"", "\"$field\":\"a${escape}b\"")
        }
    }
    for (escape in listOf("\\uD800", "\\uDC00")) {
        invalid += "body" to base.replace("\"body\":\"body\"", "\"body\":\"a${escape}b\"")
    }
    for (value in listOf("", " ", "\t\r\n", "\u2003")) {
        invalid += "type" to typedRequest(type = value)
    }
    for (kind in listOf("42", "true", "[]", "{}")) {
        invalid += "decode" to base.replace("\"type\":\"Reference\"", "\"type\":$kind")
    }
    return invalid
}

private fun fileCount(root: Path): Long = Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).count() }
