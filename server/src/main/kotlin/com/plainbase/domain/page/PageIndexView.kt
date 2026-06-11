package com.plainbase.domain.page

import com.plainbase.domain.content.TreePath

/**
 * The read-only lookup/URL seam the [com.plainbase.domain.service.LinkResolver] resolves against
 * (PB-LINK-1 §A2). It exposes exactly the questions resolution asks of the scanned page index —
 * "does this path exist, and as what?" and "what is this page's emitted URL?" — and nothing else.
 *
 * Chunk 5's `PageIndex` implements this over the real snapshot (`byPath`, `byUrlPath`, the
 * `CanonicalUrlBuilder` output). In chunk 2 it is **stubbed** in tests over the fixture path set,
 * because URL *construction* (§A4) is chunk 5's job — the resolver only consumes the URL the view
 * hands it. Keeping this an interface is what lets the resolver be pure domain code with zero
 * dependency on the index implementation or any framework type.
 *
 * All path keys are chunk 1.5 [TreePath]s (NFC by construction); the view re-derives no path
 * semantics.
 */
interface PageIndexView {

    /** What an indexed [TreePath] is, when present. */
    enum class EntryKind { PAGE, ASSET, DIRECTORY }

    /**
     * The kind of the entry at [path], or null if no such path is indexed (case-sensitive — the
     * exact-match probe of §A2 step 5). A path may be a PAGE (a `.md` file), an ASSET (any other
     * file), or a DIRECTORY (a folder known to contain indexed entries).
     */
    fun kindOf(path: TreePath): EntryKind?

    /**
     * The page's canonical emitted URL (§A4): a `/docs/...` path URL, or a `/p/{id}` permalink for a
     * path-space collision loser. [page] MUST be a known PAGE path (caller resolved it first).
     */
    fun pageUrl(page: TreePath): String

    /**
     * The asset's emitted URL: `/assets/{content-root-relative-path}`, NFC, RFC-3986 percent-encoded
     * (§A2). [asset] MUST be a known ASSET path.
     */
    fun assetUrl(asset: TreePath): String

    /**
     * Case-insensitive rescue candidates for [path] (§A2 step 6) — the set of indexed paths whose
     * value equals [path]'s value ignoring ASCII case. Used ONLY for broken-link classification
     * (`broken_case_mismatch` for exactly one, `ambiguous` for two or more), never to silently
     * resolve.
     *
     * The default returns an empty set — which **silently downgrades every rescue-scan to
     * `broken_missing`** (no `broken_case_mismatch`/`ambiguous` is ever produced). It is a default
     * only so trivial stubs need not implement it; any real index (and the fixture stub) MUST
     * override it, or the §A2 step-6 classification is a no-op. The port shape is frozen for chunk 2,
     * so this stays a defaulted method rather than abstract; the obligation is documented instead.
     */
    fun caseInsensitiveMatches(path: TreePath): List<TreePath> = emptyList()
}
