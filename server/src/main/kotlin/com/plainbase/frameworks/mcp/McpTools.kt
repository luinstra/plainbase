package com.plainbase.frameworks.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The frozen seven P3 tools plus four C4 discussion tools.
 * `read_page` is the sole whole-file read (read_section/read_file dropped, owner-settled): it returns the verbatim
 * on-disk markdown (frontmatter header + body), exactly as `GET /api/v1/pages/{id}` does. These names + their input
 * schemas are checked against the wire inventory in McpSurfaceTest.
 */
object McpTools {
    const val SEARCH = "search"
    const val READ_PAGE = "read_page"
    const val GET_PAGE_METADATA = "get_page_metadata"
    const val VALIDATE_LINKS = "validate_links"
    const val PROPOSE_CHANGE = "propose_change"
    const val LIST_CHANGES = "list_changes"
    const val GET_CHANGE = "get_change"
    const val LIST_DISCUSSIONS = "list_discussions"
    const val GET_DISCUSSION = "get_discussion"
    const val START_DISCUSSION = "start_discussion"
    const val ADD_COMMENT = "add_comment"

    val ALL: Set<String> = setOf(
        SEARCH,
        READ_PAGE,
        GET_PAGE_METADATA,
        VALIDATE_LINKS,
        PROPOSE_CHANGE,
        LIST_CHANGES,
        GET_CHANGE,
        LIST_DISCUSSIONS,
        GET_DISCUSSION,
        START_DISCUSSION,
        ADD_COMMENT,
    )
}

// Tool descriptions surfaced to MCP clients (used in addTool). The original seven note the contract
// parity with the REST API. propose_change describes proposed_content as plain UTF-8 markdown (NEVER base64).
internal const val SEARCH_DESCRIPTION =
    "Full-text search the docs (same contract as GET /api/v1/search). Returns ranked hits with snippets + citations."
internal const val READ_PAGE_DESCRIPTION =
    "Read a page's verbatim on-disk markdown source (frontmatter header + body), same as GET /api/v1/pages/{id}."
internal const val GET_PAGE_METADATA_DESCRIPTION =
    "A page's server-derived metadata projection (id/root/path/url/permalink/content_hash/commit/title/headings)."
internal const val VALIDATE_LINKS_DESCRIPTION =
    "The broken links + anchors on a page (same contract as GET /api/v1/pages/{id}/validate-links)."
internal const val PROPOSE_CHANGE_DESCRIPTION =
    "Propose an edit or a new page for human review. Agents propose; humans approve. Returns the new proposal id + diff."
internal const val LIST_CHANGES_DESCRIPTION =
    "List every proposal, newest-first (same contract as GET /api/v1/changes)."
internal const val GET_CHANGE_DESCRIPTION =
    "The full detail of one proposal by id (same contract as GET /api/v1/changes/{id})."
internal const val LIST_DISCUSSIONS_DESCRIPTION =
    "List discussions on page_id (optional root; no state, default limit 200), or in required root (optional state, default/max limit 50)."
internal const val GET_DISCUSSION_DESCRIPTION =
    "Get a discussion and a bounded comment window by id; optional root disambiguates multiple candidates (default limit 50)."
internal const val START_DISCUSSION_DESCRIPTION =
    "Start a discussion on page_id with an anchor and body; optional root disambiguates multiple candidates."
internal const val ADD_COMMENT_DESCRIPTION =
    "Add a comment to a discussion by id; optional root disambiguates multiple candidates."

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

private fun integerProperty(minimum: Int, maximum: Long, description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("minimum", minimum)
    put("maximum", maximum)
    put("description", description)
}

private fun anchorAlternative(quote: Boolean, offsets: Boolean = false): JsonObject = buildJsonObject {
    put("type", "object")
    put(
        "properties",
        buildJsonObject {
            put("kind", enumProperty(listOf(if (quote) "quote" else "page"), "Anchor kind."))
            put("content_hash", stringProperty("sha256: followed by 64 lowercase hex digits."))
            if (quote) put("selected_text", stringProperty("Selected text, at most 16,384 UTF-8 bytes."))
            if (offsets) {
                put("block_start", integerProperty(0, 9_999_999_999L, "Inclusive block start offset."))
                put("block_end", integerProperty(0, 9_999_999_999L, "Exclusive block end offset."))
            }
        },
    )
    put(
        "required",
        buildJsonArray {
            add("kind")
            add("content_hash")
            if (quote) add("selected_text")
            if (offsets) {
                add("block_start")
                add("block_end")
            }
        },
    )
    put("additionalProperties", false)
}

