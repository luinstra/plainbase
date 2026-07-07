package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.service.CitationFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * The object-mode boot sequence (`serve()`/`adopt`/`reindex`, all: lock -> hydrate -> rebuild ->
 * reconcile): [ObjectContentStore.hydrate] must run BEFORE any recovery reconcile reads the mirror
 * through the port, and its delete-absent step must EXCLUDE the live dirty-journal paths. The
 * literal call order in `Application.kt`/`AdoptCommand.kt`/`ReindexCommand.kt` is a source-level
 * fact (review-checked against the ADDENDUM step 3 cites); this suite proves the PROPERTY the
 * order depends on, isolated from the full `WritePipeline`/`IndexBuilder` machinery.
 */
class ObjectBootOrderTest : FunSpec({

    val hasher = CitationFactory()::contentHash

    test(
        "hydrate-before-reconcile commits a retained dirty mark whose expectedHash matches bucket bytes " +
            "not yet in the mirror; the inverted order (reconcile against the stale pre-hydrate mirror) drift-skips",
    ) {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("recovering.md")
            val oldBytes = "old (crash-time mirror)".toByteArray()
            val newBytes = "new (durable at the bucket, not yet mirrored)".toByteArray()
            // The mirror is STALE (as if a crash landed the PUT at the bucket but never applied it
            // locally - exactly Q8b's class): only the bucket holds newBytes.
            hybrid.mirror.write(path, oldBytes)
            hybrid.mirror.scan()
            val key = hybrid.mirror.resolveRepoRelativePath(path)
            hybrid.fake.seed(key, newBytes)
            val retainedMark = hasher(newBytes) // the write-ahead dirty mark records the INTENDED hash

            // INVERTED order: reconcile (a read through the port) runs BEFORE hydrate - sees the stale
            // mirror bytes, drift-skips (never commits the mark).
            val onDiskBeforeHydrate = hybrid.store.read(path)
            (onDiskBeforeHydrate?.let(hasher) == retainedMark) shouldBe false

            // CORRECT order: hydrate first, THEN reconcile - the mirror now holds the bucket's bytes,
            // so the retained mark's expectedHash matches and reconcile would commit-and-clear.
            hybrid.store.hydrate()
            val onDiskAfterHydrate = hybrid.store.read(path)
            (onDiskAfterHydrate?.let(hasher) == retainedMark) shouldBe true
            onDiskAfterHydrate shouldBe newBytes
        }
    }

    test(
        "hydrate's delete-absent step EXCLUDES dirtyPaths(): an unpushed dirty edit survives a boot " +
            "where its key is LIST-absent from the bucket (mark-precedes-CAS makes this safe, not lucky)",
    ) {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("unpushed.md")
            val bytes = "written locally, not yet confirmed at the bucket".toByteArray()
            hybrid.mirror.write(path, bytes) // mirror-only - the bucket LIST will not carry this key
            hybrid.mirror.scan()
            hybrid.dirtyPaths += path // the write-ahead mark: WritePipeline.write marks dirty BEFORE the CAS

            hybrid.store.hydrate() // the bucket has nothing at all - an empty LIST

            hybrid.mirror.read(path) shouldBe bytes // NOT reaped - dirtyPaths() protected it
        }
    }

    test("hydrate's delete-absent step DOES remove a mirror file absent from LIST and not dirty-journaled") {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("stale-mirror-only.md")
            hybrid.mirror.write(path, "orphaned".toByteArray())
            hybrid.mirror.scan()
            // Not in dirtyPaths - an ordinary stale mirror entry the bucket no longer has (deleted upstream).

            hybrid.store.hydrate()

            Files.exists(hybrid.mirror.onDiskTargetForTest(path)) shouldBe false
        }
    }

    test(
        "finding 1: hydrate re-fetches a mirror file DELETED while its mirror-state entry survived " +
            "(DATA_DIR/mirror is deletable derived state - it must self-heal on boot, not vanish from the index)",
    ) {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("deletable.md")
            val bytes = "important content".toByteArray()
            hybrid.seedExisting(path, bytes) // bucket + mirror + state all consistent, etags matching
            // Simulate `rm DATA_DIR/mirror/deletable.md` WITHOUT touching mirror-state: the etag entry
            // survives, pointing at a now-missing file. A pure etag-diff would skip the GET and lose it.
            Files.delete(hybrid.mirrorRoot.resolve(hybrid.mirror.resolveRepoRelativePath(path)))
            hybrid.mirror.scan()
            hybrid.store.read(path) shouldBe null // gone from the mirror

            hybrid.store.hydrate() // presence-aware diff must re-fetch despite the etag matching state

            hybrid.mirror.scan()
            hybrid.store.read(path) shouldBe bytes // self-healed from the authoritative bucket
        }
    }

    test(
        "finding 2: applying a new raw key removes the stale NFC-equivalent mirror sibling, so exactly one " +
            "raw file backs each TreePath (no B3 collision serving a stale generation)",
    ) {
        HybridFixture().use { hybrid ->
            val nfcPath = TreePath.require("caf\u00e9.md") // precomposed U+00E9
            // A prior generation's NFD-named mirror file for the SAME TreePath (as a normalization-
            // preserving filesystem - the CI runner - would leave it); the bucket now serves the NFC key.
            Files.write(hybrid.mirrorRoot.resolve("cafe\u0301.md"), "old NFD generation".toByteArray()) // 'e' + U+0301
            hybrid.fake.seed("caf\u00e9.md", "new NFC generation".toByteArray()) // precomposed U+00E9

            hybrid.store.hydrate()

            val files = Files.list(hybrid.mirrorRoot).use { stream -> stream.toList() }.filter { Files.isRegularFile(it) }
            files.size shouldBe 1 // the stale NFD sibling was swept; only the current raw file remains
            hybrid.mirror.scan()
            hybrid.store.read(nfcPath) shouldBe "new NFC generation".toByteArray()
        }
    }

    test(
        "BLOCKING 1: a hydrate GET that FAILS for a mirror-file-missing key invalidates its state entry so a " +
            "later hydrate re-detects and heals (never wedged absent by the retained old etag)",
    ) {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("flaky.md")
            val bytes = "authoritative".toByteArray()
            hybrid.seedExisting(path, bytes) // bucket + mirror + state consistent, etag recorded
            val key = hybrid.mirror.resolveRepoRelativePath(path)
            // Delete the mirror file (deletable derived state) while its state entry survives; the presence-
            // aware diff (!mirrorHasRaw) will mark it for re-fetch, but the GET fails on the FIRST hydrate.
            Files.delete(hybrid.mirrorRoot.resolve(key))
            hybrid.mirror.scan()
            hybrid.fake.failNextGetFor += key

            hybrid.store.hydrate() // GET throws -> the entry MUST be invalidated, not retained at the old etag

            hybrid.state.etagOf(path) shouldBe null // invalidated, so poll/next-hydrate's diff re-triggers
            Files.exists(hybrid.mirrorRoot.resolve(key)) shouldBe false // still missing after the failed GET

            hybrid.store.hydrate() // GET now succeeds (one-shot failure spent) -> re-detect + heal

            Files.exists(hybrid.mirrorRoot.resolve(key)) shouldBe true // healed
            hybrid.state.etagOf(path).shouldNotBeNull()
            hybrid.mirror.scan()
            hybrid.store.read(path) shouldBe bytes
        }
    }

    test(
        "BLOCKING 2: hydrate's delete-absent phase sweeps an orphaned _folder.yaml sidecar even with " +
            "EMPTY mirror-state (mirrorFilePaths enumerates sidecars, which scan().files excludes)",
    ) {
        HybridFixture().use { hybrid ->
            // The mirror holds a page AND a stale folder sidecar; mirror-state is EMPTY (the corrupt-load
            // cold path). The bucket holds ONLY the page (the operator deleted the sidecar upstream).
            Files.createDirectories(hybrid.mirrorRoot.resolve("guides"))
            Files.writeString(hybrid.mirrorRoot.resolve("guides/intro.md"), "# Intro\n")
            Files.writeString(hybrid.mirrorRoot.resolve("guides/_folder.yaml"), "title: Stale Title\n")
            hybrid.fake.seed("guides/intro.md", "# Intro\n".toByteArray())
            hybrid.mirror.scan()

            hybrid.store.hydrate()

            // The orphaned sidecar (absent from the bucket) is swept despite the empty state; the page survives.
            Files.exists(hybrid.mirrorRoot.resolve("guides/_folder.yaml")) shouldBe false
            Files.exists(hybrid.mirrorRoot.resolve("guides/intro.md")) shouldBe true
        }
    }

    test(
        "SECURITY: a hostile bucket key that could escape DATA_DIR/mirror on Windows (backslash / drive-letter / " +
            "traversal) is funnel-rejected on ANY platform - hydrate writes NOTHING outside the mirror root and " +
            "legit keys in the same batch still hydrate",
    ) {
        HybridFixture().use { hybrid ->
            val legit = "guides/intro.md"
            hybrid.fake.seed(legit, "# Intro\n".toByteArray())
            // `..\evil.md` and `C:/evil.md` PASS the shared POSIX TreePath gate (backslash + colon are ordinary
            // filename bytes there) yet escape the mirror on a Windows host; `../evil.md` / `../../evil.md` are the
            // POSIX traversal forms. All four must be rejected regardless of OS.
            val hostile = listOf("..\\evil.md", "C:/evil.md", "../evil.md", "../../evil.md")
            hostile.forEach { hybrid.fake.seed(it, "PWNED".toByteArray()) }

            hybrid.store.hydrate()

            // Legit key hydrated normally.
            Files.exists(hybrid.mirrorRoot.resolve(legit)) shouldBe true
            // No hostile key wrote anything: not at the escaped locations, not as a literal-name file inside the mirror.
            Files.exists(hybrid.mirrorRoot.parent.resolve("evil.md")) shouldBe false
            Files.exists(hybrid.mirrorRoot.parent.parent.resolve("evil.md")) shouldBe false
            Files.exists(hybrid.mirrorRoot.resolve("..\\evil.md")) shouldBe false
            // The mirror holds EXACTLY the one legit file, and state recorded only it (no hostile key reached apply).
            val mirrorFiles = Files.walk(hybrid.mirrorRoot).use { stream -> stream.filter { Files.isRegularFile(it) }.toList() }
            mirrorFiles.size shouldBe 1
            hybrid.state.snapshot().keys shouldBe setOf(TreePath.require(legit))
        }
    }
})

/** Test-only exposure of the internal accessor, mirroring the seam (f) visibility. */
private fun com.plainbase.frameworks.filesystem.LocalContentStore.onDiskTargetForTest(path: TreePath) = onDiskTarget(path)
