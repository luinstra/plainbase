package com.plainbase.frameworks.git

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.discussion.Actor
import com.plainbase.domain.discussion.Anchor
import com.plainbase.domain.discussion.AnchorSelection
import com.plainbase.domain.discussion.Author
import com.plainbase.domain.discussion.AuthorKind
import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.CommentRecord
import com.plainbase.domain.discussion.DiscussionAssembly
import com.plainbase.domain.discussion.DiscussionCodec
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionIndex
import com.plainbase.domain.discussion.DiscussionPageSource
import com.plainbase.domain.discussion.DiscussionRead
import com.plainbase.domain.discussion.DiscussionRecord
import com.plainbase.domain.discussion.DiscussionStatus
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.discussion.EntriesRead
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.EntryPut
import com.plainbase.domain.discussion.FrontmatterExtras
import com.plainbase.domain.discussion.HeadingPath
import com.plainbase.domain.discussion.PageRef
import com.plainbase.domain.discussion.QuoteCapture
import com.plainbase.domain.discussion.StoreWrite
import com.plainbase.domain.history.CommitOutcome
import com.plainbase.domain.history.HistoryChange
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.principal.SubjectKey
import com.plainbase.domain.principal.grantForTests
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.ContentWriteMonitor
import com.plainbase.domain.service.DiscussionCommand
import com.plainbase.domain.service.DiscussionIdProvider
import com.plainbase.domain.service.DiscussionWriteOutcome
import com.plainbase.domain.service.DiscussionWriter
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.WriteHistoryHook
import com.plainbase.domain.service.WriteIntent
import com.plainbase.domain.service.write
import com.plainbase.frameworks.filesystem.LocalDiscussionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.treewalk.TreeWalk
import org.slf4j.LoggerFactory
import java.io.IOException
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Instant

