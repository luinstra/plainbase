package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.page.PageIndexView
import com.plainbase.domain.page.RootSection
import com.plainbase.domain.render.MarkdownRenderer
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath

/** Builds a render-complete snapshot from materialized scans and resolved identities. */
internal class IndexSnapshotAssembler(
    private val rendererFactory: (PageIndexView) -> MarkdownRenderer,
    private val citations: CitationFactory,
) {

    /** [roots] supplies the unique registered roots in publication order, including every scanned root. */
    fun assemble(
        scans: List<SourceScan>,
        identities: Map<RootedPath, Identity>,
        roots: List<RootName>,
        previous: PageIndex,
    ): PageIndex {
        // Build the complete URL skeleton from resolved identities before rendering links between pages.
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
                        sections = rendered.sections.toList(),
                    )
                },
            )
        }

        // Carry only when a root has no scanned section; even an incomplete scan takes precedence.
        // Scanned and carried roots are disjoint, so their rooted page ids cannot collide.
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
            // Keep the served payload derived from the same read as frontmatter and the hash.
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
