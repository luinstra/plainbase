package com.plainbase.frameworks.git

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.CommitOutcome
import com.plainbase.domain.history.HistoryChange
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class DiscussionCommitTest : FunSpec({
    test("put history changes compare byte content") {
        val path = TreePath.require("docs/page.md")
        val bytes = "same\n".toByteArray()
        val first = HistoryChange.Put(path, bytes)
        val second = HistoryChange.Put(path, bytes.copyOf())

        first shouldBe second
        first.hashCode() shouldBe second.hashCode()
    }

    test("puts and deletes land in one commit") {
        withGitRepoHome { root, exec, home ->
            val page = TreePath.require("docs/page.md")
            val discussionPath = "docs/.plainbase/discussions/01900000-0000-7000-8000-000000000001/"
            val removed = TreePath.require("${discussionPath}old.md")
            val added = TreePath.require("${discussionPath}01900000-0000-7000-8000-000000000002.md")
            val provider = providerOver(exec, root, home)
            provider.commit(page, "keep\n".toByteArray())
            provider.commit(removed, "old\n".toByteArray())
            val originalHead = exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()
            val originalCount = exec.run(listOf("rev-list", "--count", "HEAD")).stdoutText.trim().toInt()

            val outcome = provider.commitChanges(
                listOf(
                    HistoryChange.Put(added, "comment\n".toByteArray()),
                    HistoryChange.Delete(removed),
                ),
                "discussion: purge 01900000-0000-7000-8000-000000000001",
                testIdentity("Ada", "ada@example.test"),
                testIdentity("Ada", "ada@example.test"),
            ).shouldBeInstanceOf<CommitOutcome.Committed>()

            outcome.sha shouldBe outcome.commit?.sha
            outcome.commit?.message shouldBe "discussion: purge 01900000-0000-7000-8000-000000000001"
            val commitSha = requireNotNull(outcome.sha)
            exec.run(listOf("rev-parse", "$commitSha^")).stdoutText.trim() shouldBe originalHead
            exec.run(listOf("rev-list", "--count", commitSha)).stdoutText.trim().toInt() shouldBe originalCount + 1
            openOracle(root).use { repo ->
                repo.treePaths(repo.headCommits().first()).sorted() shouldContainExactly listOf(page.value, added.value).sorted()
                repo.blobBytes(repo.headCommits().first(), added.value)?.decodeToString() shouldBe "comment\n"
            }
        }
    }

    test("a real cas refusal is reconciled to not committed") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            val page = TreePath.require("docs/page.md")
            provider.commit(page, "base\n".toByteArray())
            val oldHead = exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()
            val mergeBaseTally = home.resolve("merge-base-tally")
            val shim =
                installShim(
                    home,
                    """
                if [ "${'$'}sub" = "update-ref" ]; then
                    old_head=${'$'}(git -C "${'$'}repo" rev-parse HEAD) || exit ${'$'}?
                    old_tree=${'$'}(git -C "${'$'}repo" rev-parse HEAD^{tree}) || exit ${'$'}?
                    branch=${'$'}(git -C "${'$'}repo" symbolic-ref HEAD) || exit ${'$'}?
                    external=${'$'}(printf "external commit\\n" | git -C "${'$'}repo" commit-tree "${'$'}old_tree" -p "${'$'}old_head") || exit ${'$'}?
                    git -C "${'$'}repo" update-ref "${'$'}branch" "${'$'}external" "${'$'}old_head" || exit ${'$'}?
                    moved=${'$'}(git -C "${'$'}repo" rev-parse HEAD) || exit ${'$'}?
                    [ "${'$'}moved" = "${'$'}external" ] || exit 1
                    exec git "${'$'}@"
                fi
                if [ "${'$'}sub" = "merge-base" ]; then echo checked >> "${'$'}mergeBaseTally"; fi
                exec git "${'$'}@"
            """.trimIndent(),
                        mapOf("repo" to root.toString(), "mergeBaseTally" to mergeBaseTally.toString()),
                )
            try {
                val shimExec = GitExecutor(workTree = root, home = home, gitBinary = shim.toString())
                val result = providerOver(shimExec, root, home).commitChanges(
                    listOf(HistoryChange.Put(page, "next\n".toByteArray())),
                    "discussion: edit id",
                    testIdentity(),
                    testIdentity(),
                ).shouldBeInstanceOf<CommitOutcome.NotCommitted>()
                result.cause.message.isNullOrBlank() shouldBe false
                exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim() shouldNotBe oldHead
                Files.readAllLines(mergeBaseTally).size shouldBe 1
            } finally {
                Files.deleteIfExists(shim)
            }
        }
    }

    test("a shimmed refusal is reconciled to not committed") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            val page = TreePath.require("docs/page.md")
            provider.commit(page, "base\n".toByteArray())
            val oldHead = exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()
            val tally = home.resolve("merge-base-tally")
            val shim = installShim(
                home,
                """
                if [ "${'$'}sub" = "update-ref" ]; then exit 1; fi
                if [ "${'$'}sub" = "merge-base" ]; then echo checked >> "${'$'}tally"; fi
                exec git "${'$'}@"
                """.trimIndent(),
                mapOf("tally" to tally.toString()),
            )
            try {
                providerOver(GitExecutor(root, home, gitBinary = shim.toString()), root, home)
                    .commitChanges(
                        listOf(HistoryChange.Put(page, "next\n".toByteArray())),
                        "discussion: edit id",
                        testIdentity(),
                        testIdentity(),
                    ).shouldBeInstanceOf<CommitOutcome.NotCommitted>()
                exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim() shouldBe oldHead
                Files.readAllLines(tally).size shouldBe 1
            } finally {
                Files.deleteIfExists(shim)
            }
        }
    }

    test("an uncertain update ref that landed is committed") {
        withGitRepoHome { root, exec, home ->
            val page = TreePath.require("docs/page.md")
            providerOver(exec, root, home).commit(page, "base\n".toByteArray())
            val shim = installShim(
                home,
                """
                if [ "${'$'}sub" = "update-ref" ]; then
                    git "${'$'}@" || exit ${'$'}?
                    sleep 3
                    exit 1
                fi
                exec git "${'$'}@"
                """.trimIndent(),
                emptyMap(),
            )
            try {
                providerOver(GitExecutor(root, home, timeoutSeconds = 2, gitBinary = shim.toString()), root, home)
                    .commitChanges(
                        listOf(HistoryChange.Put(page, "next\n".toByteArray())),
                        "discussion: edit id",
                        testIdentity(),
                        testIdentity(),
                    ).shouldBeInstanceOf<CommitOutcome.Committed>()
            } finally {
                Files.deleteIfExists(shim)
            }
        }
    }

    test("an uncertain update ref under an external descendant is committed") {
        withGitRepoHome { root, exec, home ->
            val page = TreePath.require("docs/page.md")
            providerOver(exec, root, home).commit(page, "base\n".toByteArray())
            val shim = installShim(
                home,
                """
                if [ "${'$'}sub" = "update-ref" ]; then
                    git "${'$'}@" || exit ${'$'}?
                    landed=${'$'}(git -C "${'$'}repo" rev-parse HEAD) || exit ${'$'}?
                    tree=${'$'}(git -C "${'$'}repo" rev-parse "${'$'}landed^{tree}") || exit ${'$'}?
                    branch=${'$'}(git -C "${'$'}repo" symbolic-ref HEAD) || exit ${'$'}?
                    external=${'$'}(printf "external descendant\\n" | git -C "${'$'}repo" commit-tree "${'$'}tree" -p "${'$'}landed") || exit ${'$'}?
                    git -C "${'$'}repo" update-ref "${'$'}branch" "${'$'}external" "${'$'}landed" || exit ${'$'}?
                    exit 1
                fi
                exec git "${'$'}@"
                """.trimIndent(),
                mapOf("repo" to root.toString()),
            )
            try {
                providerOver(GitExecutor(root, home, gitBinary = shim.toString()), root, home)
                    .commitChanges(
                        listOf(HistoryChange.Put(page, "next\n".toByteArray())),
                        "discussion: edit id",
                        testIdentity(),
                        testIdentity(),
                    ).shouldBeInstanceOf<CommitOutcome.Committed>()
            } finally {
                Files.deleteIfExists(shim)
            }
        }
    }

    test("an uncertain update ref that never landed is not committed") {
        withGitRepoHome { root, exec, home ->
            val page = TreePath.require("docs/page.md")
            providerOver(exec, root, home).commit(page, "base\n".toByteArray())
            val shim = installShim(
                home,
                """
                if [ "${'$'}sub" = "update-ref" ]; then
                    sleep 3
                    exec git "${'$'}@"
                fi
                exec git "${'$'}@"
                """.trimIndent(),
                emptyMap(),
            )
            try {
                providerOver(GitExecutor(root, home, timeoutSeconds = 2, gitBinary = shim.toString()), root, home)
                    .commitChanges(
                        listOf(HistoryChange.Put(page, "next\n".toByteArray())),
                        "discussion: edit id",
                        testIdentity(),
                        testIdentity(),
                    ).shouldBeInstanceOf<CommitOutcome.NotCommitted>()
            } finally {
                Files.deleteIfExists(shim)
            }
        }
    }

    test("a nonzero update ref that landed is committed") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            val page = TreePath.require("docs/page.md")
            provider.commit(page, "base\n".toByteArray())
            val tally = home.resolve("merge-base-tally")
            val shim = installShim(
                home,
                """
                if [ "${'$'}sub" = "update-ref" ]; then
                    git "${'$'}@"
                    exit 1
                fi
                if [ "${'$'}sub" = "merge-base" ]; then echo checked >> "${'$'}tally"; fi
                exec git "${'$'}@"
                """.trimIndent(),
                mapOf("tally" to tally.toString()),
            )
            try {
                val result = providerOver(GitExecutor(root, home, gitBinary = shim.toString()), root, home)
                    .commitChanges(
                        listOf(HistoryChange.Put(page, "next\n".toByteArray())),
                        "discussion: edit id",
                        testIdentity(),
                        testIdentity(),
                    ).shouldBeInstanceOf<CommitOutcome.Committed>()
                result.sha shouldBe exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()
                Files.exists(tally) shouldBe false
            } finally {
                Files.deleteIfExists(shim)
            }
        }
    }

    test("a failed ancestry query makes an update ref outcome unknown") {
        withGitRepoHome { root, exec, home ->
            val page = TreePath.require("docs/page.md")
            providerOver(exec, root, home).commit(page, "base\n".toByteArray())
            val shim = installShim(
                home,
                """
                if [ "${'$'}sub" = "update-ref" ]; then echo refused 1>&2; exit 1; fi
                if [ "${'$'}sub" = "merge-base" ]; then echo uncertain 1>&2; exit 2; fi
                exec git "${'$'}@"
                """.trimIndent(),
                emptyMap(),
            )
            try {
                providerOver(GitExecutor(root, home, gitBinary = shim.toString()), root, home)
                    .commitChanges(
                        listOf(HistoryChange.Put(page, "next\n".toByteArray())),
                        "discussion: edit id",
                        testIdentity(),
                        testIdentity(),
                    ).shouldBeInstanceOf<CommitOutcome.Unknown>()
            } finally {
                Files.deleteIfExists(shim)
            }
        }
    }

    test("a failed ref reread makes an update ref outcome unknown") {
        withGitRepoHome { root, exec, home ->
            val page = TreePath.require("docs/page.md")
            providerOver(exec, root, home).commit(page, "base\n".toByteArray())
            val landed = root.resolve("update-ref-landed")
            val shim = installShim(
                home,
                """
                if [ "${'$'}sub" = "update-ref" ]; then
                    git -C "${'$'}repo" "${'$'}@" || exit ${'$'}?
                    touch "${'$'}repo/update-ref-landed"
                    exit 1
                fi
                if [ "${'$'}sub" = "rev-parse" ] && [ -f "${'$'}repo/update-ref-landed" ]; then
                    for arg in "${'$'}@"; do
                        if [ "${'$'}arg" = "--verify" ]; then echo unreadable 1>&2; exit 128; fi
                    done
                fi
                exec git -C "${'$'}repo" "${'$'}@"
                """.trimIndent(),
                mapOf("repo" to root.toString()),
            )
            try {
                providerOver(GitExecutor(root, home, gitBinary = shim.toString()), root, home)
                    .commitChanges(
                        listOf(HistoryChange.Put(page, "next\n".toByteArray())),
                        "discussion: edit id",
                        testIdentity(),
                        testIdentity(),
                    ).shouldBeInstanceOf<CommitOutcome.Unknown>()
                Files.exists(landed) shouldBe true
            } finally {
                Files.deleteIfExists(shim)
            }
        }
    }

    test("an interrupted commit after update ref is unknown when reconciliation cannot read the ref") {
        withGitRepoHome { root, exec, home ->
            val page = TreePath.require("docs/page.md")
            providerOver(exec, root, home).commit(page, "base\n".toByteArray())
            val updated = home.resolve("update-ref-landed")
            val shim = installShim(
                home,
                """
                if [ "${'$'}sub" = "update-ref" ]; then
                    git -C "${'$'}repo" "${'$'}@" || exit ${'$'}?
                    touch "${'$'}updated"
                    sleep 60
                fi
                if [ "${'$'}sub" = "rev-parse" ] && [ -f "${'$'}updated" ]; then
                    for arg in "${'$'}@"; do
                        if [ "${'$'}arg" = "--verify" ]; then echo interrupted-ref-read 1>&2; exit 2; fi
                    done
                fi
                exec git -C "${'$'}repo" "${'$'}@"
                """.trimIndent(),
                mapOf("updated" to updated.toString(), "repo" to root.toString()),
            )
            try {
                val outcome = AtomicReference<CommitOutcome>()
                val interruptedOnReturn = AtomicBoolean(false)
                val finished = CountDownLatch(1)
                val worker = Thread {
                    outcome.set(
                        providerOver(GitExecutor(root, home, timeoutSeconds = 30, gitBinary = shim.toString()), root, home)
                            .commitChanges(
                                listOf(HistoryChange.Put(page, "next\n".toByteArray())),
                                "discussion: edit id",
                                testIdentity(),
                                testIdentity(),
                            ),
                    )
                    interruptedOnReturn.set(Thread.currentThread().isInterrupted)
                    finished.countDown()
                }
                worker.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!Files.exists(updated) && System.nanoTime() < deadline) Thread.sleep(10)
                Files.exists(updated) shouldBe true
                worker.interrupt()
                finished.await(10, TimeUnit.SECONDS) shouldBe true
                outcome.get().shouldBeInstanceOf<CommitOutcome.Unknown>()
                interruptedOnReturn.get() shouldBe true
            } finally {
                Files.deleteIfExists(shim)
            }
        }
    }

    test("an interrupt after update ref is unknown without a verify shim") {
        withGitRepoHome { root, exec, home ->
            val page = TreePath.require("docs/page.md")
            providerOver(exec, root, home).commit(page, "base\n".toByteArray())
            val updated = home.resolve("update-ref-landed")
            val shim = installShim(
                home,
                """
                if [ "${'$'}sub" = "update-ref" ]; then
                    git -C "${'$'}repo" "${'$'}@" || exit ${'$'}?
                    touch "${'$'}updated"
                    sleep 60
                fi
                exec git -C "${'$'}repo" "${'$'}@"
                """.trimIndent(),
                mapOf("repo" to root.toString(), "updated" to updated.toString()),
            )
            try {
                val outcome = AtomicReference<CommitOutcome>()
                val interruptedOnReturn = AtomicBoolean(false)
                val finished = CountDownLatch(1)
                val worker = Thread {
                    outcome.set(
                        providerOver(GitExecutor(root, home, timeoutSeconds = 30, gitBinary = shim.toString()), root, home)
                            .commitChanges(
                                listOf(HistoryChange.Put(page, "next\n".toByteArray())),
                                "discussion: edit id",
                                testIdentity(),
                                testIdentity(),
                            ),
                    )
                    interruptedOnReturn.set(Thread.currentThread().isInterrupted)
                    finished.countDown()
                }
                worker.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!Files.exists(updated) && System.nanoTime() < deadline) Thread.sleep(10)
                Files.exists(updated) shouldBe true
                worker.interrupt()
                finished.await(10, TimeUnit.SECONDS) shouldBe true
                outcome.get().shouldBeInstanceOf<CommitOutcome.Unknown>()
                interruptedOnReturn.get() shouldBe true
            } finally {
                Files.deleteIfExists(shim)
            }
        }
    }

    test("a failure before update ref is not committed") {
        withGitRepoHome { root, exec, home ->
            val page = TreePath.require("docs/page.md")
            providerOver(exec, root, home).commit(page, "base\n".toByteArray())
            val oldHead = exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()
            val shim = installShim(
                home,
                """
                if [ "${'$'}sub" = "commit-tree" ]; then echo commit-tree-failed 1>&2; exit 2; fi
                exec git "${'$'}@"
                """.trimIndent(),
                emptyMap(),
            )
            try {
                providerOver(GitExecutor(root, home, gitBinary = shim.toString()), root, home)
                    .commitChanges(
                        listOf(HistoryChange.Put(page, "next\n".toByteArray())),
                        "discussion: edit id",
                        testIdentity(),
                        testIdentity(),
                    ).shouldBeInstanceOf<CommitOutcome.NotCommitted>()
                exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim() shouldBe oldHead
            } finally {
                Files.deleteIfExists(shim)
            }
        }
    }

    test("hydration failure after exit zero stays committed") {
        withGitRepoHome { root, exec, home ->
            val page = TreePath.require("docs/page.md")
            val logger = LoggerFactory.getLogger(GitCliHistoryProvider::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            val shim = installShim(
                home,
                """
                if [ "${'$'}sub" = "show" ]; then echo hydration-failed 1>&2; exit 128; fi
                exec git "${'$'}@"
                """.trimIndent(),
                emptyMap(),
            )
            try {
                val result = providerOver(GitExecutor(root, home, gitBinary = shim.toString()), root, home)
                    .commitChanges(
                        listOf(HistoryChange.Put(page, "new\n".toByteArray())),
                        "discussion: start id",
                        testIdentity(),
                        testIdentity(),
                    ).shouldBeInstanceOf<CommitOutcome.Committed>()
                result.sha shouldBe exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()
                result.commit shouldBe null
            } finally {
                logger.detachAppender(appender)
                appender.stop()
                Files.deleteIfExists(shim)
            }
            appender.list.any {
                it.level == Level.WARN && "landed but could not be read back" in it.formattedMessage
            } shouldBe true
        }
    }

    test("an unchanged tree resyncs the live index without maintenance") {
        withGitRepoHome { root, exec, home ->
            val page = TreePath.require("docs/page.md")
            var maintenanceCalls = 0
            val provider = providerOver(exec, root, home, maintenance = { maintenanceCalls++ })
            Files.createDirectories(root.resolve(page.value).parent)
            Files.writeString(root.resolve(page.value), "same\n")
            provider.commit(page, "same\n".toByteArray())
            maintenanceCalls = 0
            exec.run(listOf("update-index", "--force-remove", "--", page.value)).ok shouldBe true

            val result = provider.commitChanges(
                listOf(HistoryChange.Put(page, "same\n".toByteArray())),
                "discussion: edit id",
                testIdentity(),
                testIdentity(),
            ).shouldBeInstanceOf<CommitOutcome.Committed>()

            result.sha shouldBe exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()
            maintenanceCalls shouldBe 0
            exec.run(listOf("status", "--porcelain")).stdoutText shouldBe ""
        }
    }

    test("a moved HEAD during a same-tree write is not accepted as a no-op") {
        assertNoOpHeadRace("moved")
    }

    test("an absent HEAD during a same-tree write is not accepted as a no-op") {
        assertNoOpHeadRace("absent")
    }

    test("a failed HEAD read during a same-tree write is not accepted as a no-op") {
        assertNoOpHeadRace("failed")
    }

    test("a no-op hydration failure stays committed") {
        withGitRepoHome { root, exec, home ->
            val page = TreePath.require("docs/page.md")
            providerOver(exec, root, home).commit(page, "same\n".toByteArray())
            val oldHead = exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()
            val logger = LoggerFactory.getLogger(GitCliHistoryProvider::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            val shim = installShim(
                home,
                """
                if [ "${'$'}sub" = "show" ]; then echo hydration-failed 1>&2; exit 128; fi
                exec git "${'$'}@"
                """.trimIndent(),
                emptyMap(),
            )
            try {
                val result = providerOver(GitExecutor(root, home, gitBinary = shim.toString()), root, home)
                    .commitChanges(
                        listOf(HistoryChange.Put(page, "same\n".toByteArray())),
                        "discussion: edit id",
                        testIdentity(),
                        testIdentity(),
                    ).shouldBeInstanceOf<CommitOutcome.Committed>()
                result.sha shouldBe oldHead
                result.commit shouldBe null
            } finally {
                logger.detachAppender(appender)
                appender.stop()
                Files.deleteIfExists(shim)
            }
            appender.list.any {
                it.level == Level.WARN && "landed but could not be read back" in it.formattedMessage
            } shouldBe true
        }
    }

    test("hydration reports the created commit when HEAD advances first") {
        withGitRepoHome { root, exec, home ->
            val path = TreePath.require(".plainbase/discussions/id/discussion.md")
            val externalMade = root.resolve("external-before-hydration")
            val shim = installShim(
                home,
                """
                if [ "${'$'}sub" = "show" ] && [ ! -f "${'$'}repo/external-before-hydration" ]; then
                    landed=${'$'}(git -C "${'$'}repo" rev-parse HEAD) || exit ${'$'}?
                    tree=${'$'}(git -C "${'$'}repo" rev-parse "${'$'}landed^{tree}") || exit ${'$'}?
                    branch=${'$'}(git -C "${'$'}repo" symbolic-ref HEAD) || exit ${'$'}?
                    external=${'$'}(printf "external before hydration\\n" | GIT_AUTHOR_NAME=External GIT_AUTHOR_EMAIL=external@example.test GIT_COMMITTER_NAME=External GIT_COMMITTER_EMAIL=external@example.test git -C "${'$'}repo" commit-tree "${'$'}tree" -p "${'$'}landed") || exit ${'$'}?
                    git -C "${'$'}repo" update-ref "${'$'}branch" "${'$'}external" "${'$'}landed" || exit ${'$'}?
                    touch "${'$'}repo/external-before-hydration"
                fi
                exec git -C "${'$'}repo" "${'$'}@"
                """.trimIndent(),
                mapOf("repo" to root.toString()),
            )
            try {
                val outcome = providerOver(
                    GitExecutor(root, home, gitBinary = shim.toString()),
                    root,
                    home,
                ).commitChanges(
                    listOf(HistoryChange.Put(path, "marker\n".toByteArray())),
                    "discussion: start id",
                    testIdentity(),
                    testIdentity(),
                ).shouldBeInstanceOf<CommitOutcome.Committed>()

                val finalHead = exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()
                outcome.commit?.sha shouldBe outcome.sha
                outcome.commit?.sha shouldNotBe finalHead
                Files.exists(externalMade) shouldBe true
            } finally {
                Files.deleteIfExists(shim)
            }
        }
    }

    test("a first commit on an unborn head is a root commit") {
        withGitRepoHome { root, exec, home ->
            val result = providerOver(exec, root, home).commitChanges(
                listOf(HistoryChange.Put(TreePath.require("docs/.plainbase/discussions/id/discussion.md"), "marker\n".toByteArray())),
                "discussion: start id",
                testIdentity(),
                testIdentity(),
            ).shouldBeInstanceOf<CommitOutcome.Committed>()
            val sha = requireNotNull(result.sha)
            openOracle(root).use { repo ->
                val commit = repo.parseCommit(repo.resolve(sha))
                commit.parentCount shouldBe 0
            }
        }
    }

    test("a failed initial HEAD read in a born repository is not an unborn delete no-op") {
        withGitRepoHome { root, exec, home ->
            val path = TreePath.require(".plainbase/discussions/id/comment.md")
            providerOver(exec, root, home).commit(path, "comment\n".toByteArray())
            val oldHead = exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()
            val updateRefTally = home.resolve("unexpected-update-ref")
            val failedHead = home.resolve("failed-combined-head-read")
            val shim = installShim(
                home,
                """
                if [ "${'$'}sub" = "rev-parse" ] && [ ! -f "${'$'}failedHead" ]; then
                    for arg in "${'$'}@"; do
                        if [ "${'$'}arg" = "--symbolic-full-name" ]; then
                            touch "${'$'}failedHead"
                            echo transient-head-failure 1>&2
                            exit 128
                        fi
                    done
                fi
                if [ "${'$'}sub" = "update-ref" ]; then echo called >> "${'$'}updateRefTally"; fi
                exec git -C "${'$'}repo" "${'$'}@"
                """.trimIndent(),
                mapOf(
                    "repo" to root.toString(),
                    "failedHead" to failedHead.toString(),
                    "updateRefTally" to updateRefTally.toString(),
                ),
            )
            try {
                providerOver(GitExecutor(root, home, gitBinary = shim.toString()), root, home)
                    .commitChanges(
                        listOf(HistoryChange.Delete(path)),
                        "discussion: purge id",
                        testIdentity(),
                        testIdentity(),
                    ).shouldBeInstanceOf<CommitOutcome.NotCommitted>()
                exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim() shouldBe oldHead
                Files.exists(updateRefTally) shouldBe false
            } finally {
                Files.deleteIfExists(shim)
            }
        }
    }

    test("the page commit refuses a failed combined HEAD read on a born repository") {
        withGitRepoHome { root, exec, home ->
            val path = TreePath.require("docs/page.md")
            providerOver(exec, root, home).commit(path, "base\n".toByteArray())
            val oldHead = exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()
            val updateRefTally = home.resolve("unexpected-update-ref")
            val failedHead = home.resolve("failed-combined-head-read")
            val shim = installShim(
                home,
                """
                if [ "${'$'}sub" = "rev-parse" ] && [ ! -f "${'$'}failedHead" ]; then
                    for arg in "${'$'}@"; do
                        if [ "${'$'}arg" = "--symbolic-full-name" ]; then
                            touch "${'$'}failedHead"
                            echo transient-head-failure 1>&2
                            exit 128
                        fi
                    done
                fi
                if [ "${'$'}sub" = "update-ref" ]; then echo called >> "${'$'}updateRefTally"; fi
                exec git -C "${'$'}repo" "${'$'}@"
                """.trimIndent(),
                mapOf(
                    "repo" to root.toString(),
                    "failedHead" to failedHead.toString(),
                    "updateRefTally" to updateRefTally.toString(),
                ),
            )
            try {
                shouldThrow<GitCommandException> {
                    providerOver(GitExecutor(root, home, gitBinary = shim.toString()), root, home)
                        .commit(path, "next\n".toByteArray())
                }
                exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim() shouldBe oldHead
                Files.exists(updateRefTally) shouldBe false
            } finally {
                Files.deleteIfExists(shim)
            }
        }
    }

    test("the noop provider commits nothing") {
        com.plainbase.frameworks.git.NoOpHistoryProvider.commitChanges(
            emptyList(),
            "discussion: start id",
            testIdentity(),
            testIdentity(),
        ) shouldBe CommitOutcome.Committed(null, null)
    }

    test("a delete on an unborn head creates a root audit commit") {
        withGitRepoHome { root, exec, home ->
            val provider = providerOver(exec, root, home)
            val path = TreePath.require(".plainbase/discussions/id/01900000-0000-7000-8000-000000000002.md")
            val result = provider.commitChanges(
                listOf(HistoryChange.Delete(path)),
                "discussion: purge id",
                testIdentity(),
                testIdentity(),
            ).shouldBeInstanceOf<CommitOutcome.Committed>()
            val sha = requireNotNull(result.sha)

            exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim() shouldBe sha
            exec.run(listOf("show", "-s", "--format=%s", sha)).stdoutText.trim() shouldBe "discussion: purge id"
            openOracle(root).use { repo ->
                repo.parseCommit(repo.resolve(sha)).parentCount shouldBe 0
            }
        }
    }

    test("an unborn update ref refusal with no ref is unknown and keeps the file") {
        withGitRepoHome { root, exec, home ->
            val path = TreePath.require("docs/.plainbase/discussions/id/discussion.md")
            val file = root.resolve(path.value)
            Files.createDirectories(file.parent)
            Files.writeString(file, "marker\n")
            val quietVerify = home.resolve("quiet-verify-seen")
            val shim = installShim(
                home,
                """
                if [ "${'$'}sub" = "update-ref" ]; then echo unborn-refused 1>&2; exit 1; fi
                if [ "${'$'}sub" = "rev-parse" ]; then
                    verify=0
                    quiet=0
                    for arg in "${'$'}@"; do
                        [ "${'$'}arg" = "--verify" ] && verify=1
                        [ "${'$'}arg" = "--quiet" ] && quiet=1
                    done
                    if [ "${'$'}verify" -eq 1 ]; then
                        if [ "${'$'}quiet" -ne 1 ]; then echo expected-quiet-verify 1>&2; exit 128; fi
                        touch "${'$'}quietVerify"
                    fi
                fi
                exec git -C "${'$'}repo" "${'$'}@"
                """.trimIndent(),
                mapOf("repo" to root.toString(), "quietVerify" to quietVerify.toString()),
            )
            try {
                providerOver(GitExecutor(root, home, gitBinary = shim.toString()), root, home)
                    .commitChanges(
                        listOf(HistoryChange.Put(path, "marker\n".toByteArray())),
                        "discussion: start id",
                        testIdentity(),
                        testIdentity(),
                    ).shouldBeInstanceOf<CommitOutcome.Unknown>()
                exec.run(listOf("rev-parse", "--verify", "HEAD")).ok shouldBe false
                Files.exists(quietVerify) shouldBe true
                Files.readString(file) shouldBe "marker\n"
            } finally {
                Files.deleteIfExists(shim)
            }
        }
    }
})

