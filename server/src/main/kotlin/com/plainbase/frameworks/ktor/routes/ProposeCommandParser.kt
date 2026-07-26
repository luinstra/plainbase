package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.ProposeCommand
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import com.plainbase.frameworks.ktor.dto.ProposalOperationWire
import com.plainbase.frameworks.ktor.dto.ProposeChangeRequest

/**
 * Validates a decoded [ProposeChangeRequest] into a typed [ProposeCommand], or returns [ProposeCommandParse.Invalid].
 *
 * [roots] is the registry's NAME SET - a data param, never a service - which is what keeps this call-free and lets
 * BOTH propose surfaces reuse it. It is why the declared-root check lives here rather than in a route: a
 * create-PROPOSAL never goes near `POST /pages`, so a route-side check would have left the two propose surfaces
 * falling through to the facade's fail-closed `editableOf` and answering 403 `root_not_editable` for a root that
 * does not exist. It is also why a create's root is REQUIRED here rather than in a schema: the MCP tool's flat
 * input schema cannot demand a field for one operation and forbid it for the other, so the rule lives in the one
 * place both surfaces pass through. An EDIT's root is an OPTIONAL disambiguation pin (C4), grammar-checked only:
 * omitted, the facade resolves it from the page id; named, the facade durable-validates it AFTER the auth gate.
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
    val pageId = rawPageId?.let(PageId::of)
    val baseHash = request.baseHash
    // The optional client target_path is non-authoritative; if present it MUST be a valid TreePath (a traversal is a 400).
    val rawTargetPath = request.targetPath
    val clientTargetPath = rawTargetPath?.let(TreePath::of)
    // The optional edit `root` pin (C4, 5.2a #6): GRAMMAR only - `RootName.of`, NOT `RootName.registered`. An
    // unregistered-but-legal slug must NOT leak a root's existence pre-auth; the facade durable-validates it AFTER
    // checkEdit (a pin that holds no live binding answers StaleBase). A create's root stays REQUIRED + registered
    // (parseCreateCommand, 5.2a #9): a required authoritative field for a NEW page leaks nothing.
    val rawRoot = request.root
    val editRoot = rawRoot?.let(RootName::of)
    return when {
        rawPageId.isNullOrBlank() -> ProposeCommandParse.Invalid("an edit requires page_id")
        pageId == null -> ProposeCommandParse.Invalid("page_id is not a valid UUID")
        baseHash.isNullOrBlank() -> ProposeCommandParse.Invalid("an edit requires base_hash")
        !isContentHash(baseHash) -> ProposeCommandParse.Invalid("base_hash must be the sha256:<64-hex> form")
        rawTargetPath != null && clientTargetPath == null ->
            ProposeCommandParse.Invalid("target_path is not a valid content-relative path: '$rawTargetPath'")
        // The message names the BODY field, not `?root=`: this pin arrives in the propose JSON, and an agent sent
        // hunting for a query string it never sent is an agent that cannot fix its request.
        rawRoot != null && editRoot == null ->
            ProposeCommandParse.Invalid("root must be a valid root name: '$rawRoot'", ErrorCodes.INVALID_ROOT)
        else ->
            ProposeCommandParse.Ok(
                ProposeCommand.Edit(
                    pageId = pageId,
                    baseHash = baseHash,
                    clientTargetPath = clientTargetPath,
                    proposedContent = request.proposedContent.encodeToByteArray(),
                    rationale = request.rationale,
                    root = editRoot,
                ),
            )
    }
}

private fun parseCreateCommand(request: ProposeChangeRequest, roots: Set<RootName>): ProposeCommandParse {
    if (request.pageId != null) return ProposeCommandParse.Invalid("a create has no existing page; page_id is contradictory")
    if (request.baseHash != null) return ProposeCommandParse.Invalid("a new page has no base; base_hash is contradictory")
    // A create says WHERE, always. An omitted root used to mean `main`, which made forgetting the field a silent
    // relocation into main - and a proposal judged against MAIN's editable bit and MAIN's globs, which is an
    // authorization decision no client should be able to reach by leaving a field out.
    val rawRoot = request.root ?: return ProposeCommandParse.Invalid("a create requires root", ErrorCodes.INVALID_ROOT)
    // ONE total resolution for a wire root string: it can fail two ways - not a legal slug, or a legal slug naming
    // no registered root - and both are the same 400 `invalid_root`, on every surface that can name a root.
    val root = RootName.registered(rawRoot, roots)
        ?: return ProposeCommandParse.Invalid("Unknown root: '$rawRoot'", ErrorCodes.INVALID_ROOT)
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
