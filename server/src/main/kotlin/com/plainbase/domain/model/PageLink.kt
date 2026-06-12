package com.plainbase.domain.model

/**
 * One link/image occurrence on a rendered page: the raw [target] exactly as written in the
 * Markdown, the link's [text] content (alt text for images, the address for autolinks), and the
 * PB-LINK-1 [outcome] the resolver produced at render time.
 *
 * This is the link checker's unit of input (chunk 8): outcomes are resolved exactly once, during
 * render, and aggregated straight off the published index — the checker never re-resolves, so
 * [target]/[text] must be captured here or the broken-link report could not name what broke.
 * Pure domain code.
 */
data class PageLink(
    val target: String,
    val text: String,
    val outcome: LinkOutcome,
)
