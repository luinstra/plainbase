package com.plainbase.frameworks.cli

import com.plainbase.domain.search.SearchQuery
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import org.junit.jupiter.api.Tag
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Native-image smoke for the `plainbase reindex` path (S8 criterion 8): the closed-world image
 * would otherwise never exercise `ReindexCommand.run` over the real `createDriver` + file-backed
 * `SearchDb` + `IndexBuilder.rebuildSearchIndex()` generation swap — the same FTS5/JDBC/native-load
 * surface the search-route smoke guards, now over the offline CLI. kotlin.test + @Tag("native")
 * only (this source set compiles INTO the native test image; no Kotest/MockK).
 */
@Tag("native")
class ReindexCommandNativeTest {

    @Test
    fun `reindex builds search dot db for the content tree and the indexed term is findable`() {
        val content = Files.createTempDirectory("pb-native-reindex-content")
        val data = Files.createTempDirectory("pb-native-reindex-data")
        try {
            Files.writeString(content.resolve("guide.md"), "---\ntitle: Guide\n---\n\n# Guide\n\nfind the flux capacitor here.\n")
            val config = PlainbaseConfig(contentDir = content, dataDir = data, host = "127.0.0.1", port = 0)

            val out = captureStdout { assertEquals(0, ReindexCommand.run(emptyList(), config)) }
            assertContains(out, "reindex:")

            SearchDb(config.searchDatabasePath).use { db ->
                val provider = Fts5SearchProvider(db)
                val total = provider.search(SearchQuery(text = "capacitor", limit = 20, offset = 0)).total
                assertTrue(total > 0L, "the reindexed term was not findable in the native image")
            }
        } finally {
            listOf(content, data).forEach { dir ->
                Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
    }
}

/** Captures System.out for the duration of [block] — reindex's stdout is its output contract. */
private fun captureStdout(block: () -> Unit): String {
    val buffer = ByteArrayOutputStream()
    val previous = System.out
    System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
    try {
        block()
    } finally {
        System.setOut(previous)
    }
    return buffer.toString(Charsets.UTF_8)
}
