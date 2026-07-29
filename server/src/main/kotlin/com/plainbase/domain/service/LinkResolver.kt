package com.plainbase.domain.service

import com.plainbase.domain.content.ContentRoot
import com.plainbase.domain.content.PercentCoding
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.LinkOutcome
import com.plainbase.domain.model.LinkOutcome.BrokenReason
import com.plainbase.domain.page.PageIndexView

/**
 * PB-LINK-1 (§A2) — internal link resolution as a pure function of (current page [TreePath], raw link
 * href, a [PageIndexView]) → [LinkOutcome]. Owns the frozen scheme **allowlist**; emits canonical
 * `/{root}`/`/assets` URLs via the index's URL view (URL *construction* is chunk 5's job; here it is
 * stubbed behind [PageIndexView]).
 *
 * Imports chunk 1.5's [PercentCoding] / [TreePath] / [ContentRoot] and **re-derives nothing**: the
 * single percent-decoder, the single NFC site (inside those types), and the single containment guard
 * are reused. Pure domain code — no framework or Markdown-library type appears here.
 */
class LinkResolver(private val index: PageIndexView) {

    /**
     * Resolves [rawLink] as written in the page at [sourcePath] (a content-relative `.md` file path).
     * The classification order is frozen (§A2): scheme/protocol-relative → same-page anchor →
     * empty target → internal.
     */
    fun resolve(sourcePath: TreePath, rawLink: String): LinkOutcome =
        classify(rawLink) ?: resolveInternal(sourcePath, rawLink)

    /**
     * The frozen classification prefix (§A2): scheme/protocol-relative and same-page anchor cases
     * that resolve without touching the index. Returns null when [rawLink] is an internal target to
     * be resolved by [resolveInternal].
     */
    private fun classify(rawLink: String): LinkOutcome? {
        // Scheme'd / protocol-relative: allowlist decides external pass-through vs blocked_scheme.
        val scheme = schemePrefix(rawLink)
        return when {
            scheme != null && scheme.lowercase() in ALLOWED_SCHEMES ->
                LinkOutcome.Resolved.External(rawLink)
            scheme != null -> LinkOutcome.Broken(BrokenReason.BLOCKED_SCHEME)
            rawLink.startsWith("//") -> LinkOutcome.Resolved.External(rawLink)

            rawLink.startsWith("#") ->
                decodedOrNull(rawLink.substring(1))
                    ?.let { LinkOutcome.Resolved.Anchor("#${PercentCoding.encodeSegment(it)}") }
                    ?: LinkOutcome.Broken(BrokenReason.MALFORMED)
            else -> null
        }
    }

    /** §A2 steps 1–6 for an internal target (path/query/fragment split, decode, lexical resolve, match). */
    private fun resolveInternal(sourcePath: TreePath, rawLink: String): LinkOutcome {
        // Step 1: split on the RAW link string — fragment at the first UNENCODED '#', query at the
        // first UNENCODED '?'. Encoded %23/%3F therefore remain path data. The query is discarded
        // for page targets but PRESERVED (raw, still-encoded — never decoded, so it is never emitted
        // raw) for assets/external (§A2 step 1 + output-form table).
        val (beforeFragment, fragment) = splitOnce(rawLink, '#')
        val (rawPath, rawQuery) = splitOnce(beforeFragment, '?')

        // Empty path (`[]()` or a `?...#...` with no path) is malformed; a bare `#...` was handled above.
        val decodedFragment = fragment?.let(::decodedOrNull)
        val decodedPath = decodedOrNull(rawPath)
        return when {
            rawPath.isEmpty() -> LinkOutcome.Broken(BrokenReason.MALFORMED)
            fragment != null && decodedFragment == null -> LinkOutcome.Broken(BrokenReason.MALFORMED)
            decodedPath == null -> LinkOutcome.Broken(BrokenReason.MALFORMED)
            else -> {
                val emittedFragment = decodedFragment?.let(PercentCoding::encodeSegment)
                when (val resolved = ContentRoot.resolve(sourcePath.parent, decodedPath)) {
                    is ContentRoot.ResolveResult.Resolved ->
                        resolveTarget(resolved.path, decodedPath.endsWith("/"), emittedFragment, rawQuery)
                    ContentRoot.ResolveResult.Outside ->
                        LinkOutcome.Broken(BrokenReason.OUTSIDE_CONTENT_ROOT)
                    ContentRoot.ResolveResult.Root -> resolveDirectory(null, emittedFragment)
                }
            }
        }
    }

    /** §A2 step 5/6 — match a resolved [path] against the index by target form, then classify. */
    private fun resolveTarget(path: TreePath, trailingSlash: Boolean, fragment: String?, rawQuery: String?): LinkOutcome {
        val name = path.name
        return when {
            trailingSlash -> resolveDirectory(path, fragment)
            name.endsWith(".md") -> when (index.kindOf(path)) {
                PageIndexView.EntryKind.PAGE -> pageOutcome(path, fragment)
                else -> rescue(path)
            }
            index.kindOf(path) == PageIndexView.EntryKind.DIRECTORY -> resolveDirectory(path, fragment)
            !name.contains('.') -> resolveExtensionless(path, fragment, rawQuery)
            index.kindOf(path) == PageIndexView.EntryKind.ASSET -> assetOutcome(path, fragment, rawQuery)
            else -> rescue(path)
        }
    }

