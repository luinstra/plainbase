package com.plainbase.frameworks.protocol

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
}
