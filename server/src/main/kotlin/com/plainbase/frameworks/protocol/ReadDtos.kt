package com.plainbase.frameworks.protocol

import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.service.BrokenLink
import com.plainbase.domain.service.LinkReport
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * PB-READ-2 (Phase 5, chunk P2) — the FROZEN agent-read wire shapes for `validate_links`
 * (`GET /api/v1/pages/{id}/validate-links`) and `get_page_metadata` (`GET /api/v1/pages/{id}/metadata`).
 * Manual-`RestJson` DTOs (snake_case `@SerialName`), encoded/decoded through the shared protocol serializer.
 *
 * `read_file` adds NO shape here: it is an op-NAME that maps to the EXISTING `read_page` op
 * (`GET /api/v1/pages/{id}` → frozen PB-REST-1 `PageResponse.markdown`, the verbatim on-disk file incl.
 * frontmatter). PB-READ-2 is exactly the two response shapes below.
 */

/** `GET /api/v1/pages/{id}/validate-links` — the page's broken links + anchors (PB-READ-2, frozen). */
@Serializable
data class ValidateLinksResponse(val broken: List<BrokenLinkDto>)

/** One broken link/anchor on the page, mapped from the domain without re-listing its reason vocabulary. */
@Serializable
data class BrokenLinkDto(
    val page: String,
    val target: String,
    val text: String,
    val reason: String,
)

/** `GET /api/v1/pages/{id}/metadata` — the server-derived metadata projection (PB-READ-2, frozen). */
@Serializable
data class PageMetadataResponse(
    val id: String,
    /** Additive amendment (ADR-0011 D3, multi-root C3): the page's root-name slug. */
    val root: String,
    val path: String,
    val url: String?,
    @SerialName("permalink") val permalink: String,
    @SerialName("content_hash") val contentHash: String,
    val commit: String?,
    val title: String,
    val headings: List<HeadingDto>,
)

fun LinkReport.toDto(): ValidateLinksResponse = ValidateLinksResponse(broken = broken.map { it.toDto() })

fun BrokenLink.toDto(): BrokenLinkDto =
    BrokenLinkDto(page = page.path.value, target = target, text = text, reason = reason.wireValue)

fun IndexedPage.toMetadataDto(): PageMetadataResponse = PageMetadataResponse(
    id = id.value,
    root = root.value,
    path = path.value,
    url = url,
    permalink = permalink,
    contentHash = contentHash,
    commit = commit,
    title = title,
    headings = headings.map { it.toDto() },
)
