// The file's primary export is the `parseProposeCommand` function (the REST+MCP shared parser); `ProposeCommandParse`
// is its small result type. Named after the parser, not the result type — suppress ktlint's single-class filename rule.
@file:Suppress("ktlint:standard:filename")

package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.ProposeCommand
import com.plainbase.frameworks.ktor.dto.ErrorCodes
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

    /** [code] is the wire error code the caller emits — the default for every malformed shape, `invalid_root` for a
     *  root that is not a legal slug or names no registered root. Carrying it HERE is what keeps the vocabulary ONE
     *  across the three write surfaces instead of each mapping site hardcoding its own. */
    data class Invalid(val message: String, val code: String = ErrorCodes.INVALID_PROPOSE_REQUEST) : ProposeCommandParse
}

/**
 * Validates a decoded [ProposeChangeRequest] into a typed [ProposeCommand], or returns [ProposeCommandParse.Invalid].
 *
 * [roots] is the registry's NAME SET - a data param, never a service - which is what keeps this call-free and lets
 * BOTH propose surfaces reuse it. It is why the declared-root check lives here rather than in a route: a
 * create-PROPOSAL never goes near `POST /pages`, so a route-side check would have left the two propose surfaces
 * falling through to the facade's fail-closed `editableOf` and answering 403 `root_not_editable` for a root that
 * does not exist. An EDIT needs no check at all - its root is never declared, it is resolved from the page id.
 */
internal fun parseProposeCommand(request: ProposeChangeRequest, roots: Set<RootName>): ProposeCommandParse {
    // Shared field validation.
    if (request.proposedContent.isBlank()) return ProposeCommandParse.Invalid("proposed_content must not be empty")
    if (request.rationale.isBlank()) return ProposeCommandParse.Invalid("rationale must not be blank")
    return when (request.operation) {
        ProposalOperationWire.EDIT -> parseEditCommand(request)
        ProposalOperationWire.CREATE -> parseCreateCommand(request, roots)
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

private fun parseCreateCommand(request: ProposeChangeRequest, roots: Set<RootName>): ProposeCommandParse {
    if (request.pageId != null) return ProposeCommandParse.Invalid("a create has no existing page; page_id is contradictory")
    if (request.baseHash != null) return ProposeCommandParse.Invalid("a new page has no base; base_hash is contradictory")
    // ONE total resolution for a wire root string: it can fail two ways - not a legal slug, or a legal slug naming
    // no registered root - and both are the same 400 `invalid_root`, on every surface that can name a root.
    val root = RootName.registered(request.root, roots)
        ?: return ProposeCommandParse.Invalid("Unknown root: '${request.root}'", ErrorCodes.INVALID_ROOT)
    val rawTargetPath = request.targetPath
    if (rawTargetPath.isNullOrBlank()) return ProposeCommandParse.Invalid("a create requires target_path")
    // SECURITY: the wire target_path goes through TreePath.of — a `..`/absolute/empty/NUL is structurally
    // unrepresentable, so a traversal is a deterministic 400, never a 500 or a raw-string store.
    val targetPath = TreePath.of(rawTargetPath)
        ?: return ProposeCommandParse.Invalid("target_path is not a valid content-relative path: '$rawTargetPath'")
    return ProposeCommandParse.Ok(
        ProposeCommand.Create(
            root = root,
            targetPath = targetPath,
            proposedContent = request.proposedContent.encodeToByteArray(),
            rationale = request.rationale,
        ),
    )
}

/** The `sha256:` + 64-lowercase-hex content-hash shape (the CitationFactory form) — a malformed base_hash is a 400. */
private val CONTENT_HASH = Regex("sha256:[0-9a-f]{64}")

private fun isContentHash(value: String): Boolean = CONTENT_HASH.matches(value)
