package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.CasResult
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.CreateResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.principal.grantForTests
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.service.AdoptionPass
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.FrontmatterPatcher
import com.plainbase.domain.service.PageIdentityService
import com.plainbase.domain.service.TestIdProvider
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

/**
 * The C4 differential-oracle suite: [ObjectContentStore] over the [FakeObjectStore] (the hybrid)
 * proves equal to a bare [LocalContentStore] (the oracle) over an EQUIVALENT tree, for every
 * `ContentStore` contract outcome - parameterized over `conflictStatus` in {409, 412} so the Q8
 * mapping is proven provider-agnostic (SP1: R2 returns 412 for both preconditions; the future S3
 * finding cannot un-prove this).
 *
 * house pattern: any hole a review finds here widens the SCENARIO LIST, not just the code.
 */
class ObjectContentStoreOracleTest : FunSpec({

    val hasher = CitationFactory()::contentHash

    listOf(409, 412).forEach { conflictStatus ->

        test("[$conflictStatus] scan/read/list membership: hybrid == a bare LocalContentStore over the same bytes") {
            HybridFixture(conflictStatus).use { hybrid ->
                localOracle().use { oracle ->
                    val bytes = "# Hello\n\nbody.\n".toByteArray()
                    val path = TreePath.require("guide.md")
                    hybrid.store.createExclusive(path, bytes, hasher)
                    oracle.store.createExclusive(path, bytes, hasher)

                    hybrid.store.scan().files.map { it.path } shouldBe oracle.store.scan().files.map { it.path }
                    hybrid.store.read(path) shouldBe oracle.store.read(path)
                    hybrid.store.list(null).map { it.path } shouldBe oracle.store.list(null).map { it.path }
                }
            }
        }

        test("[$conflictStatus] createExclusive: fresh create == Created on both; a second create == Exists(path) on both") {
            HybridFixture(conflictStatus).use { hybrid ->
                localOracle().use { oracle ->
                    val bytes = "# fresh\n".toByteArray()
                    val path = TreePath.require("fresh.md")

                    val h1 = hybrid.store.createExclusive(path, bytes, hasher)
                    val o1 = oracle.store.createExclusive(path, bytes, hasher)
                    h1.shouldBe(o1)
                    h1.shouldBe(CreateResult.Created(hasher(bytes)))

                    val h2 = hybrid.store.createExclusive(path, "different".toByteArray(), hasher)
                    val o2 = oracle.store.createExclusive(path, "different".toByteArray(), hasher)
                    h2.shouldBe(o2)
                    h2.shouldBe(CreateResult.Exists(path))
                }
            }
        }

        test("[$conflictStatus] createExclusive: a dot-prefixed segment is Rejected identically on both") {
            HybridFixture(conflictStatus).use { hybrid ->
                localOracle().use { oracle ->
                    val path = TreePath.require(".hidden/page.md")
                    val bytes = "x".toByteArray()
                    val h = hybrid.store.createExclusive(path, bytes, hasher)
                    val o = oracle.store.createExclusive(path, bytes, hasher)
                    h.shouldBe(o)
                    h.shouldBeInstanceOfRejected()
                }
            }
        }

        test(
            "[$conflictStatus] createExclusive: an NFC-equivalent sibling already reconciled into the mirror " +
                "(the externally-minted-NFD-key shape) is Exists(the REQUESTED path) on both",
        ) {
            HybridFixture(conflictStatus).use { hybrid ->
                localOracle().use { oracle ->
                    // An NFD-named file already on disk (as a prior hydrate/scan would leave it) - never
                    // created through the port, mirroring an external upload the last reconcile already caught.
                    val nfd = "cafe\u0301.md" // 'e' + combining acute accent (U+0301) - NFD
                    Files.writeString(hybrid.mirrorRoot.resolve(nfd), "nfd body")
                    Files.writeString(oracle.root.resolve(nfd), "nfd body")
                    hybrid.mirror.scan()
                    oracle.store.scan()

                    val nfcPath = TreePath.require("caf\u00e9.md") // NFC (precomposed \u00e9)
                    val h = hybrid.store.createExclusive(nfcPath, "new".toByteArray(), hasher)
                    val o = oracle.store.createExclusive(nfcPath, "new".toByteArray(), hasher)
                    h.shouldBe(o)
                    h.shouldBe(CreateResult.Exists(nfcPath))
                }
            }
        }

        test("[$conflictStatus] compareAndSwapWrite: matching baseHash == Written on both; the mirror gains the new bytes") {
            HybridFixture(conflictStatus).use { hybrid ->
                localOracle().use { oracle ->
                    val path = TreePath.require("cas.md")
                    val original = "v1".toByteArray()
                    hybrid.seedExisting(path, original)
                    oracle.store.createExclusive(path, original, hasher) // oracle needs the SAME starting bytes
                    oracle.store.scan() // createExclusive does not itself refresh the index snapshot

                    val updated = "v2".toByteArray()
                    val h = hybrid.store.compareAndSwapWrite(path, hasher(original), updated, hasher)
                    val o = oracle.store.compareAndSwapWrite(path, hasher(original), updated, hasher)
                    h.shouldBe(o)
                    h.shouldBe(CasResult.Written(hasher(updated)))
                    hybrid.store.read(path) shouldBe oracle.store.read(path)
                }
            }
        }

        test("[$conflictStatus] compareAndSwapWrite: a stale baseHash == Mismatch(currentBytes, currentHash) on both") {
            HybridFixture(conflictStatus).use { hybrid ->
                localOracle().use { oracle ->
                    val path = TreePath.require("stale.md")
                    val original = "v1".toByteArray()
                    hybrid.seedExisting(path, original)
                    oracle.store.createExclusive(path, original, hasher)
                    oracle.store.scan()

                    val h = hybrid.store.compareAndSwapWrite(path, "not-the-real-hash", "v2".toByteArray(), hasher)
                    val o = oracle.store.compareAndSwapWrite(path, "not-the-real-hash", "v2".toByteArray(), hasher)
                    assertMismatchParity(h, o, original)
                }
            }
        }

        test("[$conflictStatus] compareAndSwapWrite: a never-indexed path == Deleted on both") {
            HybridFixture(conflictStatus).use { hybrid ->
                localOracle().use { oracle ->
                    val path = TreePath.require("never-existed.md")
                    val h = hybrid.store.compareAndSwapWrite(path, "any", "x".toByteArray(), hasher)
                    val o = oracle.store.compareAndSwapWrite(path, "any", "x".toByteArray(), hasher)
                    h.shouldBe(o)
                    h.shouldBe(CasResult.Deleted)
                }
            }
        }

        test(
            "[$conflictStatus] compareAndSwapWrite over a MirrorState cache miss (invalidated entry) still " +
                "resolves == the oracle via the map-absent read-back (seam h)",
        ) {
            HybridFixture(conflictStatus).use { hybrid ->
                localOracle().use { oracle ->
                    val path = TreePath.require("healed.md")
                    val original = "v1".toByteArray()
                    hybrid.seedExisting(path, original)
                    oracle.store.createExclusive(path, original, hasher)
                    oracle.store.scan()
                    hybrid.state.invalidate(path) // force the cache-miss path - no `!!`, no NPE

                    val updated = "v2".toByteArray()
                    val h = hybrid.store.compareAndSwapWrite(path, hasher(original), updated, hasher)
                    val o = oracle.store.compareAndSwapWrite(path, hasher(original), updated, hasher)
                    h.shouldBe(o)
                    h.shouldBe(CasResult.Written(hasher(updated)))
                }
            }
        }
    }

    // --- writeAssetExclusive oracle pairs (MINOR 3): the asset family's local-vs-object equivalence is
    // oracle-PROVEN, not just reasoned. Both stores get an equivalent tree; an existing folder is minted by
    // creating a page in it first (writeAssetExclusive never creates parent dirs - W3b).
    test("writeAssetExclusive Created: a valid asset into an existing folder == the oracle (Created, same hash)") {
        assetOracle { hybrid, oracle ->
            val asset = TreePath.require("guides/diagram.png")
            val bytes = "PNGDATA".toByteArray()
            val h = hybrid.writeAssetExclusive(grantForTests(), asset, bytes, hasher)
            val o = oracle.writeAssetExclusive(grantForTests(), asset, bytes, hasher)
            h shouldBe o
            h shouldBe CreateResult.Created(hasher(bytes))
        }
    }

    test("writeAssetExclusive Exists: a second write of the same asset == the oracle (Exists, no clobber)") {
        assetOracle { hybrid, oracle ->
            val asset = TreePath.require("guides/diagram.png")
            hybrid.writeAssetExclusive(grantForTests(), asset, "first".toByteArray(), hasher)
            oracle.writeAssetExclusive(grantForTests(), asset, "first".toByteArray(), hasher)
            val h = hybrid.writeAssetExclusive(grantForTests(), asset, "second".toByteArray(), hasher)
            val o = oracle.writeAssetExclusive(grantForTests(), asset, "second".toByteArray(), hasher)
            h shouldBe o
            h shouldBe CreateResult.Exists(asset)
        }
    }

    test("writeAssetExclusive ParentMissing: an asset into a non-existent folder == the oracle (ParentMissing)") {
        assetOracle { hybrid, oracle ->
            val asset = TreePath.require("nope/orphan.png")
            val bytes = "x".toByteArray()
            val h = hybrid.writeAssetExclusive(grantForTests(), asset, bytes, hasher)
            val o = oracle.writeAssetExclusive(grantForTests(), asset, bytes, hasher)
            h shouldBe o
            h shouldBe CreateResult.ParentMissing
        }
    }

    test("writeAssetExclusive Rejected: a scan-skipped (dot-prefixed) asset name == the oracle (Rejected)") {
        assetOracle { hybrid, oracle ->
            val asset = TreePath.require("guides/.hidden.png")
            val bytes = "x".toByteArray()
            val h = hybrid.writeAssetExclusive(grantForTests(), asset, bytes, hasher)
            val o = oracle.writeAssetExclusive(grantForTests(), asset, bytes, hasher)
            (h is CreateResult.Rejected) shouldBe true
            (o is CreateResult.Rejected) shouldBe true
        }
    }

    // --- Adoption pass over the hybrid (addendum acceptance: "adoption runs inside the oracle incl.
    // one injected mid-pass failure"). AdoptionPass.MATERIALIZE routes through the hybrid's write().
    test("AdoptionPass.MATERIALIZE over the hybrid == over a local store: same dispositions, same patched bytes") {
        val pages = mapOf(
            TreePath.require("a.md") to "---\ntitle: A\n---\n\nbody a\n".toByteArray(),
            TreePath.require("dir/b.md") to "---\ntitle: B\n---\n\nbody b\n".toByteArray(),
        )
        HybridFixture().use { hybrid ->
            localOracle().use { oracle ->
                pages.forEach { (path, bytes) ->
                    hybrid.seedExisting(path, bytes)
                    Files.createDirectories(oracle.root.resolve(path.value).parent)
                    Files.write(oracle.root.resolve(path.value), bytes)
                }
                oracle.store.scan()

                // A deterministic TestIdProvider on BOTH so the minted ids (and thus the patched bytes)
                // align page-for-page in scan order - otherwise UUIDv7 randomness would defeat equality.
                inMemoryIdMap { hybridIdMap ->
                    inMemoryIdMap { localIdMap ->
                        val hybridReport = AdoptionPass(
                            hybrid.store,
                            hybridIdMap,
                            PageIdentityService(TestIdProvider()),
                            FrontmatterPatcher(),
                        )
                            .run(AdoptionPass.Mode.MATERIALIZE)
                        val localReport = AdoptionPass(
                            oracle.store,
                            localIdMap,
                            PageIdentityService(TestIdProvider()),
                            FrontmatterPatcher(),
                        )
                            .run(AdoptionPass.Mode.MATERIALIZE)

                        hybridReport.pages.map { it.path to it.disposition } shouldBe localReport.pages.map { it.path to it.disposition }
                        pages.keys.forEach { path ->
                            hybrid.store.read(path) shouldBe oracle.store.read(path) // identical patched id: bytes
                        }
                    }
                }
            }
        }
    }

    test(
        "AdoptionPass over the hybrid, injected mid-pass mirror-write failure: the pass surfaces the failure " +
            "fail-closed, the bucket keeps the durable patched bytes, and a later hydrate heals the mirror",
    ) {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("patchme.md")
            hybrid.seedExisting(path, "---\ntitle: X\n---\n\nbody\n".toByteArray())
            val key = hybrid.mirror.resolveRepoRelativePath(path)
            hybrid.mirrorAtomics.failAlways() // every mirror write in this pass throws (the injected mid-pass failure)

            inMemoryIdMap { idMap ->
                shouldThrow<ObjectStoreException> {
                    AdoptionPass(hybrid.store, idMap, PageIdentityService(TestIdProvider()), FrontmatterPatcher())
                        .run(AdoptionPass.Mode.MATERIALIZE)
                }
            }
            // The bucket PUT landed BEFORE the mirror write failed (bucket-first): the patched id line is
            // durable at the authority even though the local apply threw.
            val bucketBytes = runBlocking { hybrid.fake.get(key) }?.bytes
            String(bucketBytes!!, Charsets.UTF_8) shouldContain "id: "

            // Recovery: with the mirror writable again, a hydrate pulls the durable bucket bytes back down.
            hybrid.mirrorAtomics.shouldFail = { false }
            hybrid.store.hydrate()
            String(hybrid.store.read(path)!!, Charsets.UTF_8) shouldContain "id: "
        }
    }
})