private fun installShim(home: java.nio.file.Path, behavior: String, values: Map<String, String>): java.nio.file.Path {
    val assignments = values.entries.joinToString("\n") { (name, value) -> "$name=\"$value\"" }
    val script = """
        #!/bin/sh
        sub=""
        skip=0
        for arg in "${'$'}@"; do
            if [ "${'$'}skip" -eq 1 ]; then skip=0; continue; fi
            case "${'$'}arg" in -C|-c) skip=1;; -*) ;; *) sub="${'$'}arg"; break;; esac
        done
        $assignments
        $behavior
    """.trimIndent().trimStart()
    check(script.startsWith("#!/bin/sh\n"))
    val file = Files.createTempFile(home, "discussion-git-shim", ".sh")
    Files.writeString(file, script)
    Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
    return file
}

private fun assertNoOpHeadRace(readOutcome: String) {
    withGitRepoHome { root, exec, home ->
        val page = TreePath.require("docs/page.md")
        providerOver(exec, root, home).commit(page, "same\n".toByteArray())
        val oldHead = exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()
        val writeTreeSeen = home.resolve("write-tree-seen")
        val updateRefTally = home.resolve("update-ref-tally")
        val shim = installShim(
            home,
            """
            if [ "${'$'}sub" = "write-tree" ] && [ ! -f "${'$'}writeTreeSeen" ]; then
                old_head=${'$'}(git -C "${'$'}repo" rev-parse HEAD) || exit ${'$'}?
                tree=${'$'}(git -C "${'$'}repo" rev-parse "${'$'}old_head^{tree}") || exit ${'$'}?
                branch=${'$'}(git -C "${'$'}repo" symbolic-ref HEAD) || exit ${'$'}?
                external=${'$'}(printf "external no-op race\\n" | GIT_AUTHOR_NAME=External GIT_AUTHOR_EMAIL=external@example.test GIT_COMMITTER_NAME=External GIT_COMMITTER_EMAIL=external@example.test git -C "${'$'}repo" commit-tree "${'$'}tree" -p "${'$'}old_head") || exit ${'$'}?
                git -C "${'$'}repo" update-ref "${'$'}branch" "${'$'}external" "${'$'}old_head" || exit ${'$'}?
                touch "${'$'}writeTreeSeen"
            fi
            if [ "${'$'}sub" = "rev-parse" ] && [ -f "${'$'}writeTreeSeen" ]; then
                verify=0
                quiet=0
                for arg in "${'$'}@"; do
                    [ "${'$'}arg" = "--verify" ] && verify=1
                    [ "${'$'}arg" = "--quiet" ] && quiet=1
                done
                if [ "${'$'}verify" -eq 1 ] && [ "${'$'}quiet" -eq 1 ]; then
                    if [ "${'$'}readOutcome" = "absent" ]; then exit 1; fi
                    if [ "${'$'}readOutcome" = "failed" ]; then echo head-read-failed 1>&2; exit 128; fi
                fi
            fi
            if [ "${'$'}sub" = "update-ref" ]; then echo called >> "${'$'}updateRefTally"; fi
            exec git -C "${'$'}repo" "${'$'}@"
            """.trimIndent(),
            mapOf(
                "repo" to root.toString(),
                "writeTreeSeen" to writeTreeSeen.toString(),
                "updateRefTally" to updateRefTally.toString(),
                "readOutcome" to readOutcome,
            ),
        )
        try {
            providerOver(GitExecutor(root, home, gitBinary = shim.toString()), root, home)
                .commitChanges(
                    listOf(HistoryChange.Put(page, "same\n".toByteArray())),
                    "discussion: edit id",
                    testIdentity(),
                    testIdentity(),
                ).shouldBeInstanceOf<CommitOutcome.NotCommitted>()

            Files.exists(writeTreeSeen) shouldBe true
            exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim() shouldNotBe oldHead
            Files.exists(updateRefTally) shouldBe false
        } finally {
            Files.deleteIfExists(shim)
        }
    }
}
