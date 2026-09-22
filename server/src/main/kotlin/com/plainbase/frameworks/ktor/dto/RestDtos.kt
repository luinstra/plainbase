package com.plainbase.frameworks.ktor.dto

import com.plainbase.domain.service.PageHtmlPayload
import com.plainbase.domain.service.TreeNode
import com.plainbase.frameworks.protocol.CitationDto
import com.plainbase.frameworks.protocol.HeadingDto
import com.plainbase.frameworks.protocol.toDto
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /api/v1/pages/{id}/html` wire shape (frozen; the `html` markup itself is non-frozen). */
@Serializable
data class PageHtmlResponse(
    val id: String,
    /** Additive amendment (ADR-0011 D3, multi-root C3): the page's root-name slug. */
    val root: String,
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

/**
 * `GET /api/v1/tree` wire shape (frozen as SHAPE; child ordering is documented-not-frozen): one
 * entry per CONFIGURED root, in registry (D7) order. Reshaped from `{root: <node>}` under the same
 * ADR-0011 D3 amendment as the url values (multi-root C3; the ForeverApiGoldenSuite ledger records
 * it).
 */
@Serializable
data class TreeResponse(val roots: List<RootTreeDto>)

/**
 * One root's tree entry: the validated root-name slug, whether it is currently SERVING, whether it accepts
 * WRITES, whether it is the PRIMARY, and its synthetic root folder node. [tree] is always a folder node, but it is DECLARED as the sealed
 * interface so the polymorphic serializer emits the `type` discriminator on the root exactly like on every child
 * (the pre-C3 TreeResponse rule, unchanged).
 *
 * [available] `false` means the root is configured but not serving: its subtree is EMPTY here (never its stale
 * carried-forward listing) and every read of it answers 503. It is listed rather than omitted so a client can tell
 * "this root is down" from "this root does not exist" - and so the client's known-root set matches the server's,
 * which is what lets it route `/{root}/...` without guessing.
 *
 * [editable] is the root's CONFIGURED write disposition (ADR-0011 `roots.<name>.editable`), and it is on the wire
 * for the same reason [available] is: without it the SPA cannot tell a writable root from a read-only one, so it
 * offers Edit/New on every page of a root whose every write answers 403 `root_not_editable` - and `plainbase root
 * add` defaults an extra root to `editable = false`, which makes that the DEFAULT experience of a CLI-added root,
 * not an exotic one. Config, not authorization: it says what the TOPOLOGY allows, never what this principal may do
 * (the 403 remains the authority, and the client's buffer-preserving 403 path remains the backstop).
 *
 * [primary] flags the reserved primary root (ADR-0011 D1). It is on the wire because the primary is NOT
 * `roots[0]`: D7 order is the operator's config order, so it sits wherever config declared it, and a client
 * deriving it positionally is wrong on any install that declared its primary second. Exactly one entry carries
 * `true`, guaranteed by `RootRegistry`'s construction-time resolution rather than by a search here.
 */
@Serializable
data class RootTreeDto(
    val root: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val displayName: String? = null,
    val available: Boolean,
    val editable: Boolean,
    val primary: Boolean,
    val tree: TreeNodeDto,
)

/** A tree node; the `type` discriminator comes from the sealed serializer. */
@Serializable
sealed interface TreeNodeDto {

    @Serializable
    @SerialName("folder")
    data class Folder(
        val name: String,
        val title: String?,
        // provisional (Chunk-3 landing): the `_folder.yaml` plaintext summary; null when absent/blank.
        val description: String?,
        val path: String,
        /** Additive amendment (ADR-0003): the folder's `/{root}` URL prefix; null for a collision-loser subtree. */
        val url: String?,
        // provisional (Chunk-3 landing): page_count is DIRECT child pages only (not recursive).
        @SerialName("page_count") val pageCount: Int,
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
        // editorial author-declared date, validated YYYY-MM-DD; provisional — Phase-3 Git may add a
        // distinct last_modified (never a repoint of updated).
        val updated: String?,
    ) : TreeNodeDto

    @Serializable
    @SerialName("diagram")
    data class Diagram(
        val title: String,
        val path: String,
        val url: String,
        @SerialName("source_url") val sourceUrl: String,
    ) : TreeNodeDto
}

/** `POST /api/v1/admin/rescan` response — a §C4 convenience, NOT a frozen PB-REST-1 shape. */
@Serializable
data class RescanResponse(val status: String, val pages: Int)

/**
 * `POST /api/v1/admin/reindex` response — a convenience like [RescanResponse], NOT a frozen PB-* shape (§A5 says
 * the reindex body is not frozen). Its field names parallel [RescanResponse], but the counts differ: `RescanResponse`
 * reports pages in the rescanned snapshot, while `pages` here counts accepted reindex input after current durable
 * retirement filtering. It excludes retired pages and does not count additional unretired engine rows carried
 * forward. No forever golden pins it.
 */
@Serializable
data class ReindexResponse(val status: String, val pages: Int)

fun PageHtmlPayload.toDto(): PageHtmlResponse = PageHtmlResponse(
    id = page.id.value,
    root = page.root.value,
    path = page.path.value,
    slug = page.slug,
    url = page.url,
    title = page.title,
    html = page.html,
    contentHash = page.contentHash,
    commit = page.commit,
    headings = page.headings.map { it.toDto() },
    citation = citation.toDto(),
)

fun TreeNode.Folder.toDto(): TreeNodeDto.Folder = TreeNodeDto.Folder(
    name = name,
    title = title,
    description = description,
    // The synthetic root's domain path is null; the frozen wire shape spells it "" (§A4 example).
    path = path?.value ?: "",
    url = url,
    pageCount = pageCount,
    children = children.map { child ->
        when (child) {
            is TreeNode.Folder -> child.toDto()
            is TreeNode.Page -> child.toDto()
            is TreeNode.Diagram -> child.toDto()
        }
    },
)

fun TreeNode.Diagram.toDto(): TreeNodeDto.Diagram = TreeNodeDto.Diagram(
    title = title,
    path = path.value,
    url = url,
    sourceUrl = sourceUrl,
)

fun TreeNode.Page.toDto(): TreeNodeDto.Page = TreeNodeDto.Page(
    id = id.value,
    title = title,
    slug = slug,
    path = path.value,
    url = url,
    status = status,
    updated = updated,
)
