package com.plainbase.frameworks.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The frozen P3 MCP tool surface (§0.4 / §2.6) — EXACTLY seven, contract-parity with the REST routes.
 * `read_page` is the sole whole-file read (read_section/read_file dropped, owner-settled): it returns the verbatim
 * on-disk markdown (frontmatter header + body), exactly as `GET /api/v1/pages/{id}` does. These names + their input
 * schemas are the single source of truth the exact-seven surface assertion (McpSurfaceTest) checks.
 */
object McpTools {
    const val SEARCH = "search"
    const val READ_PAGE = "read_page"
    const val GET_PAGE_METADATA = "get_page_metadata"
    const val VALIDATE_LINKS = "validate_links"
    const val PROPOSE_CHANGE = "propose_change"
    const val LIST_CHANGES = "list_changes"
    const val GET_CHANGE = "get_change"

    val ALL: Set<String> = setOf(
        SEARCH,
        READ_PAGE,
        GET_PAGE_METADATA,
        VALIDATE_LINKS,
        PROPOSE_CHANGE,
        LIST_CHANGES,
        GET_CHANGE,
    )
}

// The seven tool descriptions surfaced to MCP clients (used in addTool). Accurate + terse; they note the contract
// parity with the REST API. propose_change describes proposed_content as plain UTF-8 markdown (NEVER base64).
internal const val SEARCH_DESCRIPTION =
    "Full-text search the docs (same contract as GET /api/v1/search). Returns ranked hits with snippets + citations."
internal const val READ_PAGE_DESCRIPTION =
    "Read a page's verbatim on-disk markdown source (frontmatter header + body), same as GET /api/v1/pages/{id}."
internal const val GET_PAGE_METADATA_DESCRIPTION =
    "A page's server-derived metadata projection (id/path/url/permalink/content_hash/commit/title/headings)."
internal const val VALIDATE_LINKS_DESCRIPTION =
    "The broken links + anchors on a page (same contract as GET /api/v1/pages/{id}/validate-links)."
internal const val PROPOSE_CHANGE_DESCRIPTION =
    "Propose an edit or a new page for human review. Agents propose; humans approve. Returns the new proposal id + diff."
internal const val LIST_CHANGES_DESCRIPTION =
    "List every proposal, newest-first (same contract as GET /api/v1/changes)."
internal const val GET_CHANGE_DESCRIPTION =
    "The full detail of one proposal by id (same contract as GET /api/v1/changes/{id})."

/** A `{ "type": "string", "description": … }` JSON-schema property. */
private fun stringProperty(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

/** A `{ "type": "string", "enum": […], "description": … }` JSON-schema property. */
private fun enumProperty(values: List<String>, description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("enum", buildJsonArray { values.forEach { add(it) } })
    put("description", description)
}

/**
 * A canonical-shape-id input schema (`{ id: string }`, required), shared by the four id-taking tools. The
 * `ToolSchema` `type` is the SDK-fixed "object"; the ctor takes (schema, properties, required, defs) — pass only
 * what we author (properties + required), letting the optional `$schema`/`$defs` default.
 */
private fun idSchema(description: String): ToolSchema = ToolSchema(
    properties = buildJsonObject { put("id", stringProperty(description)) },
    required = listOf("id"),
)

internal val searchSchema = ToolSchema(
    properties = buildJsonObject {
        put("q", stringProperty("The search query (the PB-SEARCH-1 §A1 grammar)."))
        put("limit", stringProperty("Maximum number of hits to return (optional; the server validates the range)."))
        put("offset", stringProperty("Result offset for pagination (optional)."))
    },
    required = listOf("q"),
)

internal val readPageSchema = idSchema("The canonical-shape page UUID.")

internal val getPageMetadataSchema = idSchema("The canonical-shape page UUID.")

internal val validateLinksSchema = idSchema("The canonical-shape page UUID.")

internal val getChangeSchema = idSchema("The canonical-shape proposal UUID.")

/** list_changes takes no arguments — an empty object schema. */
internal val listChangesSchema = ToolSchema(
    properties = JsonObject(emptyMap()),
    required = emptyList(),
)

// propose_change: the property names + the lowercase operation enum MUST match ProposeChangeRequest's @SerialNames
// verbatim (ProposalDtos.kt) so the shared decode validates identically to REST.
internal val proposeChangeSchema = ToolSchema(
    properties = buildJsonObject {
        put("operation", enumProperty(listOf("edit", "create"), "edit an existing page or create a new one."))
        put("page_id", stringProperty("The page to edit (an edit requires it; a create omits it)."))
        put("base_hash", stringProperty("The sha256:<64-hex> content hash you edited against (an edit requires it)."))
        put("target_path", stringProperty("A create's content-relative path (required for create); optional for an edit."))
        put(
            "proposed_content",
            stringProperty("The full UTF-8 markdown source of the page after your edit (frontmatter header + body)."),
        )
        put("rationale", stringProperty("A short human-readable reason for the change."))
    },
    required = listOf("operation", "proposed_content", "rationale"),
)
