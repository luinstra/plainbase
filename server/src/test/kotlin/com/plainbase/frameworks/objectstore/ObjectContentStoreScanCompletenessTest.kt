package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.TreePath
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * Tests the object store's walk-completeness observation (ADR-0011 D5). A warm hydrate may defer a failed GET or
 * mirror write so boot can continue. Scans still return the files present in the mirror, but report incomplete
 * while the mirror does not hold the latest listed generation.
 *
 * An incomplete walk cannot support a new OBJECT_LIST absence proof. A complete walk is only one prerequisite:
 * selected-page reads and binding/proof checks are covered by separate indexing integration tests. Previously
 * committed retirements can still be reconciled independently of this scan evidence.
 */
class ObjectContentStoreScanCompletenessTest : FunSpec({

    val deferred = TreePath.require("deferred.md")
    val hydrated = TreePath.require("hydrated.md")

    test("a hydrate that DEFERRED an object scans INCOMPLETE - and retains hydrated files in the scan") {
        HybridFixture().use { hybrid ->
            hybrid.fake.seed(hybrid.mirror.resolveRepoRelativePath(hydrated), "# Hydrated\n".toByteArray())
            val key = hybrid.mirror.resolveRepoRelativePath(deferred)
            hybrid.fake.seed(key, "# Deferred\n".toByteArray())
            hybrid.fake.failNextGetFor += key // the warm-boot deferral: the GET fails, the boot goes on

            hybrid.store.hydrate() // non-strict: must NOT throw

            val scan = hybrid.store.scan()
            withClue("an incomplete walk supplies no new OBJECT_LIST retirement evidence") {
                scan.complete shouldBe false
            }
            withClue("hydrated files remain in the incomplete scan") {
                scan.files.map { it.path } shouldContain hydrated
            }
        }
    }

    test("a mirror WRITE failure defers the object too - the same incomplete view, from the other site") {
        HybridFixture().use { hybrid ->
            hybrid.fake.seed(hybrid.mirror.resolveRepoRelativePath(deferred), "# Deferred\n".toByteArray())
            hybrid.mirrorAtomics.failAlways() // the write and its one retry both fail (seam g)

            hybrid.store.hydrate()

            hybrid.store.scan().complete shouldBe false
        }
    }

    test("a CLEAN hydrate is COMPLETE, and a later clean hydrate CLEARS a previous deferral") {
        HybridFixture().use { hybrid ->
            val key = hybrid.mirror.resolveRepoRelativePath(deferred)
            hybrid.fake.seed(key, "# Deferred\n".toByteArray())
            hybrid.fake.failNextGetFor += key

            hybrid.store.hydrate()
            hybrid.store.scan().complete shouldBe false

            // The bucket is reachable again (`failNextGetFor` consumed itself), so this hydrate fetches what the
            // last one deferred. The next hydrate restores the mirror's walk-completeness evidence.
            hybrid.store.hydrate()

            withClue("a successful retry restores walk completeness") {
                hybrid.store.scan().complete shouldBe true
            }
            hybrid.store.scan().files.map { it.path } shouldContain deferred
        }
    }

    test("a store that has never LISTED vouches for nothing - not even that its mirror is a whole corpus") {
        HybridFixture().use { hybrid ->
            withClue("completeness is DERIVED from the latest generation, and a store with no generation has none") {
                hybrid.store.scan().complete shouldBe false
            }
        }
    }

    test("STALE same-path bytes make the generation INCOMPLETE - the etag is the point, not the file name") {
        HybridFixture().use { hybrid ->
            val key = hybrid.mirror.resolveRepoRelativePath(hydrated)
            hybrid.fake.seed(key, "# Hydrated\n".toByteArray())
            hybrid.store.hydrate()
            hybrid.store.scan().complete shouldBe true

            // The object is REPLACED at the bucket (a new etag) and the GET that would bring it down fails. The mirror
            // file still EXISTS - it is simply the WRONG GENERATION. A manifest of bare key names could not tell the
            // difference, and treating the mirror as complete would supply false walk evidence for a new OBJECT_LIST retirement.
            hybrid.fake.seed(key, "# Hydrated, rewritten\n".toByteArray())
            hybrid.fake.failNextGetFor += key
            hybrid.store.pollOnce()

            withClue("present is not current: the mirror must hold the LISTED etag, not merely a file at that path") {
                hybrid.store.scan().complete shouldBe false
            }
        }
    }

    test("a DIRECTORY where a file should be makes the generation INCOMPLETE - a directory exists, and serves no bytes") {
        HybridFixture().use { hybrid ->
            val key = hybrid.mirror.resolveRepoRelativePath(hydrated)
            hybrid.fake.seed(key, "# Hydrated\n".toByteArray())
            hybrid.store.hydrate()
            hybrid.store.scan().complete shouldBe true

            val target = hybrid.mirror.onDiskTarget(hydrated)
            Files.delete(target)
            Files.createDirectory(target) // `exists` says yes. It is not a mirror file, and it never was.

            hybrid.store.scan().complete shouldBe false
        }
    }
})
