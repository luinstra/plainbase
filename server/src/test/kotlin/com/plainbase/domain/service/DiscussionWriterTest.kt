package com.plainbase.domain.service

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
import com.plainbase.domain.discussion.BootTombstone
import com.plainbase.domain.discussion.CollectionVisit
import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.CommentRecord
import com.plainbase.domain.discussion.Decoded
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
import com.plainbase.domain.discussion.EntryPath
import com.plainbase.domain.discussion.EntryPut
import com.plainbase.domain.discussion.EntryVersion
import com.plainbase.domain.discussion.FrontmatterExtras
import com.plainbase.domain.discussion.HeadingPath
import com.plainbase.domain.discussion.MAX_MARKER_BYTES
import com.plainbase.domain.discussion.PageRef
import com.plainbase.domain.discussion.QuoteCapture
import com.plainbase.domain.discussion.RawEntry
import com.plainbase.domain.discussion.Stamp
import com.plainbase.domain.discussion.StoreWrite
import com.plainbase.domain.discussion.Tombstone
import com.plainbase.domain.discussion.UnreadableReason
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.history.CommitOutcome
import com.plainbase.domain.history.HistoryChange
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.principal.SubjectKey
import com.plainbase.domain.principal.discussionGrantForTests
import com.plainbase.domain.repository.Role
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.service.RootSync
import com.plainbase.frameworks.discussion.DbFaults
import com.plainbase.frameworks.discussion.DiscussionDb
import com.plainbase.frameworks.discussion.JdbcDiscussionRows
import com.plainbase.frameworks.filesystem.LocalDiscussionStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightApiTokenRepository
import com.plainbase.frameworks.sqldelight.SqlDelightAuditRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRoleRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

