package com.plainbase.domain.service

import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.StoreRead
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

class IndexBuilderPassScansTest : FunSpec({
    val docs = RootName.PRIMARY
    val readableId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
    val unreadId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5b")
    fun page(id: PageId, body: String = "Body") = "---\nid: ${id.value}\ntitle: Page\n---\n$body\n"

    test("a full pass publishes the available root and omits the unavailable root") {
        withTempTree(seed = {}) { base ->
            val primaryPath = Files.createDirectory(base.resolve("docs"))
            val extraPath = Files.createDirectory(base.resolve("extra"))
            writePage(primaryPath, "readable.md", page(readableId))
            writePage(extraPath, "offline.md", page(unreadId))
            val primary = localRoot("docs", primaryPath)
            val extra = localRoot("extra", extraPath)
            IndexHarness(
                primaryPath,
                rootRegistry = RootRegistry.of(listOf(primary, extra)),
                sources = listOf(
                    IndexBuilder.Source(primary, LocalContentStore(primaryPath), NoOpHistoryProvider),
                    IndexBuilder.Source(extra, LocalContentStore(extraPath), NoOpHistoryProvider),
                ),
            ).use { harness ->
                harness.availability.markUnavailable(extra.name, UnavailableCause.MISSING_AT_BOOT)
                harness.builder.rebuild().pages.map { it.path } shouldBe listOf(TreePath.require("readable.md"))

                harness.builder.lastFullPassScans[docs].shouldNotBeNull() shouldBe ScanCompleteness(true, true)
                harness.builder.lastFullPassScans.containsKey(extra.name) shouldBe false
            }
        }
    }

    test("a materialized incomplete walk publishes its false walk flag") {
        withTempTree(seed = { writePage(it, "readable.md", page(readableId)) }) { root ->
            val real = LocalContentStore(root)
            val incomplete = object : ContentStore by real {
                override fun scan() = real.scan().copy(complete = false)
            }
            IndexHarness(root, contentStore = incomplete).use { harness ->
                harness.builder.rebuild().pages.single().id shouldBe readableId

                harness.builder.lastFullPassScans[docs].shouldNotBeNull() shouldBe ScanCompleteness(false, true)
            }
        }
    }

    test("an uncertain read of a pre-bound page publishes its false page read flag") {
        withTempTree(seed = {
            writePage(it, "readable.md", page(readableId))
            writePage(it, "unread.md", page(unreadId))
        }) { root ->
            val real = LocalContentStore(root)
            val unread = RootedPath(docs, TreePath.require("unread.md"))
            val uncertain = object : ContentStore by real {
                override fun readClassified(path: TreePath): StoreRead =
                    if (path == unread.path) StoreRead.NoBytes else real.readClassified(path)
            }
            IndexHarness(root, contentStore = uncertain).use { harness ->
                harness.idMap.bind(unread, unreadId, materialized = true)
                harness.absence.read(uncertain, unread) shouldBe ContentRead.AbsenceUnknown
                harness.builder.rebuild().pages.single().id shouldBe readableId

                harness.builder.lastFullPassScans[docs].shouldNotBeNull() shouldBe ScanCompleteness(true, false)
            }
        }
    }

    test("a targeted reindex preserves the non-empty evidence of the latest full pass") {
        withTempTree(seed = { writePage(it, "readable.md", page(readableId)) }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild().pages.single().id shouldBe readableId
                val fullPass = harness.builder.lastFullPassScans
                fullPass.isNotEmpty() shouldBe true
                fullPass[docs].shouldNotBeNull() shouldBe ScanCompleteness(true, true)
                writePage(root, "readable.md", page(readableId, "Changed body"))

                harness.builder.reindex(RootedPath(docs, TreePath.require("readable.md")))

                harness.builder.current.pages.single().markdown shouldContain "Changed body"
                harness.builder.lastFullPassScans shouldBe fullPass
            }
        }
    }
})
