package com.plainbase.frameworks.mcp

import com.plainbase.frameworks.protocol.ListChangesResponse
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val INTERNAL_ERROR_BODY = "{\"error\":{\"code\":\"internal\",\"message\":\"Internal error\"}}"

private class NativeContainmentFailure : IllegalStateException("native containment sentinel")

/** The lean in-image companion to the JVM/SSE characterization; no fixture or duplicate network harness. */
@Tag("native")
class McpErrorContainmentNativeTest {

    @Test
    fun `toolResult contains failures and still encodes direct success in the native image`() {
        val failure = toolResult(ListChangesResponse.serializer()) { throw NativeContainmentFailure() }
        assertEquals(1, failure.content.size)
        assertTrue(failure.isError == true)
        assertTrue(failure.content.single() is TextContent)
        assertEquals(INTERNAL_ERROR_BODY, (failure.content.single() as TextContent).text)

        val success = toolResult(ListChangesResponse.serializer()) { ListChangesResponse(emptyList()) }
        assertEquals(1, success.content.size)
        assertTrue(success.isError != true)
        assertEquals("{\"proposals\":[]}", (success.content.single() as TextContent).text)
    }
}