class DiscussionWriterTest : FunSpec({
    test("start and comment mint comment ids from the provider") {
        withWriterFixture { fixture ->
            val firstId = DiscussionId.require("01900000-0000-7000-8000-000000000101")
            val firstComment = CommentId.require("01900000-0000-7000-8000-000000000102")
            val nextComment = CommentId.require("01900000-0000-7000-8000-000000000103")
            val ids = FixedDiscussionIds(firstId, listOf(firstComment, nextComment))
            val writer = fixture.writer(ids = ids)

            val started = writer.write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "start body\n"))
                .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            started.id shouldBe firstId
            started.commentId shouldBe firstComment
            fixture.comment(firstId, firstComment)?.author shouldBe AUTHOR

            val added = writer.write(DiscussionCommand.AddComment(ROOT, AUTHOR, firstId, "next body\n"))
                .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            added.commentId shouldBe nextComment
            fixture.comment(firstId, nextComment)?.author shouldBe AUTHOR
        }
    }

    test("a comment on a missing discussion is not found") {
        withWriterFixture { fixture ->
            val result = fixture.writer().write(DiscussionCommand.AddComment(ROOT, AUTHOR, ID, "hello"))
            expectRefusal(result, 404, "discussion_not_found")
            fixture.index.publishCount shouldBe 0
        }
    }

    test("a comment on an incomplete discussion is not found") {
        withWriterFixture { fixture ->
            val directory = fixture.content.resolve(".plainbase/discussions/${ID.value}")
            Files.createDirectories(directory)
            Files.write(directory.resolve(COMMENT_NAME.fileName), DiscussionCodec.encodeComment(comment()))
            fixture.store.read(ROOT, ID).shouldBeInstanceOf<EntriesRead.Present>().entries.map { it.name } shouldBe
                listOf(COMMENT_NAME)
            val result = fixture.writer().write(DiscussionCommand.AddComment(ROOT, AUTHOR, ID, "hello"))
            expectRefusal(result, 404, "discussion_not_found")
            fixture.index.publishCount shouldBe 0
        }
    }

    test("a missing comment is not found") {
        withWriterFixture { fixture ->
            fixture.install()
            val result = fixture.writer().write(DiscussionCommand.EditComment(ROOT, AUTHOR, ID, OTHER_COMMENT_ID, "edit"))
            expectRefusal(result, 404, "comment_not_found")
            fixture.index.publishCount shouldBe 0
        }
    }

    test("a malformed marker refuses writes") {
        withWriterFixture { fixture ->
            fixture.store.createFiles(
                ROOT,
                ID,
                listOf(EntryPut(COMMENT_NAME, DiscussionCodec.encodeComment(comment())), EntryPut(EntryName.Marker, byteArrayOf(1))),
            )
            val result = fixture.writer().write(DiscussionCommand.AddComment(ROOT, AUTHOR, ID, "hello"))
            expectRefusal(result, 409, "discussion_unreadable")
            fixture.index.publishCount shouldBe 0
        }
    }

    test("page absence is classified") {
        withWriterFixture { fixture ->
            val absent = fixture.writer(pages = DiscussionPageSource { _, _ -> ContentRead.ConfirmedAbsent })
                .write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body"))
            expectRefusal(absent, 404, "page_not_found")

            val unknown = fixture.writer(pages = DiscussionPageSource { _, _ -> ContentRead.AbsenceUnknown })
                .write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body"))
            expectRefusal(unknown, 503, "absence_unverified")

            shouldThrow<RootUnavailable> {
                fixture.writer(pages = DiscussionPageSource { _, _ -> ContentRead.RootDown })
                    .write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body"))
            }
            fixture.index.publishCount shouldBe 0
        }
    }

    test("a changed page is page changed") {
        withWriterFixture { fixture ->
            val changed = fixture.writer(pages = DiscussionPageSource { _, _ -> ContentRead.Bytes("changed".toByteArray()) })
                .write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body"))
            expectRefusal(changed, 409, "page_changed")

            fixture.install(anchor = fixture.quoteAnchor)
            val reattached = fixture.writer(pages = DiscussionPageSource { _, _ -> ContentRead.Bytes("changed".toByteArray()) })
                .write(DiscussionCommand.Reattach(ROOT, AUTHOR, ID, fixture.quoteAnchor))
            expectRefusal(reattached, 409, "page_changed")
        }
    }

    test("reattach reads the page the marker names") {
        withWriterFixture { fixture ->
            fixture.install(anchor = fixture.quoteAnchor)
            var readPage: PageRef? = null
            val pages = DiscussionPageSource { _, page ->
                readPage = page
                ContentRead.Bytes(fixture.pageBytes)
            }
            fixture.writer(pages = pages).write(DiscussionCommand.Reattach(ROOT, AUTHOR, ID, fixture.quoteAnchor))
                .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            readPage shouldBe PAGE
        }
    }

    test("edit relies on the comment author rather than the actor or discussion starter") {
        withWriterFixture { fixture ->
            fixture.install(comments = listOf(comment(author = RELIED_COMMENT_AUTHOR)), starter = RELIED_STARTER)
            val before = fixture.raw(ID, COMMENT_NAME)
            fixture.writer().write(
                DiscussionCommand.EditComment(
                    ROOT, AUTHOR, ID, COMMENT_ID, "edit",
                ),
                ReliedOn(author = RELIED_COMMENT_AUTHOR.actor.subject),
            ).shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            fixture.raw(ID, COMMENT_NAME).contentEquals(before) shouldBe false
        }
        withWriterFixture { fixture ->
            fixture.install(comments = listOf(comment(author = RELIED_COMMENT_AUTHOR)), starter = RELIED_STARTER)
            val before = fixture.raw(ID, COMMENT_NAME)
            expectRefusal(
                fixture.writer().write(
                    DiscussionCommand.EditComment(ROOT, AUTHOR, ID, COMMENT_ID, "edit"),
                    ReliedOn(author = AUTHOR.actor.subject),
                ),
                409,
                "discussion_changed",
            )
            fixture.raw(ID, COMMENT_NAME) shouldBe before
        }
        withWriterFixture { fixture ->
            fixture.install(comments = listOf(comment(author = RELIED_COMMENT_AUTHOR)), starter = RELIED_STARTER)
            val before = fixture.raw(ID, COMMENT_NAME)
            expectRefusal(
                fixture.writer().write(
                    DiscussionCommand.EditComment(
                        ROOT, AUTHOR, ID, COMMENT_ID, "edit",
                    ),
                    ReliedOn(author = RELIED_STARTER.actor.subject),
                ),
                409,
                "discussion_changed",
            )
            fixture.raw(ID, COMMENT_NAME) shouldBe before
        }
    }

    test("retract relies on the comment author rather than the actor") {
        withWriterFixture { fixture ->
            fixture.install(comments = listOf(comment(author = RELIED_COMMENT_AUTHOR)), starter = RELIED_STARTER)
            fixture.writer().write(
                DiscussionCommand.RetractComment(
                    ROOT, AUTHOR, ID, COMMENT_ID,
                ),
                ReliedOn(author = RELIED_COMMENT_AUTHOR.actor.subject),
            ).shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
        }
        withWriterFixture { fixture ->
            fixture.install(comments = listOf(comment(author = RELIED_COMMENT_AUTHOR)), starter = RELIED_STARTER)
            val before = fixture.raw(ID, COMMENT_NAME)
            expectRefusal(
                fixture.writer().write(
                    DiscussionCommand.RetractComment(ROOT, AUTHOR, ID, COMMENT_ID),
                    ReliedOn(author = AUTHOR.actor.subject),
                ),
                409,
                "discussion_changed",
            )
            fixture.raw(ID, COMMENT_NAME) shouldBe before
        }
    }

    test("status relies on the discussion starter rather than the actor") {
        withWriterFixture { fixture ->
            fixture.install(comments = listOf(comment(author = RELIED_COMMENT_AUTHOR)), starter = RELIED_STARTER)
            fixture.writer().write(
                DiscussionCommand.SetStatus(
                    ROOT, AUTHOR, ID, DiscussionStatus.RESOLVED,
                ),
                ReliedOn(starter = RELIED_STARTER.actor.subject),
            ).shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
        }
        withWriterFixture { fixture ->
            fixture.install(comments = listOf(comment(author = RELIED_COMMENT_AUTHOR)), starter = RELIED_STARTER)
            val before = fixture.raw(ID, EntryName.Marker)
            expectRefusal(
                fixture.writer().write(
                    DiscussionCommand.SetStatus(
                        ROOT, AUTHOR, ID, DiscussionStatus.RESOLVED,
                    ),
                    ReliedOn(starter = AUTHOR.actor.subject),
                ),
                409,
                "discussion_changed",
            )
            fixture.raw(ID, EntryName.Marker) shouldBe before
        }
    }

    test("reattach relies on the discussion starter rather than the actor") {
        withWriterFixture { fixture ->
            fixture.install(
                anchor = fixture.quoteAnchor,
                comments = listOf(comment(author = RELIED_COMMENT_AUTHOR)),
                starter = RELIED_STARTER,
            )
            fixture.writer().write(
                DiscussionCommand.Reattach(
                    ROOT, AUTHOR, ID, fixture.quoteAnchor,
                ),
                ReliedOn(starter = RELIED_STARTER.actor.subject),
            ).shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
        }
        withWriterFixture { fixture ->
            fixture.install(
                anchor = fixture.quoteAnchor,
                comments = listOf(comment(author = RELIED_COMMENT_AUTHOR)),
                starter = RELIED_STARTER,
            )
            val before = fixture.raw(ID, EntryName.Marker)
            expectRefusal(
                fixture.writer().write(
                    DiscussionCommand.Reattach(
                        ROOT, AUTHOR, ID, fixture.quoteAnchor,
                    ),
                    ReliedOn(starter = AUTHOR.actor.subject),
                ),
                409,
                "discussion_changed",
            )
            fixture.raw(ID, EntryName.Marker) shouldBe before
        }
    }

    test("an author appearing after policy facts cannot be edited or retracted without a relied identity") {
        listOf(
            DiscussionFacts.Known("ok", PAGE.pageId, "open", null, null),
            DiscussionFacts.Unknown,
        ).forEach { facts ->
            listOf(DiscussionAction.EDIT, DiscussionAction.RETRACT).forEach { operation ->
                withWriterFixture { fixture ->
                DatabaseFactory.createInMemoryDriver().use { driver ->
                    val db = DatabaseFactory.createDatabase(driver)
                    val roles = SqlDelightRoleRepository(db)
                    val principal = Principal.Human("builtin", "u-1")
                    val fixedClock = object : Clock {
                        override fun now(): Instant = WRITER_NOW
                    }
                    roles.upsert("builtin", "u-1", Role.VIEWER, WRITER_NOW)
                    val policy = PolicyService(
                        roles, SqlDelightApiTokenRepository(db), SqlDelightAuditRepository(db),
                        IdProvider { PAGE.pageId }, fixedClock, enforced = true,
                        discussionsEnabledOf = { true },
                    )
                    val grant = policy.checkDiscussion(
                        principal, operation, facts, RootedResource(ROOT, "discussion/${ID.value}/comment/${COMMENT_ID.value}"),
                    )
                    fixture.install(comments = listOf(comment(author = AUTHOR)))
                    val before = fixture.raw(ID, COMMENT_NAME)
                    val command = if (operation == DiscussionAction.EDIT) {
                        DiscussionCommand.EditComment(ROOT, AUTHOR, ID, COMMENT_ID, "unauthorized")
                    } else {
                        DiscussionCommand.RetractComment(ROOT, AUTHOR, ID, COMMENT_ID)
                    }
                    val refused = fixture.writer().write(grant, command)
                        .shouldBeInstanceOf<DiscussionWriteOutcome.Refused>()
                    refused.refusal.status shouldBe 503
                    refused.refusal.code shouldBe "content_unreadable"
                    fixture.raw(ID, COMMENT_NAME) shouldBe before
                }
            }
            }
        }
    }

    test("a discussion grant cannot be reused for another action") {
        withWriterFixture { fixture ->
            fixture.install()
            val before = fixture.raw(ID, COMMENT_NAME)
            shouldThrow<IllegalArgumentException> {
                fixture.writer().write(
                    discussionGrantForTests(ROOT, DiscussionAction.COMMENT),
                    DiscussionCommand.EditComment(ROOT, AUTHOR, ID, COMMENT_ID, "changed"),
                )
            }
            fixture.raw(ID, COMMENT_NAME) shouldBe before
        }
    }

    test("writer refuses an invalid anchor commit before storing it") {
        withWriterFixture { fixture ->
            val anchor = Anchor.Quote(
                fixture.quoteAnchor.contentHash,
                "not-a-valid-commit",
                QuoteCapture("x", "", "", 0, 1, 0, 1, AnchorSelection.NARROWED, HeadingPath(emptyList())),
            )
            val marker = fixture.marker(anchor = anchor)
            DiscussionCodec.decodeDiscussion(DiscussionCodec.encodeDiscussion(marker))
                .shouldBeInstanceOf<Decoded.Unreadable>()
            shouldThrow<IllegalArgumentException> {
                fixture.writer().write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, anchor, "body"))
            }
            Files.exists(fixture.content.resolve(".plainbase/discussions/${START_ID.value}")) shouldBe false
            fixture.history.requests shouldBe emptyList()
        }
    }

    test("writer refuses an 11-digit anchor offset before storing it") {
        withWriterFixture { fixture ->
            val anchor = Anchor.Quote(
                fixture.quoteAnchor.contentHash,
                null,
                QuoteCapture(
                    "x", "", "", 10_000_000_000L, 10_000_000_001L, 0, 1,
                    AnchorSelection.NARROWED, HeadingPath(emptyList()),
                ),
            )
            val marker = fixture.marker(anchor = anchor)
            DiscussionCodec.decodeDiscussion(DiscussionCodec.encodeDiscussion(marker))
                .shouldBeInstanceOf<Decoded.Unreadable>()
            shouldThrow<IllegalArgumentException> {
                fixture.writer().write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, anchor, "body"))
            }
            Files.exists(fixture.content.resolve(".plainbase/discussions/${START_ID.value}")) shouldBe false
            fixture.history.requests shouldBe emptyList()
        }
    }

    test("a null relied field skips its check") {
        withWriterFixture { fixture ->
            fixture.install()
            fixture.writer().write(
                DiscussionCommand.EditComment(ROOT, AUTHOR, ID, COMMENT_ID, "edited"),
                ReliedOn(),
            ).shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
        }
    }

    test("a relied author is ignored on status") {
        withWriterFixture { fixture ->
            fixture.install()
            fixture.writer().write(
                DiscussionCommand.SetStatus(
                    ROOT,
                    AUTHOR,
                    ID,
                    DiscussionStatus.RESOLVED,
                ),
                ReliedOn(author = SubjectKey("builtin", "not-the-comment-author")),
            ).shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
        }
    }

    test("transition refusals and allowed edits follow the current status") {
        withWriterFixture { fixture ->
            fixture.install(status = DiscussionStatus.RESOLVED)
            expectRefusal(
                fixture.writer().write(DiscussionCommand.AddComment(ROOT, AUTHOR, ID, "hello")),
                409,
                "discussion_resolved",
            )
            fixture.writer().write(DiscussionCommand.EditComment(ROOT, AUTHOR, ID, COMMENT_ID, "edited"))
                .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            fixture.writer().write(DiscussionCommand.RetractComment(ROOT, AUTHOR, ID, COMMENT_ID))
                .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            expectRefusal(
                fixture.writer().write(DiscussionCommand.EditComment(ROOT, AUTHOR, ID, COMMENT_ID, "again")),
                409,
                "comment_retracted",
            )
            expectRefusal(
                fixture.writer().write(DiscussionCommand.SetStatus(ROOT, AUTHOR, ID, DiscussionStatus.RESOLVED)),
                409,
                "already_resolved",
            )
            fixture.writer().write(DiscussionCommand.SetStatus(ROOT, AUTHOR, ID, DiscussionStatus.OPEN))
                .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            expectRefusal(
                fixture.writer().write(DiscussionCommand.SetStatus(ROOT, AUTHOR, ID, DiscussionStatus.OPEN)),
                409,
                "already_open",
            )
        }
    }

    test("reattach refuses resolved and page-level discussions before reading the page") {
        withWriterFixture { fixture ->
            fixture.install(anchor = fixture.quoteAnchor, status = DiscussionStatus.RESOLVED)
            var reads = 0
            val pages = DiscussionPageSource { _, _ ->
                reads++
                ContentRead.Bytes(fixture.pageBytes)
            }
            expectRefusal(
                fixture.writer(pages = pages).write(DiscussionCommand.Reattach(ROOT, AUTHOR, ID, fixture.quoteAnchor)),
                409,
                "discussion_resolved",
            )
            reads shouldBe 0
        }
        withWriterFixture { fixture ->
            fixture.install(anchor = fixture.pageAnchor)
            var reads = 0
            val pages = DiscussionPageSource { _, _ ->
                reads++
                ContentRead.Bytes(fixture.pageBytes)
            }
            expectRefusal(
                fixture.writer(pages = pages).write(DiscussionCommand.Reattach(ROOT, AUTHOR, ID, fixture.quoteAnchor)),
                422,
                "reattach_page_level",
            )
            reads shouldBe 0
        }
    }

    test("a body change between read and replace is stale discussion") {
        withWriterFixture { fixture ->
            fixture.install()
            val scripted = ScriptedDiscussionStore(fixture.store).apply { answer("replace", StoreWrite.Mismatch(null)) }
            expectRefusal(
                fixture.writer(store = scripted).write(DiscussionCommand.EditComment(ROOT, AUTHOR, ID, COMMENT_ID, "edit")),
                409,
                "stale_discussion",
            )
        }
    }

    test("a refused edit restores raw CRLF bytes with an unknown key before format") {
        withWriterFixture { fixture ->
            fixture.install(
                comments = emptyList(),
                rawComments = listOf(
                    COMMENT_NAME to DiscussionCodec.encodeComment(comment()).decodeToString()
                        .replace("\n", "\r\n")
                        .replace("format:", "x_future: \"kept\"\r\nformat:")
                        .toByteArray(),
                ),
            )
            val original = fixture.raw(ID, COMMENT_NAME)
            fixture.history.outcome = CommitOutcome.NotCommitted(IOException("update ref refused"))

            expectRefusal(
                fixture.writer().write(DiscussionCommand.EditComment(ROOT, AUTHOR, ID, COMMENT_ID, "edited")),
                503,
                "discussion_commit_failed",
            )

            fixture.raw(ID, COMMENT_NAME).toList() shouldBe original.toList()
        }
    }

    test("start onto an existing id is an internal error") {
        withWriterFixture { fixture ->
            val ids = FixedDiscussionIds(ID, listOf(OTHER_COMMENT_ID))
            fixture.install()
            shouldThrow<IllegalStateException> {
                fixture.writer(ids = ids).write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body"))
            }
        }
    }

    test("purge publishes the surviving comments") {
        withWriterFixture { fixture ->
            fixture.install(comments = listOf(comment(), comment(OTHER_COMMENT_ID, "second")))
            fixture.writer().write(DiscussionCommand.PurgeComment(ROOT, AUTHOR, ID, COMMENT_ID))
                .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            val published = fixture.index.lastPublishedById.getValue(ID).shouldBeInstanceOf<DiscussionRead.Ok>()
            published.files.comments.map { it.value.id } shouldContainExactly listOf(OTHER_COMMENT_ID)
            published shouldBe DiscussionAssembly.assemble(ID, fixture.store.read(ROOT, ID))
        }
    }

    test("purge removes the malformed comment that made a discussion unreadable") {
        withWriterFixture { fixture ->
            fixture.install(comments = emptyList(), rawComments = listOf(COMMENT_NAME to byteArrayOf(1)))
            fixture.index.lastPublishedById[ID] = DiscussionRead.Unreadable(
                UnreadableReason.BAD_VALUE,
                COMMENT_NAME.fileName,
                PAGE.pageId,
            )
            fixture.writer().write(DiscussionCommand.PurgeComment(ROOT, AUTHOR, ID, COMMENT_ID))
                .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            fixture.index.lastPublishedById.getValue(ID).shouldBeInstanceOf<DiscussionRead.Ok>()
        }
    }

    test("purge needs the marker file but does not decode it") {
        withWriterFixture { fixture ->
            val directory = fixture.content.resolve(".plainbase/discussions/${ID.value}")
            Files.createDirectories(directory)
            Files.write(directory.resolve(EntryName.Marker.fileName), byteArrayOf(0xff.toByte()))
            Files.write(directory.resolve(COMMENT_NAME.fileName), DiscussionCodec.encodeComment(comment()))

            fixture.writer().write(DiscussionCommand.PurgeComment(ROOT, AUTHOR, ID, COMMENT_ID))
                .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            Files.exists(directory.resolve(COMMENT_NAME.fileName)) shouldBe false
        }
    }

    test("an unknown purge keeps its tombstone and a committed purge discards it") {
        withWriterFixture { fixture ->
            fixture.install()
            val history = fixture.history.apply { outcome = CommitOutcome.Unknown(IOException("cannot reconcile")) }
            expectRefusal(
                fixture.writer(history = history).write(DiscussionCommand.PurgeComment(ROOT, AUTHOR, ID, COMMENT_ID)),
                503,
                "discussion_commit_uncertain",
            )
            val directory = fixture.content.resolve(".plainbase/discussions/${ID.value}")
            Files.list(directory).use { stream -> stream.noneMatch { it.fileName.toString().startsWith(".pbpurge.") } shouldBe false }
        }
        withWriterFixture { fixture ->
            fixture.install()
            fixture.writer().write(DiscussionCommand.PurgeComment(ROOT, AUTHOR, ID, COMMENT_ID))
                .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            val directory = fixture.content.resolve(".plainbase/discussions/${ID.value}")
            Files.list(directory).use { stream -> stream.noneMatch { it.fileName.toString().startsWith(".pbpurge.") } shouldBe true }
        }
    }

    test("an unknown commit emits one warning") {
        withWriterFixture { fixture ->
            val rootLogger = LoggerFactory.getLogger(DiscussionWriter::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            rootLogger.addAppender(appender)
            try {
                fixture.history.outcome = CommitOutcome.Unknown(IOException("cannot reconcile"))
                expectRefusal(
                    fixture.writer().write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body")),
                    503,
                    "discussion_commit_uncertain",
                )
            } finally {
                rootLogger.detachAppender(appender)
                appender.stop()
            }
            appender.list.count {
                it.level == Level.WARN && "commit outcome for discussion" in it.formattedMessage && "is unknown" in it.formattedMessage
            } shouldBe 1
        }
    }

    test("a refused commit logs its cause after a successful undo") {
        withWriterFixture { fixture ->
            val cause = IOException("update ref refused")
            val logger = LoggerFactory.getLogger(DiscussionWriter::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                fixture.history.outcome = CommitOutcome.NotCommitted(cause)
                expectRefusal(
                    fixture.writer().write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body")),
                    503,
                    "discussion_commit_failed",
                )
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }

            appender.list.any {
                it.level == Level.WARN && it.formattedMessage.contains(ROOT.value) &&
                    it.formattedMessage.contains(START_ID.value) && it.throwableProxy?.message == cause.message
            } shouldBe true
            fixture.index.lastPublishedById.getValue(START_ID).shouldBeInstanceOf<DiscussionRead.Absent>()
        }
    }

    test("store write and read failures log their root, discussion, and cause") {
        withWriterFixture { fixture ->
            val logger = LoggerFactory.getLogger(DiscussionWriter::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                val failedWrite = ScriptedDiscussionStore(fixture.store).apply {
                    answer("createFiles", StoreWrite.Failed("store fault"))
                }
                expectRefusal(
                    fixture.writer(store = failedWrite).write(
                        DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body"),
                    ),
                    503,
                    "content_unreadable",
                )
                val failedRead = ScriptedDiscussionStore(fixture.store).apply {
                    answer("read", EntriesRead.Failed("read fault"))
                }
                expectRefusal(
                    fixture.writer(store = failedRead).write(DiscussionCommand.AddComment(ROOT, AUTHOR, ID, "body")),
                    503,
                    "content_unreadable",
                )
                val failedPurgeRead = ScriptedDiscussionStore(fixture.store).apply {
                    answer("read", EntriesRead.Failed("purge read fault"))
                }
                expectRefusal(
                    fixture.writer(store = failedPurgeRead).write(
                        DiscussionCommand.PurgeComment(ROOT, AUTHOR, ID, COMMENT_ID),
                    ),
                    503,
                    "content_unreadable",
                )
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }

            val warnings = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
            warnings.any { ROOT.value in it && START_ID.value in it && "store fault" in it } shouldBe true
            warnings.any { ROOT.value in it && ID.value in it && "read fault" in it } shouldBe true
            warnings.any { ROOT.value in it && ID.value in it && "purge read fault" in it } shouldBe true
        }
    }

    test("history lookup failure rolls back stored changes") {
        withWriterFixture { fixture ->
            val cause = IOException("history lookup failed")
            expectRefusal(
                fixture.writer(historyProvider = { throw cause })
                    .write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body")),
                503,
                "discussion_commit_failed",
            )

            fixture.index.lastPublishedById.getValue(START_ID).shouldBeInstanceOf<DiscussionRead.Absent>()
            fixture.store.read(ROOT, START_ID).shouldBeInstanceOf<EntriesRead.Absent>()
        }
    }

    test("a thrown commit call keeps stored changes as uncertain") {
        withWriterFixture { fixture ->
            fixture.history.failOnCommit = IOException("commit call failed")

            expectRefusal(
                fixture.writer().write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body")),
                503,
                "discussion_commit_uncertain",
            )

            fixture.store.read(ROOT, START_ID).shouldBeInstanceOf<EntriesRead.Present>()
            fixture.index.lastPublishedById.getValue(START_ID).shouldBeInstanceOf<DiscussionRead.Ok>()
        }
    }

    test("a missing purge tombstone during undo logs an error and leaves a residual") {
        withWriterFixture { fixture ->
            fixture.install()
            fixture.history.outcome = CommitOutcome.NotCommitted(IOException("update ref refused"))
            val storeWithoutTombstone = object : DiscussionStore by fixture.store {
                override fun purge(root: RootName, entry: EntryPath, version: EntryVersion): StoreWrite {
                    val result = fixture.store.purge(root, entry, version).shouldBeInstanceOf<StoreWrite.Written>()
                    return result.copy(tombstone = null)
                }
            }
            val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            rootLogger.addAppender(appender)
            try {
                expectRefusal(
                    fixture.writer(store = storeWithoutTombstone).write(
                        DiscussionCommand.PurgeComment(ROOT, AUTHOR, ID, COMMENT_ID),
                    ),
                    503,
                    "discussion_commit_failed",
                )
            } finally {
                rootLogger.detachAppender(appender)
                appender.stop()
            }
            appender.list.any {
                it.level == Level.ERROR && "undo of ${ID.value} failed; files left uncommitted" in it.formattedMessage
            } shouldBe true
            Files.list(fixture.content.resolve(".plainbase/discussions/${ID.value}")).use { stream ->
                stream.anyMatch { it.fileName.toString().startsWith(".pbpurge.") } shouldBe true
            }
        }
    }

    test("a publish failure never changes the result") {
        listOf(
            CommitOutcome.Committed(null, null) to null,
            CommitOutcome.Unknown(IOException("uncertain")) to "discussion_commit_uncertain",
            CommitOutcome.NotCommitted(IOException("refused")) to "discussion_commit_failed",
        ).forEach { (commitOutcome, expectedCode) ->
            withWriterFixture { fixture ->
                fixture.index.failPublish = true
                fixture.history.outcome = commitOutcome
                val result = fixture.writer().write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body"))
                if (expectedCode == null) {
                    result.shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
                } else {
                    expectRefusal(result, 503, expectedCode)
                }
                fixture.index.publishFailedCalls shouldBe 1
            }
        }
    }

    test("a post-write publish failure turns the root unsynced once") {
        withWriterFixture { fixture ->
            val databasePath = fixture.base.resolve("discussions.db")
            DiscussionDb(databasePath).use { db ->
                val rows = JdbcDiscussionRows(db)
                val sync = DiscussionSyncState(setOf(ROOT))
                val index = SyncedDiscussionIndex(rows, fixture.store, DiscussionFullReads(fixture.store), sync)
                DbFaults(databasePath).use { faults ->
                    faults.failWrites()

                    val result = fixture.writer(index = index).write(
                        DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body"),
                    )

                    result.shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
                    val unsynced = sync.current(ROOT).shouldBeInstanceOf<RootSync.Unsynced>()
                    unsynced.generation shouldBe 1L
                }
            }
        }
    }

    test("an interrupted post-write publish keeps the committed result and interrupt flag") {
        withWriterFixture { fixture ->
            DiscussionDb(fixture.base.resolve("discussions.db")).use { db ->
                val sync = DiscussionSyncState(setOf(ROOT))
                val realIndex = SyncedDiscussionIndex(JdbcDiscussionRows(db), fixture.store, DiscussionFullReads(fixture.store), sync)
                val index = object : DiscussionIndex by realIndex {
                    override fun publish(root: RootName, id: DiscussionId, markerChanged: Boolean, read: () -> EntriesRead) {
                        Thread.currentThread().interrupt()
                        realIndex.publish(root, id, markerChanged, read)
                    }
                }
                try {
                    val result = fixture.writer(index = index).write(
                        DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body"),
                    ).shouldBeInstanceOf<DiscussionWriteOutcome.Done>()

                    result.id shouldBe START_ID
                    sync.current(ROOT).shouldBeInstanceOf<RootSync.Unsynced>().generation shouldBe 1L
                    Thread.currentThread().isInterrupted shouldBe true
                    Thread.interrupted()
                    try {
                        fixture.comment(result.id, checkNotNull(result.commentId))?.body shouldBe "body"
                        DiscussionCodec.decodeDiscussion(fixture.raw(result.id, EntryName.Marker))
                            .shouldBeInstanceOf<Decoded.Ok<DiscussionRecord>>()
                    } finally {
                        Thread.currentThread().interrupt()
                    }
                    Thread.currentThread().isInterrupted shouldBe true
                } finally {
                    Thread.interrupted()
                }
            }
        }
    }

    test("a failed post-write reread reaches the index and still returns the commit result") {
        withWriterFixture { fixture ->
            val store = object : DiscussionStore by fixture.store {
                var reads = 0
                override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    reads++
                    return if (reads == 1) {
                        EntriesRead.Failed("post-write readback fault")
                    } else {
                        fixture.store.read(root, id, only)
                    }
                }
            }
            val logger = LoggerFactory.getLogger(DiscussionWriter::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                fixture.writer(store = store).write(
                    DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body"),
                ).shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }
            fixture.index.lastPublishedById.getValue(START_ID).shouldBeInstanceOf<DiscussionRead.Failed>()
            appender.list.any {
                it.level == Level.WARN && ROOT.value in it.formattedMessage && START_ID.value in it.formattedMessage &&
                    "post-write readback fault" in it.formattedMessage
            } shouldBe true
        }
    }

    test("an interrupted refused start still undoes cleanly") {
        withWriterFixture { fixture ->
            fixture.history.outcome = CommitOutcome.NotCommitted(IOException("refused"))
            fixture.history.interruptOnCommit = true
            try {
                expectRefusal(
                    fixture.writer().write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body")),
                    503,
                    "discussion_commit_failed",
                )
                Thread.currentThread().isInterrupted shouldBe true
                fixture.index.lastPublishedById.getValue(START_ID).shouldBeInstanceOf<DiscussionRead.Absent>()
            } finally {
                Thread.interrupted()
            }
        }
    }

    test("an interrupted unknown keeps the flag") {
        withWriterFixture { fixture ->
            fixture.history.outcome = CommitOutcome.Unknown(IOException("uncertain"))
            fixture.history.interruptOnCommit = true
            try {
                expectRefusal(
                    fixture.writer().write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body")),
                    503,
                    "discussion_commit_uncertain",
                )
                Thread.currentThread().isInterrupted shouldBe true
                Thread.interrupted() shouldBe true
                try {
                    fixture.index.lastPublished shouldBe DiscussionAssembly.assemble(
                        START_ID,
                        fixture.store.read(ROOT, START_ID),
                    )
                } finally {
                    Thread.currentThread().interrupt()
                }
            } finally {
                Thread.interrupted()
            }
        }
    }

    test("an uninterrupted undo leaves the flag clear") {
        withWriterFixture { fixture ->
            fixture.history.outcome = CommitOutcome.NotCommitted(IOException("refused"))
            expectRefusal(
                fixture.writer().write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body")),
                503,
                "discussion_commit_failed",
            )
            Thread.currentThread().isInterrupted shouldBe false
        }
    }

    test("fifty concurrent starts at 199 admit exactly one") {
        withWriterFixture { fixture ->
            fixture.index.seed(ROOT, PAGE.pageId, 199)
            val writer = fixture.writer()
            val pool = Executors.newFixedThreadPool(50)
            val gate = CountDownLatch(1)
            try {
                val results = (0 until 50).map {
                    pool.submit<DiscussionWriteOutcome> {
                        gate.await()
                        writer.write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body"))
                    }
                }
                gate.countDown()
                val outcomes = results.map { it.get(30, TimeUnit.SECONDS) }
                outcomes.count { it is DiscussionWriteOutcome.Done } shouldBe 1
                outcomes.filterIsInstance<DiscussionWriteOutcome.Refused>().count { it.refusal.code == "page_discussion_limit" } shouldBe 49
                fixture.index.maxConcurrentCountReads shouldBe 1
            } finally {
                pool.shutdownNow()
            }
        }
    }

    test("unreadable discussions count toward the page cap") {
        withWriterFixture { fixture ->
            fixture.index.seed(ROOT, PAGE.pageId, 199)
            fixture.index.seedPublication(
                ROOT,
                ID,
                DiscussionRead.Unreadable(UnreadableReason.BAD_VALUE, "x.md", PAGE.pageId),
            )
            expectRefusal(
                fixture.writer().write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body")),
                409,
                "page_discussion_limit",
            )
        }
    }

    test("fifty concurrent comments at 999 admit exactly one") {
        withWriterFixture { fixture ->
            fixture.install(comments = (1..999).map { comment(commentId(it + 200), "body $it") })
            val countingStore = CountingDiscussionStore(fixture.store)
            val writer = fixture.writer(store = countingStore)
            val pool = Executors.newFixedThreadPool(50)
            val gate = CountDownLatch(1)
            try {
                val results = (0 until 50).map {
                    pool.submit<DiscussionWriteOutcome> {
                        gate.await()
                        writer.write(DiscussionCommand.AddComment(ROOT, AUTHOR, ID, "body"))
                    }
                }
                gate.countDown()
                val outcomes = results.map { it.get(45, TimeUnit.SECONDS) }
                outcomes.count { it is DiscussionWriteOutcome.Done } shouldBe 1
                outcomes.filterIsInstance<DiscussionWriteOutcome.Refused>().count { it.refusal.code == "discussion_full" } shouldBe 49
                fixture.index.lastPublishedById.getValue(ID).shouldBeInstanceOf<DiscussionRead.Ok>().files.comments.size shouldBe 1_000
                fixture.store.read(ROOT, ID, only = setOf(EntryName.Marker)).let { (it as EntriesRead.Present).commentCount } shouldBe 1_000
                countingStore.maxConcurrentReads shouldBe 1
            } finally {
                pool.shutdownNow()
            }
        }
    }

    test("a failing page count writes nothing") {
        withWriterFixture { fixture ->
            val index = object : DiscussionIndex by fixture.index {
                override fun pageDiscussionCount(root: RootName, pageId: PageId): Int = error("count failed")
            }
            shouldThrow<IllegalStateException> {
                fixture.writer(index = index).write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body"))
            }
            fixture.store.visit(ROOT) { _, _ -> } shouldBe CollectionVisit.Absent
        }
    }

    test("a store residual is republished from disk") {
        withWriterFixture { fixture ->
            val scripted = ScriptedDiscussionStore(fixture.store).apply {
                answer("createFiles", StoreWrite.Failed("partial create", residual = true))
            }
            expectRefusal(
                fixture.writer(store = scripted).write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body")),
                503,
                "content_unreadable",
            )
            fixture.index.publishCount shouldBe 1
            fixture.index.lastPublishedById.getValue(START_ID) shouldBe
                DiscussionAssembly.assemble(START_ID, fixture.store.read(ROOT, START_ID))
        }
    }

    test("unknown keys survive every rewrite") {
        withWriterFixture { fixture ->
            val t0 = "2026-09-23T10:00:00.000Z"
            val t1 = "2026-09-23T11:30:00.250Z"
            val t1Instant = Instant.parse(t1)
            val pageId = "0190aaaa-0000-4000-8000-000000000003"
            val hash = "sha256:" + "a".repeat(64)
            val markerExtras = FrontmatterExtras(
                leading = "  lead: 1\n",
                trailing = listOf("x_future: \"kept\"", "x_list:\n  - \"a\"", "# note", "x_note: |\n  one\n\n  two"),
            )
            val commentExtras = FrontmatterExtras(leading = null, trailing = listOf("x_mood: \"calm\""))
            val xLines = listOf("x_list:", "  - \"a\"", "# note", "x_note: |", "  one", "", "  two")
            val resolvedXLines = listOf("x_future: \"kept\"") + xLines
            val idDirectory = fixture.content.resolve(".plainbase/discussions/${ID.value}")
            Files.createDirectories(idDirectory)
            Files.write(
                idDirectory.resolve(EntryName.Marker.fileName),
                (
                    listOf(
                        "---",
                        "  lead: 1",
                        "format: \"plainbase-discussion/1\"",
                        "id: \"${ID.value}\"",
                        "x_future: \"kept\"",
                        "page_id: \"$pageId\"",
                        "page_path: \"guides/setup.md\"",
                        "status: \"open\"",
                        "created: \"$t0\"",
                        "started_by_issuer: \"builtin\"",
                        "started_by_id: \"u-1\"",
                        "started_by_label: \"Ada\"",
                        "started_by_kind: \"human\"",
                        "anchor_kind: \"page\"",
                        "anchor_content_hash: \"$hash\"",
                    ) + xLines + "---"
                ).joinToString("\n", postfix = "\n").toByteArray(),
            )
            val initialComment = listOf(
                "---",
                "format: \"plainbase-comment/1\"",
                "id: \"${COMMENT_ID.value}\"",
                "discussion_id: \"${ID.value}\"",
                "author_issuer: \"builtin\"",
                "author_id: \"u-1\"",
                "author_label: \"Ada\"",
                "author_kind: \"human\"",
                "created: \"$t0\"",
                "x_mood: \"calm\"",
                "---",
                "Looks good.",
            ).joinToString("\n", postfix = "\n").toByteArray()
            Files.write(idDirectory.resolve(COMMENT_NAME.fileName), initialComment)
            val secondCommentName = EntryName.Comment(OTHER_COMMENT_ID)
            Files.write(
                idDirectory.resolve(secondCommentName.fileName),
                initialComment.decodeToString().replace(COMMENT_ID.value, OTHER_COMMENT_ID.value).encodeToByteArray(),
            )
            val writerAtT1 = DiscussionWriter(
                fixture.monitor,
                fixture.store,
                DiscussionPageSource { _, _ -> ContentRead.Bytes(fixture.pageBytes.copyOf()) },
                { fixture.history },
                fixture.index,
                FixedDiscussionIds(),
                object : Clock {
                    override fun now(): Instant = t1Instant
                },
                CitationFactory()::contentHash,
            )
            val resolver = Author(Actor(SubjectKey("builtin", "u-2"), "Bo"), AuthorKind.HUMAN)

            writerAtT1.write(DiscussionCommand.SetStatus(ROOT, resolver, ID, DiscussionStatus.RESOLVED))
                .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            val expectedResolved = (
                listOf(
                    "---",
                    "  lead: 1",
                    "format: \"plainbase-discussion/1\"",
                    "id: \"${ID.value}\"",
                    "page_id: \"$pageId\"",
                    "page_path: \"guides/setup.md\"",
                    "status: \"resolved\"",
                    "created: \"$t0\"",
                    "started_by_issuer: \"builtin\"",
                    "started_by_id: \"u-1\"",
                    "started_by_label: \"Ada\"",
                    "started_by_kind: \"human\"",
                    "status_changed_by_issuer: \"builtin\"",
                    "status_changed_by_id: \"u-2\"",
                    "status_changed_by_label: \"Bo\"",
                    "status_changed_at: \"$t1\"",
                    "anchor_kind: \"page\"",
                    "anchor_content_hash: \"$hash\"",
                ) + resolvedXLines + "---"
            ).joinToString("\n", postfix = "\n").toByteArray()
            fixture.raw(ID, EntryName.Marker).contentEquals(expectedResolved) shouldBe true

            writerAtT1.write(DiscussionCommand.SetStatus(ROOT, resolver, ID, DiscussionStatus.OPEN))
                .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            val reopened = (DiscussionCodec.decodeDiscussion(fixture.raw(ID, EntryName.Marker)) as Decoded.Ok).value
            reopened.extras shouldBe markerExtras

            writerAtT1.write(DiscussionCommand.EditComment(ROOT, AUTHOR, ID, COMMENT_ID, "Edited.\n"))
                .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            val expectedEdited = listOf(
                "---",
                "format: \"plainbase-comment/1\"",
                "id: \"${COMMENT_ID.value}\"",
                "discussion_id: \"${ID.value}\"",
                "author_issuer: \"builtin\"",
                "author_id: \"u-1\"",
                "author_label: \"Ada\"",
                "author_kind: \"human\"",
                "created: \"$t0\"",
                "edited_at: \"$t1\"",
                "x_mood: \"calm\"",
                "---",
                "Edited.",
            ).joinToString("\n", postfix = "\n").toByteArray()
            fixture.raw(ID, COMMENT_NAME).toList() shouldBe expectedEdited.toList()

            writerAtT1.write(DiscussionCommand.RetractComment(ROOT, AUTHOR, ID, OTHER_COMMENT_ID))
                .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            val expectedRetracted = listOf(
                "---",
                "format: \"plainbase-comment/1\"",
                "id: \"${OTHER_COMMENT_ID.value}\"",
                "discussion_id: \"${ID.value}\"",
                "author_issuer: \"builtin\"",
                "author_id: \"u-1\"",
                "author_label: \"Ada\"",
                "author_kind: \"human\"",
                "created: \"$t0\"",
                "retracted_by_issuer: \"builtin\"",
                "retracted_by_id: \"u-1\"",
                "retracted_by_label: \"Ada\"",
                "retracted_at: \"$t1\"",
                "x_mood: \"calm\"",
                "---",
                "retracted by Ada",
            ).joinToString("\n", postfix = "\n").toByteArray()
            fixture.raw(ID, secondCommentName).toList() shouldBe expectedRetracted.toList()
            val retracted = (DiscussionCodec.decodeComment(fixture.raw(ID, secondCommentName)) as Decoded.Ok).value
            retracted.extras shouldBe commentExtras
            retracted.retraction?.by shouldBe AUTHOR.actor
            retracted.body shouldBe "retracted by Ada\n"
        }

        withWriterFixture { fixture ->
            val markerExtras = FrontmatterExtras(leading = "  lead: 1\n", trailing = listOf("x_future: \"kept\""))
            fixture.install(anchor = fixture.quoteAnchor, markerExtras = markerExtras)
            fixture.writer().write(DiscussionCommand.Reattach(ROOT, AUTHOR, ID, fixture.quoteAnchor))
                .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            val reattached = (DiscussionCodec.decodeDiscussion(fixture.raw(ID, EntryName.Marker)) as Decoded.Ok).value
            reattached.extras shouldBe markerExtras
        }
    }

    test("a malformed other comment does not block writes") {
        withWriterFixture { fixture ->
            fixture.install(rawComments = listOf(EntryName.Comment(OTHER_COMMENT_ID) to byteArrayOf(1)))
            fixture.writer().write(DiscussionCommand.AddComment(ROOT, AUTHOR, ID, "added"))
                .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            fixture.writer().write(DiscussionCommand.EditComment(ROOT, AUTHOR, ID, COMMENT_ID, "edited"))
                .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            fixture.writer().write(DiscussionCommand.SetStatus(ROOT, AUTHOR, ID, DiscussionStatus.RESOLVED))
                .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            val read = fixture.index.lastPublishedById.getValue(ID).shouldBeInstanceOf<DiscussionRead.Unreadable>()
            read.pageId shouldBe PAGE.pageId
        }
    }

    test("unknown entries do not count toward the comment cap") {
        withWriterFixture { fixture ->
            fixture.install(comments = (1..999).map { comment(commentId(it + 4_000), "body $it") })
            val directory = fixture.content.resolve(".plainbase/discussions/${ID.value}")
            Files.writeString(directory.resolve("operator-note.txt"), "unknown")
            fixture.writer().write(DiscussionCommand.AddComment(ROOT, AUTHOR, ID, "body"))
                .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            val read = fixture.store.read(ROOT, ID, only = setOf(EntryName.Marker)).shouldBeInstanceOf<EntriesRead.Present>()
            read.commentCount shouldBe 1_000
        }
    }

    test("a start under a heading larger than the marker cap writes nothing") {
        withWriterFixture { fixture ->
            val heading = "h".repeat(512 * 1024)
            val capture = QuoteCapture(
                "x",
                "",
                "",
                0,
                1,
                0,
                1,
                AnchorSelection.NARROWED,
                HeadingPath(listOf(HeadingPath.Entry(1, heading))),
            )
            val anchor = Anchor.Quote(fixture.pageAnchor.contentHash, null, capture)
            expectRefusal(
                fixture.writer().write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, anchor, "body")),
                422,
                "discussion_too_large",
            )
            fixture.store.visit(ROOT) { _, _ -> } shouldBe CollectionVisit.Absent
            fixture.history.requests shouldBe emptyList()
        }
    }

    test("a reattach under a heading larger than the marker cap writes nothing") {
        withWriterFixture { fixture ->
            fixture.install(anchor = fixture.quoteAnchor)
            val before = fixture.raw(ID, EntryName.Marker)
            val capture = QuoteCapture(
                "x",
                "",
                "",
                0,
                1,
                0,
                1,
                AnchorSelection.NARROWED,
                HeadingPath(listOf(HeadingPath.Entry(1, "h".repeat(512 * 1024)))),
            )
            val anchor = Anchor.Quote(fixture.pageAnchor.contentHash, null, capture)
            expectRefusal(
                fixture.writer().write(DiscussionCommand.Reattach(ROOT, AUTHOR, ID, anchor)),
                422,
                "discussion_too_large",
            )
            fixture.raw(ID, EntryName.Marker) shouldBe before
        }
    }

    test("a comment with oversized escaped identities is refused before storage") {
        withWriterFixture { fixture ->
            fixture.install()
            val identity = "x\u0001".repeat(1_367)
            val actor = Author(
                Actor(SubjectKey(identity, identity), identity),
                AuthorKind.HUMAN,
            )
            expectRefusal(
                fixture.writer().write(DiscussionCommand.AddComment(ROOT, actor, ID, "b".repeat(65_536))),
                422,
                "discussion_too_large",
            )
            fixture.index.publishCount shouldBe 0
        }
    }

    test("resolving a hand-placed marker ten bytes under the cap is refused") {
        withWriterFixture { fixture ->
            fixture.install()
            val path = fixture.content.resolve(".plainbase/discussions/${ID.value}/discussion.md")
            val raw = paddedMarker(fixture, MAX_MARKER_BYTES - 10, finalNewline = true)
            Files.write(path, raw)
            (DiscussionCodec.decodeDiscussion(raw) as Decoded.Ok).value.id shouldBe ID
            expectRefusal(
                fixture.writer().write(DiscussionCommand.SetStatus(ROOT, AUTHOR, ID, DiscussionStatus.RESOLVED)),
                422,
                "discussion_too_large",
            )
            Files.readAllBytes(path).contentEquals(raw) shouldBe true
        }
    }

    test("resolving a cap-sized marker without a final newline is refused") {
        withWriterFixture { fixture ->
            fixture.install()
            val path = fixture.content.resolve(".plainbase/discussions/${ID.value}/discussion.md")
            val raw = paddedMarker(fixture, MAX_MARKER_BYTES, finalNewline = false)
            Files.write(path, raw)
            (DiscussionCodec.decodeDiscussion(raw) as Decoded.Ok).value.id shouldBe ID
            expectRefusal(
                fixture.writer().write(DiscussionCommand.SetStatus(ROOT, AUTHOR, ID, DiscussionStatus.RESOLVED)),
                422,
                "discussion_too_large",
            )
            Files.readAllBytes(path).contentEquals(raw) shouldBe true
        }
    }

    test("a lone surrogate label fails before any write") {
        withWriterFixture { fixture ->
            val malformedAuthor = Author(
                Actor(SubjectKey("builtin", "u-1"), "\uD800"),
                AuthorKind.HUMAN,
            )
            shouldThrow<IllegalArgumentException> {
                fixture.writer().write(DiscussionCommand.Start(ROOT, malformedAuthor, PAGE, fixture.pageAnchor, "body"))
            }
            fixture.store.visit(ROOT) { _, _ -> } shouldBe CollectionVisit.Absent
            fixture.history.requests shouldBe emptyList()
        }
    }

    test("a refused commit republishes after a clean undo") {
        withWriterFixture { fixture ->
            fixture.install()
            val before = fixture.raw(ID, COMMENT_NAME)
            fixture.history.outcome = CommitOutcome.NotCommitted(IOException("refused"))
            expectRefusal(
                fixture.writer().write(DiscussionCommand.EditComment(ROOT, AUTHOR, ID, COMMENT_ID, "edited")),
                503,
                "discussion_commit_failed",
            )
            fixture.raw(ID, COMMENT_NAME) shouldBe before
            fixture.index.lastPublished shouldBe DiscussionAssembly.assemble(ID, fixture.store.read(ROOT, ID))
        }
    }

    test("a failed undo leaves the residual and still republishes") {
        withWriterFixture { fixture ->
            fixture.install()
            fixture.history.outcome = CommitOutcome.NotCommitted(IOException("refused"))
            val scripted = ScriptedDiscussionStore(fixture.store).apply {
                answer("replace", StoreWrite.Failed("undo failed"), call = 2)
            }
            val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            rootLogger.addAppender(appender)
            try {
                expectRefusal(
                    fixture.writer(store = scripted).write(DiscussionCommand.EditComment(ROOT, AUTHOR, ID, COMMENT_ID, "edited")),
                    503,
                    "discussion_commit_failed",
                )
            } finally {
                rootLogger.detachAppender(appender)
                appender.stop()
            }
            val disk = DiscussionAssembly.assemble(ID, fixture.store.read(ROOT, ID))
            fixture.index.lastPublished shouldBe disk
            fixture.index.publishCount shouldBe 1
            appender.list.any {
                it.level == Level.ERROR && it.throwableProxy?.message?.contains(
                    "store undo did not complete: Failed(cause=undo failed, residual=false)",
                ) == true
            } shouldBe true
        }
    }

    test("an oversized purge under a refused commit restores exact bytes") {
        withWriterFixture { fixture ->
            val oversized = "z".repeat(600 * 1024).toByteArray()
            fixture.install(comments = emptyList())
            val path = fixture.content.resolve(".plainbase/discussions/${ID.value}/${COMMENT_NAME.fileName}")
            Files.write(path, oversized)
            fixture.history.outcome = CommitOutcome.NotCommitted(IOException("refused"))
            expectRefusal(
                fixture.writer().write(DiscussionCommand.PurgeComment(ROOT, AUTHOR, ID, COMMENT_ID)),
                503,
                "discussion_commit_failed",
            )
            Files.readAllBytes(path) shouldBe oversized
            Files.list(path.parent).use { stream -> stream.noneMatch { it.fileName.toString().startsWith(".pbpurge.") } shouldBe true }
        }
    }

    test("discard failure after a committed purge keeps the successful result") {
        withWriterFixture { fixture ->
            fixture.install()
            val scripted = ScriptedDiscussionStore(fixture.store).apply {
                answer("discard", StoreWrite.Refused("discard refused"))
            }
            val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            rootLogger.addAppender(appender)
            try {
                fixture.writer(store = scripted).write(DiscussionCommand.PurgeComment(ROOT, AUTHOR, ID, COMMENT_ID))
                    .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            } finally {
                rootLogger.detachAppender(appender)
                appender.stop()
            }
            fixture.index.publishCount shouldBe 1
            val directory = fixture.content.resolve(".plainbase/discussions/${ID.value}")
            Files.list(directory).use { stream -> stream.anyMatch { it.fileName.toString().startsWith(".pbpurge.") } shouldBe true }
            appender.list.any {
                it.level == Level.WARN && "remains as a tombstone" in it.formattedMessage &&
                    "Refused(reason=discard refused)" in it.formattedMessage
            } shouldBe true
        }
    }

    test("a root lost during a committed purge discard publishes failure and propagates") {
        withWriterFixture { fixture ->
            fixture.install()
            val scripted = ScriptedDiscussionStore(fixture.store).apply {
                throwOn("discard", RootUnavailable(ROOT, UnavailableCause.VANISHED))
            }
            shouldThrow<RootUnavailable> {
                fixture.writer(store = scripted).write(DiscussionCommand.PurgeComment(ROOT, AUTHOR, ID, COMMENT_ID))
            }
            fixture.index.publishFailedCalls shouldBe 1
            fixture.index.publishCount shouldBe 0
        }
    }

    test("a root lost during undo publishes failure and propagates") {
        withWriterFixture { fixture ->
            fixture.install()
            fixture.history.outcome = CommitOutcome.NotCommitted(IOException("refused"))
            val scripted = ScriptedDiscussionStore(fixture.store).apply {
                throwOn("replace", RootUnavailable(ROOT, UnavailableCause.VANISHED), call = 2)
            }
            shouldThrow<RootUnavailable> {
                fixture.writer(store = scripted).write(DiscussionCommand.EditComment(ROOT, AUTHOR, ID, COMMENT_ID, "edited"))
            }
            fixture.index.publishFailedCalls shouldBe 1
            fixture.index.publishCount shouldBe 0
        }
    }

    test("a root lost during the publish reread publishes failure and propagates") {
        withWriterFixture { fixture ->
            val scripted = ScriptedDiscussionStore(fixture.store).apply {
                throwOn("read", RootUnavailable(ROOT, UnavailableCause.VANISHED))
            }
            shouldThrow<RootUnavailable> {
                fixture.writer(store = scripted).write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body"))
            }
            fixture.index.publishFailedCalls shouldBe 1
        }
    }

    test("over the comment bound comment is full and purge restores readability") {
        withWriterFixture { fixture ->
            fixture.install(comments = (1..1_001).map { comment(commentId(it + 3_000), "body $it") })
            expectRefusal(
                fixture.writer().write(DiscussionCommand.AddComment(ROOT, AUTHOR, ID, "body")),
                409,
                "discussion_full",
            )
            val purgeId = commentId(3_001)
            fixture.writer().write(DiscussionCommand.PurgeComment(ROOT, AUTHOR, ID, purgeId))
                .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            val published = fixture.index.lastPublishedById.getValue(ID).shouldBeInstanceOf<DiscussionRead.Ok>()
            published.files.comments.size shouldBe 1_000
        }
    }

    test("published records equal their files at millisecond precision") {
        withWriterFixture { fixture ->
            fixture.writer().write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body"))
                .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
            val published = fixture.index.lastPublishedById.getValue(START_ID).shouldBeInstanceOf<DiscussionRead.Ok>()
            published.files.marker.value.created shouldBe Instant.parse("2026-09-25T12:34:56.789Z")
            val reread = DiscussionCodec.decodeDiscussion(fixture.raw(START_ID, EntryName.Marker))
                .shouldBeInstanceOf<Decoded.Ok<DiscussionRecord>>()
            reread.value shouldBe published.files.marker.value
        }
    }

    test("the writer maps every store and page arm") {
        withWriterFixture { fixture ->
            fixture.install()
            val failedRead = ScriptedDiscussionStore(fixture.store).apply { answer("read", EntriesRead.Failed("read")) }
            expectRefusal(
                fixture.writer(store = failedRead).write(DiscussionCommand.AddComment(ROOT, AUTHOR, ID, "body")),
                503,
                "content_unreadable",
            )
            fixture.index.publishCount shouldBe 0
            val linkedRead = ScriptedDiscussionStore(fixture.store).apply { answer("read", EntriesRead.Symlinked(COMMENT_NAME.fileName)) }
            expectRefusal(
                fixture.writer(store = linkedRead).write(DiscussionCommand.AddComment(ROOT, AUTHOR, ID, "body")),
                409,
                "discussion_unreadable",
            )
            fixture.index.publishCount shouldBe 0
            val thrownRead = ScriptedDiscussionStore(fixture.store).apply {
                throwOn("read", RootUnavailable(ROOT, UnavailableCause.VANISHED))
            }
            shouldThrow<RootUnavailable> {
                fixture.writer(store = thrownRead).write(DiscussionCommand.AddComment(ROOT, AUTHOR, ID, "body"))
            }
            fixture.index.publishCount shouldBe 0
        }

        withWriterFixture { fixture ->
            val purgeRows = listOf(
                EntriesRead.Absent to "discussion_not_found",
                EntriesRead.Present(emptyList(), 0) to "discussion_not_found",
                EntriesRead.Present(listOf(fixture.rawEntry(EntryName.Marker, DiscussionCodec.encodeDiscussion(fixture.marker()))), 0) to
                    "comment_not_found",
                EntriesRead.Symlinked(COMMENT_NAME.fileName) to "discussion_unreadable",
                EntriesRead.Failed("read") to "content_unreadable",
            )
            purgeRows.forEach { (read, code) ->
                val scripted = ScriptedDiscussionStore(fixture.store).apply { answer("read", read) }
                val result = fixture.writer(store = scripted).write(DiscussionCommand.PurgeComment(ROOT, AUTHOR, ID, COMMENT_ID))
                val expectedStatus = when (code) {
                    "content_unreadable" -> 503
                    "comment_not_found", "discussion_not_found" -> 404
                    else -> 409
                }
                expectRefusal(result, expectedStatus, code)
                fixture.index.publishCount shouldBe 0
            }
        }

        listOf(
            ContentRead.ConfirmedAbsent to "page_not_found",
            ContentRead.AbsenceUnknown to "absence_unverified",
        ).forEach { (read, code) ->
            withWriterFixture { fixture ->
                expectRefusal(
                    fixture.writer(pages = DiscussionPageSource { _, _ -> read })
                        .write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body")),
                    if (code == "page_not_found") 404 else 503,
                    code,
                )
                fixture.index.publishCount shouldBe 0
            }
        }

        withWriterFixture { fixture ->
            shouldThrow<RootUnavailable> {
                fixture.writer(pages = DiscussionPageSource { _, _ -> ContentRead.RootDown })
                    .write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body"))
            }
            fixture.index.publishCount shouldBe 0
        }

        withWriterFixture { fixture ->
            listOf(
                StoreWrite.Missing to "stale_discussion", StoreWrite.Refused("refused") to "discussion_path_refused",
                StoreWrite.Failed("failed") to "content_unreadable",
            )
                .forEach { (write, code) ->
                    val scripted = ScriptedDiscussionStore(fixture.store).apply { answer("createFiles", write) }
                    expectRefusal(
                        fixture.writer(store = scripted).write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body")),
                        if (code == "discussion_path_refused") 422 else 409.let { if (code == "content_unreadable") 503 else it },
                        code,
                    )
                    if (write is StoreWrite.Failed || write == StoreWrite.Missing || write is StoreWrite.Refused) {
                        fixture.index.publishCount shouldBe 0
                    }
                }
            val thrown = ScriptedDiscussionStore(fixture.store).apply { throwOn("createFiles", IOException("create")) }
            shouldThrow<IOException> {
                fixture.writer(store = thrown).write(DiscussionCommand.Start(ROOT, AUTHOR, PAGE, fixture.pageAnchor, "body"))
            }
            fixture.index.publishCount shouldBe 0
        }

        withWriterFixture { fixture ->
            fixture.install()
            listOf(
                StoreWrite.Mismatch(null) to "stale_discussion",
                StoreWrite.Missing to "stale_discussion",
                StoreWrite.Failed("failed") to "content_unreadable",
                StoreWrite.Refused("refused") to "discussion_path_refused",
            ).forEach { (write, code) ->
                val scripted = ScriptedDiscussionStore(fixture.store).apply { answer("replace", write) }
                expectRefusal(
                    fixture.writer(store = scripted).write(DiscussionCommand.EditComment(ROOT, AUTHOR, ID, COMMENT_ID, "edit")),
                    when (code) {
                        "content_unreadable" -> 503
                        "discussion_path_refused" -> 422
                        else -> 409
                    },
                    code,
                )
                if (write is StoreWrite.Failed || write == StoreWrite.Missing || write is StoreWrite.Refused ||
                    write is StoreWrite.Mismatch
                ) {
                    fixture.index.publishCount shouldBe 0
                }
            }
            val thrown = ScriptedDiscussionStore(fixture.store).apply { throwOn("replace", IOException("replace")) }
            shouldThrow<IOException> {
                fixture.writer(store = thrown).write(DiscussionCommand.EditComment(ROOT, AUTHOR, ID, COMMENT_ID, "edit"))
            }
            fixture.index.publishCount shouldBe 0
        }

        withWriterFixture { fixture ->
            fixture.install()
            listOf(
                StoreWrite.Mismatch(null) to "stale_discussion",
                StoreWrite.Missing to "stale_discussion",
                StoreWrite.Failed("failed") to "content_unreadable",
                StoreWrite.Refused("refused") to "discussion_path_refused",
            ).forEach { (write, code) ->
                val scripted = ScriptedDiscussionStore(fixture.store).apply { answer("purge", write) }
                expectRefusal(
                    fixture.writer(store = scripted).write(DiscussionCommand.PurgeComment(ROOT, AUTHOR, ID, COMMENT_ID)),
                    when (code) {
                        "content_unreadable" -> 503
                        "discussion_path_refused" -> 422
                        else -> 409
                    },
                    code,
                )
                if (write is StoreWrite.Failed || write == StoreWrite.Missing || write is StoreWrite.Refused ||
                    write is StoreWrite.Mismatch
                ) {
                    fixture.index.publishCount shouldBe 0
                }
            }
            val thrown = ScriptedDiscussionStore(fixture.store).apply { throwOn("purge", IOException("purge")) }
            shouldThrow<IOException> {
                fixture.writer(store = thrown).write(DiscussionCommand.PurgeComment(ROOT, AUTHOR, ID, COMMENT_ID))
            }
            fixture.index.publishCount shouldBe 0
        }
    }
})

