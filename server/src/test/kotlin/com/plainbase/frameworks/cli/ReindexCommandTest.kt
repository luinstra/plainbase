package com.plainbase.frameworks.cli

import com.plainbase.domain.search.SearchQuery
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files

/**
 * The `plainbase reindex` CLI contract (S8 Resolution 2 / criteria 6–8 JVM half + criterion 14).
 * A temp content tree + temp DATA_DIR drives the real `ReindexCommand.run`; the resulting
 * search.db is reopened independently and queried to prove the rebuild took. The cross-process
 * lock leg holds the DATA_DIR lock and asserts the exact refusal message + exit 1.
 */
class ReindexCommandTest : FunSpec({

    test("reindex exits 0, prints the exact summary line, and the rebuilt search.db answers a known term") {
        withReindexTree { config ->
            // The println summary is the output contract. Logback also writes INFO diagnostics to
            // the test's stdout, so assert the exact summary LINE is present (not the whole buffer).
            val out = captureStdout { ReindexCommand.run(emptyList(), config) shouldBe 0 }
            out.lineSequence().toList() shouldContain "reindex: rebuilt the search index for 2 page(s) under ${config.contentDir}"

            // Reopen the engine independently of the CLI's lifetime and confirm the term is indexed.
            SearchDb(config.searchDatabasePath).use { db ->
                val provider = Fts5SearchProvider(db)
                provider.search(SearchQuery(text = "capacitor", limit = 20, offset = 0)).total shouldBeGreaterThan 0L
            }
        }
    }

    test("extra arguments are a usage error (exit 2)") {
        withReindexTree { config ->
            ReindexCommand.run(listOf("--bogus"), config) shouldBe 2
        }
    }

    test("criterion 14: a running server's DATA_DIR lock makes reindex refuse with exit 1 and write nothing") {
        withReindexTree { config ->
            DataDirLock.tryAcquire(config.dataDir)!!.use {
                val err = captureStderr { ReindexCommand.run(emptyList(), config) shouldBe 1 }
                err shouldContain "a Plainbase server is holding ${config.dataDir}"
                err shouldContain "POST /api/v1/admin/reindex"
                // The refusal happens before any engine open: no search.db was created underneath the server.
                Files.exists(config.searchDatabasePath) shouldBe false
            }
            // After release, a run succeeds.
            captureStdout { ReindexCommand.run(emptyList(), config) shouldBe 0 }
        }
    }
})

/** A two-page tree + fresh DATA_DIR; both cleaned up. */
private fun withReindexTree(block: (PlainbaseConfig) -> Unit) {
    val content = Files.createTempDirectory("pb-reindex-content")
    val data = Files.createTempDirectory("pb-reindex-data")
    try {
        Files.writeString(content.resolve("alpha.md"), "---\ntitle: Alpha\n---\n\n# Alpha\n\nfind the flux capacitor here.\n")
        Files.writeString(content.resolve("beta.md"), "---\ntitle: Beta\n---\n\n# Beta\n\nplain filler text.\n")
        block(PlainbaseConfig(contentDir = content, dataDir = data, host = "127.0.0.1", port = 0))
    } finally {
        listOf(content, data).forEach { dir ->
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

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

private fun captureStderr(block: () -> Unit): String {
    val buffer = ByteArrayOutputStream()
    val previous = System.err
    System.setErr(PrintStream(buffer, true, Charsets.UTF_8))
    try {
        block()
    } finally {
        System.setErr(previous)
    }
    return buffer.toString(Charsets.UTF_8)
}
