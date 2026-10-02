package com.plainbase.frameworks.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Tag("native")
class DiscussionRequestUnicodeNativeTest {
    @Test
    fun `escaped lone surrogates are refused and pairs retain UTF-8 bytes`() {
        listOf("""{"body":"\uD800"}""", """{"body":"\uDC00"}""", """{"\uD800":"x","body":"valid"}""")
            .forEach { text ->
                val invalid = assertFailsWith<DiscussionRequestInvalid> {
                    DiscussionRequestParser.validUnicode(RestJson.parseToJsonElement(text))
                }
                assertEquals("invalid_utf8", invalid.code)
            }
        val pair = RestJson.parseToJsonElement("""{"body":"\uD83D\uDE00"}""")
        DiscussionRequestParser.validUnicode(pair)
        assertEquals(4, DiscussionRequestParser.body(pair).encodeToByteArray().size)
    }

    @Test
    fun `MCP preflight validates decoded Unicode and depth before byte cap`() {
        val invalid = RestJson.parseToJsonElement("""{"body":"\uD800"}""")
        assertEquals(
            "invalid_utf8",
            assertFailsWith<DiscussionRequestInvalid> {
                DiscussionRequestParser.mcpPreflight(invalid, 1)
            }.code,
        )
        var deep: JsonElement = JsonPrimitive("safe")
        repeat(65) { deep = JsonArray(listOf(deep)) }
        assertEquals(
            "invalid_request_body",
            assertFailsWith<DiscussionRequestInvalid> {
                DiscussionRequestParser.mcpPreflight(deep, 1)
            }.code,
        )
        val paired = RestJson.parseToJsonElement("""{"body":"\uD83D\uDE00"}""")
        assertEquals(
            "body_too_large",
            assertFailsWith<DiscussionRequestInvalid> {
                DiscussionRequestParser.mcpPreflight(paired, 10)
            }.code,
        )
    }
}
