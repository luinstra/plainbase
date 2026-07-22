package com.plainbase.domain.root

import com.plainbase.domain.page.PageId

/**
 * A page's real identity under multi-root: the [PageId] qualified by the [root] that holds it. The seam
 * type every id-bearing surface funnels through, sibling of [RootedPath]/[BindingRef] (pure domain).
 *
 * Introduced ahead of its consumers so there is ONE type to key on rather than scattered `(root, id)`
 * parameter pairs. The single funnel is [com.plainbase.domain.page.IndexedPage.rooted].
 */
data class RootedPageId(val root: RootName, val id: PageId) {

    /** The permanent ID permalink (§A4's durability layer). One definition, owned by [Permalink]. */
    val permalink: String get() = Permalink.of(id)
}

/**
 * The ONE definition of a page's permalink string. [of] is the sole constructor: [IndexedPage],
 * the create-identity fallback, and the alias-target arm all route through it, so the format lives
 * in a single place.
 *
 * The owning root does NOT shape the string: every permalink this EMITS is the bare `/p/{id}`, byte-identical
 * to what `PageId.permalink` emitted before the seam. C4 ships the root-qualified
 * `/p/r/{root}/{id}` as a ROUTE - the disambiguation surface a 300/409 hands out - not as the canonical
 * form. Emitting it from here is the later change, and it is the one that would move bytes on every wire.
 */
object Permalink {

    fun of(id: PageId): String = "/p/${id.value}"
}
