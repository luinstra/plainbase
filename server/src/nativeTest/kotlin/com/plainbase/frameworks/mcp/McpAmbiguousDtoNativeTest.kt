package com.plainbase.frameworks.mcp

import com.plainbase.frameworks.protocol.RestJson
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/** The ambiguity response preserves its flat root/id retry candidates on the wire. */
@Tag("native")
class McpAmbiguousDtoNativeTest {

    @Test
    fun `ambiguity response preserves its literal wire bytes and round trips`() {
        val value = McpAmbiguousResponse(
            code = "ambiguous_page_id",
            id = "page-id",
            candidates = listOf(
                McpAmbiguousCandidate(root = "docs", id = "page-id"),
                McpAmbiguousCandidate(root = "archive", id = "page-id"),
            ),
            message = "Choose a root",
        )
        val encoded = RestJson.encodeToString(McpAmbiguousResponse.serializer(), value)

        assertEquals(
            "{\"code\":\"ambiguous_page_id\",\"id\":\"page-id\",\"candidates\":[{" +
                "\"root\":\"docs\",\"id\":\"page-id\"},{\"root\":\"archive\",\"id\":\"page-id\"}]," +
                "\"message\":\"Choose a root\"}",
            encoded,
        )
        assertEquals(value, RestJson.decodeFromString(McpAmbiguousResponse.serializer(), encoded))
    }
}