private const val ROOT_ID_TEXT = "01900000-0000-7000-8000-000000000001"
private const val COMMENT_ID_TEXT = "01900000-0000-7000-8000-000000000002"
private const val OTHER_COMMENT_ID_TEXT = "01900000-0000-7000-8000-000000000003"
private const val PAGE_ID_TEXT = "01900000-0000-7000-8000-000000000010"

private val ROOT = RootName.PRIMARY
private val ID = DiscussionId.require(ROOT_ID_TEXT)
private val COMMENT_ID = CommentId.require(COMMENT_ID_TEXT)
private val OTHER_COMMENT_ID = CommentId.require(OTHER_COMMENT_ID_TEXT)
private val COMMENT_NAME = EntryName.Comment(COMMENT_ID)
private val PAGE = PageRef(PageId.require(PAGE_ID_TEXT), TreePath.require("guides/setup.md"))
private val START_ID = DiscussionId.require("01900000-0000-7000-8000-000000000101")
private val AUTHOR = Author(Actor(SubjectKey("builtin", "u-1"), "Ada"), AuthorKind.HUMAN)
private val RELIED_COMMENT_AUTHOR = Author(Actor(SubjectKey("builtin", "u-comment"), "Comment author"), AuthorKind.HUMAN)
private val RELIED_STARTER = Author(Actor(SubjectKey("builtin", "u-starter"), "Discussion starter"), AuthorKind.HUMAN)
private val WRITER_NOW = Instant.parse("2026-09-25T12:34:56.789123Z")

