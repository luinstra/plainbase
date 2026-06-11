package com.plainbase.domain.page

/**
 * A page's parsed frontmatter values — the documented YAML subset (§C2): string scalars and
 * lists of string scalars. Unknown top-level keys within the subset are preserved; anything OUTSIDE
 * the subset — nested maps, anchors/aliases, tags, block (multiline) scalars — is **dropped with a
 * per-page warning** by the reader (`FrontmatterReader`), never surfaced here. (This is the actual
 * behavior: the reader enforces the subset against the block text, so the leaked shallow-nested keys
 * flexmark's metadata regex would otherwise emit do not appear.)
 *
 * [FrontmatterValue] distinguishes a scalar from a list so the REST `frontmatter` object (§A4) can
 * emit a JSON string vs a JSON array of strings without re-parsing. An absent block is
 * [Frontmatter.EMPTY] (`{}` in the API), not null — "no frontmatter" and "empty frontmatter" are
 * the same observable shape.
 *
 * List-shape caveat (consumers must tolerate it): a single-item BLOCK list collapses to a
 * [FrontmatterValue.Scalar] while a flow list or a multi-item block list yields a
 * [FrontmatterValue.StringList] — the extension visitor erases the distinction below this layer, so
 * a list-typed key (`tags`, `redirect_from`) must be read as scalar-OR-list, never assumed to be a
 * list.
 *
 * Pure domain code: the model carries no parser type. Extraction (the flexmark visitor + quote
 * normalization) lives in `frameworks/markdown/FrontmatterReader`.
 */
data class Frontmatter(val values: Map<String, FrontmatterValue>) {

    /** The value for [key], or null when absent. */
    operator fun get(key: String): FrontmatterValue? = values[key]

    /** The scalar value for [key], or null when absent or a list. */
    fun scalar(key: String): String? = (values[key] as? FrontmatterValue.Scalar)?.value

    companion object {
        /** The shared no-frontmatter / empty-block value (`{}` in the API). */
        val EMPTY: Frontmatter = Frontmatter(emptyMap())
    }
}

/** A frontmatter value in the documented subset: either a single scalar or a list of scalars (§C2). */
sealed interface FrontmatterValue {

    /** A single string scalar (matching surrounding quotes already stripped). */
    data class Scalar(val value: String) : FrontmatterValue

    /** A flow (`[a, b]`) or block (`- a` lines) list of string scalars. */
    data class StringList(val values: List<String>) : FrontmatterValue
}
