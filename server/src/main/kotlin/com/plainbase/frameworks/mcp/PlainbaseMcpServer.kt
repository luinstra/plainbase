package com.plainbase.frameworks.mcp

import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.ProposalId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.service.AccessDenied
import com.plainbase.domain.service.ProposeOutcome
import com.plainbase.domain.service.SearchService
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.dto.ChangeDetail
import com.plainbase.frameworks.ktor.dto.ErrorBody
import com.plainbase.frameworks.ktor.dto.ErrorEnvelope
import com.plainbase.frameworks.ktor.dto.ListChangesResponse
import com.plainbase.frameworks.ktor.dto.PageMetadataResponse
import com.plainbase.frameworks.ktor.dto.PageResponse
import com.plainbase.frameworks.ktor.dto.ProposalStatusWire
import com.plainbase.frameworks.ktor.dto.ProposeChangeRequest
import com.plainbase.frameworks.ktor.dto.ProposeChangeResponse
import com.plainbase.frameworks.ktor.dto.RestJson
import com.plainbase.frameworks.ktor.dto.SearchResponse
import com.plainbase.frameworks.ktor.dto.ValidateLinksResponse
import com.plainbase.frameworks.ktor.dto.toDto
import com.plainbase.frameworks.ktor.dto.toMetadataDto
import com.plainbase.frameworks.ktor.routes.CANONICAL_PAGE_ID
import com.plainbase.frameworks.ktor.routes.CANONICAL_PROPOSAL_ID
import com.plainbase.frameworks.ktor.routes.ProposeCommandParse
import com.plainbase.frameworks.ktor.routes.parseProposeCommand
import io.github.oshai.kotlinlogging.KotlinLogging
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}

/**
 * The per-connection MCP [Server] factory (P3): given the connect-time-authenticated [principal] and the shared
 * [RouteContext], register the seven §2.6 tools — each a THIN adapter over the EXISTING guarded facades
 * `ctx.read`/`ctx.proposals`, closing the principal over the handler so the A3 choke point (`policy.check*`) runs
 * ONCE inside each facade method exactly as the REST routes invoke it. No second authz path, no new wire DTO: the
 * tools reuse the frozen PB-* DTO mappers + the scoped [RestJson], so the six read/list/get tools are byte-identical
 * to their REST endpoints and `propose_change` is structural-parity (its only divergence is the freshly minted id).
 * Every handler returns through [toolResult]/[catchingErrors]/[errorResult] so NO exception can escape an open SSE
 * stream (a throw after the header flush can't become a clean error).
 */
fun buildPlainbaseMcpServer(principal: Principal.Agent, ctx: RouteContext): Server {
    val server = Server(
        Implementation(name = "plainbase", version = PlainbaseConfig.VERSION),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    )

    server.addTool(McpTools.SEARCH, SEARCH_DESCRIPTION, searchSchema) { request ->
        catchingErrors {
            val args = request.arguments
            when (val outcome = ctx.read.search(principal, args?.stringArg("q"), args?.stringArg("limit"), args?.stringArg("offset"))) {
                is SearchService.Outcome.Results -> jsonResult(SearchResponse.serializer(), outcome.payload.toDto())
                is SearchService.Outcome.InvalidQuery -> errorResult("invalid_query", outcome.message)
            }
        }
    }

    server.addTool(McpTools.READ_PAGE, READ_PAGE_DESCRIPTION, readPageSchema) { request ->
        catchingErrors {
            val id = request.canonicalPageId() ?: return@catchingErrors invalidPageId(request.arguments?.stringArg("id"))
            toolResult(PageResponse.serializer()) { ctx.read.pageById(principal, id)?.toDto() }
        }
    }

    server.addTool(McpTools.GET_PAGE_METADATA, GET_PAGE_METADATA_DESCRIPTION, getPageMetadataSchema) { request ->
        catchingErrors {
            val id = request.canonicalPageId() ?: return@catchingErrors invalidPageId(request.arguments?.stringArg("id"))
            toolResult(PageMetadataResponse.serializer()) { ctx.read.pageMetadata(principal, id)?.toMetadataDto() }
        }
    }

    server.addTool(McpTools.VALIDATE_LINKS, VALIDATE_LINKS_DESCRIPTION, validateLinksSchema) { request ->
        catchingErrors {
            val id = request.canonicalPageId() ?: return@catchingErrors invalidPageId(request.arguments?.stringArg("id"))
            toolResult(ValidateLinksResponse.serializer()) { ctx.read.validateLinks(principal, id)?.toDto() }
        }
    }

    server.addTool(McpTools.PROPOSE_CHANGE, PROPOSE_CHANGE_DESCRIPTION, proposeChangeSchema) { request ->
        catchingErrors {
            val decoded = try {
                RestJson.decodeFromJsonElement(ProposeChangeRequest.serializer(), request.arguments ?: JsonObject(emptyMap()))
            } catch (_: SerializationException) {
                return@catchingErrors errorResult(
                    "invalid_propose_request",
                    "Request must be {operation, page_id?, base_hash?, target_path?, proposed_content, rationale}",
                )
            }
            when (val parse = parseProposeCommand(decoded)) {
                is ProposeCommandParse.Invalid -> errorResult("invalid_propose_request", parse.message)
                is ProposeCommandParse.Ok -> when (val outcome = ctx.proposals.propose(principal, parse.command)) {
                    is ProposeOutcome.Created -> jsonResult(
                        ProposeChangeResponse.serializer(),
                        ProposeChangeResponse(
                            id = outcome.id.value,
                            status = ProposalStatusWire.PENDING,
                            unifiedDiff = outcome.unifiedDiff,
                        ),
                    )
                    ProposeOutcome.StaleBase -> errorResult(
                        "stale_base",
                        "The base you proposed against is no longer current; re-read the page and re-propose.",
                    )
                    ProposeOutcome.InvalidRequest -> errorResult(
                        "invalid_propose_request",
                        "target_path disagrees with the page_id-resolved path; the server resolves the path from page_id.",
                    )
                    is ProposeOutcome.InvalidCreateContent -> errorResult("invalid_create_content", outcome.message)
                }
            }
        }
    }

    server.addTool(McpTools.LIST_CHANGES, LIST_CHANGES_DESCRIPTION, listChangesSchema) { _ ->
        toolResult(ListChangesResponse.serializer()) { ListChangesResponse(proposals = ctx.proposals.list(principal).map { it.toDto() }) }
    }

    server.addTool(McpTools.GET_CHANGE, GET_CHANGE_DESCRIPTION, getChangeSchema) { request ->
        catchingErrors {
            val raw = request.arguments?.stringArg("id")
            if (raw == null || !CANONICAL_PROPOSAL_ID.matches(raw)) {
                // REST↔MCP parity: a malformed proposal id is the SAME code the REST `proposalId()` parser emits
                // (`invalid_propose_request`), quoting the raw id like REST does — NOT a divergent `invalid_proposal_id`.
                return@catchingErrors errorResult("invalid_propose_request", "Not a canonical-shape UUID: '${raw.orEmpty()}'")
            }
            toolResult(ChangeDetail.serializer()) { ctx.proposals.get(principal, ProposalId.require(raw))?.toDto() }
        }
    }

    return server
}

