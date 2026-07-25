package com.plainbase.domain.page

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.RootName

/**
 * The frozen §5.3 citation object, embedded in every page payload (PB-REST-1):
 *  - [root] — the root the page lives in (per-root identity, C5). NOT serialized: it root-qualifies the [uri]
 *    STRING only, so `CitationDto` gains no field and the frozen envelope is unchanged;
 *  - [pageId] — canonical lowercase UUID text on the wire (any version; opaque identity, §A4);
 *  - [headingId] — a PB-SLUG-1 id, null for a page-level citation;
 *  - [path] — the content-root-relative FILE path (NFC, `/`-separated, no leading `/`);
 *  - [contentHash] — `sha256:` + 64 lowercase hex of the EXACT raw file bytes (pre-decode, BOM
 *    included) — the same value Phase 3 CAS uses;
 *  - [commit] — the last commit touching the file in Git mode (the page's snapshot-resident
 *    last-commit SHA); null off Git / for an uncommitted page / in Phase 1-2 — the field is present
 *    from day one;
 *  - [uri] — the §A4 grammar `plainbase://{root}/{page_id} [ "#" heading_id ] "@" ref` (root-qualified, C5);
 *    Phase 1's ref is always the [contentHash] form (git-sha refs arrive with the Phase 3 commit field).
 *
 * Citations are ID-based and unaffected by path-based routing (owner decision: IDs for
 * durability). Pure domain code; the wire shape lives in `frameworks/ktor/dto`.
 */
data class Citation(
    val root: RootName,
    val pageId: PageId,
    val headingId: String?,
    val path: TreePath,
    val contentHash: String,
    val commit: String?,
) {

    /** The §A4 citation URI: `plainbase://{root}/{page_id}["#"heading_id]"@"ref` (root-qualified, C5). */
    val uri: String = buildString {
        append("plainbase://").append(root.value).append('/').append(pageId.value)
        headingId?.let { append('#').append(it) }
        append('@').append(contentHash)
    }
}
