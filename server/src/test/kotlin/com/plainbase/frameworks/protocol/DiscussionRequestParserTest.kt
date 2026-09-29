package com.plainbase.frameworks.protocol

import com.plainbase.domain.discussion.SelectionRequest
import com.plainbase.domain.service.DiscussionAnchorRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DiscussionRequestParserTest : FunSpec({
    val hash = "sha256:" + "a".repeat(64)
    val id = "01900000-0000-7000-8000-000000000001"

    fun json(value: String): JsonElement = RestJson.parseToJsonElement(value)
    fun refusal(value: String, parse: (JsonElement) -> Any): DiscussionRequestInvalid =
        shouldThrow<DiscussionRequestInvalid> { parse(json(value)) }

    test("should reject structural fields before selection size") {
        val oversized = "q".repeat(16_385)
        val result = refusal("""{"anchor":{"kind":"quote","content_hash":"$hash","selected_text":"$oversized"},"body":1}""") {
            DiscussionRequestParser.start(it)
        }
        result.status shouldBe 400
        result.code shouldBe "invalid_request_body"
    }

    test("should parse one effective JSON value and preserve comment bytes") {
        val request = DiscussionRequestParser.start(
            json("""{"anchor":{"kind":"page","content_hash":"$hash"},"body":"old","body":"  é 😀  "}"""),
        )
        request.body shouldBe "  é 😀  "
        request.anchor shouldBe DiscussionAnchorRequest.Page(hash)
    }

    test("should reject an already decoded deeply nested tree with a typed refusal") {
        var boundary: JsonElement = JsonPrimitive("safe")
        repeat(64) { boundary = JsonArray(listOf(boundary)) }
        DiscussionRequestParser.validUnicode(boundary)
        val boundaryRefusal = shouldThrow<DiscussionRequestInvalid> {
            DiscussionRequestParser.validUnicode(JsonArray(listOf(boundary)))
        }
        boundaryRefusal.status shouldBe 400
        boundaryRefusal.code shouldBe "invalid_request_body"

        var tree: JsonElement = JsonPrimitive("safe")
        repeat(12_000) { tree = JsonArray(listOf(tree)) }
        val refusal = shouldThrow<DiscussionRequestInvalid> { DiscussionRequestParser.validUnicode(tree) }
        refusal.status shouldBe 400
        refusal.code shouldBe "invalid_request_body"
    }

    test("should count only JSON containers outside quoted strings") {
        DiscussionRequestParser.requireSafeJsonNesting("[".repeat(64) + "0" + "]".repeat(64))
        val refusal = shouldThrow<DiscussionRequestInvalid> {
            DiscussionRequestParser.requireSafeJsonNesting("[".repeat(65) + "0" + "]".repeat(65))
        }
        refusal.status shouldBe 400
        refusal.code shouldBe "invalid_request_body"
        DiscussionRequestParser.requireSafeJsonNesting("""{"body":"${"{}[]".repeat(100)} \" \\ \u005b"}""")

        val escapedBackslashPrefix = """["\\","""
        json(escapedBackslashPrefix + "[0]]")
        val deepAfterQuote = escapedBackslashPrefix + "[".repeat(64) + "0" + "]".repeat(65)
        val escapedRefusal = shouldThrow<DiscussionRequestInvalid> {
            DiscussionRequestParser.requireSafeJsonNesting(deepAfterQuote)
        }
        escapedRefusal.status shouldBe 400
        escapedRefusal.code shouldBe "invalid_request_body"
    }

    test("should accept only exact anchor forms and bounded decimal offsets") {
        val quote = """{"kind":"quote","content_hash":"$hash","selected_text":"text","block_start":0,"block_end":9999999999}"""
        val parsed = DiscussionRequestParser.preview(json(quote))
        parsed.selection shouldBe SelectionRequest.Spa(0, 9_999_999_999, "text")
        listOf("-1", "+1", "1.0", "1e1", "10000000000", "\"1\"").forEach { bad ->
            refusal(quote.replace("\"block_start\":0", "\"block_start\":$bad")) { DiscussionRequestParser.preview(it) }
                .code shouldBe "invalid_request_body"
        }
        refusal(quote.replace("\"block_end\":9999999999", "\"block_end\":0")) { DiscussionRequestParser.preview(it) }
            .code shouldBe "invalid_request_body"
        refusal(quote.replace("\"selected_text\":\"text\",", "")) { DiscussionRequestParser.preview(it) }
            .code shouldBe "invalid_request_body"
        refusal("""{"kind":"page","content_hash":"$hash"}""") { DiscussionRequestParser.preview(it) }
            .code shouldBe "invalid_request_body"
    }

    test("should reject client commit and stored capture fields") {
        val request = """{"anchor":{"kind":"page","content_hash":"$hash","commit":"deadbeef"},"body":"hello"}"""
        refusal(request) { DiscussionRequestParser.start(it) }.code shouldBe "invalid_request_body"
        refusal("""{"anchor":{"kind":"page","content_hash":"$hash"},"body":"hello","commit":"bad"}""") {
            DiscussionRequestParser.start(it)
        }.code shouldBe "invalid_request_body"
    }

    test("should enforce comment byte bounds without altering content") {
        DiscussionRequestParser.body(json("""{"body":"${"😀".repeat(16_384)}"}"""))
            .encodeToByteArray().size shouldBe 65_536
        refusal("""{"body":"${"😀".repeat(16_385)}"}""") { DiscussionRequestParser.body(it) }
            .code shouldBe "comment_too_large"
        refusal("""{"body":"  \n  "}""") { DiscussionRequestParser.body(it) }
            .code shouldBe "comment_empty"
    }

    test("should parse IDs, cursors and query limits with strict token grammar") {
        DiscussionRequestParser.discussionId(id).value shouldBe id
        DiscussionRequestParser.pageId(id.uppercase()).value shouldBe id
        DiscussionRequestParser.listQuery(mapOf("limit" to listOf("0002"), "cursor" to listOf(id)), false)
            .limit shouldBe 2
        listOf("", "0", "201", "-1", "+1", "1.0", "1e1", "2147483648").forEach { bad ->
            shouldThrow<DiscussionRequestInvalid> {
                DiscussionRequestParser.listQuery(mapOf("limit" to listOf(bad)), false)
            }.code shouldBe "invalid_query"
        }
        shouldThrow<DiscussionRequestInvalid> {
            DiscussionRequestParser.listQuery(mapOf("state" to listOf("exact")), false)
        }.code shouldBe "invalid_query"
        shouldThrow<DiscussionRequestInvalid> {
            DiscussionRequestParser.listQuery(mapOf("future" to listOf("a", "b")), false)
        }.code shouldBe "invalid_query"
        shouldThrow<DiscussionRequestInvalid> {
            DiscussionRequestParser.detailQuery(mapOf("cursor" to listOf("")))
        }.code shouldBe "invalid_query"
        shouldThrow<DiscussionRequestInvalid> {
            DiscussionRequestParser.detailQuery(mapOf("state" to listOf("exact")))
        }.code shouldBe "invalid_query"
        shouldThrow<DiscussionRequestInvalid> {
            DiscussionRequestParser.rootOnlyQuery(mapOf("limit" to listOf("1")))
        }.code shouldBe "invalid_query"
    }

    test("should reject wrong field types hashes and anchor shapes") {
        listOf(
            """{"anchor":{"kind":"page","content_hash":"sha256:${"A".repeat(64)}"},"body":"hello"}""",
            """{"anchor":{"kind":"page","content_hash":"$hash","selected_text":"extra"},"body":"hello"}""",
            """{"anchor":{"kind":"quote","content_hash":"$hash","selected_text":7},"body":"hello"}""",
            """{"anchor":{"kind":"quote","content_hash":"$hash","selected_text":"x","block_start":0},"body":"hello"}""",
            """{"anchor":{"kind":"page","content_hash":"$hash"},"body":true}""",
            """{"anchor":{"kind":"page","content_hash":"$hash"},"body":null}""",
            """{"anchor":{"kind":"page","content_hash":"$hash"},"body":"hello","root":"docs"}""",
        ).forEach { bad ->
            refusal(bad) { DiscussionRequestParser.start(it) }.code shouldBe "invalid_request_body"
        }
        refusal("""{"anchor":{"kind":"page","content_hash":"$hash"}}""") {
            DiscussionRequestParser.reattach(it)
        }.code shouldBe "invalid_request_body"
        listOf("[]", "null", "1", """{"reason":"x"}""").forEach { bad ->
            refusal(bad) { DiscussionRequestParser.empty(it) }.code shouldBe "invalid_request_body"
        }
        DiscussionRequestParser.empty(json("{}"))
        DiscussionRequestParser.empty(null)
    }

    test("should enforce quote UTF-8 bytes and preserve paired surrogate text") {
        val selected = "😀".repeat(4_096)
        val parsed = DiscussionRequestParser.preview(
            json("""{"kind":"quote","content_hash":"$hash","selected_text":"$selected"}"""),
        )
        parsed.selection shouldBe SelectionRequest.Agent(selected)
        refusal("""{"kind":"quote","content_hash":"$hash","selected_text":"${selected}x"}""") {
            DiscussionRequestParser.preview(it)
        }.code shouldBe "anchor_too_large"
        val pair = json("""{"body":"\uD83D\uDE00"}""")
        DiscussionRequestParser.validUnicode(pair)
        DiscussionRequestParser.body(pair) shouldBe "😀"
        listOf("""{"body":"\uD800"}""", """{"body":"\uDC00"}""").forEach { bad ->
            shouldThrow<DiscussionRequestInvalid> {
                DiscussionRequestParser.validUnicode(json(bad))
            }.code shouldBe "invalid_utf8"
        }
    }

    test("should reject malformed canonical IDs and query duplicates") {
        listOf("", "01900000-0000-7000-8000-00000000000A", "01900000-0000-4000-8000-000000000001", "not-an-id")
            .forEach { bad ->
            shouldThrow<DiscussionRequestInvalid> { DiscussionRequestParser.discussionId(bad) }
                .code shouldBe "invalid_request_body"
        }
        shouldThrow<DiscussionRequestInvalid> { DiscussionRequestParser.pageId("not-an-id") }
            .code shouldBe "invalid_page_id"
        listOf(
            mapOf("cursor" to listOf("not-an-id")),
            mapOf("state" to listOf("invalid")),
            mapOf("limit" to listOf("51")),
            mapOf("cursor" to listOf(id, id)),
        ).forEach { query ->
            shouldThrow<DiscussionRequestInvalid> { DiscussionRequestParser.listQuery(query, true) }
                .code shouldBe "invalid_query"
        }
        DiscussionRequestParser.listQuery(mapOf("state" to listOf("incomplete"), "future" to listOf("x")), true)
            .state shouldBe "incomplete"
    }

    test("should select MCP list mode and enforce required strings and query types") {
        val page = DiscussionRequestParser.mcpList(json("""{"page_id":"$id","limit":1}"""))
        page shouldBe DiscussionListArguments.Page(DiscussionRequestParser.pageId(id), null, DiscussionQuery(null, 1, null))
        val root = DiscussionRequestParser.mcpList(json("""{"root":"docs","state":"exact","limit":1}"""))
        root shouldBe DiscussionListArguments.Root(com.plainbase.domain.root.RootName.require("docs"), DiscussionQuery(null, 1, "exact"))
        listOf("{}", """{"page_id":null,"root":"docs"}""", """{"page_id":12,"root":"docs"}""").forEach { bad ->
            refusal(bad, DiscussionRequestParser::mcpList).code shouldBe
                if (bad == "{}") "invalid_root" else "invalid_request_body"
        }
        listOf(
            """{"page_id":"$id","state":"exact"}""", """{"root":"docs","limit":"1"}""",
            """{"root":"docs","limit":1e0}""", """{"root":"docs","cursor":null}""",
        ).forEach { bad ->
            refusal(bad, DiscussionRequestParser::mcpList).code shouldBe "invalid_query"
        }
        refusal("""{"root":"docs","future":1}""", DiscussionRequestParser::mcpList).code shouldBe "invalid_request_body"
    }

    test("should preflight MCP Unicode depth and serialized byte cap before field grammar") {
        val lone = buildJsonObject { put("\uD800", "bad") }
        shouldThrow<DiscussionRequestInvalid> { DiscussionRequestParser.mcpPreflight(lone, 524_288) }.code shouldBe "invalid_utf8"
        val oversized = json("""{"body":"${"😀".repeat(50)}"}""")
        shouldThrow<DiscussionRequestInvalid> { DiscussionRequestParser.mcpPreflight(oversized, 20) }.code shouldBe "body_too_large"
        var deep: JsonElement = JsonPrimitive("safe")
        repeat(65) { deep = JsonArray(listOf(deep)) }
        shouldThrow<DiscussionRequestInvalid> { DiscussionRequestParser.mcpPreflight(deep, 1) }.code shouldBe
            "invalid_request_body"
    }

    test("should accept only four closed MCP argument forms") {
        val start = DiscussionRequestParser.mcpStart(
            json("""{"page_id":"$id","root":"docs","anchor":{"kind":"page","content_hash":"$hash"},"body":"  hi  "}"""),
        )
        start.body shouldBe "  hi  "
        start.anchor shouldBe DiscussionAnchorRequest.Page(hash)
        DiscussionRequestParser.mcpComment(json("""{"id":"$id","body":"hello"}""")).body shouldBe "hello"
        listOf(
            """{"id":null,"body":"hello"}""", """{"id":"$id","body":null}""",
            """{"id":"$id","body":3}""",
        ).forEach { bad ->
            refusal(bad, DiscussionRequestParser::mcpComment).code shouldBe "invalid_request_body"
        }
        refusal("""{"page_id":"$id","anchor":{"kind":"page","content_hash":"$hash"},"body":"x","commit":"x"}""") {
            DiscussionRequestParser.mcpStart(it)
        }.code shouldBe "invalid_request_body"
        refusal("""{"page_id":"bad","anchor":{"kind":"page","content_hash":"$hash"},"body":null}""") {
            DiscussionRequestParser.mcpStart(it)
        }.code shouldBe "invalid_request_body"
    }
})