private fun comment(
    id: CommentId = COMMENT_ID,
    body: String = "hello\n",
    author: Author = AUTHOR,
    extras: FrontmatterExtras = FrontmatterExtras.NONE,
) = CommentRecord(id, ID, author, WRITER_NOW, null, null, body, extras)

private class FixedDiscussionIds(
    private val discussionId: DiscussionId = START_ID,
    commentIds: List<CommentId> = emptyList(),
) : DiscussionIdProvider {
    private val comments = commentIds.toMutableList()
    private val next = AtomicInteger(200)

    override fun nextDiscussion(): DiscussionId = discussionId
    override fun nextComment(): CommentId = comments.removeFirstOrNull() ?: commentId(next.getAndIncrement())
}

private fun commentId(number: Int): CommentId =
    CommentId.require("01900000-0000-7000-8000-${number.toString().padStart(12, '0')}")

private class WriterHistory : HistoryProvider by NoOpHistoryProvider {
    data class Request(
        val changes: List<HistoryChange>,
        val message: String,
        val author: CommitIdentity,
        val committer: CommitIdentity,
    )

    var outcome: CommitOutcome = CommitOutcome.Committed(null, null)
    var interruptOnCommit = false
    var failOnCommit: Exception? = null
    val requests = mutableListOf<Request>()

