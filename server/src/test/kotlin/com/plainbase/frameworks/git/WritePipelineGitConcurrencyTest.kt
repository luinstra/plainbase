package com.plainbase.frameworks.git

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.WriteHistoryHook
import com.plainbase.domain.service.WriteIntent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.concurrent.CountDownLatch

/**
 * End-to-end through the W1 pipeline (W4 §6 #4): two pages saved concurrently both commit, with no
 * `.git/index.lock` error, producing two one-file commits. The pipeline's `@Synchronized` monitor
 * serializes the commits; the per-op temp index means neither commit ever touches a shared `.git/index`
 * (so there is no lock to contend on), and each commit's tree touches exactly its own page.
 */
class WritePipelineGitConcurrencyTest : FunSpec({

    test("two pages saved through the monitor both commit, no index.lock, two one-file commits") {
        val root = Files.createTempDirectory("plainbase-git-concurrency")
        val home = Files.createTempDirectory("plainbase-git-concurrency-home")
        val citations = CitationFactory()

        // Seed two pages on disk so the pipeline's CAS resolves them.
        val pageA = "pages/a.md"
        val pageB = "pages/b.md"
        Files.createDirectories(root.resolve("pages"))
        val seedA = "---\ntitle: A\n---\n\n# A\n".toByteArray()
        val seedB = "---\ntitle: B\n---\n\n# B\n".toByteArray()
        Files.write(root.resolve(pageA), seedA)
        Files.write(root.resolve(pageB), seedB)

        try {
            val exec = GitExecutor(workTree = root, home = home)
            val provider = providerOver(exec, root, home)
            val hook = WriteHistoryHook { path, bytes -> provider.commit(path, bytes)?.sha }

            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val idA = harness.builder.current.pages.first { it.path.value == pageA }.id
                val idB = harness.builder.current.pages.first { it.path.value == pageB }.id
                val pipeline = harness.writePipeline(historyHook = hook)

                val newA = "---\ntitle: A\n---\n\n# A edited\n".toByteArray()
                val newB = "---\ntitle: B\n---\n\n# B edited\n".toByteArray()
                val intentA = WriteIntent(idA, TreePath.require(pageA), citations.contentHash(seedA), newA)
                val intentB = WriteIntent(idB, TreePath.require(pageB), citations.contentHash(seedB), newB)

                val go = CountDownLatch(1)
                val threads = listOf(intentA, intentB).map { intent ->
                    Thread {
                        go.await()
                        pipeline.write(intent)
                    }
                }
                threads.forEach { it.start() }
                go.countDown()
                threads.forEach { it.join() }

                openOracle(root).use { repo ->
                    val commits = repo.headCommits()
                    commits.size shouldBe 2
                    // The two serialized commits between them introduced exactly the two pages: the root
                    // commit holds one page, HEAD adds the other — never a 2-file commit from a shared index.
                    val rootCommit = commits.first { it.parentCount == 0 }
                    val headCommit = commits.first { it.parentCount > 0 }
                    repo.treePaths(rootCommit).size shouldBe 1
                    repo.treePaths(headCommit).toSet() shouldBe setOf(pageA, pageB)
                }
                // No .git/index.lock left behind (the live index is never used).
                Files.exists(root.resolve(".git/index.lock")) shouldBe false
            }
        } finally {
            root.toFile().deleteRecursively()
            home.toFile().deleteRecursively()
        }
    }
})
