package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.ktor.dto.BrokenLinkDto
import com.plainbase.frameworks.ktor.dto.HeadingDto
import com.plainbase.frameworks.ktor.dto.PageMetadataResponse
import com.plainbase.frameworks.ktor.dto.RestJson
import com.plainbase.frameworks.ktor.dto.ValidateLinksResponse
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PB-READ-2 native gate (P2): the closed-world image would otherwise never compile the agent-read DTO serializers.
 * Proves the two NET-NEW response DTOs ([ValidateLinksResponse], [PageMetadataResponse]) encode+decode round-trip
 * through the scoped [RestJson] (manual serialization, NOT content-negotiation — so NO reflect-config triple is
 * needed, the ProposalDto/AuthDto idiom). `read_file` adds no DTO — `PageResponse` already has a shipped native
 * round-trip.
 *
 * @Tag("native") + kotlin.test only.
 */
@Tag("native")
class ReadDtoNativeTest {

    private fun <T> assertRoundTrips(serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        assertEquals(value, RestJson.decodeFromString(serializer, RestJson.encodeToString(serializer, value)))
    }

    @Test
    fun `the PB-READ-2 response DTOs ENCODE+DECODE round-trip through RestJson natively`() {
        val validateLinks = ValidateLinksResponse(
            broken = listOf(
                BrokenLinkDto(page = "guides/a.md", target = "./gone.md", text = "gone", reason = "broken_missing"),
                BrokenLinkDto(page = "guides/a.md", target = "#nope", text = "top", reason = "broken_anchor"),
            ),
        )
        assertRoundTrips(ValidateLinksResponse.serializer(), validateLinks)

        val metadata = PageMetadataResponse(
            id = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a",
            path = "guides/a.md",
            url = "/docs/guides/a",
            permalink = "/p/0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a",
            contentHash = "sha256:${"0".repeat(64)}",
            commit = null,
            title = "A",
            headings = listOf(HeadingDto(id = "intro", level = 1, text = "Intro")),
        )
        assertRoundTrips(PageMetadataResponse.serializer(), metadata)
    }
}