    override fun commitChanges(
        changes: List<HistoryChange>,
        message: String,
        author: CommitIdentity,
        committer: CommitIdentity,
    ): CommitOutcome {
        requests += Request(changes, message, author, committer)
        if (interruptOnCommit) Thread.currentThread().interrupt()
        failOnCommit?.let { throw it }
        return outcome
    }
}

private class FakeDiscussionIndex : DiscussionIndex {
    private val seeds = mutableMapOf<Pair<RootName, PageId>, Int>()
    private val latest = mutableMapOf<Pair<RootName, DiscussionId>, DiscussionRead>()
    private val activeCounts = AtomicInteger()
    private val maxCounts = AtomicInteger()
    var failPublish = false
    var publishFailedCalls = 0
        private set
    var publishCount = 0
        private set
    var lastPublished: DiscussionRead? = null
        private set
    val lastPublishedById: MutableMap<DiscussionId, DiscussionRead> = mutableMapOf()
    val maxConcurrentCountReads: Int get() = maxCounts.get()

    fun seed(root: RootName, pageId: PageId, count: Int) {
        seeds[root to pageId] = count
    }

    fun seedPublication(root: RootName, id: DiscussionId, read: DiscussionRead) {
        latest[root to id] = read
    }

    override fun pageDiscussionCount(root: RootName, pageId: PageId): Int {
        val active = activeCounts.incrementAndGet()
        maxCounts.updateAndGet { maxOf(it, active) }
        try {
            Thread.sleep(5)
            val publishedCount = latest.count { (key, read) ->
                key.first == root && read.pageIdOrNull() == pageId
            }
            return seeds[root to pageId].orZero() + publishedCount
        } finally {
            activeCounts.decrementAndGet()
        }
    }

