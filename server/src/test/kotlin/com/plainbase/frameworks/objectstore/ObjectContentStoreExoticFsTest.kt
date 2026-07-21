package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.TreePath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/** Filesystem portability contracts at the object-store mirror boundary. */
class ObjectContentStoreExoticFsTest : FunSpec({

    test("a poll falls back to copy-and-delete when the mirror filesystem cannot atomically rename") {
        HybridFixture().use { hybrid ->
            val path = TreePath.require("guide.md")
            val bytes = "authoritative bucket bytes".toByteArray()
            val etag = hybrid.fake.seed(path.value, bytes)
            hybrid.mirrorAtomics.atomicMoveUnsupported = true
            val changes = mutableListOf<TreePath>()

            hybrid.store.pollOnce(changes::add)
            hybrid.mirror.scan()

            hybrid.mirrorAtomics.copyReplaceCalls.get() shouldBe 1
            hybrid.store.read(path) shouldBe bytes
            hybrid.state.etagOf(path) shouldBe etag
            changes shouldContainExactly listOf(path)
        }
    }
})
