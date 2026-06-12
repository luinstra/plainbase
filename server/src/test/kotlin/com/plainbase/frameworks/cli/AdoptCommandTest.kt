package com.plainbase.frameworks.cli

import com.plainbase.domain.content.TreePath
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files

/**
 * The `adopt` CLI contract over a tiny temp tree: flag surface (exact, usage-error otherwise),
 * the dry-run listing (unmaterialized + would-refuse with rule-naming reasons), the pre-write
 * intent lines, the operator-facing network-filesystem caveat (plan line 555), and idempotence
 * as the operator sees it.
 */
class AdoptCommandTest : FunSpec({

    test("--dry-run alone and unknown flags are usage errors (exit 2)") {
        withCliTree { config ->
            AdoptCommand.run(listOf("--dry-run"), config) shouldBe 2
            AdoptCommand.run(listOf("--bogus"), config) shouldBe 2
            AdoptCommand.run(listOf("--write-ids", "extra"), config) shouldBe 2
        }
    }

    test("adopt --write-ids --dry-run lists the unmaterialized and would-refuse pages, prints the caveat, writes nothing") {
        withCliTree { config ->
            val plainBefore = Files.readAllBytes(config.contentDir.resolve("plain.md"))
            val out = captureStdout {
                AdoptCommand.run(listOf("--write-ids", "--dry-run"), config) shouldBe 0
            }

            out shouldContain "dry run: nothing was written"
            out shouldContain "would materialize 2 page(s):"
            out shouldContain "  plain.md"
            out shouldContain "  titled.md"
            out shouldContain "would refuse 1 page(s):"
            out shouldContain "refused.md: frontmatter keys and values must be plain unquoted scalars"
            out shouldContain "NFS/SMB"
            out shouldNotContain "intent:"

            Files.readAllBytes(config.contentDir.resolve("plain.md")) shouldBe plainBefore
            // "nothing was written" includes the app db: a dry run on a fresh tree must not create it.
            Files.exists(config.appDatabasePath) shouldBe false
        }
    }

    test("adopt --write-ids --dry-run against an existing install reads it without changing a byte") {
        withCliTree { config ->
            captureStdout { AdoptCommand.run(listOf("--write-ids"), config) shouldBe 0 }
            val dbBefore = Files.readAllBytes(config.appDatabasePath)

            val out = captureStdout {
                AdoptCommand.run(listOf("--write-ids", "--dry-run"), config) shouldBe 0
            }

            // Accurate against persisted state: the materialized pages are not re-listed as pending.
            out shouldContain "would materialize 0 page(s):"
            out shouldContain "would refuse 1 page(s):"
            Files.readAllBytes(config.appDatabasePath) shouldBe dbBefore
        }
    }

    test("dry run consults the existing id_map: a pasted copy of a mapped id surfaces as duplicate_id") {
        withCliTree { config ->
            captureStdout { AdoptCommand.run(emptyList(), config) shouldBe 0 } // RECORD binds map-only ids
            val mappedId = DatabaseFactory.createDriver(config.appDatabasePath).use { driver ->
                DatabaseFactory.createDatabase(driver).idMapQueries
                    .selectBinding(TreePath.require("titled.md")).executeAsOne().id
            }
            Files.writeString(config.contentDir.resolve("copy.md"), "---\nid: $mappedId\n---\nA pasted duplicate.\n")

            val out = captureStdout {
                AdoptCommand.run(listOf("--write-ids", "--dry-run"), config) shouldBe 0
            }
            // Only detectable because PREVIEW read the on-disk bindings: against an empty stand-in
            // db, copy.md's claim on titled.md's map-only id would have gone unchallenged.
            out shouldContain "duplicate_id $mappedId: kept by titled.md; copy.md reassigned a fresh id"
        }
    }

    test("adopt --write-ids intent-logs then materializes; a second run reports zero writes") {
        withCliTree { config ->
            val first = captureStdout {
                AdoptCommand.run(listOf("--write-ids"), config) shouldBe 0
            }
            first shouldContain "intent: write id"
            first shouldContain "materialized 2 page(s); 0 already carried their id; 1 refused"
            first shouldContain "patch_refused refused.md:"
            first shouldContain "NFS/SMB"
            String(Files.readAllBytes(config.contentDir.resolve("plain.md"))) shouldContain "id: "

            val second = captureStdout {
                AdoptCommand.run(listOf("--write-ids"), config) shouldBe 0
            }
            second shouldContain "materialized 0 page(s); 2 already carried their id; 1 refused"
            second shouldNotContain "intent:"
        }
    }

    test("plain adopt records identities without touching any file") {
        withCliTree { config ->
            val plainBefore = Files.readAllBytes(config.contentDir.resolve("plain.md"))
            val out = captureStdout {
                AdoptCommand.run(emptyList(), config) shouldBe 0
            }
            out shouldContain "adopt: 3 page(s)"
            out shouldContain "recorded 3 id_map-only identity(ies); 0 page(s) already carry their id"
            out shouldNotContain "NFS/SMB" // no writes in default mode; the caveat is write-path text
            Files.readAllBytes(config.contentDir.resolve("plain.md")) shouldBe plainBefore
        }
    }
})

/** A three-page tree: two patchable pages and one §A3 case-9 refusal, plus a fresh DATA_DIR. */
private fun withCliTree(block: (PlainbaseConfig) -> Unit) {
    val content = Files.createTempDirectory("pb-cli-content")
    val data = Files.createTempDirectory("pb-cli-data")
    try {
        Files.writeString(content.resolve("plain.md"), "# Plain\n\nNo frontmatter here.\n")
        Files.writeString(content.resolve("titled.md"), "---\ntitle: Titled\n---\n# Titled\n")
        Files.writeString(content.resolve("refused.md"), "---\n'quoted': key\n---\nbody\n")
        block(PlainbaseConfig(contentDir = content, dataDir = data, host = "127.0.0.1", port = 0))
    } finally {
        listOf(content, data).forEach { dir ->
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

/** Captures System.out for the duration of [block] — the CLI's output contract under test. */
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
