package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.TreePath
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

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

    test("a store that never hydrated (PREVIEW adopt) is COMPLETE - it claimed no tree, so it lost nothing") {
        HybridFixture().use { hybrid ->
            hybrid.store.scan().complete shouldBe true
        }
    }
})
