package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.nio.file.Path

internal data class ReaderPage(
    val id: PageId,
    val path: String,
    val title: String,
    val aliases: List<String> = emptyList(),
    val description: String? = null,
) {
    val rooted: RootedPageId get() = RootedPageId(RootName.PRIMARY, id)
    val markdown: String = buildString {
        append("---\nid: ${id.value}\ntitle: $title\n")
        description?.let { append("description: $it\n") }
        if (aliases.isNotEmpty()) append("redirect_from: [${aliases.joinToString(", ")}]\n")
        append("---\n\n# $title\n\n$title content.\n")
    }
}

internal object ReaderPages {
    private val ids = TestIdProvider()
    val R = ReaderPage(ids.next(), "index.md", "Root", description = "Root page")
    val M = ReaderPage(ids.next(), "README.md", "Readme")
    val G = ReaderPage(ids.next(), "guides/index.md", "Guides", listOf("/old-guides"))
    val A = ReaderPage(ids.next(), "guides/alpha.md", "Alpha", listOf("/old/alpha"))
    val S = ReaderPage(ids.next(), "shadow.md", "Shadow")
    val SI = ReaderPage(ids.next(), "shadow/index.md", "Shadow landing")
    val N = ReaderPage(ids.next(), "named/index.md", "Named", listOf("/named"))
    val RF = ReaderPage(ids.next(), "ref/index.md", "Reference")
    val O = ReaderPage(ids.next(), "other.md", "Other", listOf("/ref"))
    val CU = ReaderPage(ids.next(), "custom/index.md", "Custom")
    val D = ReaderPage(ids.next(), "deep/sub/index.md", "Deep")
    val C = ReaderPage(ids.next(), "caf\u00e9/index.md", "Cafe")
    val all = listOf(R, M, G, A, S, SI, N, RF, O, CU, D, C)
    val top = ReaderPage(ids.next(), "top.md", "Top")
    val RL = ReaderPage(ids.next(), "ref2/index.md", "Local reference")
    val goneId = ids.next()
    val absentId = ids.next()
    const val GONE_MARKDOWN = "---\ntitle: Gone\nredirect_from: [/ref2]\n---\n\n# Gone\n\nGone content.\n"
}

internal fun readerCorpus(root: Path) {
    ReaderPages.all.forEach { writePage(root, it.path, it.markdown) }
    writePage(root, "custom/_folder.yaml", "slug: renamed\n")
}

internal fun bareCorpus(root: Path) {
    writePage(root, ReaderPages.top.path, ReaderPages.top.markdown)
}

internal fun limboCorpus(root: Path) {
    listOf(ReaderPages.R, ReaderPages.RL).forEach { writePage(root, it.path, it.markdown) }
    writePage(root, "gone.md", ReaderPages.GONE_MARKDOWN)
}

internal fun assertReaderCorpus(
    snapshot: PageIndex,
    registry: UrlAliasRegistry,
    pages: List<ReaderPage> = ReaderPages.all,
) {
    pages.forEach { page ->
        withClue("fixture ${page.path}") {
            snapshot.pageAt(page.rooted)?.path shouldBe TreePath.require(page.path)
            snapshot.pageAt(page.rooted)?.markdown shouldBe page.markdown
            page.aliases.forEach { alias ->
                registry.find(RootedPath(RootName.PRIMARY, TreePath.require(alias.removePrefix("/")))) shouldBe page.rooted
            }
        }
    }
}
