package com.plainbase.domain.service

import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.principal.grantForTests
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * PB-WRITE-1 named test 6 (master criterion 4): the pipeline serializes saves. Two pages save
 * concurrently and both land verbatim; two threads racing the SAME page with the same stale
 * `base_hash` produce exactly one [WriteOutcome.Written] and one [WriteOutcome.Conflict] — the CAS
 * disk-recheck catches the loser, and the final on-disk bytes are exactly one writer's, never mixed.
 */
class WritePipelineConcurrencyTest : FunSpec({

    fun seedTwo(root: Path) {
        writePage(root, "a.md", "---\ntitle: A\n---\n\n# A\n\nbody a.\n")
        writePage(root, "b.md", "---\ntitle: B\n---\n\n# B\n\nbody b.\n")
    }

    test("concurrent saves to two different pages both succeed and write verbatim") {
        withTempTree(::seedTwo) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val pipeline = harness.writePipeline()
                val pageA = harness.builder.current.byPath.getValue(com.plainbase.domain.content.TreePath.require("a.md"))
                val pageB = harness.builder.current.byPath.getValue(com.plainbase.domain.content.TreePath.require("b.md"))
                val saveA = "---\ntitle: A\n---\n\n# A\n\nnew a.\n".toByteArray()
                val saveB = "---\ntitle: B\n---\n\n# B\n\nnew b.\n".toByteArray()

                val start = CountDownLatch(1)
                val outA = AtomicReference<WriteOutcome>()
                val outB = AtomicReference<WriteOutcome>()
                val ta = thread {
                    start.await()
                    outA.set(pipeline.write(grantForTests(), WriteIntent(pageA.id, pageA.path, pageA.contentHash, saveA)))
                }
                val tb = thread {
                    start.await()
                    outB.set(pipeline.write(grantForTests(), WriteIntent(pageB.id, pageB.path, pageB.contentHash, saveB)))
                }
                start.countDown()
                ta.join(10_000)
                tb.join(10_000)

                (outA.get() is WriteOutcome.Written) shouldBe true
                (outB.get() is WriteOutcome.Written) shouldBe true
                Files.readAllBytes(root.resolve("a.md")) shouldBe saveA
                Files.readAllBytes(root.resolve("b.md")) shouldBe saveB
            }
        }
    }

    test("two threads racing the same page with the same stale base_hash leave exactly one winner") {
        withTempTree(::seedTwo) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val pipeline = harness.writePipeline()
                val page = harness.builder.current.byPath.getValue(com.plainbase.domain.content.TreePath.require("a.md"))
                val baseHash = page.contentHash
                val save1 = "---\ntitle: A\n---\n\n# A\n\nwriter one.\n".toByteArray()
                val save2 = "---\ntitle: A\n---\n\n# A\n\nwriter two.\n".toByteArray()

                val start = CountDownLatch(1)
                val out1 = AtomicReference<WriteOutcome>()
                val out2 = AtomicReference<WriteOutcome>()
                val t1 = thread {
                    start.await()
                    out1.set(pipeline.write(grantForTests(), WriteIntent(page.id, page.path, baseHash, save1)))
                }
                val t2 = thread {
                    start.await()
                    out2.set(pipeline.write(grantForTests(), WriteIntent(page.id, page.path, baseHash, save2)))
                }
                start.countDown()
                t1.join(10_000)
                t2.join(10_000)

                val outcomes = listOf(out1.get(), out2.get())
                outcomes.count { it is WriteOutcome.Written } shouldBe 1
                outcomes.count { it is WriteOutcome.Conflict && it.reason == "content_changed" } shouldBe 1
                // The final on-disk bytes are exactly one writer's, never a byte-mix.
                val onDisk = Files.readAllBytes(root.resolve("a.md"))
                (onDisk.contentEquals(save1) || onDisk.contentEquals(save2)) shouldBe true
            }
        }
    }
})
