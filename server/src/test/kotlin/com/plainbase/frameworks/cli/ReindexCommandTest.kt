package com.plainbase.frameworks.cli

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.search.SearchQuery
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.RootsConfig
import com.plainbase.frameworks.config.RootsOrigin
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.config.StorageConfig
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `plainbase reindex` CLI contract (S8 Resolution 2 / criteria 6-8 JVM half + criterion 14).
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

    test(
        "storage.backend=object with an incomplete Q9 matrix fails fast at requireContentDir (exit 1), " +
            "taking no lock and writing nothing - C4 replaced the outright refusal with real object wiring, " +
            "but requireContentDir()'s pre-lock Q9 gate still refuses a config missing its required keys",
    ) {
        withReindexTree { config ->
            val objectConfig = config.copy(storage = StorageConfig(backend = StorageBackend.OBJECT))
            val err = captureStderr { ReindexCommand.run(emptyList(), objectConfig) shouldBe 1 }
            err shouldNotContain "storage.backend=object is configured but the object backend is not available"
            // The gate precedes the lock and any driver open: no db/search.db, and the lock is free.
            Files.exists(objectConfig.appDatabasePath) shouldBe false
            Files.exists(objectConfig.searchDatabasePath) shouldBe false
            DataDirLock.tryAcquire(objectConfig.dataDir)!!.use { }
        }
    }

    test(
        "storage.backend=object, fully configured but unreachable: reindex hydrates first, fails fast and " +
            "actionably (exit 1) rather than silently reindexing an empty/stale mirror",
    ) {
        withReindexTree { config ->
            val objectConfig = config.copy(
                storage = StorageConfig(
                    backend = StorageBackend.OBJECT,
                    endpoint = "https://127.0.0.1:9", // loopback, nothing listening - fails fast, not a timeout
                    bucket = "docs",
                    accessKeyId = "k",
                    secretAccessKey = "s",
                ),
            )
            // The failure is logged via the facade (logger.error), not println - the exit code is the contract.
            captureStderr { ReindexCommand.run(emptyList(), objectConfig) shouldBe 1 }
        }
    }

    test("a multi-root reindex covers EVERY configured root: both roots' documents survive the swap, and the count is the whole corpus") {
        withTwoRootTree { config, _ ->
            val out = captureStdout { ReindexCommand.run(emptyList(), config) shouldBe 0 }
            out.lineSequence().toList() shouldContain
                "reindex: rebuilt the search index for 3 page(s) across 2 roots: main (2), handbook (1)"

            SearchDb(config.searchDatabasePath).use { db ->
                val provider = Fts5SearchProvider(db)
                // The regression this pins: a main-only source list would leave the engine holding `main` alone,
                // because the rebuild is a GENERATION SWAP - every root it does not see is deleted from the index.
                provider.indexedState().values.map { it.root }.toSet() shouldBe setOf(RootName.MAIN, HANDBOOK)
                provider.search(SearchQuery(text = "capacitor", limit = 20, offset = 0)).total shouldBeGreaterThan 0L
                provider.search(SearchQuery(text = "onboarding", limit = 20, offset = 0)).total shouldBeGreaterThan 0L
            }
        }
    }

    test("an unavailable extra root refuses the whole reindex (exit 1) rather than silently purging that root's search rows") {
        withTwoRootTree { config, handbook ->
            captureStdout { ReindexCommand.run(emptyList(), config) shouldBe 0 } // seed the engine with BOTH roots
            Files.delete(handbook.resolve("onboarding.md"))
            Files.delete(handbook) // the NAS came unmounted; config validation still accepts this (ADR-0011 D13)

            val err = captureStderr { ReindexCommand.run(emptyList(), config) shouldBe 1 }
            err shouldContain "root 'handbook' is not available"
            err shouldContain "refusing to rebuild"

            // Nothing was swapped: the vanished root's documents are still in the engine, and come back when it does.
            SearchDb(config.searchDatabasePath).use { db ->
                Fts5SearchProvider(db).indexedState().values.map { it.root }.toSet() shouldBe setOf(RootName.MAIN, HANDBOOK)
            }
        }
    }

    test(
        "a root that vanishes MID-REBUILD - past the preflight - aborts before the swap (exit 1), leaving the PRIOR " +
            "search generation intact rather than silently purging the root that went away",
    ) {
        withTwoRootTree { config, _ ->
            captureStdout { ReindexCommand.run(emptyList(), config) shouldBe 0 } // seed the engine with BOTH roots

            // handbook answers the preflight probe and is gone from the rebuild's own probe on - the window a
            // check-then-act guard cannot see. IndexBuilder SKIPS it (the carry-forward rule), and a fresh CLI
            // process has no previous section to carry, so the published snapshot has no handbook section at all:
            // swapping it in is a DELETE of handbook's rows, reported as a success.
            val err = captureStderr { ReindexCommand.run(emptyList(), config, vanishAfterFirstProbe(HANDBOOK)) shouldBe 1 }
            err shouldContain "root 'handbook' went away while it was being indexed"
            err shouldContain "nothing was written"

            // The swap never happened: handbook's documents are still searchable, exactly as for a root nobody touched.
            SearchDb(config.searchDatabasePath).use { db ->
                val provider = Fts5SearchProvider(db)
                provider.indexedState().values.map { it.root }.toSet() shouldBe setOf(RootName.MAIN, HANDBOOK)
                provider.search(SearchQuery(text = "onboarding", limit = 20, offset = 0)).total shouldBeGreaterThan 0L
            }
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

private val HANDBOOK = RootName.require("handbook")

/**
 * The disappearance a preflight structurally cannot catch: [root]'s store answers the FIRST availability probe (the
 * command's preflight) and reports gone from the second on (the rebuild's own probe) - a NAS unmounting in the window
 * between the two. Everything else, and every other root, is the real store. Single-threaded by construction: the
 * probes happen on the CLI's own thread, in order.
 */
private fun vanishAfterFirstProbe(root: RootName): StoreDecorator = { name, store ->
    if (name != root) {
        store
    } else {
        object : ContentStore by store {
            private var probed = false

            override fun available(): Boolean {
                if (probed) return false
                probed = true
                return true
            }
        }
    }
}

/** A two-page tree + fresh DATA_DIR; both cleaned up. */
private fun withReindexTree(block: (PlainbaseConfig) -> Unit) {
    val content = Files.createTempDirectory("pb-reindex-content")
    val data = Files.createTempDirectory("pb-reindex-data")
    try {
        Files.writeString(content.resolve("alpha.md"), "---\ntitle: Alpha\n---\n\n# Alpha\n\nfind the flux capacitor here.\n")
        Files.writeString(content.resolve("beta.md"), "---\ntitle: Beta\n---\n\n# Beta\n\nplain filler text.\n")
        block(PlainbaseConfig(contentDir = content, dataDir = data, host = "127.0.0.1", port = 0))
    } finally {
        listOf(content, data).forEach(::deleteTree)
    }
}

/**
 * The same two-page `main` plus a one-page `handbook` extra root, wired through an EXPLICIT `roots {}` block (the
 * registry the server builds), and handed to [block] alongside the extra's path so a test can unmount it.
 */
private fun withTwoRootTree(block: (PlainbaseConfig, Path) -> Unit) {
    val handbook = Files.createTempDirectory("pb-reindex-handbook")
    try {
        withReindexTree { config ->
            Files.writeString(
                handbook.resolve("onboarding.md"),
                "---\ntitle: Onboarding\n---\n\n# Onboarding\n\nday-one onboarding steps.\n",
            )
            block(
                config.copy(
                    roots = RootsConfig(
                        list = listOf(
                            Root(RootName.MAIN, RootBackend.Local(config.contentDir), editable = true, history = HistoryMode.OFF),
                            Root(HANDBOOK, RootBackend.Local(handbook), editable = true, history = HistoryMode.OFF),
                        ),
                        origin = RootsOrigin.EXPLICIT,
                    ),
                ),
                handbook,
            )
        }
    } finally {
        deleteTree(handbook)
    }
}

/** Tolerates an already-deleted tree: the unmount test removes the extra root itself. */
private fun deleteTree(dir: Path) {
    if (!Files.exists(dir)) return
    Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
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
