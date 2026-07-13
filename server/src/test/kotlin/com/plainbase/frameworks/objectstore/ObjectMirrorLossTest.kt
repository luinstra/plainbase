package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.TreePath
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.file.Files

/**
 * The hybrid store's own ADR-0011 D5 arm: the tree it SERVES is the local mirror, so a mirror that is missing (or
 * is no longer the directory we hydrated) is UNAVAILABLE - never "available and empty".
 *
 * The difference is the whole rule. An empty scan on an AVAILABLE root is a full-corpus delete instruction, and a
 * rebuild acting on one does exactly what a genuine emptying asks for: the root enters the pass's delete-authority
 * set, its `page_checkpoint` rows go, its search rows go, and its pages answer 404 - for a corpus the bucket still
 * holds in full. `rm -rf DATA_DIR/mirror` (or a DATA_DIR volume that unmounts) must therefore read as an outage,
 * exactly as an unmounted content root does for the local store.
 *
 * The never-hydrated arm stays available on purpose, and it is the one place an absent mirror is honest: PREVIEW
 * adopt never hydrates and never mkdirs, and a store that has materialized nothing holds nothing anyone can lose.
 */
class ObjectMirrorLossTest : FunSpec({

    val deploy = TreePath.require("guides/deploy.md")

    test("a HYDRATED mirror that is deleted makes the store unavailable, and its reads answer RootDown, never Absent") {
        HybridFixture().use { hybrid ->
            hybrid.seedExisting(deploy, "# Deploy\n\nbody\n".toByteArray())
            hybrid.store.hydrate()
            hybrid.store.available() shouldBe true

            hybrid.mirrorRoot.toFile().deleteRecursively() // DATA_DIR/mirror is deletable derived state - so delete it

            hybrid.store.available() shouldBe false
            withClue("an empty ScanResult here would be a mass-delete instruction; the NIO failure is the honest answer") {
                shouldThrow<IOException> { hybrid.store.scan() }
            }
            withClue("404 tells an agent to drop its citations - for a page the BUCKET still holds") {
                hybrid.store.readClassified(deploy) shouldBe ContentRead.RootDown
            }
        }
    }

    test("a mirror REPLACED by a different directory is unavailable too - liveness is the tree's identity, not the path's") {
        HybridFixture().use { hybrid ->
            hybrid.seedExisting(deploy, "# Deploy\n\nbody\n".toByteArray())
            hybrid.store.hydrate()

            // What an unmount-and-remount (or a restore-from-backup into a fresh dir) leaves behind: the path is
            // there, traversable, and readable - and it is not the tree we hydrated.
            hybrid.mirrorRoot.toFile().deleteRecursively()
            Files.createDirectories(hybrid.mirrorRoot)

            withClue("three stats on the PATH cannot tell a swapped tree from the original; the file key can") {
                hybrid.store.available() shouldBe false
            }
        }
    }

    test("a NEVER-hydrated store with no mirror is available and scans empty - the PREVIEW/fresh-install seam") {
        HybridFixture().use { hybrid ->
            hybrid.mirrorRoot.toFile().deleteRecursively() // no hydrate has run: nothing was ever materialized here

            hybrid.store.available() shouldBe true
            hybrid.store.scan().files.shouldBeEmpty()
        }
    }

    test("the poll re-derives a mirror FILE deleted at runtime - a missing file is a diff, not just a stale etag") {
        HybridFixture().use { hybrid ->
            hybrid.seedExisting(deploy, "# Deploy\n\nbody\n".toByteArray())
            hybrid.store.hydrate()
            // Only the FILE goes (the mirror directory survives, so the store stays available). The bucket etag is
            // unchanged, so an etag-only poll diff saw NOTHING to do - and the next rebuild's scan would drop the
            // page from the snapshot and delete its checkpoint + search rows on a root that is scanned and live.
            Files.delete(hybrid.mirrorRoot.resolve("guides/deploy.md"))

            hybrid.store.pollOnce()

            hybrid.store.available() shouldBe true
            hybrid.store.scan().files.map { it.path } shouldBe listOf(deploy)
            hybrid.store.read(deploy)?.decodeToString() shouldBe "# Deploy\n\nbody\n"
        }
    }
})
