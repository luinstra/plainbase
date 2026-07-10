package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.TreePath
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

/**
 * C5 FORK 1 (BLOCKING 2 / HOLE A): the RESTORE-path `hydrate(strict = true)` must abort the boot - no
 * reconcile commit follows - the instant ANY of the three best-effort deferral sites would otherwise
 * leave the mirror INCOMPLETE: (a) a GET failure/404-while-listed, (b) a mirror-WRITE failure after a
 * good GET, (c) a delete-phase failure. A WARM boot (`strict = false`, the default) keeps the C4
 * best-effort behavior unchanged - covered by the existing `ObjectBootOrderTest`/`ObjectOutageTest` suites;
 * this file only adds the NEW strict-mode assertions.
 */
class ObjectContentStoreStrictHydrateTest : FunSpec({

    test("(a) strict hydrate: a GET failure for a bucket-only key aborts the boot") {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("bucket-only.md")
            val key = hybrid.mirror.resolveRepoRelativePath(path)
            hybrid.fake.seed(key, "v1".toByteArray()) // present at the bucket, absent from the mirror -> GET is owed
            hybrid.fake.failNextGetFor += key

            val failure = shouldThrow<ObjectStoreException> { hybrid.store.hydrate(strict = true) }
            failure.message shouldContain "GET"
        }
    }

    test("(b) strict hydrate: a mirror-WRITE failure after a successful GET aborts the boot") {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("write-fails.md")
            val key = hybrid.mirror.resolveRepoRelativePath(path)
            hybrid.fake.seed(key, "v1".toByteArray())
            hybrid.mirrorAtomics.failAlways() // the write AND its one retry both fail

            val failure = shouldThrow<ObjectStoreException> { hybrid.store.hydrate(strict = true) }
            failure.message shouldContain "mirror write"
        }
    }

    test("(c) strict hydrate: a delete-phase failure (bucket-absent mirror file that cannot be deleted) aborts the boot") {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("stale.md")
            // A directory at the exact on-disk target, made non-empty via an IGNORED dotfile child
            // (never scanned/never itself a delete-loop target, so it can't be reaped out from under this
            // assertion) - Files.deleteIfExists on a non-empty directory throws DirectoryNotEmptyException
            // PORTABLY, regardless of OS/user privilege (unlike a chmod-based injection, which root bypasses).
            val target = hybrid.mirrorRoot.resolve("stale.md")
            Files.createDirectories(target)
            Files.writeString(target.resolve(".hidden"), "x")
            hybrid.state.recordConfirmed(path, "\"stale-etag\"") // bucket-known once; never re-seeded at the fake
            hybrid.state.persist()

            val failure = shouldThrow<ObjectStoreException> { hybrid.store.hydrate(strict = true) }
            failure.message shouldContain "deleting bucket-absent mirror file"
        }
    }

    test("the default (strict = false, warm-boot) still tolerates a GET failure - C4 behavior unchanged") {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("bucket-only.md")
            val key = hybrid.mirror.resolveRepoRelativePath(path)
            hybrid.fake.seed(key, "v1".toByteArray())
            hybrid.fake.failNextGetFor += key

            hybrid.store.hydrate() // must NOT throw
        }
    }
})
