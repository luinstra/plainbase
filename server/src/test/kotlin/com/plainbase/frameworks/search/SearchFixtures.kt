package com.plainbase.frameworks.search

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.search.PageDocuments
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.search.SectionDocument
import java.nio.file.Files
import java.nio.file.Path

/** Deterministic test ids: canonical-shape UUIDs whose last group carries [n] (byte order == numeric order). */
fun pageId(n: Int): PageId = PageId.require("0197aaaa-0000-7000-8000-%012x".format(n))

fun section(
    pageId: PageId,
    path: TreePath,
    title: String,
    headingId: String? = null,
    heading: String? = null,
    body: String = "",
    tags: List<String> = emptyList(),
    aliases: List<String> = emptyList(),
    owner: String? = null,
    status: String = "active",
) = SectionDocument(
    pageId = pageId,
    headingId = headingId,
    title = title,
    heading = heading,
    headingPath = heading?.let { listOf(title, it) } ?: emptyList(),
    body = body,
    tags = tags,
    aliases = aliases,
    owner = owner,
    path = path,
    status = status,
)

/** One page whose sections are (headingId to body) pairs after the page-level document. */
fun pageDocuments(
    n: Int,
    path: String = "docs/page-$n.md",
    title: String = "Page $n",
    contentHash: String = "sha256:$n",
    tags: List<String> = emptyList(),
    aliases: List<String> = emptyList(),
    owner: String? = null,
    status: String = "active",
    preamble: String = "",
    sections: List<Pair<String, String>> = emptyList(),
): PageDocuments {
    val id = pageId(n)
    val treePath = TreePath.require(path)
    fun doc(headingId: String?, heading: String?, body: String) =
        section(id, treePath, title, headingId, heading, body, tags, aliases, owner, status)
    return PageDocuments(
        pageId = id,
        contentHash = contentHash,
        path = treePath,
        sections = listOf(doc(null, null, preamble)) + sections.map { (headingId, body) -> doc(headingId, headingId, body) },
    )
}

fun query(text: String, limit: Int = 20, offset: Int = 0, statusFilter: Set<String>? = null) =
    SearchQuery(text = text, limit = limit, offset = offset, statusFilter = statusFilter)

/**
 * A fresh file-backed [SearchDb] + provider in a temp dir; always closed and cleaned up.
 * Inline so suspend callers (the property tests) work.
 */
inline fun <T> withProvider(block: (Fts5SearchProvider, Path) -> T): T {
    val dir = Files.createTempDirectory("plainbase-search-test")
    val dbPath = dir.resolve("search.db")
    return try {
        SearchDb(dbPath).use { db -> block(Fts5SearchProvider(db), dbPath) }
    } finally {
        dir.toFile().deleteRecursively()
    }
}