/** The `id` arg parsed via the §A4 canonical page-id shape, or null (a non-canonical id → `invalid_page_id`). */
private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.canonicalPageId(): PageId? {
    val raw = arguments?.stringArg("id") ?: return null
    return if (CANONICAL_PAGE_ID.matches(raw)) PageId.require(raw) else null
}

/** REST parity: the SAME `invalid_page_id` code + quoted-raw-id message the REST `pageId()` parser emits. */
private fun invalidPageId(raw: String?): CallToolResult = errorResult("invalid_page_id", "Not a canonical-shape UUID: '${raw.orEmpty()}'")

/** Reads a named string argument off the tool's `arguments` object (null when absent / not a string). */
private fun JsonObject.stringArg(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

/** A success [CallToolResult] carrying the [dto] pre-encoded through the scoped [RestJson] (byte-identical to REST). */
private fun <T> jsonResult(serializer: KSerializer<T>, dto: T): CallToolResult =
    CallToolResult(content = listOf(TextContent(RestJson.encodeToString(serializer, dto))))

/**
 * Run [body], serializing its DTO through [RestJson] into a success [CallToolResult]; map a null result to
 * `not_found` and EVERY failure to `CallToolResult(isError=true)`. NEVER lets an exception escape (an open SSE
 * stream can't become a clean error after the header flush). Catch ORDER is load-bearing: [AccessDenied]
 * (Anonymous → `unauthorized` / else → `forbidden`) BEFORE the null path so existence never leaks; then a generic
 * `internal` net (cause to the log, NEVER on the wire).
 */
private inline fun <T> toolResult(serializer: KSerializer<T>, body: () -> T?): CallToolResult =
    try {
        val dto = body() ?: return errorResult("not_found", "No such resource")
        jsonResult(serializer, dto)
    } catch (denied: AccessDenied) {
        deniedResult(denied)
    } catch (t: Throwable) {
        logger.error(t) { "MCP tool failed" } // cause to the log, never the wire
        errorResult("internal", "Internal error")
    }

/**
 * The outer wrapper for any handler that PRE-VALIDATES an arg or maps a discriminated outcome to a [CallToolResult]
 * (search / propose_change / the id-shape tools): the SAME catch order as [toolResult] (AccessDenied → forbidden/
 * unauthorized, then a catch-all `internal`), so the discriminated mapping ALSO runs inside the full catch net and
 * no throw escapes the SSE stream. A handler returns a known bad-input outcome via `errorResult` from inside [body].
 */
private inline fun catchingErrors(body: () -> CallToolResult): CallToolResult =
    try {
        body()
    } catch (denied: AccessDenied) {
        deniedResult(denied)
    } catch (t: Throwable) {
        logger.error(t) { "MCP tool failed" }
        errorResult("internal", "Internal error")
    }

/** Maps an [AccessDenied] to the SAME 401-vs-403 split the REST `guarded` block uses (Anonymous → unauthorized). */
private fun deniedResult(denied: AccessDenied): CallToolResult =
    if (denied.principal is Principal.Anonymous) {
        errorResult("unauthorized", "Authentication required")
    } else {
        errorResult("forbidden", "You do not have permission for this action")
    }

/** A `CallToolResult(isError=true)` carrying the frozen `{error:{code,message}}` envelope — the SAME shape as a REST error. */
private fun errorResult(code: String, message: String): CallToolResult =
    CallToolResult(
        content = listOf(TextContent(RestJson.encodeToString(ErrorEnvelope.serializer(), ErrorEnvelope(ErrorBody(code, message))))),
        isError = true,
    )
