package com.plainbase.frameworks.ktor

import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.Permalink
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.frameworks.ktor.dto.RootTreeDto
import com.plainbase.frameworks.ktor.dto.TreeNodeDto
import com.plainbase.frameworks.protocol.BrokenLinkDto
import com.plainbase.frameworks.protocol.HeadingDto
import com.plainbase.frameworks.protocol.PageMetadataResponse
import com.plainbase.frameworks.protocol.RestJson
import com.plainbase.frameworks.protocol.ValidateLinksResponse
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun `tree discussion false round-trips and default true stays omitted in native`() {
        val tree = TreeNodeDto.Folder("", null, null, "", "/docs", 0, emptyList())
        val root = RootTreeDto("docs", available = true, editable = true, primary = true, tree = tree)
        val enabled = RestJson.encodeToString(RootTreeDto.serializer(), root)
        assertFalse(enabled.contains("discussionsEnabled"))
        assertTrue(RestJson.decodeFromString(RootTreeDto.serializer(), enabled).discussionsEnabled)
        val disabled = RestJson.encodeToString(RootTreeDto.serializer(), root.copy(discussionsEnabled = false))
        assertTrue(disabled.contains("\"discussionsEnabled\":false"))
        assertRoundTrips(RootTreeDto.serializer(), root.copy(discussionsEnabled = false))
    }

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
            root = "docs",
            path = "guides/a.md",
            url = "/docs/guides/a",
            permalink = "/p/docs/0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a",
            contentHash = "sha256:${"0".repeat(64)}",
            commit = null,
            title = "A",
            headings = listOf(HeadingDto(id = "intro", level = 1, text = "Intro")),
        )
        assertRoundTrips(PageMetadataResponse.serializer(), metadata)
    }

    @Test
    fun `the PRODUCTION permalink emitter is rooted in-image`() {
        // The emission proof (R8): the ONE definition of a permalink is rooted (per-root identity, C5). Backing
        // `Permalink.of` out to the bare `/p/{id}` form reds BOTH assertions; the hand-built literal above does not.
        val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
        assertEquals("/p/docs/${id.value}", Permalink.of(RootName.PRIMARY, id))
        assertEquals("/p/docs/${id.value}", RootedPageId(RootName.PRIMARY, id).permalink)
    }
}