    override fun publish(root: RootName, id: DiscussionId, markerChanged: Boolean, read: () -> EntriesRead) {
        val assembled = DiscussionAssembly.assemble(id, read())
        if (failPublish) throw IllegalStateException("publish failed")
        publishCount++
        lastPublished = assembled
        lastPublishedById[id] = assembled
        latest[root to id] = assembled
    }

    override fun publishFailed(root: RootName, cause: Exception) {
        publishFailedCalls++
    }
}

private fun DiscussionRead.pageIdOrNull() = when (this) {
    is DiscussionRead.Ok -> files.marker.value.page.pageId
    is DiscussionRead.Unreadable -> pageId
    else -> null
}

private fun Int?.orZero(): Int = this ?: 0

private class ScriptedDiscussionStore(private val delegate: DiscussionStore) : DiscussionStore by delegate {
    private data class Key(val operation: String, val call: Int)
    private val calls = mutableMapOf<String, Int>()
    private val answers = mutableMapOf<Key, () -> Any>()
    private val activeReads = AtomicInteger()
    private val maxReads = AtomicInteger()
    val maxConcurrentReads: Int get() = maxReads.get()

    fun answer(operation: String, result: Any, call: Int = 1) {
        answers[Key(operation, call)] = { result }
    }

    fun throwOn(operation: String, failure: Exception, call: Int = 1) {
        answers[Key(operation, call)] = { throw failure }
    }

