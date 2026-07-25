package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.TreePath
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * DELETE AUTHORITY for the object backend (ADR-0011 D5): the mirror is the tree this store SERVES, and a WARM boot
 * is allowed to hand back an INCOMPLETE one - a GET that failed, a mirror write that failed, deferred so a
 * transient bucket fault cannot stop the server. The rebuild walks that mirror, and a walk cannot tell a page whose
 * GET failed from a page an operator deleted: both are simply not there.
 *
 * So the store says which one it is. `ScanResult.complete = false` while any object is deferred withholds the pass's
 * DELETE AUTHORITY over this root (`IndexBuilder`), and withholds nothing else: the pages the mirror DID hydrate
 * publish and serve exactly as they always did. That split is the entire point - a transient GET failure must not
 * blank a site, and it must not delete its rows either.
 */
class ObjectContentStoreScanCompletenessTest : FunSpec({

    val deferred = TreePath.require("deferred.md")
    val hydrated = TreePath.require("hydrated.md")

    test("a hydrate that DEFERRED an object scans INCOMPLETE - and still publishes every page it did hydrate") {
        HybridFixture().use { hybrid ->
            hybrid.fake.seed(hybrid.mirror.resolveRepoRelativePath(hydrated), "# Hydrated\n".toByteArray())
            val key = hybrid.mirror.resolveRepoRelativePath(deferred)
            hybrid.fake.seed(key, "# Deferred\n".toByteArray())
            hybrid.fake.failNextGetFor += key // the warm-boot deferral: the GET fails, the boot goes on

            hybrid.store.hydrate() // non-strict: must NOT throw

            val scan = hybrid.store.scan()
            withClue("a mirror with holes in it is not a corpus: nothing may be DELETED for this root") {
                scan.complete shouldBe false
            }
            withClue("...and nothing is withheld from the READ path either - the site does not go blank over one GET") {
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
            // last one deferred. Delete authority comes BACK - the root was never unhealthy, only unproven.
            hybrid.store.hydrate()

            withClue("a root that heals must not stay unauthoritative forever: the next pass may delete again") {
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
            // difference, and the root would take delete authority over a mirror it had never actually verified.
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
