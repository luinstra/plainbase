package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.page.PageIndexView
import com.plainbase.domain.page.RootSection
import com.plainbase.domain.render.MarkdownRenderer
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath

/** Builds the eager, render-complete snapshot from materialized scans and already-resolved identities.
 * Reading, identity authority, aliases, holder publication, limbo derivation, and listeners remain coordinator-owned.
 */
internal class IndexSnapshotAssembler(
    private val rendererFactory: (PageIndexView) -> MarkdownRenderer,
    private val citations: CitationFactory,
) {

    /**
     * Builds every scanned root against the single URL-complete provisional snapshot.
     *
     * [roots] is the builder's already-ranked, unique source-root list. It contains every root represented by [scans],
     * in publication order, and is used only to retain the existing scanned-first carry rule for skipped roots.
     */
    fun assemble(
        scans: List<SourceScan>,
        identities: Map<RootedPath, Identity>,
        roots: List<RootName>,
        previous: PageIndex,
    ): PageIndex {
        // Build ALL provisional sections, then render each root's pages against ITS view of the
        // ONE URL-complete skeleton (identity and URLs final; render fields filled below).
        val provisionalSections = scans.map { scan ->
            RootSection(
                root = scan.root,
                pages = scan.drafts.map { draft ->
                    provisionalPage(scan, draft, identities.getValue(RootedPath(scan.root, draft.file.path)))
                },
                folders = scan.folders,
                assets = scan.assets,
            )
        }
        val provisional = PageIndex(provisionalSections)
        val scanned = scans.zip(provisionalSections) { scan, section ->
            val renderer = rendererFactory(provisional.view(scan.root))
            section.copy(
                pages = section.pages.zip(scan.drafts) { page, draft ->
                    val rendered = renderer.render(page.path, draft.bytes)
                    page.copy(
                        title = draft.frontmatter.scalar("title")
                            ?: rendered.headings.firstOrNull { it.level == 1 }?.text
                            ?: page.path.stem,
                        html = rendered.html,
                        headings = rendered.headings.toList(),
                        links = rendered.links.toList(),
                        // The search sections are captured from the SAME single render: no extra read,
                        // no second parse (see the IndexedPage.sections doc for the accepted memory cost).
                        sections = rendered.sections.toList(),
                    )
                },
            )
        }

        // Carry each SKIPPED root's last-good section forward, so omission alone cannot remove its published pages
        // from a listener's projection (a never-scanned root has no previous section and simply contributes none -
        // `section` is total). Search independently excludes current durable retired-unbound identities. In the
        // builder's ranked [roots] order, like the sources themselves, so the snapshot is deterministic either way.
        //
        // Nothing is filtered out of a carried section. A carried page's ROOTED id cannot also appear in the scanned
        // pages. The load-bearing reason is PROVENANCE: every page in a section carries that section's own root
        // (true at each producer, and asserted by PageIndex's init), the elvis below fires only for a root with NO
        // scanned section, and RootedPageId equality includes the root - so a carried page's rooted id cannot appear
        // among the scanned ones. That proof depends on the elvis meaning exactly "no scanned section for this root".
        // Re-key the carry on scan COMPLETENESS and a carried root could coexist with a scanned section of its own
        // root, at which point the deadness needs re-proving. If that ever happens it now fails LOUDLY in PageIndex's
        // init - on the duplicate-root-section `require` if the carry adds a second section for the root, or on the
        // duplicate-(root, id) `check` if the pages were merged into the scanned one - rather than silently dropping
        // the page and logging a warning, which is the better of the two failures.
        val sections = roots.mapNotNull { root ->
            scanned.firstOrNull { it.root == root }
                ?: previous.sections.firstOrNull { it.root == root }
        }

        return PageIndex(sections)
    }

    /** The URL-complete, render-empty skeleton page for one draft. */
    private fun provisionalPage(scan: SourceScan, draft: Draft, identityOf: Identity): IndexedPage {
        val assignment = scan.urls.byPage.getValue(draft.file.path)
        return IndexedPage(
            id = identityOf.id,
            root = scan.root,
            path = draft.file.path,
            slug = assignment.slug,
            urlPath = assignment.urlPath,
            title = draft.file.path.stem,
            frontmatter = draft.frontmatter,
            materialized = identityOf.materialized,
            // Captured from the one read, alongside everything else the page serves: the
            // payload a request answers with is coherent BY CONSTRUCTION (see IndexedPage doc).
            markdown = String(draft.bytes, Charsets.UTF_8),
            contentHash = citations.contentHash(draft.bytes),
            commit = scan.commits[draft.file.path]?.sha,
            html = "",
            headings = emptyList(),
            links = emptyList(),
            sections = emptyList(),
        )
    }

    private val TreePath.stem: String get() = name.removeSuffix(".md")
}