class DiscussionWriterGitTest : FunSpec({
    test("each action is one prefixed commit touching only its discussion") {
        withGitWriterHarness(pageCount = 1) { env ->
            val otherId = discussionId(90)
            seedOtherDiscussion(env, otherId)
            val baseHead = env.provider.currentHead()
            val page = env.pageRef(0)
            val started = expectDone("start") {
                env.writer().write(DiscussionCommand.Start(ROOT, AUTHOR, page, env.quoteAnchor(0), "start\n"))
            }
            started.commentId shouldBe commentId(101)
            val id = started.id

            expectDone("comment") { env.writer().write(DiscussionCommand.AddComment(ROOT, AUTHOR, id, "reply\n")) }
                .commentId shouldBe commentId(102)
            expectDone("edit") { env.writer().write(DiscussionCommand.EditComment(ROOT, AUTHOR, id, commentId(102), "edited\n")) }
            expectDone("retract") { env.writer().write(DiscussionCommand.RetractComment(ROOT, AUTHOR, id, commentId(102))) }
            expectDone("resolve") { env.writer().write(DiscussionCommand.SetStatus(ROOT, AUTHOR, id, DiscussionStatus.RESOLVED)) }
            expectDone("reopen") { env.writer().write(DiscussionCommand.SetStatus(ROOT, AUTHOR, id, DiscussionStatus.OPEN)) }
            expectDone("reattach") { env.writer().write(DiscussionCommand.Reattach(ROOT, AUTHOR, id, env.quoteAnchor(0))) }
            expectDone("purge") { env.writer().write(DiscussionCommand.PurgeComment(ROOT, AUTHOR, id, commentId(102))) }

            openOracle(env.root).use { repo ->
                val additions = repo.headCommits().takeWhile { it.name != baseHead }
                additions.size shouldBe 8
                val chronological = additions.reversed()
                val actions = listOf("start", "comment", "edit", "retract", "resolve", "reopen", "reattach", "purge")
                val expectedPaths = listOf(
                    setOf(discussionPath(id, EntryName.Comment(commentId(101))), discussionPath(id, EntryName.Marker)),
                    setOf(discussionPath(id, EntryName.Comment(commentId(102)))),
                    setOf(discussionPath(id, EntryName.Comment(commentId(102)))),
                    setOf(discussionPath(id, EntryName.Comment(commentId(102)))),
                    setOf(discussionPath(id, EntryName.Marker)),
                    setOf(discussionPath(id, EntryName.Marker)),
                    setOf(discussionPath(id, EntryName.Marker)),
                    setOf(discussionPath(id, EntryName.Comment(commentId(102)))),
                )
                chronological.zip(actions.zip(expectedPaths)).forEach { (commit, expected) ->
                    commit.fullMessage.trimEnd() shouldBe "discussion: ${expected.first} ${id.value}"
                    changedPaths(repo, commit) shouldContainExactly expected.second.sorted()
                }
                repo.treePaths(additions.first()).any { it.startsWith(".plainbase/discussions/${otherId.value}/") } shouldBe true
            }
            Files.exists(env.root.resolve(".git/index.lock")) shouldBe false
        }
    }

    test("purge commits the deletion and leaves a clean worktree") {
        withGitWriterHarness(pageCount = 1) { env ->
            val start = env.writer().write(
                DiscussionCommand.Start(ROOT, AUTHOR, env.pageRef(0), env.pageAnchor(0), "first\n"),
            ).shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            env.exec.run(listOf("status", "--porcelain")).stdoutText shouldBe ""

            env.writer().write(DiscussionCommand.PurgeComment(ROOT, AUTHOR, start.id, checkNotNull(start.commentId)))
                .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()

            val deletedPath = discussionPath(start.id, EntryName.Comment(checkNotNull(start.commentId)))
            openOracle(env.root).use { repo ->
                changedPaths(repo, repo.headCommits().first()) shouldContainExactly listOf(deletedPath)
                repo.treePaths(repo.headCommits().first()).none {
                    it.startsWith(".plainbase/discussions/${start.id.value}/.pbpurge.")
                } shouldBe
                    true
            }
            env.exec.run(listOf("status", "--porcelain")).stdoutText shouldBe ""
        }
    }

    test("purging an uncommitted file logs that there is no audit commit") {
        withGitWriterHarness(pageCount = 1) { env ->
            val name = EntryName.Comment(commentId(101))
            val comment = CommentRecord(name.id, DISCUSSION_ID, AUTHOR, NOW, null, null, "uncommitted\n", FrontmatterExtras.NONE)
            val marker = DiscussionRecord(
                DISCUSSION_ID,
                env.pageRef(0),
                DiscussionStatus.OPEN,
                NOW,
                AUTHOR,
                null,
                env.pageAnchor(0),
                null,
                FrontmatterExtras.NONE,
            )
            env.store.createFiles(
                ROOT,
                DISCUSSION_ID,
                listOf(
                    EntryPut(name, DiscussionCodec.encodeComment(comment)),
                    EntryPut(EntryName.Marker, DiscussionCodec.encodeDiscussion(marker)),
                ),
            ).shouldBeInstanceOf<StoreWrite.Written>()
            val oldHead = env.provider.currentHead()
            val logger = LoggerFactory.getLogger(GitCliHistoryProvider::class.java) as Logger
            val priorLevel = logger.level
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.level = Level.INFO
            logger.addAppender(appender)
            try {
                env.writer().write(DiscussionCommand.PurgeComment(ROOT, AUTHOR, DISCUSSION_ID, name.id))
                    .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            } finally {
                logger.detachAppender(appender)
                logger.level = priorLevel
                appender.stop()
            }

            env.provider.currentHead() shouldBe oldHead
            Files.exists(env.root.resolve(discussionPath(DISCUSSION_ID, name))) shouldBe false
            appender.list.any {
                it.level == Level.INFO && "uncommitted" in it.formattedMessage &&
                    "no audit commit" in it.formattedMessage && name.fileName in it.formattedMessage
            } shouldBe true
        }
    }

    test("a refused start leaves no id directory") {
        withGitWriterHarness(pageCount = 1) { env ->
            val oldHead = env.provider.currentHead()
            val shim = installWriterGitShim(
                env.home,
                env.root,
                """
                if [ "${'$'}sub" = "commit-tree" ] || [ "${'$'}sub" = "update-ref" ]; then
                    echo commit-refused 1>&2
                    exit 2
                fi
                exec git -C "${'$'}repo" "${'$'}@"
                """.trimIndent(),
            )
            try {
                val provider = providerOver(
                    GitExecutor(env.root, env.home, gitBinary = shim.toString()),
                    env.root,
                    env.home,
                    maintenance = {},
                )
                val refusal = env.writer(provider).write(
                    DiscussionCommand.Start(ROOT, AUTHOR, env.pageRef(0), env.pageAnchor(0), "start\n"),
                ).shouldBeInstanceOf<DiscussionWriteOutcome.Refused>()

                refusal.refusal.status shouldBe 503
                refusal.refusal.code shouldBe "discussion_commit_failed"
                Files.exists(env.root.resolve(".plainbase/discussions/${DISCUSSION_ID.value}")) shouldBe false
                env.provider.currentHead() shouldBe oldHead
                env.exec.run(listOf("status", "--porcelain")).stdoutText shouldBe ""
                env.index.publishCount shouldBe 1
                env.index.published(DISCUSSION_ID) shouldBe DiscussionRead.Absent
            } finally {
                Files.deleteIfExists(shim)
            }
        }
    }

    test("a symlinked collection refuses a start before git") {
        withGitWriterHarness(pageCount = 1) { env ->
            val outside = Files.createTempDirectory("plainbase-discussion-refused-start")
            val collection = env.root.resolve(".plainbase/discussions")
            Files.createDirectories(collection.parent)
            try {
                Files.createSymbolicLink(collection, outside)
            } catch (_: IOException) {
                outside.toFile().deleteRecursively()
                return@withGitWriterHarness
            } catch (_: UnsupportedOperationException) {
                outside.toFile().deleteRecursively()
                return@withGitWriterHarness
            }
            val oldHead = env.provider.currentHead()

            val refusal = env.writer().write(
                DiscussionCommand.Start(ROOT, AUTHOR, env.pageRef(0), env.pageAnchor(0), "start\n"),
            ).shouldBeInstanceOf<DiscussionWriteOutcome.Refused>()

            refusal.refusal.status shouldBe 422
            refusal.refusal.code shouldBe "discussion_path_refused"
            Files.exists(outside.resolve(DISCUSSION_ID.value)) shouldBe false
            env.provider.currentHead() shouldBe oldHead
            env.index.publishCount shouldBe 0
            outside.toFile().deleteRecursively()
        }
    }

    test("a refused edit restores raw bytes and leaves HEAD unchanged") {
        withGitWriterHarness(pageCount = 1) { env ->
            val start = expectDone("start") {
                env.writer().write(DiscussionCommand.Start(ROOT, AUTHOR, env.pageRef(0), env.pageAnchor(0), "original\n"))
            }
            val commentName = EntryName.Comment(requireNotNull(start.commentId))
            val commentPath = env.root.resolve(discussionPath(start.id, commentName))
            val originalBytes = Files.readAllBytes(commentPath).decodeToString()
                .replaceFirst("format:", "x_mood: \"calm\"\nformat:")
                .replace("\n", "\r\n")
                .toByteArray()
            Files.write(commentPath, originalBytes)
            val rawBeforeRefusal = Files.readAllBytes(commentPath)
            val oldHead = env.provider.currentHead()
            val shim = installWriterGitShim(
                env.home,
                env.root,
                """
                if [ "${'$'}sub" = "commit-tree" ]; then echo commit-tree-refused 1>&2; exit 2; fi
                exec git -C "${'$'}repo" "${'$'}@"
                """.trimIndent(),
            )
            try {
                val provider = providerOver(
                    GitExecutor(env.root, env.home, gitBinary = shim.toString()),
                    env.root,
                    env.home,
                    maintenance = {},
                )
                val refusal = env.writer(provider).write(
                    DiscussionCommand.EditComment(ROOT, AUTHOR, start.id, commentName.id, "changed\n"),
                ).shouldBeInstanceOf<DiscussionWriteOutcome.Refused>()

                refusal.refusal.status shouldBe 503
                refusal.refusal.code shouldBe "discussion_commit_failed"
                Files.readAllBytes(commentPath).toList() shouldBe rawBeforeRefusal.toList()
                env.provider.currentHead() shouldBe oldHead
                env.exec.run(listOf("status", "--porcelain")).stdoutText shouldBe
                    " M ${discussionPath(start.id, commentName)}\n"
            } finally {
                Files.deleteIfExists(shim)
            }
        }
    }

    test("a page save cannot land inside a discussion commit") {
        withGitWriterHarness(pageCount = 1) { env ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val blockedHistory = PausingHistoryProvider(env.provider, entered, release)
            val writer = env.writer(blockedHistory)
            val pipeline = env.pipeline()
            val writerResult = AtomicReference<DiscussionWriteOutcome?>()
            val pageResult = AtomicReference<WriteOutcome?>()
            val failure = AtomicReference<Throwable?>()
            val discussionThread = Thread({
                runCatching {
                    writer.write(DiscussionCommand.Start(ROOT, AUTHOR, env.pageRef(0), env.pageAnchor(0), "start\n"))
                }.onSuccess(writerResult::set).onFailure(failure::set)
            }, "discussion-commit")
            val pageBytes = env.pageBytes(0)
            val editedBytes = pageBytes.decodeToString().replace("# Page 0", "# Edited 0").encodeToByteArray()
            val snapshotPage = env.harness.builder.current.pages.first { it.path == env.pageRef(0).path }
            val saveIntent = WriteIntent(
                snapshotPage.id,
                ROOT,
                env.pageRef(0).path,
                env.citations.contentHash(pageBytes),
                editedBytes,
            )
            val saverThread = Thread({
                runCatching { pipeline.write(grantForTests(), saveIntent) }
                    .onSuccess(pageResult::set).onFailure(failure::set)
            }, "page-save")

            try {
                discussionThread.start()
                (entered.await(10, TimeUnit.SECONDS)) shouldBe true
                saverThread.start()
                awaitBlockedOnMonitor(saverThread, env.monitor) shouldBe true
            } finally {
                release.countDown()
                discussionThread.join(10_000)
                if (saverThread.state != Thread.State.NEW) saverThread.join(10_000)
            }

            failure.get() shouldBe null
            writerResult.get().shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            pageResult.get().shouldBeInstanceOf<WriteOutcome.Written>()
            env.harness.dirtyPages.all() shouldBe emptyList()
            Files.exists(env.root.resolve(".git/index.lock")) shouldBe false
            env.exec.run(listOf("status", "--porcelain")).stdoutText shouldBe ""
            openOracle(env.root).use { repo -> repo.headCommits().size shouldBe 3 }
        }
    }

    test("concurrent page saves and discussion comments all commit") {
        withGitWriterHarness(pageCount = 11) { env ->
            val start = env.writer().write(
                DiscussionCommand.Start(ROOT, AUTHOR, env.pageRef(10), env.pageAnchor(10), "start\n"),
            ).shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            val baseHead = env.provider.currentHead()
            val pipeline = env.pipeline()
            val pageIntents = (0 until 10).map { index ->
                val page = env.pageRef(index)
                val bytes = env.pageBytes(index)
                val indexed = env.harness.builder.current.pages.first { it.path == page.path }
                WriteIntent(
                    indexed.id,
                    ROOT,
                    page.path,
                    env.citations.contentHash(bytes),
                    bytes.decodeToString().replace("# Page $index", "# Saved $index").encodeToByteArray(),
                )
            }
            val go = CountDownLatch(1)
            val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
            val results = java.util.Collections.synchronizedList(mutableListOf<Any>())
            val threads = buildList {
                pageIntents.forEach { intent ->
                    add(
                        Thread({
                            go.await()
                            runCatching { pipeline.write(grantForTests(), intent) }
                                .onSuccess(results::add).onFailure(failures::add)
                        }, "page-${intent.path.value}"),
                    )
                }
                repeat(10) { index ->
                    add(
                        Thread({
                            go.await()
                            runCatching {
                                env.writer().write(DiscussionCommand.AddComment(ROOT, AUTHOR, start.id, "reply-$index\n"))
                            }.onSuccess(results::add).onFailure(failures::add)
                        }, "comment-$index"),
                    )
                }
            }
            threads.forEach(Thread::start)
            go.countDown()
            threads.forEach { it.join(30_000) }

            failures shouldBe emptyList()
            results.size shouldBe 20
            results.filterIsInstance<WriteOutcome>().filterNot { it is WriteOutcome.Written }.map { it.toString() } shouldBe emptyList()
            results.filterIsInstance<WriteOutcome.Written>().size shouldBe 10
            results.filterIsInstance<DiscussionWriteOutcome.Done>().size shouldBe 10
            env.harness.dirtyPages.all() shouldBe emptyList()
            Files.exists(env.root.resolve(".git/index.lock")) shouldBe false
            env.exec.run(listOf("status", "--porcelain")).stdoutText shouldBe ""
            openOracle(env.root).use { repo ->
                repo.headCommits().takeWhile { it.name != baseHead }.size shouldBe 20
            }
        }
    }

    test("unknown commit outcome keeps the discussion files") {
        withGitWriterHarness(pageCount = 1) { env ->
            val uncertain = object : HistoryProvider by env.provider {
                override fun commitChanges(
                    changes: List<HistoryChange>,
                    message: String,
                    author: com.plainbase.domain.history.CommitIdentity,
                    committer: com.plainbase.domain.history.CommitIdentity,
                ): CommitOutcome = CommitOutcome.Unknown(IOException("reconciliation unavailable"))
            }
            val result = env.writer(uncertain).write(
                DiscussionCommand.Start(ROOT, AUTHOR, env.pageRef(0), env.pageAnchor(0), "start\n"),
            ).shouldBeInstanceOf<DiscussionWriteOutcome.Refused>()
            result.refusal.status shouldBe 503
            result.refusal.code shouldBe "discussion_commit_uncertain"
            val id = DISCUSSION_ID
            Files.exists(env.root.resolve(".plainbase/discussions/${id.value}/discussion.md")) shouldBe true
            env.index.publishCount shouldBe 1
        }
    }

    test("writer keeps files when post-update-ref hydration fails") {
        withGitWriterHarness(pageCount = 1) { env ->
            val logger = LoggerFactory.getLogger(GitCliHistoryProvider::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            val shim = installWriterGitShim(
                env.home,
                env.root,
                """
                if [ "${'$'}sub" = "show" ]; then echo hydration-failed 1>&2; exit 128; fi
                exec git "${'$'}@"
                """.trimIndent(),
            )
            try {
                val provider = providerOver(
                    GitExecutor(env.root, env.home, gitBinary = shim.toString()),
                    env.root,
                    env.home,
                    maintenance = {},
                )
                val result = env.writer(provider).write(
                    DiscussionCommand.Start(ROOT, AUTHOR, env.pageRef(0), env.pageAnchor(0), "start\n"),
                ).shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
                result.commit shouldBe env.exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()
                Files.exists(env.root.resolve(discussionPath(result.id, EntryName.Marker))) shouldBe true
                Files.exists(env.root.resolve(discussionPath(result.id, EntryName.Comment(commentId(101))))) shouldBe true
                env.index.publishCount shouldBe 1
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

    test("a failed born HEAD read refuses purge and restores its tombstone") {
        withGitWriterHarness(pageCount = 1) { env ->
            val started = env.writer().write(
                DiscussionCommand.Start(ROOT, AUTHOR, env.pageRef(0), env.pageAnchor(0), "start\n"),
            ).shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            val commentId = requireNotNull(started.commentId)
            val commentPath = env.root.resolve(discussionPath(started.id, EntryName.Comment(commentId)))
            val originalHead = env.exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()
            val updateRefTally = env.root.resolve("unexpected-update-ref")
            val shim = installWriterGitShim(
                env.home,
                env.root,
                """
                failedHead="${'$'}repo/failed-combined-head-read"
                updateRefTally="${'$'}repo/unexpected-update-ref"
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
            )
            try {
                val result = env.writer(
                    providerOver(GitExecutor(env.root, env.home, gitBinary = shim.toString()), env.root, env.home),
                ).write(DiscussionCommand.PurgeComment(ROOT, AUTHOR, started.id, commentId))
                    .shouldBeInstanceOf<DiscussionWriteOutcome.Refused>()
                result.refusal.status shouldBe 503
                result.refusal.code shouldBe "discussion_commit_failed"
                Files.exists(commentPath) shouldBe true
                Files.list(commentPath.parent).use { stream ->
                    stream.noneMatch { it.fileName.toString().startsWith(".pbpurge.") } shouldBe true
                }
                env.exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim() shouldBe originalHead
                Files.exists(updateRefTally) shouldBe false
            } finally {
                Files.deleteIfExists(shim)
            }
        }
    }

    test("a born HEAD misread as absent keeps the purge tombstone") {
        withGitWriterHarness(pageCount = 1) { env ->
            val started = env.writer().write(
                DiscussionCommand.Start(ROOT, AUTHOR, env.pageRef(0), env.pageAnchor(0), "start\n"),
            ).shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            val commentId = requireNotNull(started.commentId)
            val commentPath = env.root.resolve(discussionPath(started.id, EntryName.Comment(commentId)))
            val commentBytes = Files.readAllBytes(commentPath)
            val originalHead = env.exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()
            val updateRefTally = env.root.resolve("unexpected-update-ref")
            val shim = installWriterGitShim(
                env.home,
                env.root,
                """
                updateRefTally="${'$'}repo/unexpected-update-ref"
                if [ "${'$'}sub" = "rev-parse" ]; then
                    verify=0
                    quiet=0
                    combined=0
                    for arg in "${'$'}@"; do
                        [ "${'$'}arg" = "--verify" ] && verify=1
                        [ "${'$'}arg" = "--quiet" ] && quiet=1
                        [ "${'$'}arg" = "--symbolic-full-name" ] && combined=1
                    done
                    if [ "${'$'}combined" -eq 1 ] || { [ "${'$'}verify" -eq 1 ] && [ "${'$'}quiet" -eq 1 ]; }; then exit 1; fi
                fi
                if [ "${'$'}sub" = "update-ref" ]; then
                    echo called >> "${'$'}updateRefTally"
                    exec git -C "${'$'}repo" "${'$'}@"
                fi
                exec git -C "${'$'}repo" "${'$'}@"
                """.trimIndent(),
            )
            try {
                val result = env.writer(
                    providerOver(GitExecutor(env.root, env.home, gitBinary = shim.toString()), env.root, env.home),
                ).write(DiscussionCommand.PurgeComment(ROOT, AUTHOR, started.id, commentId))
                    .shouldBeInstanceOf<DiscussionWriteOutcome.Refused>()

                result.refusal.status shouldBe 503
                result.refusal.code shouldBe "discussion_commit_uncertain"
                Files.exists(updateRefTally) shouldBe true
                env.exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim() shouldBe originalHead
                Files.exists(commentPath) shouldBe false
                val tombstone = Files.list(commentPath.parent).use { stream ->
                    stream.filter { it.fileName.toString().startsWith(".pbpurge.") }.findFirst().orElse(null)
                }
                tombstone shouldNotBe null
                Files.readAllBytes(requireNotNull(tombstone)).decodeToString() shouldBe commentBytes.decodeToString()
            } finally {
                Files.deleteIfExists(shim)
            }
        }
    }

    test("a landed unborn first commit with an exit one empty reread keeps new files") {
        val root = Files.createTempDirectory("plainbase-discussion-unborn-writer")
        val home = Files.createTempDirectory("plainbase-discussion-unborn-writer-home")
        try {
            val exec = GitExecutor(root, home)
            val store = LocalDiscussionStore(mapOf(ROOT to root))
            val index = GitWriterIndex()
            val monitor = ContentWriteMonitor()
            val pageBytes = "# Unborn page\n".toByteArray()
            val citations = CitationFactory()
            val page = PageRef(
                com.plainbase.domain.page.PageId.require("01900000-0000-7000-8000-000000000099"),
                TreePath.require("pages/unborn.md"),
            )
            val updateRefLanded = root.resolve("update-ref-landed")
            val shim = installWriterGitShim(
                home,
                root,
                """
                if [ "${'$'}sub" = "update-ref" ]; then
                    git -C "${'$'}repo" "${'$'}@" || exit ${'$'}?
                    touch "${'$'}repo/update-ref-landed"
                    exit 1
                fi
                if [ "${'$'}sub" = "rev-parse" ] && [ -f "${'$'}repo/update-ref-landed" ]; then
                    verify=0
                    for arg in "${'$'}@"; do if [ "${'$'}arg" = "--verify" ]; then verify=1; fi; done
                    if [ "${'$'}verify" -eq 1 ]; then exit 1; fi
                fi
                exec git -C "${'$'}repo" "${'$'}@"
                """.trimIndent(),
            )
            try {
                val provider = providerOver(
                    GitExecutor(root, home, gitBinary = shim.toString()),
                    root,
                    home,
                )
                val writer = DiscussionWriter(
                    monitor = monitor,
                    store = store,
                    pages = DiscussionPageSource { _, _ -> ContentRead.Bytes(pageBytes) },
                    histories = { provider },
                    index = index,
                    ids = GitWriterIds(),
                    clock = fixedClockClock,
                    hasher = citations::contentHash,
                )
                val writeResult = writer.write(
                    DiscussionCommand.Start(ROOT, AUTHOR, page, Anchor.Page(citations.contentHash(pageBytes), null), "start\n"),
                )
                Files.exists(updateRefLanded) shouldBe true
                exec.run(listOf("rev-parse", "--verify", "HEAD")).ok shouldBe true
                val result = writeResult.shouldBeInstanceOf<DiscussionWriteOutcome.Refused>()
                result.refusal.status shouldBe 503
                result.refusal.code shouldBe "discussion_commit_uncertain"
                Files.exists(updateRefLanded) shouldBe true
                Files.exists(root.resolve(discussionPath(DISCUSSION_ID, EntryName.Marker))) shouldBe true
                Files.exists(root.resolve(discussionPath(DISCUSSION_ID, EntryName.Comment(commentId(101))))) shouldBe true
                index.published(DISCUSSION_ID).shouldBeInstanceOf<DiscussionRead.Ok>()
            } finally {
                Files.deleteIfExists(shim)
            }
        } finally {
            root.toFile().deleteRecursively()
            home.toFile().deleteRecursively()
        }
    }

    test("writer accepts an external descendant after update-ref without undoing files") {
        withGitWriterHarness(pageCount = 1) { env ->
            env.exec.run(listOf("config", "user.useConfigOnly", "true")).ok shouldBe true
            val shim = installWriterGitShim(
                env.home,
                env.root,
                """
                if [ "${'$'}sub" = "update-ref" ]; then
                    git "${'$'}@" || exit ${'$'}?
                    landed=${'$'}(git -C "${'$'}repo" rev-parse HEAD) || exit ${'$'}?
                    tree=${'$'}(git -C "${'$'}repo" rev-parse "${'$'}landed^{tree}") || exit ${'$'}?
                    branch=${'$'}(git -C "${'$'}repo" symbolic-ref HEAD) || exit ${'$'}?
                    external=${'$'}(printf "external descendant\\n" |
                        GIT_AUTHOR_NAME=External GIT_AUTHOR_EMAIL=external@example.test \
                        GIT_COMMITTER_NAME=External GIT_COMMITTER_EMAIL=external@example.test \
                        git -C "${'$'}repo" commit-tree "${'$'}tree" -p "${'$'}landed") || exit ${'$'}?
                    git -C "${'$'}repo" update-ref "${'$'}branch" "${'$'}external" "${'$'}landed" || exit ${'$'}?
                    exit 1
                fi
                exec git "${'$'}@"
                """.trimIndent(),
            )
            try {
                val provider = providerOver(
                    GitExecutor(env.root, env.home, gitBinary = shim.toString()),
                    env.root,
                    env.home,
                    maintenance = {},
                )
                val result = env.writer(provider).write(
                    DiscussionCommand.Start(ROOT, AUTHOR, env.pageRef(0), env.pageAnchor(0), "start\n"),
                ).shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
                val head = env.exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()
                val committedSha = requireNotNull(result.commit)
                head shouldNotBe committedSha
                env.exec.run(listOf("merge-base", "--is-ancestor", committedSha, head)).ok shouldBe true
                Files.exists(env.root.resolve(discussionPath(result.id, EntryName.Marker))) shouldBe true
                Files.exists(env.root.resolve(discussionPath(result.id, EntryName.Comment(commentId(101))))) shouldBe true
                env.index.publishCount shouldBe 1
            } finally {
                Files.deleteIfExists(shim)
            }
        }
    }

    test("an external commit before hydration still publishes the discussion") {
        withGitWriterHarness(pageCount = 1) { env ->
            val injected = env.root.resolve("external-before-hydration")
            val shim = installWriterGitShim(
                env.home,
                env.root,
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
            )
            try {
                val provider = providerOver(
                    GitExecutor(env.root, env.home, gitBinary = shim.toString()),
                    env.root,
                    env.home,
                    maintenance = {},
                )
                val result = env.writer(provider).write(
                    DiscussionCommand.Start(ROOT, AUTHOR, env.pageRef(0), env.pageAnchor(0), "start\n"),
                ).shouldBeInstanceOf<DiscussionWriteOutcome.Done>()

                Files.exists(injected) shouldBe true
                val head = env.exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()
                val committedSha = requireNotNull(result.commit)
                head shouldNotBe committedSha
                env.exec.run(listOf("merge-base", "--is-ancestor", committedSha, head)).ok shouldBe true
                Files.exists(env.root.resolve(discussionPath(result.id, EntryName.Marker))) shouldBe true
                env.index.publishCount shouldBe 1
            } finally {
                Files.deleteIfExists(shim)
            }
        }
    }
})

private val ROOT = RootName.PRIMARY
private val AUTHOR = Author(Actor(SubjectKey("builtin", "git-writer"), "Git Writer"), AuthorKind.HUMAN)
private val NOW = Instant.parse("2026-09-25T12:34:56.789Z")
private val DISCUSSION_ID = discussionId(1)

private fun discussionId(number: Int) = DiscussionId.require("01900000-0000-7000-8000-${number.toString().padStart(12, '0')}")
private fun commentId(number: Int) = CommentId.require("01900000-0000-7000-8000-${number.toString().padStart(12, '0')}")

private fun discussionPath(id: DiscussionId, name: EntryName): String =
    ".plainbase/discussions/${id.value}/${name.fileName}"

private fun withGitWriterHarness(pageCount: Int, block: (GitWriterHarness) -> Unit) {
    val root = Files.createTempDirectory("plainbase-discussion-writer-git")
    val home = Files.createTempDirectory("plainbase-discussion-writer-git-home")
    try {
        repeat(pageCount) { index ->
            val pagePath = root.resolve("pages/page-$index.md")
            Files.createDirectories(pagePath.parent)
            Files.writeString(pagePath, pageText(index))
        }
        val exec = GitExecutor(workTree = root, home = home)
        val provider = providerOver(exec, root, home)
        repeat(pageCount) { index ->
            val path = TreePath.require("pages/page-$index.md")
            provider.commit(path, Files.readAllBytes(root.resolve(path.value)), testIdentity(), testIdentity())
        }
        val monitor = ContentWriteMonitor()
        val citations = CitationFactory()
        IndexHarness(root, history = provider).use { indexHarness ->
            indexHarness.builder.rebuild()
            val store = LocalDiscussionStore(mapOf(ROOT to root))
            val harness = GitWriterHarness(root, home, exec, provider, indexHarness, store, monitor, citations)
            block(harness)
        }
    } finally {
        root.toFile().deleteRecursively()
        home.toFile().deleteRecursively()
    }
}

private fun installWriterGitShim(home: java.nio.file.Path, repo: java.nio.file.Path, behavior: String): java.nio.file.Path {
    val script = buildString {
        appendLine("#!/bin/sh")
        appendLine("repo=\"$repo\"")
        appendLine("sub=\"\"")
        appendLine("skip=0")
        appendLine("for arg in \"${'$'}@\"; do")
        appendLine("    if [ \"${'$'}skip\" -eq 1 ]; then skip=0; continue; fi")
        appendLine("    case \"${'$'}arg\" in -C|-c) skip=1;; -*) ;; *) sub=\"${'$'}arg\"; break;; esac")
        appendLine("done")
        appendLine(behavior.trimIndent())
    }
    check(script.startsWith("#!/bin/sh\n"))
    val file = Files.createTempFile(home, "discussion-writer-git-shim", ".sh")
    Files.writeString(file, script)
    Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
    return file
}

private class GitWriterHarness(
    val root: java.nio.file.Path,
    val home: java.nio.file.Path,
    val exec: GitExecutor,
    val provider: GitCliHistoryProvider,
    val harness: IndexHarness,
    val store: DiscussionStore,
    val monitor: ContentWriteMonitor,
    val citations: CitationFactory,
) {
    val index = GitWriterIndex()
    private val ids = GitWriterIds()

    fun pageRef(index: Int): PageRef {
        val path = TreePath.require("pages/page-$index.md")
        val pageId = harness.builder.current.pages.first { it.path == path }.id
        return PageRef(pageId, path)
    }

    fun pageBytes(index: Int): ByteArray = Files.readAllBytes(root.resolve("pages/page-$index.md"))

    fun pageAnchor(index: Int) = Anchor.Page(citations.contentHash(pageBytes(index)), null)

    fun quoteAnchor(index: Int) = Anchor.Quote(
        citations.contentHash(pageBytes(index)),
        null,
        QuoteCapture("Page $index", "", "", 0, 6, 0, 1, AnchorSelection.NARROWED, HeadingPath(emptyList())),
    )

    fun writer(history: HistoryProvider = provider, ids: DiscussionIdProvider = this.ids) = DiscussionWriter(
        monitor = monitor,
        store = store,
        pages = DiscussionPageSource { rootName, page ->
            harness.stores(rootName).read(page.path)?.let(ContentRead::Bytes) ?: ContentRead.ConfirmedAbsent
        },
        histories = { history },
        index = index,
        ids = ids,
        clock = fixedClockClock,
        hasher = citations::contentHash,
    )

    fun pipeline() = harness.writePipeline(
        historyHook = WriteHistoryHook { _, path, bytes, author, committer ->
            provider.commit(path, bytes, author, committer).sha
        },
        monitor = monitor,
    )
}

private class GitWriterIds : DiscussionIdProvider {
    private val comments = AtomicInteger(101)
    override fun nextDiscussion(): DiscussionId = DISCUSSION_ID
    override fun nextComment(): CommentId = commentId(comments.getAndIncrement())
}

private class GitWriterIndex : DiscussionIndex {
    private val published = mutableMapOf<DiscussionId, DiscussionRead>()
    var publishCount = 0
        private set

    fun published(id: DiscussionId): DiscussionRead? = published[id]

    override fun pageDiscussionCount(root: RootName, pageId: com.plainbase.domain.page.PageId): Int =
        published.values.count { read ->
            when (read) {
                is DiscussionRead.Ok -> read.files.marker.value.page.pageId == pageId
                is DiscussionRead.Unreadable -> read.pageId == pageId
                else -> false
            }
        }

    override fun publish(root: RootName, id: DiscussionId, markerChanged: Boolean, read: () -> EntriesRead) {
        published[id] = DiscussionAssembly.assemble(id, read())
        publishCount++
    }

    override fun publishFailed(root: RootName, cause: Exception) = Unit
}

private class PausingHistoryProvider(
    private val delegate: HistoryProvider,
    private val entered: CountDownLatch,
    private val release: CountDownLatch,
) : HistoryProvider by delegate {
    override fun commitChanges(
        changes: List<HistoryChange>,
        message: String,
        author: com.plainbase.domain.history.CommitIdentity,
        committer: com.plainbase.domain.history.CommitIdentity,
    ): CommitOutcome {
        entered.countDown()
        check(release.await(10, TimeUnit.SECONDS)) { "test did not release the discussion commit" }
        return delegate.commitChanges(changes, message, author, committer)
    }
}

private fun seedOtherDiscussion(env: GitWriterHarness, id: DiscussionId) {
    val page = env.pageRef(0)
    val comment = CommentRecord(commentId(90), id, AUTHOR, NOW, null, null, "other\n", FrontmatterExtras.NONE)
    val marker = DiscussionRecord(
        id,
        page,
        DiscussionStatus.OPEN,
        NOW,
        AUTHOR,
        null,
        env.pageAnchor(0),
        null,
        FrontmatterExtras.NONE,
    )
    val puts = listOf(
        EntryPut(EntryName.Comment(comment.id), DiscussionCodec.encodeComment(comment)),
        EntryPut(EntryName.Marker, DiscussionCodec.encodeDiscussion(marker)),
    )
    env.store.createFiles(ROOT, id, puts).shouldBeInstanceOf<StoreWrite.Written>()
    val changes = puts.map { put -> HistoryChange.Put(TreePath.require(discussionPath(id, put.name)), put.bytes) }
    env.provider.commitChanges(changes, "discussion: fixture ${id.value}", testIdentity(), testIdentity())
        .shouldBeInstanceOf<CommitOutcome.Committed>()
}

private fun changedPaths(repo: org.eclipse.jgit.lib.Repository, commit: RevCommit): List<String> {
    val before = commit.parents.singleOrNull()?.let { treeEntries(repo, it) }.orEmpty()
    val after = treeEntries(repo, commit)
    return (before.keys + after.keys).distinct().filter { before[it] != after[it] }.sorted()
}

private fun treeEntries(repo: org.eclipse.jgit.lib.Repository, commit: RevCommit): Map<String, String> =
    TreeWalk(repo).use { walk ->
        walk.addTree(commit.tree)
        walk.isRecursive = true
        buildMap { while (walk.next()) put(walk.pathString, walk.getObjectId(0).name) }
    }

private fun awaitBlockedOnMonitor(thread: Thread, monitor: ContentWriteMonitor): Boolean {
    val expectedLock = "java.lang.Object@${Integer.toHexString(System.identityHashCode(monitor.lock))}"
    val bean = ManagementFactory.getThreadMXBean()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadline) {
        val info = bean.getThreadInfo(thread.threadId())
        if (info?.threadState == Thread.State.BLOCKED && info.lockName == expectedLock) return true
        Thread.sleep(5)
    }
    return false
}

private fun pageText(index: Int): String = "---\ntitle: Page $index\n---\n\n# Page $index\n"

private val fixedClockClock = object : Clock {
    override fun now(): Instant = NOW
}

private fun expectDone(action: String, block: () -> DiscussionWriteOutcome): DiscussionWriteOutcome.Done =
    when (val result = block()) {
        is DiscussionWriteOutcome.Done -> result
        is DiscussionWriteOutcome.Refused -> error("$action was refused: ${result.refusal}")
    }
