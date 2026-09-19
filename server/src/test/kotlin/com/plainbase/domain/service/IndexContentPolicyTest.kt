package com.plainbase.domain.service

import com.plainbase.domain.content.ContentPathPolicy
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.frameworks.filesystem.LocalContentStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class IndexContentPolicyTest : FunSpec({
    val fixedId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b71")

    test("a visible duplicate cannot displace a hidden materialized incumbent and reinclude restores it") {
        withTempTree(seed = { root ->
            writePage(root, "hidden/original.md", "---\nid: ${fixedId.value}\n---\noriginal\n")
        }) { root ->
            val admitted = mutableSetOf("hidden")
            val membership = firstSegmentPolicy(admitted)
            val store = LocalContentStore(root, policy = membership)
            IndexHarness(
                root = root,
                contentStore = store,
                policies = mapOf(RootName.PRIMARY to membership),
            ).use { harness ->
                harness.builder.rebuild().pages.single().id shouldBe fixedId
                writePage(root, "visible/copy.md", "---\nid: ${fixedId.value}\n---\ncopy\n")

                admitted.clear()
                admitted += "visible"
                val claimant = harness.builder.rebuild().pages.single()
                claimant.path shouldBe TreePath.require("visible/copy.md")
                claimant.id shouldNotBe fixedId
                harness.idMap.bindingInRoot(RootName.PRIMARY, fixedId)?.path shouldBe
                    RootedPath(RootName.PRIMARY, TreePath.require("hidden/original.md"))

                admitted += "hidden"
                val reIncluded = harness.builder.rebuild()
                reIncluded.byPath.getValue(RootedPath(RootName.PRIMARY, TreePath.require("hidden/original.md"))).id shouldBe fixedId
                reIncluded.byPath.getValue(RootedPath(RootName.PRIMARY, claimant.path)).id shouldBe claimant.id
            }
        }
    }

    test("an all-hidden root stays healthy and reinclude keeps the durable id") {
        withTempTree(seed = { root -> writePage(root, "docs/page.md", "# Page\n") }) { root ->
            val admitted = mutableSetOf("docs")
            val membership = firstSegmentPolicy(admitted)
            IndexHarness(
                root = root,
                contentStore = LocalContentStore(root, policy = membership),
                policies = mapOf(RootName.PRIMARY to membership),
            ).use { harness ->
                val id = harness.builder.rebuild().pages.single().id

                admitted.clear()
                harness.builder.rebuild().pages.isEmpty() shouldBe true
                harness.availability.current().isAvailable(RootName.PRIMARY).shouldBeTrue()
                harness.idMap.bindingInRoot(RootName.PRIMARY, id)?.path shouldBe
                    RootedPath(RootName.PRIMARY, TreePath.require("docs/page.md"))

                admitted += "docs"
                harness.builder.rebuild().pages.single().id shouldBe id
            }
        }
    }
})

internal fun firstSegmentPolicy(admitted: Set<String>): ContentPathPolicy = ContentPathPolicy.create(
    fileEligibility = { path -> path.segments.firstOrNull() in admitted },
    traversalEligibility = { path -> path == null || path.segments.firstOrNull() in admitted },
    metadataEligibility = { path -> path.segments.firstOrNull() in admitted },
)
