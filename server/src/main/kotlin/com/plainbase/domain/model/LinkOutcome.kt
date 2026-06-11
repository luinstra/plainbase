package com.plainbase.domain.model

import com.plainbase.domain.content.TreePath

/**
 * The frozen result of resolving a single Markdown link/image target (PB-LINK-1 §A2).
 *
 * A [LinkOutcome] is either [Resolved] (the target was classified and an output URL — or a
 * verbatim pass-through value — produced) or [Broken] (the target could not be resolved, and
 * carries one of the append-only error [BrokenReason] classes). Both the resolved variants and
 * the broken-reason classes are part of the frozen PB-LINK-1 surface; the HTML wrapper markup
 * that ultimately renders them is explicitly NOT frozen (§A2).
 *
 * This is pure domain code — it imports only chunk 1.5's [TreePath]; it touches no framework
 * type and no Markdown library.
 */
sealed interface LinkOutcome {

    /**
     * The target was classified successfully. [url] is the value emitted on the wire: a canonical
     * `/docs/...` or `/assets/...` URL for internal targets, or the verbatim source string for
     * external/anchor pass-throughs.
     */
    sealed interface Resolved : LinkOutcome {
        val url: String

        /**
         * An internal page target. [page] is the resolved page's [TreePath]; [url] is the page's
         * canonical `/docs/...` path URL (with a re-encoded `#fragment` appended if one was present),
         * or — for a path-space collision loser — its `/p/{id}` permalink (§A4).
         */
        data class Page(val page: TreePath, override val url: String) : Resolved

        /** An internal asset target. [asset] is the resolved file [TreePath]; [url] is its `/assets/...` URL. */
        data class Asset(val asset: TreePath, override val url: String) : Resolved

        /** An external target (allowed scheme or protocol-relative). [url] is the verbatim source. */
        data class External(override val url: String) : Resolved

        /** A same-page fragment (`#…`). [url] is the verbatim source; fragment existence is a validation concern. */
        data class Anchor(override val url: String) : Resolved
    }

    /** The target could not be resolved; [reason] is the frozen, append-only error class. */
    data class Broken(val reason: BrokenReason) : LinkOutcome

    /**
     * The append-only PB-LINK-1 error-class enum (§A2). New reasons may be ADDED in later phases;
     * existing reasons are never removed, renamed, or repurposed.
     *
     * Note: `broken_anchor` (cross-page fragment validation) is deliberately absent — it is a
     * link-checker / `validate_links` concern (Phase 5), not a render-time resolution outcome.
     */
    enum class BrokenReason(val wireValue: String) {
        /** No index path matches (case-sensitively, then no unique case-insensitive rescue). */
        MISSING("broken_missing"),

        /** Exactly one index path matches case-insensitively — a likely-wrong-case link. */
        CASE_MISMATCH("broken_case_mismatch"),

        /** Malformed encoding/syntax: empty target, bad percent-escape, encoded slash, invalid UTF-8. */
        MALFORMED("broken_malformed"),

        /** Lexical resolution escaped the content root (`..` above the root). */
        OUTSIDE_CONTENT_ROOT("outside_content_root"),

        /** Two or more index paths match case-insensitively — cannot pick a winner. */
        AMBIGUOUS("ambiguous"),

        /** A scheme'd / protocol-relative target whose scheme is not on the allowlist. */
        BLOCKED_SCHEME("blocked_scheme"),
    }
}
