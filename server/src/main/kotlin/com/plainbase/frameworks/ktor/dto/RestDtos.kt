package com.plainbase.frameworks.ktor.dto

import com.plainbase.domain.page.Citation
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.FrontmatterValue
import com.plainbase.domain.page.Heading
import com.plainbase.domain.service.PageHtmlPayload
import com.plainbase.domain.service.PagePayload
import com.plainbase.domain.service.TreeNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The serializer for the PB-REST-1 response DTOs in this file — and ONLY these (§A4): the frozen
 * Phase-1 shapes guarantee present-`null` for nullable fields (`url`, `title`, `commit`,
 * `heading_id`), so `explicitNulls = true` here. Deliberately NOT the app-wide ContentNegotiation
 * policy — responses built from these DTOs bypass content negotiation and encode through this
 * instance, so the guarantee is scoped to the documented shapes.
 */
val RestJson: Json = Json {
    explicitNulls = true
}

/** The frozen error-code vocabulary (§A4, append-only). */
object ErrorCodes {
    /** 404: a canonical-shape-valid id (any version) absent from the index, or an unknown by-path. */
    const val PAGE_NOT_FOUND: String = "page_not_found"

    /** 400: an id failing the §A4 canonical-shape regex (the regex decides, never JDK leniency). */
    const val INVALID_PAGE_ID: String = "invalid_page_id"

    /** 404: an unknown asset or browse path, or an unknown `/api/...` endpoint (never the SPA shell). */
    const val NOT_FOUND: String = "not_found"

    /** 400: a traversal attempt or malformed percent-encoding in a path. */
    const val INVALID_PATH: String = "invalid_path"

    /** 500: an uncaught server error (appended to the vocabulary; codes are append-only). */
    const val INTERNAL_ERROR: String = "internal_error"
}

/** The uniform error envelope (§A4, frozen): `{"error":{"code":…,"message":…}}`. */
@Serializable
data class ErrorEnvelope(val error: ErrorBody)

@Serializable
data class ErrorBody(val code: String, val message: String)

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

/** `GET /api/v1/pages/{id}/html` wire shape (frozen; the `html` markup itself is non-frozen). */
@Serializable
data class PageHtmlResponse(
    val id: String,
    val path: String,
    val slug: String,
    val url: String?,
    val title: String,
    val html: String,
    @SerialName("content_hash") val contentHash: String,
    val commit: String?,
    val headings: List<HeadingDto>,
    val citation: CitationDto,
)

@Serializable
data class HeadingDto(val id: String, val level: Int, val text: String)

/**
 * `GET /api/v1/tree` wire shape (frozen as SHAPE; child ordering is documented-not-frozen).
 * [root] is always a folder node, but it is DECLARED as the sealed interface so the polymorphic
 * serializer emits the `type` discriminator on the root exactly like on every child.
 */
@Serializable
data class TreeResponse(val root: TreeNodeDto)

/** A tree node; the `type` discriminator (`folder`/`page`) comes from the sealed serializer. */
@Serializable
sealed interface TreeNodeDto {

    @Serializable
    @SerialName("folder")
    data class Folder(
        val name: String,
        val title: String?,
        val path: String,
        /** Additive amendment (ADR-0003): the folder's `/docs` URL prefix; null for a collision-loser subtree. */
        val url: String?,
        val children: List<TreeNodeDto>,
    ) : TreeNodeDto

    @Serializable
    @SerialName("page")
    data class Page(
        val id: String,
        val title: String,
        val slug: String,
        val path: String,
        val url: String?,
        val status: String,
    ) : TreeNodeDto
}

/** `POST /api/v1/admin/rescan` response — a §C4 convenience, NOT a frozen PB-REST-1 shape. */
@Serializable
data class RescanResponse(val status: String, val pages: Int)

// ---- domain -> DTO mapping (the only place domain types meet the wire shapes) -----------------

fun PagePayload.toDto(): PageResponse = PageResponse(
    id = page.id.value,
    path = page.path.value,
    slug = page.slug,
    url = page.url,
    title = page.title,
    markdown = page.markdown,
    frontmatter = page.frontmatter.toJsonObject(idMaterialized = page.materialized),
    contentHash = page.contentHash,
    idMaterialized = page.materialized,
    commit = null, // always null in Phase 1 (Git layer is Phase 3); field present from day one
    citation = citation.toDto(),
)

fun PageHtmlPayload.toDto(): PageHtmlResponse = PageHtmlResponse(
    id = page.id.value,
    path = page.path.value,
    slug = page.slug,
    url = page.url,
    title = page.title,
    html = page.html,
    contentHash = page.contentHash,
    commit = null,
    headings = page.headings.map { it.toDto() },
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

fun TreeNode.Folder.toDto(): TreeNodeDto.Folder = TreeNodeDto.Folder(
    name = name,
    title = title,
    // The synthetic root's domain path is null; the frozen wire shape spells it "" (§A4 example).
    path = path?.value ?: "",
    url = url,
    children = children.map { child ->
        when (child) {
            is TreeNode.Folder -> child.toDto()
            is TreeNode.Page -> child.toDto()
        }
    },
)

fun TreeNode.Page.toDto(): TreeNodeDto.Page = TreeNodeDto.Page(
    id = id.value,
    title = title,
    slug = slug,
    path = path.value,
    url = url,
    status = status,
)

/**
 * The §C2 frontmatter subset as the wire `frontmatter` object: scalars and arrays-of-strings.
 *
 * The `id` key is OMITTED unless [idMaterialized] — the frozen §A4 sentence (plan line 353):
 * "`id` appears here only when materialized." An in-file id the indexer REJECTED (someone else's
 * claim, shape-invalid) must not echo through the structured view as if it were the page's
 * identity; the verbatim block stays fully visible in `markdown`, so raw truth is preserved.
 * Omission is the conservative direction — relaxable later, while a garbage echo, once relied
 * on, isn't retractable.
 */
fun Frontmatter.toJsonObject(idMaterialized: Boolean): JsonObject = buildJsonObject {
    for ((key, value) in values) {
        if (key == "id" && !idMaterialized) continue
        when (value) {
            is FrontmatterValue.Scalar -> put(key, value.value)
            is FrontmatterValue.StringList -> put(key, JsonArray(value.values.map(::JsonPrimitive)))
        }
    }
}