private val discussionAnchorProperty = buildJsonObject {
    put("description", "One page or quote anchor; client commit and capture fields are not accepted.")
    put(
        "oneOf",
        buildJsonArray {
            add(anchorAlternative(quote = false))
            add(anchorAlternative(quote = true))
            add(anchorAlternative(quote = true, offsets = true))
        },
    )
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

/**
 * A page-id input schema with the OPTIONAL C4 `root` disambiguator (`{ id: string, root?: string }`, only `id`
 * required), shared by the three page-id READ tools. A bare id held by more than one root answers `ambiguous_page_id`
 * with the candidate roots; the agent retries naming one here. A malformed root slug is `invalid_root` (a
 * connect-authenticated MCP read exception - root names are public, reads mint no write-audit).
 */
private fun pageIdSchema(description: String): ToolSchema = ToolSchema(
    properties = buildJsonObject {
        put("id", stringProperty(description))
        put(
            "root",
            stringProperty(
                "Optional root name to disambiguate an id held by more than one root; omit unless a read returned " +
                    "ambiguous_page_id, which lists the candidate roots. Use the names `list` shows on the tree.",
            ),
        )
    },
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

internal val readPageSchema = pageIdSchema("The canonical-shape page UUID.")

internal val getPageMetadataSchema = pageIdSchema("The canonical-shape page UUID.")

internal val validateLinksSchema = pageIdSchema("The canonical-shape page UUID.")

internal val getChangeSchema = idSchema("The canonical-shape proposal UUID.")

/** list_changes takes no arguments — an empty object schema. */
internal val listChangesSchema = ToolSchema(
    properties = JsonObject(emptyMap()),
    required = emptyList(),
)

// propose_change: the property names + the lowercase operation enum MUST match ProposeChangeRequest's @SerialNames
// verbatim (ProposalDtos.kt) so the shared decode validates identically to REST.
//
// `root` is REQUIRED for a create and an OPTIONAL disambiguation pin for an edit (C4), which a flat `required`
// list cannot say (it would force every edit to name a root). The DESCRIPTION says it and the shared parser
// ENFORCES it - a create without a root is `invalid_root`, exactly as on REST. It is never defaulted to `main`:
// that would let an agent's omission choose which root's disk it writes to and whose globs authorize it.
internal val proposeChangeSchema = ToolSchema(
    properties = buildJsonObject {
        put("operation", enumProperty(listOf("edit", "create"), "edit an existing page or create a new one."))
        put(
            "root",
            stringProperty(
                "Which document directory a CREATE lands in - REQUIRED for a create, there is no default. Optional " +
                    "for an edit as a disambiguation pin: name it when the tool answers ambiguous_page_id, " +
                    "otherwise omit it. Use the names `list` shows on the tree.",
            ),
        )
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

internal val listDiscussionsSchema = ToolSchema(
    properties = buildJsonObject {
        put("page_id", stringProperty("Optional page id; when present, selects page mode and forbids state."))
        put("root", stringProperty("Required without page_id; optional root retry pin with page_id."))
        put(
            "state",
            enumProperty(
                listOf("page_level", "exact", "moved", "ambiguous", "changed", "orphaned", "unavailable", "unreadable", "incomplete"),
                "Optional root-mode state filter; forbidden with page_id.",
            ),
        )
        put("cursor", stringProperty("Exclusive canonical discussion id cursor."))
        put("limit", integerProperty(1, 200, "Page default/max 200; root default/max 50."))
    },
    required = emptyList(),
)

internal val getDiscussionSchema = ToolSchema(
    properties = buildJsonObject {
        put("id", stringProperty("Canonical lowercase UUIDv7 discussion id."))
        put("root", stringProperty("Optional root retry pin."))
        put("cursor", stringProperty("Exclusive canonical comment id cursor."))
        put("limit", integerProperty(1, 50, "Comment window size, default 50."))
    },
    required = listOf("id"),
)

internal val startDiscussionSchema = ToolSchema(
    properties = buildJsonObject {
        put("page_id", stringProperty("Canonical page id."))
        put("root", stringProperty("Optional root retry pin."))
        put("anchor", discussionAnchorProperty)
        put("body", stringProperty("Discussion body, 1 to 65,536 UTF-8 bytes and nonblank."))
    },
    required = listOf("page_id", "anchor", "body"),
)

internal val addCommentSchema = ToolSchema(
    properties = buildJsonObject {
        put("id", stringProperty("Canonical lowercase UUIDv7 discussion id."))
        put("root", stringProperty("Optional root retry pin."))
        put("body", stringProperty("Comment body, 1 to 65,536 UTF-8 bytes and nonblank."))
    },
    required = listOf("id", "body"),
)