    private fun <T : Any> call(operation: String, fallback: () -> T): T {
        val number = (calls[operation] ?: 0) + 1
        calls[operation] = number
        @Suppress("UNCHECKED_CAST")
        return (answers[Key(operation, number)]?.invoke() ?: fallback()) as T
    }

    override fun visit(root: RootName, visitor: (DiscussionId, Boolean) -> Unit): CollectionVisit = call("visit") {
        delegate.visit(root, visitor)
    }
    override fun stamp(root: RootName, id: DiscussionId): Stamp? = delegate.stamp(root, id)
    override fun sweepBootResidue(root: RootName, now: Instant, minAge: Duration): List<BootTombstone> =
        call("sweepBootResidue") { delegate.sweepBootResidue(root, now, minAge) }
    override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
        val active = activeReads.incrementAndGet()
        maxReads.updateAndGet { maxOf(it, active) }
        try {
            Thread.sleep(5)
            return call("read") { delegate.read(root, id, only) }
        } finally {
            activeReads.decrementAndGet()
        }
    }
    override fun createFiles(root: RootName, id: DiscussionId, puts: List<EntryPut>): StoreWrite = call("createFiles") {
        delegate.createFiles(root, id, puts)
    }
    override fun replace(root: RootName, entry: EntryPath, version: EntryVersion, bytes: ByteArray): StoreWrite =
        call("replace") { delegate.replace(root, entry, version, bytes) }
    override fun purge(root: RootName, entry: EntryPath, version: EntryVersion): StoreWrite = call("purge") {
        delegate.purge(root, entry, version)
    }
    override fun restore(root: RootName, tombstone: Tombstone): StoreWrite = call("restore") { delegate.restore(root, tombstone) }
    override fun discard(root: RootName, tombstone: Tombstone): StoreWrite = call("discard") { delegate.discard(root, tombstone) }
}

