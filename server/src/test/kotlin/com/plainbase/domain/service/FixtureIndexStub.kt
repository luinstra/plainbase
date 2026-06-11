package com.plainbase.domain.service

import com.plainbase.domain.content.Nfc
import com.plainbase.domain.content.PercentCoding
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageIndexView
import com.plainbase.domain.render.HeadingSlugger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * A stub [PageIndexView] over a real on-disk tree (the committed `fixtures/demo-docs`), used by the
 * chunk-2 PB-LINK-1 golden test and the contract-smoke spike. It implements §A4 canonical-URL
 * construction (the job that becomes chunk 5's `CanonicalUrlBuilder`) so the resolver can emit the
 * `/docs/...` URLs the golden table predicts — without depending on chunk 5.
 *
 * Page slug = frontmatter `slug:` if present, else the filename stem, both passed through
 * PB-SLUG-1 steps 1–6 ([HeadingSlugger.slugify]). Directory segments are slugified the same way
 * (no `_folder.yaml` slug overrides exist in the fixtures). No collision handling is needed for the
 * golden path set.
 *
 * Pure test infrastructure: it imports the same chunk-1.5 path types the resolver does and zero
 * flexmark types (the only `slug:` read is a trivial line scan, not a Markdown parse).
 */
class FixtureIndexStub(root: Path) : PageIndexView {

    private val kinds = HashMap<String, PageIndexView.EntryKind>()
    private val pageUrls = HashMap<String, String>()
    private val frontmatterSlug = HashMap<String, String>()

    init {
        // First pass: read frontmatter slugs for pages (needed before URL construction).
        Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() && it.name.endsWith(".md") }.forEach { file ->
                readSlug(file)?.let { frontmatterSlug[relative(root, file)] = it }
            }
        }
        // Second pass: classify every entry and precompute page URLs.
        Files.walk(root).use { stream ->
            stream.forEach { p ->
                if (p == root) return@forEach
                val rel = relative(root, p)
                when {
                    p.isDirectory() -> kinds[rel] = PageIndexView.EntryKind.DIRECTORY
                    p.name.endsWith(".md") -> {
                        kinds[rel] = PageIndexView.EntryKind.PAGE
                        pageUrls[rel] = buildPageUrl(rel)
                    }
                    p.isRegularFile() -> kinds[rel] = PageIndexView.EntryKind.ASSET
                }
            }
        }
    }

    override fun kindOf(path: TreePath): PageIndexView.EntryKind? = kinds[path.value]

    override fun pageUrl(page: TreePath): String =
        pageUrls[page.value] ?: error("pageUrl called on a non-page path: ${page.value}")

    override fun assetUrl(asset: TreePath): String =
        "/assets/" + PercentCoding.encodePath(asset.value)

    override fun caseInsensitiveMatches(path: TreePath): List<TreePath> {
        val target = path.value.lowercase()
        return kinds.keys
            .filter { it.lowercase() == target && it != path.value }
            .map { TreePath.require(it) }
    }

    /** §A4: `/docs/` + slugified ancestor dir segments + `/` + page slug (no trailing slash). */
    private fun buildPageUrl(relPath: String): String {
        val segments = relPath.split("/")
        val dirSegments = segments.dropLast(1)
        val fileName = segments.last()
        val stem = fileName.removeSuffix(".md")
        val pageSlugSource = frontmatterSlug[relPath] ?: stem

        val slugged = dirSegments.map { HeadingSlugger.slugify(it, HeadingSlugger.FOLDER_FALLBACK) } +
            HeadingSlugger.slugify(pageSlugSource, HeadingSlugger.PAGE_FALLBACK)
        // Unicode slugs are percent-encoded on the wire (§A4/§A2).
        return "/docs/" + slugged.joinToString("/") { PercentCoding.encodeSegment(it) }
    }

    /**
     * Content-root-relative key for [p], NFC-normalized. The NFC pass mirrors the production
     * ContentStore boundary rule ("everything humans, files, URLs, and agents see is NFC") so the
     * stub keys match the [TreePath]s the resolver hands in — it is the boundary rule, not extra
     * stub robustness.
     */
    private fun relative(root: Path, p: Path): String =
        Nfc.normalize(root.relativize(p).toString().replace('\\', '/'))

    /** Reads a top-level `slug:` value from a page's frontmatter block, or null. Trivial line scan. */
    private fun readSlug(file: Path): String? {
        val lines = Files.readAllLines(file)
        if (lines.isEmpty() || lines.first().trim() != "---") return null
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.trim() == "---" || line.trim() == "...") return null
            val m = Regex("^slug:\\s*(.+?)\\s*$").find(line) ?: continue
            return m.groupValues[1].trim().trim('"', '\'')
        }
        return null
    }
}
