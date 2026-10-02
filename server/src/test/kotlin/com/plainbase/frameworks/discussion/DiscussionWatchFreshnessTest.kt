package com.plainbase.frameworks.discussion

import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionRead
import com.plainbase.domain.discussion.DiscussionStatus
import com.plainbase.domain.discussion.DiscussionWatchSink
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.page.RootSection
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.DetailPage
import com.plainbase.domain.service.DiscussionCommand
import com.plainbase.domain.service.DiscussionFacts
import com.plainbase.domain.service.DiscussionReparseExecutor
import com.plainbase.domain.service.DiscussionWriteOutcome
import com.plainbase.domain.service.RebuildScheduler
import com.plainbase.domain.service.write
import com.plainbase.frameworks.filesystem.DiscussionWatcher
import com.plainbase.frameworks.scheduling.ExecutorAlarm
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardWatchEventKinds
import java.nio.file.attribute.FileTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DiscussionWatchFreshnessTest : FunSpec({
    test("a folder moved into the collection appears and a deleted one disappears within 5 s")
        .config(enabled = LINUX_ONLY) {
            withDiscussionWorld { world ->
                val staged = stageDiscussion(world)
                withFreshnessHarness(world) { harness ->
                    awaitMissing(harness.world, staged.id)

                    val live = discussionFolder(harness.world, staged.id)
                    Files.move(staged.path, live)
                    awaitState(harness.world, staged.id, "ok")

                    live.toFile().deleteRecursively()
                    awaitMissing(harness.world, staged.id)
                }
            }
        }

    test("a folder made with mkdir then written converges within 5 s")
        .config(enabled = LINUX_ONLY) {
            withDiscussionWorld { world ->
                val staged = stageDiscussion(world)
                withFreshnessHarness(world) { harness ->
                    awaitMissing(harness.world, staged.id)
                    val live = discussionFolder(harness.world, staged.id)
                    Files.createDirectory(live)
                    awaitState(harness.world, staged.id, "incomplete")

                    copyEntries(staged.path, live)
                    awaitState(harness.world, staged.id, "ok")
                    harness.world.rows.row(DiscussionWorld.ROOT, staged.id)?.commentCount shouldBe 1
                }
            }
        }

    test("a folder copied marker first then comments later converges within 5 s")
        .config(enabled = LINUX_ONLY) {
            withDiscussionWorld { world ->
                val staged = stageDiscussion(world, commentCount = 3)
                withFreshnessHarness(world) { harness ->
                    awaitMissing(harness.world, staged.id)
                    val live = discussionFolder(harness.world, staged.id)
                    Files.createDirectory(live)
                    copyEntry(staged.path, live, EntryName.Marker.fileName)
                    Thread.sleep(2_000)
                    commentFiles(staged.path).forEach { source ->
                        copyEntry(staged.path, live, source.fileName.toString())
                    }

                    awaitCondition { harness.world.rows.row(DiscussionWorld.ROOT, staged.id)?.commentCount == 3 }
                    harness.world.rows.row(DiscussionWorld.ROOT, staged.id)?.state shouldBe "ok"
                }
            }
        }

    test("a recreated discussions folder is watched again before its scan")
        .config(enabled = LINUX_ONLY) {
            val events = CopyOnWriteArrayList<String>()
            withDiscussionWorld { world ->
                val staged = stageDiscussion(world)
                withFreshnessHarness(world, events = events) { harness ->
                    awaitMissing(harness.world, staged.id)
                    val collection = discussionCollection(harness.world)
                    val displaced = collection.resolveSibling("discussions-displaced")
                    events.clear()
                    Files.move(collection, displaced)
                    awaitCondition { "scan" in events }

                    events.clear()
                    Files.createDirectory(collection)
                    val collectionRegistration = "register:${collection.toAbsolutePath().normalize()}"
                    awaitCondition { collectionRegistration in events && "scan" in events }
                    val registerAt = events.indexOf(collectionRegistration)
                    val scanAt = events.indexOfLast { it == "scan" }
                    registerAt shouldBeLessThan scanAt

                    Files.move(staged.path, collection.resolve(staged.id.value))
                    awaitState(harness.world, staged.id, "ok")
                }
            }
        }

    test("an in-place edit inside a folder converges within one rescan period") {
        withDiscussionWorld { world ->
            val id = world.startDiscussion()
            val oldTime = FileTime.fromMillis(1_600_000_000_000L)
            Files.list(discussionFolder(world, id)).use { entries ->
                entries.forEach { Files.setLastModifiedTime(it, oldTime) }
            }
            Files.setLastModifiedTime(discussionFolder(world, id), oldTime)
            world.reparser().settle(DiscussionWorld.ROOT, id)
            val originalStamp = requireNotNull(world.rows.row(DiscussionWorld.ROOT, id)?.stamp)
            originalStamp.racy shouldBe false
            withFreshnessHarness(
                world,
                rescanInterval = 200.milliseconds,
                ignoreDiscussionChanges = true,
            ) { harness ->
                awaitState(world, id, "ok")
                val beforeTick = harness.watcher.tickCountForTest()
                val file = commentFiles(discussionFolder(world, id)).single()
                Files.writeString(file, Files.readString(file) + "\nexternal edit\n")
                Files.setLastModifiedTime(file, FileTime.fromMillis(1_600_000_001_000L))
                val editedStamp = requireNotNull(world.store.stamp(DiscussionWorld.ROOT, id))
                editedStamp.value shouldNotBe originalStamp.value
                editedStamp.racy shouldBe false

                awaitCondition { harness.watcher.tickCountForTest() > beforeTick }
                awaitCondition { world.rows.row(DiscussionWorld.ROOT, id)?.stamp?.value == editedStamp.value }
            }
        }
    }

    test("a rename out of the collection removes the row")
        .config(enabled = LINUX_ONLY) {
            withDiscussionWorld { world ->
                val id = world.startDiscussion()
                withFreshnessHarness(world) { harness ->
                    awaitState(harness.world, id, "ok")
                    val live = discussionFolder(harness.world, id)
                    Files.move(live, live.resolveSibling("departed-${id.value}"))
                    awaitMissing(harness.world, id)
                }
            }
        }

    test("twenty rounds of create comment and resolve stay fresh") {
        withDiscussionWorld { world ->
            val id = world.startDiscussion()
            withFreshnessHarness(
                world,
                alarm = world.alarm,
                ignoreDiscussionChanges = true,
                rescanInterval = 1.hours,
            ) { harness ->
                world.alarm.runAll(delayMillis = 0)
                var count = 1
                repeat(20) { index ->
                    harness.executor.sinkFor(DiscussionWorld.ROOT).collectionChanged()
                    world.alarm.pendingImmediateCount shouldBe 1

                    if (index > 0) {
                        setStatus(world, id, DiscussionStatus.OPEN)
                    }
                    count += 1
                    world.addComment(id, "fresh comment round $count\n")
                        .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
                    assertFreshReads(world, id, count, DiscussionStatus.OPEN)

                    setStatus(world, id, DiscussionStatus.RESOLVED)
                    assertFreshReads(world, id, count, DiscussionStatus.RESOLVED)
                    world.alarm.pendingImmediateCount shouldBe 1

                    world.alarm.runAll(delayMillis = 0)
                    assertFreshReads(world, id, count, DiscussionStatus.RESOLVED)
                }
            }
        }
    }

    test("a malformed file becomes unreadable") {
        withDiscussionWorld { world ->
            val id = world.startDiscussion()
            val file = commentFiles(discussionFolder(world, id)).single()
            Files.writeString(file, "not a discussion comment")
            world.reparser().settle(DiscussionWorld.ROOT, id)

            val row = requireNotNull(world.rows.row(DiscussionWorld.ROOT, id))
            row.state shouldBe "unreadable"
            row.reason shouldBe "bad_value:${file.fileName}"
            world.reads.facts(DiscussionWorld.ROOT, id) shouldBe DiscussionFacts.Known(
                state = "unreadable",
                pageId = DiscussionWorld.PAGE_ID,
                status = null,
                starterKey = null,
                authorKey = null,
            )
            world.reads.rootDiscussions(DiscussionWorld.ROOT).discussions.map { it.id } shouldContainExactly listOf(id)
        }
    }

    test("incomplete is listed on the root and never on the page") {
        withDiscussionWorld { world ->
            val incomplete = DiscussionWorld.discussionId(900)
            Files.createDirectories(discussionFolder(world, incomplete))
            world.reparser().settle(DiscussionWorld.ROOT, incomplete)

            val rootRows = world.reads.rootDiscussions(DiscussionWorld.ROOT).discussions
            rootRows.single().id shouldBe incomplete
            rootRows.single().state shouldBe "incomplete"
            world.reads.facts(DiscussionWorld.ROOT, incomplete) shouldBe DiscussionFacts.Known(
                state = "incomplete",
                pageId = null,
                status = null,
                starterKey = null,
                authorKey = null,
            )
            val page = indexedPage(world)
            val snapshot = PageIndex(listOf(RootSection(DiscussionWorld.ROOT, listOf(page), emptyList(), emptySet())))
            world.reads.pageDiscussions(DiscussionWorld.ROOT, page, snapshot).discussions shouldBe emptyList()
        }
    }
})