    private fun resolveExtensionless(path: TreePath, fragment: String?, rawQuery: String?): LinkOutcome {
        val asMd = TreePath.childOf(path.parent, "${path.name}.md")
        return when {
            index.kindOf(asMd) == PageIndexView.EntryKind.PAGE -> pageOutcome(asMd, fragment)
            index.kindOf(path) == PageIndexView.EntryKind.ASSET -> assetOutcome(path, fragment, rawQuery)
            else -> rescue(asMd, path)
        }
    }

    /** §A2 step 5 directory form — `<dir>/index.md` then `<dir>/README.md` (fixed precedence). */
    private fun resolveDirectory(dir: TreePath?, fragment: String?): LinkOutcome =
        DIRECTORY_INDEX_NAMES
            .map { TreePath.childOf(dir, it) }
            .firstNotNullOfOrNull { candidate ->
                if (index.kindOf(candidate) == PageIndexView.EntryKind.PAGE) pageOutcome(candidate, fragment) else null
            }
            ?: LinkOutcome.Broken(BrokenReason.MISSING)

    /**
     * §A2 step 6 — case-insensitive rescue scan (broken-link classification only, never resolves).
     *
     * [candidates] are tried in §A2 step-5 precedence order: the first candidate that produces ANY
     * case-insensitive match decides the classification, so an extensionless target rescues against
     * its inferred `<path>.md` page form first (`.md` wins; never ambiguous) and only falls back to
     * the bare `<path>` asset form when the page form matches nothing. A single candidate (the `.md`
     * page miss above) reduces to the original one-path scan.
     */
    private fun rescue(vararg candidates: TreePath): LinkOutcome =
        candidates.firstNotNullOfOrNull { candidate ->
            when (index.caseInsensitiveMatches(candidate).size) {
                0 -> null
                1 -> LinkOutcome.Broken(BrokenReason.CASE_MISMATCH)
                else -> LinkOutcome.Broken(BrokenReason.AMBIGUOUS)
            }
        }
            ?: LinkOutcome.Broken(BrokenReason.MISSING)

    /**
     * Builds a resolved-page outcome, appending the (already re-encoded) fragment if present. A
     * page target's `?query` is discarded (§A2 step 1).
     */
    private fun pageOutcome(page: TreePath, fragment: String?): LinkOutcome {
        val base = index.pageUrl(page)
        return LinkOutcome.Resolved.Page(page, appendFragment(base, fragment))
    }

    /**
     * Builds a resolved-asset outcome. A `?query` is PRESERVED for assets (§A2 step 1 + output-form
     * table) and emitted RAW — it was never decoded, so the "never emitted raw" rule holds trivially.
     * The query is appended BEFORE the fragment, matching URL grammar (`?query#fragment`).
     */
    private fun assetOutcome(asset: TreePath, fragment: String?, rawQuery: String?): LinkOutcome {
        val base = index.assetUrl(asset)
        val withQuery = if (rawQuery == null) base else "$base?$rawQuery"
        return LinkOutcome.Resolved.Asset(asset, appendFragment(withQuery, fragment))
    }

    /**
     * Strict-decodes [raw] exactly once, or null on a decode failure.
     *
     * Used for the path and every fragment so all three go through the SAME strict path (§A2 step 1,
     * owner-ratified amendment): an undecodable input yields null -> `broken_malformed` at the call
     * site (never emitted raw). The path uses the decoded value as-is for lexical resolution;
     * fragments re-encode it ([encodeSegment]) for the emitted URL.
     */
    private fun decodedOrNull(raw: String): String? =
        when (val r = PercentCoding.decodeOnce(raw)) {
            is PercentCoding.DecodeResult.Success -> r.value
            is PercentCoding.DecodeResult.Failure -> null
        }

    /**
     * Appends a fragment to an emitted URL. [fragment] is already the re-encoded form (an undecodable
     * fragment never reaches here; it returned `broken_malformed`). So this is a plain `#`-join.
     */
    private fun appendFragment(base: String, fragment: String?): String =
        if (fragment == null) base else "$base#$fragment"

    private companion object {
        /** §A2 step 5 directory index probe order (fixed precedence). */
        val DIRECTORY_INDEX_NAMES = listOf("index.md", "README.md")

        /** The frozen, append-only scheme allowlist (§A2). Compared case-insensitively. */
        val ALLOWED_SCHEMES = setOf("http", "https", "mailto")

        /** RFC 3986 scheme grammar: `ALPHA *( ALPHA / DIGIT / "+" / "-" / "." ) ":"`. */
        val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")

        /** Returns the scheme of [link] if it begins with an RFC-3986 scheme prefix, else null. */
        fun schemePrefix(link: String): String? = SCHEME.find(link)?.groupValues?.get(1)

        /**
         * Splits [s] at the FIRST occurrence of [delim], returning (before, after) where `after` is
         * null when the delimiter is absent (so an empty trailing component is distinguishable from
         * an absent one). The delimiter character itself is dropped.
         */
        fun splitOnce(s: String, delim: Char): Pair<String, String?> {
            val idx = s.indexOf(delim)
            return if (idx < 0) s to null else s.substring(0, idx) to s.substring(idx + 1)
        }
    }
}