private class CountingDiscussionStore(private val delegate: DiscussionStore) : DiscussionStore by delegate {
    private val activeReads = AtomicInteger()
    private val maxReads = AtomicInteger()
    val maxConcurrentReads: Int get() = maxReads.get()

    override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?): EntriesRead {
        val active = activeReads.incrementAndGet()
        maxReads.updateAndGet { maxOf(it, active) }
        try {
            Thread.sleep(5)
            return delegate.read(root, id, only)
        } finally {
            activeReads.decrementAndGet()
        }
    }
}

private class WriterFixture(val base: Path) {
    val content: Path = Files.createDirectories(base.resolve("content"))
    val store = LocalDiscussionStore(mapOf(ROOT to content))
    val index = FakeDiscussionIndex()
    val history = WriterHistory()
    val monitor = ContentWriteMonitor()
    val pageBytes = "# Setup\n\nRun make.\n".toByteArray()
    private val hasher = CitationFactory()::contentHash
    val pageAnchor: Anchor.Page = Anchor.Page(hasher(pageBytes), null)
    val quoteAnchor: Anchor.Quote = Anchor.Quote(
        hasher(pageBytes),
        null,
        QuoteCapture("x", "", "", 0, 1, 0, 1, AnchorSelection.NARROWED, HeadingPath(emptyList())),
    )

    fun writer(
        store: DiscussionStore = this.store,
        index: DiscussionIndex = this.index,
        pages: DiscussionPageSource = DiscussionPageSource { _, _ -> ContentRead.Bytes(pageBytes.copyOf()) },
        history: WriterHistory = this.history,
        ids: DiscussionIdProvider = FixedDiscussionIds(),
        historyProvider: ((RootName) -> HistoryProvider)? = null,
    ) = DiscussionWriter(
        monitor,
        store,
        pages,
        historyProvider ?: { history },
        index,
        ids,
        object : Clock {
            override fun now(): Instant = WRITER_NOW
        },
        hasher,
    )

    fun marker(
        id: DiscussionId = ID,
        anchor: Anchor = pageAnchor,
        status: DiscussionStatus = DiscussionStatus.OPEN,
        extras: FrontmatterExtras = FrontmatterExtras.NONE,
        starter: Author = AUTHOR,
    ) = DiscussionRecord(id, PAGE, status, WRITER_NOW, starter, null, anchor, null, extras)

    fun install(
        id: DiscussionId = ID,
        anchor: Anchor = pageAnchor,
        status: DiscussionStatus = DiscussionStatus.OPEN,
        comments: List<CommentRecord> = listOf(comment()),
        rawComments: List<Pair<EntryName, ByteArray>> = emptyList(),
        markerExtras: FrontmatterExtras = FrontmatterExtras.NONE,
        starter: Author = AUTHOR,
    ) {
        val puts = comments.map { EntryPut(EntryName.Comment(it.id), DiscussionCodec.encodeComment(it)) } +
            rawComments.map { EntryPut(it.first, it.second) } +
            EntryPut(EntryName.Marker, DiscussionCodec.encodeDiscussion(marker(id, anchor, status, markerExtras, starter)))
        store.createFiles(ROOT, id, puts)
    }

    fun comment(id: DiscussionId, commentId: CommentId): CommentRecord? =
        (store.read(ROOT, id, setOf(EntryName.Comment(commentId))) as? EntriesRead.Present)
            ?.entries?.firstOrNull()?.let { DiscussionCodec.decodeComment(it.take()) }
            ?.let { (it as? Decoded.Ok)?.value }

    fun raw(id: DiscussionId, name: EntryName): ByteArray {
        val read = store.read(ROOT, id, setOf(name)).shouldBeInstanceOf<EntriesRead.Present>()
        return read.entries.first { it.name == name }.take()
    }

    fun rawEntry(name: EntryName, bytes: ByteArray) = RawEntry(name, bytes, EntryVersion("raw"), complete = true)
}

private fun paddedMarker(fixture: WriterFixture, desiredSize: Int, finalNewline: Boolean): ByteArray {
    val encoded = DiscussionCodec.encodeDiscussion(fixture.marker()).decodeToString()
    val prefix = encoded.substringBeforeLast("---\n")
    val field = "x_padding: \""
    val ending = if (finalNewline) "\"\n---\n" else "\"\n---"
    val fixedSize = (prefix + field + ending).encodeToByteArray().size
    val paddingSize = desiredSize - fixedSize
    require(paddingSize >= 0)
    return (prefix + field + "x".repeat(paddingSize) + ending).encodeToByteArray()
}

private inline fun <T> withWriterFixture(block: (WriterFixture) -> T): T {
    val base = Files.createTempDirectory("plainbase-discussion-writer")
    try {
        return block(WriterFixture(base))
    } finally {
        base.toFile().deleteRecursively()
    }
}

private fun expectRefusal(result: DiscussionWriteOutcome, status: Int, code: String) {
    val refusal = result.shouldBeInstanceOf<DiscussionWriteOutcome.Refused>().refusal
    refusal.status shouldBe status
    refusal.code shouldBe code
}