private val LINUX_ONLY = System.getProperty("os.name").orEmpty().startsWith("Linux", ignoreCase = true)

private data class StagedDiscussion(val id: DiscussionId, val path: Path)

private data class FreshnessHarness(
    val world: DiscussionWorld,
    val watcher: DiscussionWatcher,
    val executor: DiscussionReparseExecutor,
)

private fun withFreshnessHarness(
    world: DiscussionWorld,
    events: MutableList<String>? = null,
    alarm: RebuildScheduler.Alarm? = null,
    ignoreDiscussionChanges: Boolean = false,
    rescanInterval: Duration = 60.seconds,
    checkDelayMillis: Long = 3_000,
    block: (FreshnessHarness) -> Unit,
) {
    val activeAlarm = alarm ?: ExecutorAlarm("plainbase-discussion-freshness")
    val executor = DiscussionReparseExecutor(
        reparser = world.reparser(),
        sync = world.sync,
        availability = world.availability,
        alarm = activeAlarm,
        checkDelayMillis = checkDelayMillis,
    )
    executor.start()
    val watcher = DiscussionWatcher.start(
        roots = listOf(world.rootPath),
        sinkFor = {
            val sink = executor.sinkFor(DiscussionWorld.ROOT)
            object : DiscussionWatchSink {
                override fun discussionChanged(id: DiscussionId) =
                    if (ignoreDiscussionChanges) Unit else sink.discussionChanged(id)

                override fun collectionChanged() {
                    events?.add("scan")
                    sink.collectionChanged()
                }
            }
        },
        registerDirectory = { directory, service ->
            events?.add("register:${directory.toAbsolutePath().normalize()}")
            directory.register(
                service,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY,
            )
        },
        rescanInterval = rescanInterval,
    )
    try {
        block(FreshnessHarness(world, watcher, executor))
    } finally {
        watcher.close()
        executor.close()
    }
}

