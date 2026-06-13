package com.plainbase.domain.service

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.page.FrontmatterParser
import com.plainbase.domain.page.PageIndexView
import com.plainbase.domain.page.UuidV7
import com.plainbase.domain.render.MarkdownRenderer
import com.plainbase.domain.repository.replaceFrom
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
import com.plainbase.frameworks.sqldelight.SqlDelightUrlAliasRepository
import java.nio.file.Files
import java.nio.file.Path

/**
 * The chunk-5 integration harness: a real [IndexBuilder] over a real tree ([LocalContentStore]),
 * real rendering ([FlexmarkRenderer]), and real persistence (in-memory SQLite repos) — the same
 * wiring `indexModule` produces, minus HTTP. One harness = one DATA_DIR lifetime, so successive
 * [IndexBuilder.rebuild]s exercise rescan semantics (move aliases, issue accumulation) for real.
 *
 * [contentStore], [frontmatterParser], and [rendererFactory] are injectable so the one-pass
 * counting test can wrap them.
 */
class IndexHarness(
    root: Path,
    contentStore: ContentStore = LocalContentStore(root),
    frontmatterParser: FrontmatterParser = FrontmatterReader(),
    rendererFactory: (PageIndexView) -> MarkdownRenderer = { view -> FlexmarkRenderer(view) },
    listeners: List<IndexBuilder.PublicationListener> = emptyList(),
) : AutoCloseable {

    private val driver = DatabaseFactory.createInMemoryDriver()
    private val database = DatabaseFactory.createDatabase(driver)

    val idMap = SqlDelightIdMapRepository(database)
    val aliases = SqlDelightUrlAliasRepository(database)
    val registry = UrlAliasRegistry(aliases)
    val checkpoints = SqlDelightPageCheckpointRepository(database)
    val builder = IndexBuilder(
        contentStore = contentStore,
        frontmatterParser = frontmatterParser,
        rendererFactory = rendererFactory,
        identity = PageIdentityService(UuidV7()),
        patcher = FrontmatterPatcher(),
        idMap = idMap,
        aliasRegistry = registry,
        checkpoint = checkpoints,
        citations = CitationFactory(),
        // The §B3 checkpoint-replace listener is part of the production graph (checkpointModule),
        // so the harness always registers it first — callers' listeners follow, as in `getAll()`.
        listeners = listOf(IndexBuilder.PublicationListener(checkpoints::replaceFrom)) + listeners,
    )

    override fun close() = driver.close()
}

/** Runs [block] with a fresh temp content tree seeded by [seed]; always cleans up. */
fun <T> withTempTree(seed: (Path) -> Unit, block: (Path) -> T): T {
    val root = Files.createTempDirectory("plainbase-index-test")
    return try {
        seed(root)
        block(root)
    } finally {
        root.toFile().deleteRecursively()
    }
}

/** Writes a page file (creating parents) under [root]. */
fun writePage(root: Path, relativePath: String, content: String) {
    val target = root.resolve(relativePath)
    Files.createDirectories(target.parent)
    Files.writeString(target, content)
}

/**
 * The Phase-1 generated-corpus page (the 1,000-page scale tests): small but realistic —
 * frontmatter, two headings, one internal link to a sibling. Shared by the chunk-5 index-pass
 * scale test and the S2 search-corpus perf test (which reuses this generator by plan).
 */
fun pageContent(n: Int): String = buildString {
    appendLine("---")
    appendLine("title: Page %03d".format(n))
    appendLine("---")
    appendLine()
    appendLine("# Page %03d".format(n))
    appendLine()
    appendLine("Body text for page $n with a [sibling link](page-%03d.md).".format((n / 10) * 10))
    appendLine()
    appendLine("## Details")
    appendLine()
    appendLine("More text.")
}
