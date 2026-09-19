package com.plainbase.frameworks.protocol

import com.plainbase.domain.page.Citation
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.FrontmatterValue
import com.plainbase.domain.page.Heading
import com.plainbase.domain.service.PagePayload
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** §5.3 citation wire shape (frozen). */
@Serializable
data class CitationDto(
    @SerialName("page_id") val pageId: String,
    @SerialName("heading_id") val headingId: String?,
    val path: String,
    @SerialName("content_hash") val contentHash: String,
    val commit: String?,
    val uri: String,
)

/** `GET /api/v1/pages/{id}` and `by-path/{path}` wire shape (frozen, identical for both). */
@Serializable
data class PageResponse(
    val id: String,
    /** Additive amendment (ADR-0011 D3, multi-root C3): the page's root-name slug. */
    val root: String,
    val path: String,
    val slug: String,
    val url: String?,
    val title: String,
    val markdown: String,
    val frontmatter: JsonObject,
    @SerialName("content_hash") val contentHash: String,
    @SerialName("id_materialized") val idMaterialized: Boolean,
    val commit: String?,
    val citation: CitationDto,
)

@Serializable
data class HeadingDto(val id: String, val level: Int, val text: String)

// ---- domain -> DTO mapping (the only place domain types meet the wire shapes) -----------------

fun PagePayload.toDto(): PageResponse = PageResponse(
    id = page.id.value,
    root = page.root.value,
    path = page.path.value,
    slug = page.slug,
    url = page.url,
    title = page.title,
    markdown = page.markdown,
    frontmatter = page.frontmatter.toJsonObject(idMaterialized = page.materialized),
    contentHash = page.contentHash,
    idMaterialized = page.materialized,
    commit = page.commit, // the page's snapshot-resident last commit; null off Git
    citation = citation.toDto(),
)

fun Citation.toDto(): CitationDto = CitationDto(
    pageId = pageId.value,
    headingId = headingId,
    path = path.value,
    contentHash = contentHash,
    commit = commit,
    uri = uri,
)

fun Heading.toDto(): HeadingDto = HeadingDto(id = id, level = level, text = text)

fun Frontmatter.toJsonObject(idMaterialized: Boolean): JsonObject = buildJsonObject {
    for ((key, value) in values) {
        if (key == "id" && !idMaterialized) continue
        when (value) {
            is FrontmatterValue.Scalar -> put(key, value.value)
            is FrontmatterValue.StringList -> put(key, JsonArray(value.values.map(::JsonPrimitive)))
        }
    }
}