private fun setStatus(world: DiscussionWorld, id: DiscussionId, status: DiscussionStatus) {
    world.writer().write(DiscussionCommand.SetStatus(DiscussionWorld.ROOT, world.actor, id, status))
        .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
}

private fun assertFreshReads(world: DiscussionWorld, id: DiscussionId, count: Int, status: DiscussionStatus) {
    val wireStatus = status.wire
    val rootRow = world.reads.rootDiscussions(DiscussionWorld.ROOT).discussions.single { it.id == id }
    rootRow.commentCount shouldBe count
    rootRow.status shouldBe wireStatus
    world.reads.facts(DiscussionWorld.ROOT, id) shouldBe DiscussionFacts.Known(
        state = "ok",
        pageId = DiscussionWorld.PAGE_ID,
        status = wireStatus,
        starterKey = rootRow.starterKey,
        authorKey = null,
    )
    val detail = world.reads.detail(DiscussionWorld.ROOT, id).shouldBeInstanceOf<DetailPage.Content>()
    val read = detail.read.shouldBeInstanceOf<DiscussionRead.Ok>()
    read.files.comments shouldHaveSize count
    read.files.marker.value.status shouldBe status
}

private fun withDiscussionWorld(block: (DiscussionWorld) -> Unit) {
    val world = DiscussionWorld()
    try {
        block(world)
    } finally {
        world.close()
    }
}

private fun stageDiscussion(world: DiscussionWorld, commentCount: Int = 1): StagedDiscussion {
    val id = world.startDiscussion()
    repeat(commentCount - 1) { index ->
        world.addComment(id, "staged comment ${index + 1}\n")
            .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
    }
    val source = discussionFolder(world, id)
    val staged = world.rootPath.resolve("staged-${id.value}")
    Files.move(source, staged)
    return StagedDiscussion(id, staged)
}

private fun discussionCollection(world: DiscussionWorld): Path =
    world.rootPath.resolve(".plainbase/discussions")

private fun discussionFolder(world: DiscussionWorld, id: DiscussionId): Path =
    discussionCollection(world).resolve(id.value)

private fun awaitState(world: DiscussionWorld, id: DiscussionId, state: String) {
    awaitCondition { world.rows.row(DiscussionWorld.ROOT, id)?.state == state }
}

private fun awaitMissing(world: DiscussionWorld, id: DiscussionId) {
    awaitCondition { world.rows.row(DiscussionWorld.ROOT, id) == null }
}

private fun awaitCondition(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (System.nanoTime() < deadline) {
        if (condition()) return
        Thread.sleep(10)
    }
    condition() shouldBe true
}

private fun copyEntries(source: Path, target: Path) {
    val files = Files.list(source).use { it.toList() }
    files.forEach { file -> copyEntry(source, target, file.fileName.toString()) }
}

private fun copyEntry(source: Path, target: Path, name: String) {
    val sourceFile = source.resolve(name)
    val targetFile = target.resolve(name)
    Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
    Files.setLastModifiedTime(targetFile, Files.getLastModifiedTime(sourceFile))
}

private fun commentFiles(directory: Path): List<Path> {
    val files = Files.list(directory).use { it.toList() }
    return files.filter { it.fileName.toString() != EntryName.Marker.fileName }.sortedBy { it.fileName.toString() }
}

private fun indexedPage(world: DiscussionWorld): IndexedPage = IndexedPage(
    id = world.page.pageId,
    root = DiscussionWorld.ROOT,
    path = world.page.path,
    slug = "discussion",
    urlPath = world.page.path,
    title = "Discussion page",
    frontmatter = Frontmatter(emptyMap()),
    materialized = true,
    markdown = world.pageBytes.decodeToString(),
    contentHash = CitationFactory().contentHash(world.pageBytes),
    commit = null,
    html = "",
    headings = emptyList(),
    links = emptyList(),
    sections = emptyList(),
)
