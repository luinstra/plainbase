package com.plainbase.frameworks.cli

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.RootsConfig
import com.plainbase.frameworks.config.RootsOrigin
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.config.StorageConfig
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import io.kotest.assertions.withClue
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
            runAdopt(listOf("--dry-run"), config) shouldBe 2
            runAdopt(listOf("--bogus"), config) shouldBe 2
            runAdopt(listOf("--write-ids", "extra"), config) shouldBe 2
        }
    }

    test("adopt --write-ids --dry-run lists the unmaterialized and would-refuse pages, prints the caveat, writes nothing") {
        withCliTree { config ->
            val plainBefore = Files.readAllBytes(config.contentDir.resolve("plain.md"))
            val out = captureStdout {
                runAdopt(listOf("--write-ids", "--dry-run"), config) shouldBe 0
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
            captureStdout { runAdopt(listOf("--write-ids"), config) shouldBe 0 }
            val dbBefore = Files.readAllBytes(config.appDatabasePath)

            val out = captureStdout {
                runAdopt(listOf("--write-ids", "--dry-run"), config) shouldBe 0
            }

            // Accurate against persisted state: the materialized pages are not re-listed as pending.
            out shouldContain "would materialize 0 page(s):"
            out shouldContain "would refuse 1 page(s):"
            Files.readAllBytes(config.appDatabasePath) shouldBe dbBefore
        }
    }

    test("dry run consults the existing id_map: a pasted copy of a mapped id surfaces as duplicate_id") {
        withCliTree { config ->
            captureStdout { runAdopt(emptyList(), config) shouldBe 0 } // RECORD binds map-only ids
            val mappedId = DatabaseFactory.createDriver(config.appDatabasePath).use { driver ->
                DatabaseFactory.createDatabase(driver).idMapQueries
                    .selectBinding(RootName.PRIMARY, TreePath.require("titled.md")).executeAsOne().id
            }
            Files.writeString(config.contentDir.resolve("copy.md"), "---\nid: $mappedId\n---\nA pasted duplicate.\n")

            val out = captureStdout {
                runAdopt(listOf("--write-ids", "--dry-run"), config) shouldBe 0
            }
            // Only detectable because PREVIEW read the on-disk bindings: against an empty stand-in
            // db, copy.md's claim on titled.md's map-only id would have gone unchallenged.
            out shouldContain "duplicate_id $mappedId: kept by titled.md; copy.md reassigned a fresh id"
        }
    }

    test("adopt --write-ids intent-logs then materializes; a second run reports zero writes") {
        withCliTree { config ->
            val first = captureStdout {
                runAdopt(listOf("--write-ids"), config) shouldBe 0
            }
            first shouldContain "intent: write id"
            first shouldContain "materialized 2 page(s); 0 already carried their id; 1 refused"
            first shouldContain "patch_refused refused.md:"
            first shouldContain "NFS/SMB"
            String(Files.readAllBytes(config.contentDir.resolve("plain.md"))) shouldContain "id: "

            val second = captureStdout {
                runAdopt(listOf("--write-ids"), config) shouldBe 0
            }
            second shouldContain "materialized 0 page(s); 2 already carried their id; 1 refused"
            second shouldNotContain "intent:"
        }
    }

    test("a failed intent publication prevents the following page write") {
        withCliTree { config ->
            val before = Files.readAllBytes(config.contentDir.resolve("plain.md"))
            val output = StreamCommandOutput(
                PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8),
                PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8),
                CommandEventSink { throw IllegalStateException("journal unavailable") },
            )

            AdoptCommand.run(listOf("--write-ids"), config, output) shouldBe 1

            Files.readAllBytes(config.contentDir.resolve("plain.md")) shouldBe before
        }
    }

    test("RECORD and MATERIALIZE refuse with exit 1 when a server holds the lock, touching neither db nor files") {
        withCliTree { config ->
            val plainBefore = Files.readAllBytes(config.contentDir.resolve("plain.md"))
            DataDirLock.tryAcquire(config.dataDir)!!.use {
                listOf(emptyList(), listOf("--write-ids")).forEach { args ->
                    val err = captureStderr { runAdopt(args, config) shouldBe 1 }
                    err shouldContain "adopt: a Plainbase server is holding ${config.dataDir}"
                }
                // The refusal precedes the driver open AND the adoption pass: no db, no file writes.
                Files.exists(config.appDatabasePath) shouldBe false
                Files.readAllBytes(config.contentDir.resolve("plain.md")) shouldBe plainBefore
            }
            // After release, a run succeeds.
            captureStdout { runAdopt(emptyList(), config) shouldBe 0 }
        }
    }

    test(
        "storage.backend=object is real (C4): RECORD/MATERIALIZE hydrate the DATA_DIR mirror first and fail fast, " +
            "actionably, when the bucket is unreachable - under the lock already taken (unlike the old outright refusal, " +
            "the db IS opened/migrated before the hydrate attempt); PREVIEW never hydrates and stays lock-free",
    ) {
        withCliTree { config ->
            val plainBefore = Files.readAllBytes(config.contentDir.resolve("plain.md"))
            val objectConfig = config.copy(
                storage = StorageConfig(
                    backend = StorageBackend.OBJECT,
                    // A well-formed but unreachable endpoint (loopback, nothing listening on port 9 -
                    // "Connection refused" comes back immediately, not a slow timeout): the FACTORY builds
                    // cleanly, so the failure is hydrate()'s, exercised exactly like a real outage would be.
                    endpoint = "https://127.0.0.1:9",
                    bucket = "docs",
                    accessKeyId = "k",
                    secretAccessKey = "s",
                ),
            )
            listOf(emptyList(), listOf("--write-ids")).forEach { args ->
                val err = captureStderr { runAdopt(args, objectConfig) shouldBe 1 }
                err shouldContain "adopt: "
                err shouldNotContain "storage.backend=object is configured but the object backend is not available"
            }
            // C4 behavior change from the old outright refusal: the lock IS taken (and released) and the
            // app db IS opened/migrated before the hydrate attempt fails - never touched under the OLD refusal.
            Files.exists(objectConfig.appDatabasePath) shouldBe true
            Files.readAllBytes(config.contentDir.resolve("plain.md")) shouldBe plainBefore
            DataDirLock.tryAcquire(objectConfig.dataDir)!!.use { } // released after the failed attempt

            // PREVIEW's contract (seam c, finding 4): zero writes, lock-free, no hydrate - it reads the
            // (absent, never hydrated) mirror as an empty tree rather than refusing, and must NOT create
            // DATA_DIR/mirror (a dry run touches no disk; the factory construction is non-mutating).
            val mirrorDir = objectConfig.dataDir.resolve("mirror")
            Files.deleteIfExists(mirrorDir) // in case a prior arm left an empty dir; assert PREVIEW re-creates none
            val out = captureStdout {
                runAdopt(listOf("--write-ids", "--dry-run"), objectConfig) shouldBe 0
            }
            out shouldContain "would materialize 0 page(s):"
            Files.exists(mirrorDir) shouldBe false // PREVIEW created no DATA_DIR/mirror
        }
    }

    test("PREVIEW stays lock-free: adopt --write-ids --dry-run runs while a server holds the lock") {
        withCliTree { config ->
            DataDirLock.tryAcquire(config.dataDir)!!.use {
                val out = captureStdout { runAdopt(listOf("--write-ids", "--dry-run"), config) shouldBe 0 }
                out shouldContain "dry run: nothing was written"
            }
        }
    }

    test("plain adopt records identities without touching any file") {
        withCliTree { config ->
            val plainBefore = Files.readAllBytes(config.contentDir.resolve("plain.md"))
            val out = captureStdout {
                runAdopt(emptyList(), config) shouldBe 0
            }
            out shouldContain "adopt: 3 page(s)"
            out shouldContain "recorded 3 id_map-only identity(ies); 0 page(s) already carry their id"
            out shouldNotContain "NFS/SMB" // no writes in default mode; the caveat is write-path text
            Files.readAllBytes(config.contentDir.resolve("plain.md")) shouldBe plainBefore
        }
    }

    // ---- every root, or none: the ids of a root adopt skips do not survive a lost DATA_DIR ---------

    test("adopt --write-ids materializes ids in EVERY configured root - not just main") {
        withTwoRootCliTree { config, handbook ->
            val out = captureStdout { runAdopt(listOf("--write-ids"), config) shouldBe 0 }

            withClue("each root gets its own named section - the same page path can exist in two of them") {
                out shouldContain "adopt: root 'docs': 3 page(s)"
                out shouldContain "adopt: root 'handbook': 1 page(s)"
            }
            withClue("THE point: the extra root's identity now lives in the tree itself, so a lost DATA_DIR cannot take it") {
                String(Files.readAllBytes(handbook.resolve("onboarding.md"))) shouldContain "id: "
            }
            out shouldContain "NFS/SMB"
            withClue("one write-mechanism caveat for the run, not one per root") {
                out.split("NFS/SMB").size shouldBe 2
            }
        }
    }

    test("the same id in two roots is legal per-root: each root keeps and materializes its OWN claim, no cross-root steal") {
        withTwoRootCliTree { config, handbook ->
            // The lower-ranked root owns an id today (map-only, the read-only first index), and the higher-ranked
            // root then turns out to hold a page carrying that same id in its frontmatter. Pre-flip this was a D17
            // contest main won; post-flip (per-root identity) it is no contest at all - both roots keep the id.
            captureStdout { runAdopt(emptyList(), config) shouldBe 0 }
            val shared = binding(config, RootName.require("handbook"), "onboarding.md")
            Files.writeString(config.contentDir.resolve("claimant.md"), "---\nid: $shared\ntitle: Claimant\n---\nbody\n")

            captureStdout { runAdopt(listOf("--write-ids"), config) shouldBe 0 }

            withClue("main keeps the id it carries in its frontmatter, materialized in its own file") {
                binding(config, RootName.PRIMARY, "claimant.md") shouldBe shared
                String(Files.readAllBytes(config.contentDir.resolve("claimant.md"))) shouldContain "id: $shared"
            }
            withClue("handbook KEEPS its own map-only claim on the SAME id (root-scoped, never stolen) and materializes it") {
                binding(config, RootName.require("handbook"), "onboarding.md") shouldBe shared
                String(Files.readAllBytes(handbook.resolve("onboarding.md"))) shouldContain "id: $shared"
            }
        }
    }

    test("adopt REFUSES (exit 1) when a configured root is not available - a skipped root is a root whose permalinks die") {
        withTwoRootCliTree { config, handbook ->
            handbook.toFile().deleteRecursively() // the unmounted-disk shape

            val err = captureStderr { runAdopt(listOf("--write-ids"), config) shouldBe 1 }

            err shouldContain "adopt: root 'handbook' is not available"
            err shouldContain "would then cost that root every permalink and citation"
            withClue("refuse BEFORE writing: a half-adopted corpus is the state an operator would never think to re-check") {
                String(Files.readAllBytes(config.contentDir.resolve("plain.md"))) shouldNotContain "id: "
            }
        }
    }
})

