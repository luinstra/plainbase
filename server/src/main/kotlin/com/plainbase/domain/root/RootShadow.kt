package com.plainbase.domain.root

import com.plainbase.domain.content.TreePath

/**
 * Main's top-level segment space (multi-root C5, D-C5-6): what a root NAME is matched against by
 * `splitRootTail`, which treats a tail's first segment as a root iff it names a registered root.
 *
 * The moment a root named `guides` is added to a main that already has a top-level `guides/` directory -
 * or a page whose frontmatter says `slug: guides`, or a folder whose `_folder.yaml` says `slug: guides`,
 * or an alias row at `guides/...` - every circulating link through that segment stops resolving inside
 * main and starts resolving inside the new root. That is a live-link correctness break, not a cosmetic one.
 *
 * **TWO grammars, and a root name shadows a segment in EITHER.** The four `splitRootTail` callers do not
 * all speak the same path language: `/docs` and `/api/v1/pages/by-path` match the SLUGIFIED url space (a
 * page's `slug:`, a folder's `_folder.yaml slug:`), while `/browse` and `/assets` match the RAW (NFC, never
 * slugified) content tree. A check that only listed directories would sail `root add guides` straight past
 * a `/docs/guides` that already resolves to a PAGE.
 */
object RootShadow {

    /**
     * The index both consumers share, keyed by FIRST SEGMENT and valued by the offending paths (every
     * consumer's message has to name them).
     *
     * [urlPaths] is the slugified `/docs` + `by-path` + alias space; [contentPaths] is the raw `/browse` +
     * `/assets` space. Boot feeds it from the built snapshot plus main's `url_alias` rows; the CLI feeds it
     * from a plain content scan (it opens no database, so the alias rows are the one surface it structurally
     * cannot see - which is the whole reason the boot WARN exists rather than being "the CLI's check, later").
     *
     * Same-role slug-collision LOSERS need no special handling: a loser carries a null url path, but its
     * WINNER carries the identical segment, so no loser can remove a segment from the set.
     */
    fun topLevelIndex(urlPaths: Collection<TreePath>, contentPaths: Collection<TreePath>): Map<String, List<TreePath>> =
        (urlPaths + contentPaths).groupBy { it.segments.first() }
}
