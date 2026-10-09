package com.plainbase.frameworks.discussion

import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.discussion.Actor
import com.plainbase.domain.discussion.Anchor
import com.plainbase.domain.discussion.Author
import com.plainbase.domain.discussion.AuthorKind
import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.CommentRecord
import com.plainbase.domain.discussion.DiscussionCodec
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionPageSource
import com.plainbase.domain.discussion.DiscussionRecord
import com.plainbase.domain.discussion.DiscussionStatus
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.FrontmatterExtras
import com.plainbase.domain.discussion.PageRef
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.DiscussionGrant
import com.plainbase.domain.principal.SubjectKey
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.service.ContentWriteMonitor
import com.plainbase.domain.service.DiscussionAction
import com.plainbase.domain.service.DiscussionCommand
import com.plainbase.domain.service.DiscussionFullReads
import com.plainbase.domain.service.DiscussionIdProvider
import com.plainbase.domain.service.DiscussionReparser
import com.plainbase.domain.service.DiscussionSyncState
import com.plainbase.domain.service.DiscussionWriteOutcome
import com.plainbase.domain.service.DiscussionWriter
import com.plainbase.domain.service.ReliedOn
import com.plainbase.domain.service.SyncedDiscussionIndex
import com.plainbase.frameworks.config.ConfigLoader
import com.plainbase.frameworks.filesystem.LocalDiscussionStore
import com.plainbase.frameworks.git.GitExecutor
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.runtime.HistoryProviders
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** Lean config/filesystem/JDBC/Git proof; the JVM real-Koin suite proves production module selection. */
@Tag("native")
class DiscussionConfigRestartNativeTest {
    @Test
    fun `disable boot preserves authoritative bytes and history and reenable reparses and writes`() {
        val base = Files.createTempDirectory("pb-discussion-config-restart")
        val root = RootName.PRIMARY
        val extra = RootName.require("extra")
        val id = DiscussionId.require("01900000-0000-7000-8000-000000000091")
        val comment = CommentId.require("01900000-0000-7000-8000-000000000092")
        val time = Instant.parse("2026-09-28T00:00:00Z")
        val clock = object : Clock {
            override fun now(): Instant = time
        }
        val author = Author(Actor(SubjectKey("anonymous", "local"), "Local"), AuthorKind.ANONYMOUS)
        val page = PageRef(PageId.require("01900000-0000-7000-8000-000000000093"), TreePath.require("page.md"))
        val anchor = Anchor.Page("sha256:" + "a".repeat(64), null)
        try {
            val data = Files.createDirectories(base.resolve("data"))
            val paths = listOf(root, extra).associateWith { Files.createDirectories(base.resolve(it.value)) }
            paths.values.forEach { path ->
                Files.writeString(path.resolve("page.md"), "# Page\n\nOrdinary content remains.\n")
                val directory = Files.createDirectories(path.resolve(".plainbase/discussions/${id.value}"))
                Files.write(
                    directory.resolve(EntryName.Marker.fileName),
                    DiscussionCodec.encodeDiscussion(
                        DiscussionRecord(id, page, DiscussionStatus.OPEN, time, author, null, anchor, null, FrontmatterExtras.NONE),
                    ),
                )
                Files.write(
                    directory.resolve(EntryName.Comment(comment).fileName),
                    DiscussionCodec.encodeComment(
                        CommentRecord(comment, id, author, time, null, null, "Preserved reply\n", FrontmatterExtras.NONE),
                    ),
                )
            }
            val git = GitExecutor(paths.getValue(extra), Files.createDirectories(base.resolve("home")))
            assertEquals(0, git.run(listOf("init")).exitCode)
            assertEquals(0, git.run(listOf("add", "--", ".")).exitCode)
            val seedCommit = listOf("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-m", "Seed")
            assertEquals(0, git.run(seedCommit).exitCode)
            val beforeHead = git.run(listOf("rev-parse", "HEAD")).stdoutText
            val before = snapshot(paths.getValue(extra))
            for ((phase, enabled) in listOf(true, false, true).withIndex()) {
                Files.writeString(
                    data.resolve("plainbase.conf"),
                    """
                    roots {
                      docs { path = "${paths.getValue(root)}", history = off }
                      extra { path = "${paths.getValue(extra)}", editable = true, history = off, discussionsEnabled = $enabled }
                    }
                """.trimIndent(),
                )
                val registry = RootRegistry.of(ConfigLoader.fromEnvAndFile(mapOf("DATA_DIR" to data.toString())).roots.list)
                val scoped = registry.roots.filter { it.supportsDiscussions }.map { it.name }
                val sync = DiscussionSyncState(scoped)
                val local = LocalDiscussionStore(paths)
                var disabledCalls = 0
                var disabledHistoryCalls = 0
                val disabledHistory = object : HistoryProvider by NoOpHistoryProvider {
                    override val enabled: Boolean
                        get() {
                            disabledHistoryCalls++
                            throw AssertionError("disabled root reached discussion boot history")
                        }
                }
                fun checkRoot(name: RootName) {
                    if (!enabled && name == extra) {
                        disabledCalls++
                        error("disabled root reached filesystem work")
                    }
                }
                val store = object : DiscussionStore by local {
                    override fun visit(root: RootName, visitor: (DiscussionId, Boolean) -> Unit) =
                        local.visit(root.also(::checkRoot), visitor)
                    override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?) =
                        local.read(root.also(::checkRoot), id, only)
                    override fun stamp(root: RootName, id: DiscussionId) = local.stamp(root.also(::checkRoot), id)
                    override fun sweepBootResidue(root: RootName, now: Instant, minAge: Duration) =
                        local.sweepBootResidue(root.also(::checkRoot), now, minAge)
                }
                DiscussionDb(data.resolve("discussions.db")).use { db ->
                    val rows = JdbcDiscussionRows(db)
                    val reads = DiscussionFullReads(store)
                    val reparser = DiscussionReparser(scoped.toSet(), rows, store, reads)
                    DiscussionBoot(
                        rows, store, reads, reparser, sync,
                        HistoryProviders(
                            paths.keys.associateWith { name ->
                                if (!enabled && name == extra) disabledHistory else NoOpHistoryProvider
                            },
                        ),
                        ContentWriteMonitor(),
                        RootAvailability(clock), registry, clock,
                    ).run()
                    assertEquals("ok", rows.row(root, id)?.state)
                    if (!enabled) {
                        assertEquals(null, rows.row(extra, id))
                        assertEquals(0, disabledCalls)
                        assertEquals(0, disabledHistoryCalls)
                        assertEquals(setOf(root), sync.scopeRoots)
                        assertEquals(before, snapshot(paths.getValue(extra)))
                        assertEquals(beforeHead, git.run(listOf("rev-parse", "HEAD")).stdoutText)
                    } else {
                        assertEquals("ok", rows.row(extra, id)?.state)
                        assertEquals(1, rows.row(extra, id)?.commentCount)
                        if (phase == 2) {
                            val ids = object : DiscussionIdProvider {
                                override fun nextDiscussion(): DiscussionId = error("no new discussion")
                                override fun nextComment(): CommentId = CommentId.require("01900000-0000-7000-8000-000000000094")
                            }
                            val writer =
                                DiscussionWriter(
                                    ContentWriteMonitor(), store,
                                    DiscussionPageSource { _, _ -> ContentRead.Bytes(byteArrayOf()) },
                                    { NoOpHistoryProvider }, SyncedDiscussionIndex(rows, store, reads, sync), ids, clock,
                                )
                            // Compare the concrete outcome: generic assertIs eagerly introspects KClass in the native image.
                            assertEquals(
                                DiscussionWriteOutcome.Done(id, ids.nextComment(), null),
                                writer.write(
                                    DiscussionGrant(extra, DiscussionAction.COMMENT, ReliedOn(), false),
                                    DiscussionCommand.AddComment(extra, author, id, "After re-enable"),
                                ),
                            )
                            assertEquals(2, rows.row(extra, id)?.commentCount)
                        }
                    }
                }
            }
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    private fun snapshot(root: Path): Map<String, List<Byte>> = Files.walk(root).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { root.relativize(it).toString() to Files.readAllBytes(it).toList() }
    }
}