/** An in-memory SQLDelight-backed [IdMapRepository] scoped to [block] (the AdoptionPassTest idiom). */
private fun inMemoryIdMap(block: (IdMapRepository) -> Unit) {
    DatabaseFactory.createInMemoryDriver().use { driver ->
        block(SqlDelightIdMapRepository(DatabaseFactory.createDatabase(driver)))
    }
}

private fun CreateResult.shouldBeInstanceOfRejected() {
    (this is CreateResult.Rejected).shouldBe(true)
}

/**
 * `CasResult.Mismatch` carries a `ByteArray`, which Kotlin's data-class `equals` compares by
 * REFERENCE (not content) - a plain `shouldBe` between two Mismatch instances backed by different
 * array instances would spuriously fail even on identical bytes. Decompose and compare content
 * explicitly, the same idiom `LocalContentStoreCasTest` uses.
 */
private fun assertMismatchParity(actual: CasResult, expected: CasResult, expectedBytes: ByteArray) {
    val h = actual.shouldBeInstanceOf<CasResult.Mismatch>()
    val o = expected.shouldBeInstanceOf<CasResult.Mismatch>()
    h.currentHash shouldBe o.currentHash
    h.currentBytes shouldBe expectedBytes
    o.currentBytes shouldBe expectedBytes
}

/**
 * Runs [block] against a hybrid store and a bare-local oracle store that BOTH already hold an existing
 * `guides/` folder (minted by creating a page in it, since writeAssetExclusive never creates parent
 * dirs - W3b), for the asset-family differential pairs.
 */
private fun assetOracle(block: (hybrid: ContentStore, oracle: ContentStore) -> Unit) {
    val hasher = CitationFactory()::contentHash
    HybridFixture().use { hybrid ->
        LocalOracle().use { oracle ->
            val page = TreePath.require("guides/index.md")
            val body = "# Guides\n".toByteArray()
            hybrid.seedExisting(page, body)
            oracle.store.createExclusive(page, body, hasher)
            oracle.store.scan()
            block(hybrid.store, oracle.store)
        }
    }
}

private fun localOracle(): LocalOracle = LocalOracle()

/** The plain [LocalContentStore] oracle over its own temp root - closed alongside the hybrid fixture. */
private class LocalOracle : AutoCloseable {
    val root = Files.createTempDirectory("pb-oracle-root")
    val store = LocalContentStore(root = root, ignoreRules = IgnoreRules())
    override fun close() {
        root.toFile().deleteRecursively()
    }
}