/** The id `adopt` bound to a rooted path, read straight out of the app db it just wrote. */
private fun binding(config: PlainbaseConfig, root: RootName, path: String) =
    DatabaseFactory.createDriver(config.appDatabasePath).use { driver ->
        DatabaseFactory.createDatabase(driver).idMapQueries.selectBinding(root, TreePath.require(path)).executeAsOne().id
    }

/** [withCliTree] plus a second configured root - the topology whose extra pages a main-only adopt used to leave behind. */
private fun withTwoRootCliTree(block: (PlainbaseConfig, java.nio.file.Path) -> Unit) {
    withCliTree { config ->
        val handbook = Files.createTempDirectory("pb-cli-handbook")
        try {
            Files.writeString(handbook.resolve("onboarding.md"), "---\ntitle: Onboarding\n---\n\n# Onboarding\n")
            block(
                config.copy(
                    roots = RootsConfig.of(
                        list = listOf(
                            Root(RootName.PRIMARY, RootBackend.Local(config.contentDir), editable = true, history = HistoryMode.OFF),
                            Root(RootName.require("handbook"), RootBackend.Local(handbook), editable = true, history = HistoryMode.OFF),
                        ),
                        origin = RootsOrigin.EXPLICIT,
                    ),
                ),
                handbook,
            )
        } finally {
            handbook.toFile().deleteRecursively()
        }
    }
}

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

private fun runAdopt(args: List<String>, config: PlainbaseConfig): Int =
    AdoptCommand.run(args, config, CommandOutputCapture.current)

/** Captures the injected result channel for the duration of [block]. */
private fun captureStdout(block: () -> Unit): String = CommandOutputCapture.captureStdout(block)

/** Captures the injected error channel for the duration of [block]. */
private fun captureStderr(block: () -> Unit): String = CommandOutputCapture.captureStderr(block)
