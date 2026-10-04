package com.plainbase.frameworks.search

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.search.PageDocuments
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.search.SectionDocument
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Native-image smoke for the S2 search stack: without this test the closed-world image would
 * never compile the production `SearchDb`/`Fts5SearchProvider` path under TEST (the spike covers
 * the binary's command path; this covers the test image). File-backed db → index → search →
 * bm25 ordering → snippet offsets, via the production provider only.
 *
 * @Tag("native") + kotlin.test only — this source set compiles INTO the native test image.
 */
@Tag("native")
class Fts5SearchProviderNativeTest {

    @Test
    fun `body snippets and bounded Unicode fallback stay within the indexed root`() {
        val dir = Files.createTempDirectory("pb-native-body-snippet")
        try {
            SearchDb(dir.resolve("search.db")).use { db ->
                val provider = Fts5SearchProvider(db)
                val original = page("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a", "body.md", "Needle", "")
                val extra = RootName.require("extra")
                fun withBody(body: String, root: RootName) = original.copy(
                    root = root,
                    sections = original.sections +
                        original.sections.single().copy(headingId = "intro", heading = "Introduction", body = body),
                )
                provider.rebuild(sequenceOf(withBody("😀".repeat(245), RootName.PRIMARY), withBody("Other body.", extra)))
                for (term in listOf("needle", "eed")) {
                    val hits = provider.search(SearchQuery(term, 10, 0)).hits
                    val primary = hits.single { it.root == RootName.PRIMARY && it.headingId == null }
                    assertEquals("😀".repeat(240) + "…", primary.snippet)
                    assertTrue(primary.highlights.isEmpty())
                    assertEquals("Other body.", hits.single { it.root == extra && it.headingId == null }.snippet)
                }
                val bodyHit = provider.search(SearchQuery("Other", 10, 0)).hits.single()
                assertEquals(extra, bodyHit.root)
                assertEquals(listOf("Other"), bodyHit.highlights.map { bodyHit.snippet.substring(it.start, it.end) })
            }
        } finally {
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `file db index, search, bm25 ordering, and snippet offsets work natively`() {
        val dir = Files.createTempDirectory("pb-native-search")
        try {
            SearchDb(dir.resolve("search.db")).use { db ->
                val provider = Fts5SearchProvider(db)
                provider.index(
                    listOf(
                        page("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a", "guides/deploy.md", "Deploy Guide", "intro to the cluster"),
                        page("0197b1c0-5e2a-7b34-9c1d-2f6a8e4b7d01", "index.md", "Welcome", "deploy the deploy of deploys"),
                    ),
                )

                val results = provider.search(SearchQuery("deploy", limit = 10, offset = 0))
                assertEquals(2L, results.total)
                // bm25 title weight: the title-hit page outranks the body-repeats page.
                assertEquals("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a", results.hits.first().pageId.value)
                assertTrue(results.hits.zipWithNext().all { (a, b) -> a.score >= b.score })

                val cluster = provider.search(SearchQuery("cluster", 10, 0)).hits.single()
                assertTrue('\u0001' !in cluster.snippet && '\u0002' !in cluster.snippet, "sentinels leaked")
                assertTrue(cluster.highlights.isNotEmpty(), "no highlight offsets")
                cluster.highlights.forEach { h ->
                    assertEquals("cluster", cluster.snippet.substring(h.start, h.end).lowercase())
                }

                val state = provider.indexedState()
                assertEquals(2, state.size)
                // The rooted key round-trips through the engine's own state (the same JDBC surface).
                assertTrue(state.keys.all { it.root == RootName.PRIMARY }, "rooted key did not round-trip")

                // Two roots sharing ONE id coexist across the JNI boundary. rebuild (not index) so the swap
                // replaces the whole corpus and indexedState is EXACTLY the pair.
                val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
                val extraRoot = RootName.require("extra")
                provider.rebuild(
                    sequenceOf(
                        page(id.value, "a.md", "Twin", "coexist body", root = RootName.PRIMARY),
                        page(id.value, "b.md", "Twin", "coexist body", root = extraRoot),
                    ),
                )
                assertEquals(setOf(RootedPageId(RootName.PRIMARY, id), RootedPageId(extraRoot, id)), provider.indexedState().keys)
                val hits = provider.search(SearchQuery("coexist", 10, 0)).hits
                assertEquals(2, hits.size)
                assertEquals(setOf(RootName.PRIMARY, extraRoot), hits.map { it.root }.toSet())
                for (term in listOf("coexist", "exist")) {
                    val scoped = SearchQuery(term, 1, 0, statusFilter = setOf("active"), rootFilter = extraRoot)
                    assertEquals(1L, provider.search(scoped).total)
                    assertEquals(extraRoot, provider.search(scoped).hits.single().root)
                    assertTrue(provider.search(scoped.copy(offset = 1)).hits.isEmpty())
                    assertEquals(1L, provider.search(scoped.copy(offset = 1)).total)
                }
            }
        } finally {
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun page(id: String, path: String, title: String, body: String, root: RootName = RootName.PRIMARY): PageDocuments {
        val pageId = PageId.require(id)
        val treePath = TreePath.require(path)
        val doc = SectionDocument(
            pageId = pageId,
            headingId = null,
            title = title,
            heading = null,
            headingPath = emptyList(),
            body = body,
            tags = emptyList(),
            owner = null,
            aliases = emptyList(),
            path = treePath,
            status = "active",
        )
        return PageDocuments(pageId = pageId, contentHash = "sha256:$title", root = root, path = treePath, sections = listOf(doc))
    }
}
