package com.plainbase.domain.page

/**
 * A single rendered heading: its allocated PB-SLUG-1 anchor [id], its [level] (h1–h6), and the
 * §A1 [text] content the slugger consumed. Document order is the order these appear in a page's
 * [com.plainbase.domain.render.RenderedPage.headings] list.
 *
 * This is the per-heading element of the PB-REST-1 `headings` payload (§A4 `/html` shape) — the
 * Phase 5 `heading_not_found` recovery list and the SPA's TOC. Pure domain code.
 */
data class Heading(
    /** The page-unique anchor id (PB-SLUG-1 steps 1–7, allocated in document order). */
    val id: String,
    /** The heading level, 1–6. */
    val level: Int,
    /** The §A1 text content the slugger received (markup stripped, code/alt/link text kept). */
    val text: String,
)
