// The file's primary export is the `parseProposeCommand` function (the REST+MCP shared parser); `ProposeCommandParse`
// is its small result type. Named after the parser, not the result type — suppress ktlint's single-class filename rule.
@file:Suppress("ktlint:standard:filename")

package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.service.ProposeCommand
import com.plainbase.frameworks.ktor.dto.ProposalOperationWire
import com.plainbase.frameworks.ktor.dto.ProposeChangeRequest

/**
 * The shared `ProposeChangeRequest` -> `ProposeCommand` validation (the F4 malformed-shape matrix), CALL-FREE so
 * BOTH the REST route ([proposalRoutes]) and the MCP `propose_change` tool reuse it — they can never drift. On a
 * bad shape it returns [Invalid] with the SAME message the REST 400 used; the caller maps it to its transport
 * (REST: 400 `invalid_propose_request`; MCP: `CallToolResult` isError `invalid_propose_request`). Every wire value
 * is parsed through its typed constructor BEFORE the semantic checks; rows 3/5/6 (well-formed but stale /
 * path-mismatch) are the `ProposalService` outcomes.
 */
sealed interface ProposeCommandParse {
    data class Ok(val command: ProposeCommand) : ProposeCommandParse

    data class Invalid(val message: String) : ProposeCommandParse
}

/** Validates a decoded [ProposeChangeRequest] into a typed [ProposeCommand], or returns [ProposeCommandParse.Invalid]. */
internal fun parseProposeCommand(request: ProposeChangeRequest): ProposeCommandParse {
    // Shared field validation.
    if (request.proposedContent.isBlank()) return ProposeCommandParse.Invalid("proposed_content must not be empty")
    if (request.rationale.isBlank()) return ProposeCommandParse.Invalid("rationale must not be blank")
    return when (request.operation) {
        ProposalOperationWire.EDIT -> parseEditCommand(request)
        ProposalOperationWire.CREATE -> parseCreateCommand(request)
        else -> ProposeCommandParse.Invalid("operation must be one of edit, create")
    }
}

private fun parseEditCommand(request: ProposeChangeRequest): ProposeCommandParse {
    val rawPageId = request.pageId
    if (rawPageId.isNullOrBlank()) return ProposeCommandParse.Invalid("an edit requires page_id")
    val pageId = PageId.of(rawPageId) ?: return ProposeCommandParse.Invalid("page_id is not a valid UUID")
    val baseHash = request.baseHash
    if (baseHash.isNullOrBlank()) return ProposeCommandParse.Invalid("an edit requires base_hash")
    if (!isContentHash(baseHash)) return ProposeCommandParse.Invalid("base_hash must be the sha256:<64-hex> form")
    // The optional client target_path is non-authoritative; if present it MUST be a valid TreePath (a traversal is a 400).
    val clientTargetPath = request.targetPath?.let { raw ->
        TreePath.of(raw) ?: return ProposeCommandParse.Invalid("target_path is not a valid content-relative path: '$raw'")
    }
    return ProposeCommandParse.Ok(
        ProposeCommand.Edit(
            pageId = pageId,
            baseHash = baseHash,
            clientTargetPath = clientTargetPath,
            proposedContent = request.proposedContent.encodeToByteArray(),
            rationale = request.rationale,
        ),
    )
}

private fun parseCreateCommand(request: ProposeChangeRequest): ProposeCommandParse {
    if (request.pageId != null) return ProposeCommandParse.Invalid("a create has no existing page; page_id is contradictory")
    if (request.baseHash != null) return ProposeCommandParse.Invalid("a new page has no base; base_hash is contradictory")
    val rawTargetPath = request.targetPath
    if (rawTargetPath.isNullOrBlank()) return ProposeCommandParse.Invalid("a create requires target_path")
    // SECURITY: the wire target_path goes through TreePath.of — a `..`/absolute/empty/NUL is structurally
    // unrepresentable, so a traversal is a deterministic 400, never a 500 or a raw-string store.
    val targetPath = TreePath.of(rawTargetPath)
        ?: return ProposeCommandParse.Invalid("target_path is not a valid content-relative path: '$rawTargetPath'")
    return ProposeCommandParse.Ok(
        ProposeCommand.Create(
            targetPath = targetPath,
            proposedContent = request.proposedContent.encodeToByteArray(),
            rationale = request.rationale,
        ),
    )
}

/** The `sha256:` + 64-lowercase-hex content-hash shape (the CitationFactory form) — a malformed base_hash is a 400. */
private val CONTENT_HASH = Regex("sha256:[0-9a-f]{64}")

private fun isContentHash(value: String): Boolean = CONTENT_HASH.matches(value)
