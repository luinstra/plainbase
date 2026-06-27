package com.plainbase.frameworks.ktor.dto

import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.service.BrokenLink
import com.plainbase.domain.service.LinkReport
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * PB-READ-2 (Phase 5, chunk P2) — the FROZEN agent-read wire shapes for `validate_links`
 * (`GET /api/v1/pages/{id}/validate-links`) and `get_page_metadata` (`GET /api/v1/pages/{id}/metadata`).
 * Manual-`RestJson` DTOs (snake_case `@SerialName`), encoded/decoded through the scoped [RestJson] exactly like
 * PB-REST-1's [PageResponse] and PB-PROPOSE-1's proposal DTOs — NEVER content-negotiation, so NO reflect-config
 * triple is needed (the native round-trip test proves it, the AuthDto/ProposalDto idiom).
 *
 * `read_file` adds NO shape here: it is an op-NAME that maps to the EXISTING `read_page` op
 * (`GET /api/v1/pages/{id}` → frozen PB-REST-1 `PageResponse.markdown`, the verbatim on-disk file incl.
 * frontmatter). PB-READ-2 is exactly the two response shapes below.
 *
 * ============================== NEVER-CHANGE POLICY ==============================
 * These shapes froze when P2 landed. They are append-only: a field is never removed or retyped; the
 * `BrokenLinkReason` wire vocabulary only grows (the closed PB-LINK-1 set + `broken_anchor`). The frozen
 * `PageMetadataResponse` fields are `id`/`path`/`url` (nullable)/`permalink` (always non-null, the `/p/{id}` ID
 * permalink)/`content_hash`/`commit` (nullable)/`title`/`headings`. P3 MCP re-exposes
 * these VERBATIM (its `validate_links`/`get_page_metadata` tools delegate to the SAME `ReadFacade` methods and
 * serialize these SAME DTOs) — a shape change is a contract break across BOTH REST and MCP. The shapes are pinned
 * by the PB-READ-2 forever-goldens (see `ForeverApiGoldenSuite.kt`) + the native round-trip.
 * =================================================================================
 */

/** `GET /api/v1/pages/{id}/validate-links` — the page's broken links + anchors (PB-READ-2, frozen). */
@Serializable
data class ValidateLinksResponse(val broken: List<BrokenLinkDto>)

/**
 * One broken link/anchor on the page. [reason] is EXACTLY a [com.plainbase.domain.service.BrokenLinkReason]`
 * .wireValue` (the append-only PB-LINK-1 set `broken_missing`/`broken_case_mismatch`/`broken_malformed`/
 * `outside_content_root`/`ambiguous`/`blocked_scheme` plus the checker's `broken_anchor`) — mapped from the domain,
 * never re-listed as literals here.
 */
@Serializable
data class BrokenLinkDto(
    // The page path. Under the per-page filter (D-A) this is CONSTANT across every row of one response, so it
    // conveys nothing within a single response — but it is frozen in DELIBERATELY (review MINOR #2): P3/MCP re-expose
    // the SAME DTO, and a future whole-tree `validate_links` variant (a separate, additive endpoint) WOULD vary
    // `page` per row. Keeping it makes the shape forward-compatible without a contract break, and self-documents
    // which page each broken link belongs to. Removing it is a PB-READ-2 break across BOTH REST and MCP — never do it.
    val page: String,
    val target: String,
    val text: String,
    val reason: String,
)

/** `GET /api/v1/pages/{id}/metadata` — the page's server-derived metadata projection (PB-READ-2, frozen). */
@Serializable
data class PageMetadataResponse(
    val id: String,
    val path: String,
    val url: String?, // present-null for a path-collision loser (IndexedPage.url)
    @SerialName("permalink") val permalink: String, // always NON-null — the /p/{id} ID permalink (IndexedPage.permalink)
    @SerialName("content_hash") val contentHash: String,
    val commit: String?, // present-null off Git / for an uncommitted page
    val title: String,
    val headings: List<HeadingDto>, // REUSE the frozen HeadingDto (RestDtos.kt), document order
)

// ---- domain -> DTO mapping (the only place the read domain types meet the wire shapes) ----------

fun LinkReport.toDto(): ValidateLinksResponse = ValidateLinksResponse(broken = broken.map { it.toDto() })

fun BrokenLink.toDto(): BrokenLinkDto =
    BrokenLinkDto(page = page.value, target = target, text = text, reason = reason.wireValue)

fun IndexedPage.toMetadataDto(): PageMetadataResponse = PageMetadataResponse(
    id = id.value,
    path = path.value,
    url = url,
    permalink = permalink,
    contentHash = contentHash,
    commit = commit,
    title = title,
    headings = headings.map { it.toDto() },
)
