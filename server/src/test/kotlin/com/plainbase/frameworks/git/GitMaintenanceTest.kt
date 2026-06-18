package com.plainbase.frameworks.git

import com.plainbase.domain.content.TreePath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.spyk
import io.mockk.verify
import java.util.concurrent.atomic.AtomicInteger

/**
 * Auto-maintenance (W4 §6 / F3): plumbing commits skip the auto-GC porcelain runs, so a best-effort
 * `maintenance` dispatcher fires after each successful commit — and a maintenance failure NEVER fails the
 * save. Verified through an injected dispatcher (the real one runs git off the W1 monitor).
 */
class GitMaintenanceTest : FunSpec({

    val page = TreePath.require("page.md")

    test("the maintenance dispatcher fires once per successful commit") {
        withGitRepoHome { root, exec, home ->
            val fired = AtomicInteger(0)
            val provider = providerOver(exec, root, home, maintenance = { fired.incrementAndGet() })
            repeat(4) { n -> provider.commit(page, "v$n\n".toByteArray()) }
            fired.get() shouldBe 4
        }
    }

    test("a no-op idempotent commit still returns and does not over-fire maintenance") {
        withGitRepoHome { root, exec, home ->
            val fired = AtomicInteger(0)
            val provider = providerOver(exec, root, home, maintenance = { fired.incrementAndGet() })
            val bytes = "same\n".toByteArray()
            provider.commit(page, bytes)
            provider.commit(page, bytes) // idempotent no-op: returns early, before the maintenance dispatch
            fired.get() shouldBe 1
        }
    }

    test("a maintenance dispatcher that throws does not fail the save (non-fatal)") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home, maintenance = { error("maintenance boom") })
            // The commit must still succeed and return despite the throwing dispatcher.
            val commit = provider.commit(page, "content\n".toByteArray())
            openOracle(root).use { repo -> repo.headCommits().single().name shouldBe commit.sha }
        }
    }

    test("the default dispatcher runs git maintenance after every commit without disturbing history") {
        withGitRepoHome { root, exec, home ->
            // No injected dispatcher → the real default (`git maintenance run --auto`, falling back to `gc
            // --auto` on old git) runs inline here. It must complete cleanly and leave history intact (the
            // dispatch is best-effort and non-fatal; git's own --auto heuristics decide WHEN to pack).
            val provider = providerOver(exec, root, home, maintenance = null)
            repeat(6) { n -> provider.commit(page, "version $n\n".toByteArray()) }
            openOracle(root).use { repo -> repo.headCommits().size shouldBe 6 }
            exec.run(listOf("fsck")).ok shouldBe true // the object store stays consistent through maintenance
        }
    }

    test("an explicit gc through the off-monitor dispatcher bounds loose-object growth (F3 outcome)") {
        withGitRepoHome { root, exec, home ->
            // The F3 protection in action: packing loose objects (here forced via an explicit gc on the
            // SAME off-monitor dispatcher seam) bounds .git/objects growth — the unbounded-loose-object bug
            // is what auto-maintenance defends against, dispatched off the W1 monitor on the repo's objects.
            val provider = providerOver(exec, root, home, maintenance = { exec.run(listOf("gc", "--quiet")) })
            repeat(8) { n -> provider.commit(page, "version $n\n".toByteArray()) }

            val packDir = root.resolve(".git/objects/pack")
            val packs = java.nio.file.Files.list(packDir).use { stream ->
                stream.filter { it.toString().endsWith(".pack") }.count().toInt()
            }
            packs shouldBeGreaterThan 0
            openOracle(root).use { repo -> repo.headCommits().size shouldBe 8 }
        }
    }

    // P2-C: when `git maintenance run` is unavailable (git < 2.30, here forced to a non-zero exit), the
    // shared runAutoMaintenance helper FALLS BACK to `gc --auto` — so old hosts still get auto-GC.
    test("auto-maintenance falls back to gc --auto when git maintenance is unavailable (P2-C)") {
        withGitRepoHome { root, _, _ ->
            val spy = spyk(GitExecutor(workTree = root, home = root.resolve(".home")))
            // Force the `maintenance run` probe to fail (as a host without `git maintenance` would).
            io.mockk.every { spy.run(match { it.firstOrNull() == "maintenance" }, any(), any()) } returns
                GitResult(exitCode = 1, stdout = ByteArray(0), stderr = "git: 'maintenance' is not a git command")
            spy.run(listOf("init")) // a real repo so gc --auto has something to run against

            runAutoMaintenance(spy)

            // The fallback fired: gc --auto was invoked after the maintenance probe failed.
            verify { spy.run(match { it.take(2) == listOf("gc", "--auto") }, any(), any()) }
        }
    }
})
