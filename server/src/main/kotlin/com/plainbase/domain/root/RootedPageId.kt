package com.plainbase.domain.root

import com.plainbase.domain.page.PageId

/**
 * A page's real identity under multi-root: the [PageId] qualified by the [root] that holds it (ADR-0012),
 * sibling of [RootedPath]/[BindingRef] (pure domain).
 *
 * Used wherever the pair travels as a VALUE - snapshot keys, retirement sets, permalink targets - so there is
 * ONE type to key on. It is deliberately NOT universal at the seams: several signatures still take `root` and
 * `id` separately, and the bare-ID read entrypoints (`ReadFacade.pageById`/`pageHtml`/`validateLinks`/
 * `pageMetadata`, whose `?root=` is optional) take a NULLABLE root this type cannot express. A page's own
 * identity value is [com.plainbase.domain.page.IndexedPage.rooted].
 */
data class RootedPageId(val root: RootName, val id: PageId) {

    /** The permanent ID permalink (§A4's durability layer). One definition, owned by [Permalink]. */
    val permalink: String get() = Permalink.of(root, id)
}

/**
 * The ONE definition of a page's permalink string. [of] is the sole constructor: [IndexedPage],
 * the create-identity fallback, and the alias-target arm all route through it, so the format lives
 * in a single place.
 *
 * The owning root SHAPES the string (per-root identity, C5): every permalink is the rooted `/p/{root}/{id}`, so
 * two roots holding the same id emit DIFFERENT permalinks. [RootName] is a validated slug and [PageId] is
 * canonical lowercase UUID text, so neither segment needs escaping.
 */
object Permalink {

    fun of(root: RootName, id: PageId): String = "/${ServerTopLevel.PERMALINK}/${root.value}/${id.value}"
}
