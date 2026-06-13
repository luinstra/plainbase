package com.plainbase.frameworks.ktor.dto

import com.plainbase.domain.search.Highlight
import com.plainbase.domain.service.SearchHitPayload
import com.plainbase.domain.service.SearchPayload
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /api/v1/search` wire shape (PB-SEARCH-1 §A2, FROZEN — golden snapshots, parsed-tree
 * comparison; `score` VALUES and snippet content selection are the deliberate non-frozen ledger,
 * §A4). Encodes through the scoped [RestJson] like every frozen shape: the §A2 nullable fields
 * (`url`, `heading_id`, `heading_text`, `commit`) serialize present-and-`null`.
 */
@Serializable
data class SearchResponse(
    val query: String,
    val engine: String,
    val limit: Int,
    val offset: Int,
    val total: Long,
    val hits: List<SearchHitDto>,
)

@Serializable
data class SearchHitDto(
    @SerialName("page_id") val pageId: String,
    val path: String,
    val url: String?,
    val title: String,
    @SerialName("heading_id") val headingId: String?,
    @SerialName("heading_text") val headingText: String?,
    @SerialName("heading_path") val headingPath: List<String>,
    val snippet: String,
    val highlights: List<HighlightDto>,
    val score: Double,
    val citation: CitationDto,
)

/** §A3: UTF-16 code-unit offsets into `snippet`, half-open `[start, end)`. */
@Serializable
data class HighlightDto(val start: Int, val end: Int)

fun SearchPayload.toDto(): SearchResponse = SearchResponse(
    query = query,
    engine = engine,
    limit = limit,
    offset = offset,
    total = total,
    hits = hits.map { it.toDto() },
)

fun SearchHitPayload.toDto(): SearchHitDto = SearchHitDto(
    pageId = pageId.value,
    path = path.value,
    url = url,
    title = title,
    headingId = headingId,
    headingText = headingText,
    headingPath = headingPath,
    snippet = snippet,
    highlights = highlights.map { it.toDto() },
    score = score,
    citation = citation.toDto(),
)

fun Highlight.toDto(): HighlightDto = HighlightDto(start = start, end = end)
