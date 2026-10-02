package com.plainbase.frameworks.discussion

import com.plainbase.domain.content.ContentPathPolicy
import com.plainbase.domain.discussion.Anchor
import com.plainbase.domain.discussion.AnchorSelection
import com.plainbase.domain.discussion.CommentRecord
import com.plainbase.domain.discussion.DiscussionCodec
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionRecord
import com.plainbase.domain.discussion.DiscussionStatus
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.EntryPut
import com.plainbase.domain.discussion.FrontmatterExtras
import com.plainbase.domain.discussion.HeadingPath
import com.plainbase.domain.discussion.MAX_COMMENT_BYTES
import com.plainbase.domain.discussion.MAX_COMMENT_ENTRIES
import com.plainbase.domain.discussion.MAX_MARKER_BYTES
import com.plainbase.domain.discussion.PageBytes
import com.plainbase.domain.discussion.QuoteCapture
import com.plainbase.domain.discussion.StoreWrite
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.Heading
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.page.RootSection
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.service.AbsenceClassifier
import com.plainbase.domain.service.AnchorMatches
import com.plainbase.domain.service.AnchorPrecompute
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.DetailPage
import com.plainbase.domain.service.DiscussionCommand
import com.plainbase.domain.service.DiscussionWriteOutcome
import com.plainbase.domain.service.ReparseOutcome
import com.plainbase.domain.service.write
import com.plainbase.frameworks.filesystem.LocalContentStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Instant

private const val PAGE_SIZE = 8_388_608
private const val HEADING_COUNT = 10_000
private const val SEEDED_DISCUSSIONS = 200
private const val FULL_READ_BYTES = MAX_MARKER_BYTES + 1 + (MAX_COMMENT_BYTES + 1) * MAX_COMMENT_ENTRIES
private const val FIXED_HEAP_BYTES = 99_461_887L
private const val DETAIL_WINDOW_BYTES = 8_421_478L
private const val LIST_ROWS_BYTES = 1_840_000L

/** Opt-in local profile measurement for one writer, one detail read, one list miss, and two reparses. */
class DiscussionHeapProbeTest : FunSpec({
    test("maximum-size indexed detail retains one selected comment")
        .config(enabled = System.getenv("PLAINBASE_DISCUSSION_HEAP_PROBE") == "1") {
            val reads = AtomicInteger()
            val world = DiscussionWorld(readEntryBytes = { path, _ ->
                reads.incrementAndGet()
                Files.readAllBytes(path)
            })
            try {
                val id = seedDerivationCorpus(world, DiscussionWorld.discussionId(950), 0, exactMaximum = true)
                world.reparser().reparseOne(DiscussionWorld.ROOT, id).shouldBeInstanceOf<ReparseOutcome.Applied>()
                reads.set(0)
                val detail = world.reads.detail(
                    DiscussionWorld.ROOT, id,
                    afterComment = DiscussionWorld.commentId(20_998), limit = 1,
                )
                    .shouldBeInstanceOf<DetailPage.Content>()
                detail.read.shouldBeInstanceOf<com.plainbase.domain.discussion.DiscussionRead.Ok>()
                    .files.comments.map { it.value.id } shouldBe listOf(DiscussionWorld.commentId(20_999))
                detail.summary?.commentCount shouldBe MAX_COMMENT_ENTRIES
                reads.get() shouldBe 2
            } finally {
                world.close()
            }
        }

    test("local profile heap probe")
        .config(enabled = System.getenv("PLAINBASE_DISCUSSION_HEAP_PROBE") == "1") {
            val pageBytes = pageBytesWithHeadings()
            val world = DiscussionWorld(pageBytes = pageBytes)
            val pool = Executors.newFixedThreadPool(6)
            var precompute: AnchorPrecompute? = null
            var sampler: Thread? = null
            try {
                val pageFile = world.rootPath.resolve(world.page.path.value)
                Files.createDirectories(pageFile.parent)
                Files.write(pageFile, pageBytes)

                val headings = List(HEADING_COUNT) { index ->
                    Heading(
                        id = "current-$index",
                        level = 2,
                        text = "Current section $index",
                    )
                }
                val indexedPage = indexedPage(world, pageBytes, headings)
                val snapshot = PageIndex(
                    listOf(RootSection(DiscussionWorld.ROOT, listOf(indexedPage), emptyList(), emptySet())),
                )
                val content = LocalContentStore(world.rootPath).also { it.scan() }
                val absence = AbsenceClassifier(
                    idMap = mockk<IdMapRepository>(relaxed = true),
                    policies = mapOf(DiscussionWorld.ROOT to ContentPathPolicy.ALL),
                )
                repeat(SEEDED_DISCUSSIONS) { index ->
                    val id = DiscussionWorld.discussionId(100 + index)
                    val record = marker(world, id, index)
                    val write = world.store.createFiles(
                        DiscussionWorld.ROOT,
                        id,
                        listOf(EntryPut(EntryName.Marker, DiscussionCodec.encodeDiscussion(record))),
                    )
                    write.shouldBeInstanceOf<StoreWrite.Written>()
                    world.index.publish(DiscussionWorld.ROOT, id, markerChanged = false) {
                        world.store.read(DiscussionWorld.ROOT, id)
                    }
                }

                val fullReadId = DiscussionWorld.discussionId(800)
                seedAdversarialFullRead(world, fullReadId)
                val reparseIds = listOf(
                    seedDerivationCorpus(world, DiscussionWorld.discussionId(900), 0),
                    seedDerivationCorpus(world, DiscussionWorld.discussionId(901), 1),
                )
                val reparser = world.reparser()
                val anchorMatches = AnchorMatches(world.rows, world.store, world.fullReads, world.sync)
                val scheduledPrecompute = AnchorPrecompute(
                    rows = world.rows,
                    discussions = world.store,
                    contents = { content },
                    fullReads = world.fullReads,
                    absence = absence,
                    sync = world.sync,
                    availability = world.availability,
                    current = { snapshot },
                    alarm = world.alarm,
                )
                precompute = scheduledPrecompute
                val ready = CountDownLatch(6)
                val start = CountDownLatch(1)
                val tasks = listOf(
                    pool.submit {
                        ready.countDown()
                        check(start.await(5, TimeUnit.SECONDS))
                        world.writer().write(
                            DiscussionCommand.Start(
                                DiscussionWorld.ROOT,
                                world.actor,
                                world.otherPage,
                                world.otherPageAnchor,
                                "heap probe writer start\n",
                            ),
                        ).shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
                    },
                    pool.submit {
                        ready.countDown()
                        check(start.await(5, TimeUnit.SECONDS))
                        val summaries = world.reads.pageDiscussions(
                            DiscussionWorld.ROOT,
                            indexedPage,
                            snapshot,
                            limit = 200,
                        )
                        summaries.discussions.size shouldBe SEEDED_DISCUSSIONS
                        val pageRows = world.rows.onPageRaw(
                            DiscussionWorld.ROOT,
                            world.page.pageId,
                            world.page.path,
                            after = null,
                            limit = 400,
                        )
                        val result = anchorMatches.forPage(
                            DiscussionWorld.ROOT,
                            world.page.pageId,
                            indexedPage.contentHash,
                            pageRows,
                        ) {
                            PageBytes.of(pageBytes.copyOf(), headings)
                        }
                        result.size shouldBe SEEDED_DISCUSSIONS
                    },
                    pool.submit {
                        ready.countDown()
                        check(start.await(5, TimeUnit.SECONDS))
                        scheduledPrecompute.reindexed(DiscussionWorld.ROOT, indexedPage)
                        world.alarm.runAll()
                    },
                    pool.submit(
                        Callable {
                        ready.countDown()
                        check(start.await(5, TimeUnit.SECONDS))
                        world.reads.detail(DiscussionWorld.ROOT, fullReadId)
                            .shouldBeInstanceOf<DetailPage.Content>()
                    },
                    ),
                ) + reparseIds.map { id ->
                    pool.submit {
                        ready.countDown()
                        check(start.await(5, TimeUnit.SECONDS))
                        reparser.reparseOne(DiscussionWorld.ROOT, id)
                            .shouldBeInstanceOf<ReparseOutcome.Applied>().state shouldBe "ok"
                        world.rows.row(DiscussionWorld.ROOT, id)?.commentCount shouldBe MAX_COMMENT_ENTRIES
                    }
                }
                check(ready.await(5, TimeUnit.SECONDS))

                System.gc()
                Thread.sleep(100)
                val runtime = Runtime.getRuntime()
                val baseline = usedHeap(runtime)
                val peak = AtomicLong(baseline)
                val sampling = CountDownLatch(1)
                sampler = Thread({
                    sampling.countDown()
                    // The bound is retained heap, so sample after collection while operations hold their live data.
                    while (!Thread.currentThread().isInterrupted) {
                        System.gc()
                        peak.accumulateAndGet(usedHeap(runtime), ::maxOf)
                        try {
                            Thread.sleep(50)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }, "discussion-heap-sampler").apply {
                    isDaemon = true
                    start()
                }
                sampling.await(5, TimeUnit.SECONDS)
                start.countDown()
                tasks.forEach { it.get(120, TimeUnit.SECONDS) }
                sampler.interrupt()
                sampler.join(5_000)

                val increment = (peak.get() - baseline).coerceAtLeast(0)
                val formula = FIXED_HEAP_BYTES + 3L * PAGE_SIZE + DETAIL_WINDOW_BYTES + LIST_ROWS_BYTES
                println("discussion-heap-probe: peak=$increment formula=$formula")
                (increment <= formula) shouldBe true
            } finally {
                sampler?.interrupt()
                sampler?.join(5_000)
                precompute?.close()
                pool.shutdownNow()
                pool.awaitTermination(5, TimeUnit.SECONDS)
                world.close()
            }
        }
})

private fun pageBytesWithHeadings(): ByteArray {
    val headingText = buildString {
        repeat(HEADING_COUNT) { index ->
            append("## Current section ").append(index).append("\n\n")
        }
    }.encodeToByteArray()
    check(headingText.size < PAGE_SIZE)
    return ByteArray(PAGE_SIZE) { 'x'.code.toByte() }.also { raw ->
        headingText.copyInto(raw)
    }
}

private fun indexedPage(
    world: DiscussionWorld,
    bytes: ByteArray,
    headings: List<Heading>,
): IndexedPage = IndexedPage(
    id = world.page.pageId,
    root = DiscussionWorld.ROOT,
    path = world.page.path,
    slug = "discussion",
    urlPath = world.page.path,
    title = "Discussion page",
    frontmatter = Frontmatter(emptyMap()),
    materialized = true,
    markdown = bytes.decodeToString(),
    contentHash = CitationFactory().contentHash(bytes),
    commit = null,
    html = "",
    headings = headings,
    links = emptyList(),
    sections = emptyList(),
)

private fun seedDerivationCorpus(
    world: DiscussionWorld,
    id: DiscussionId,
    corpus: Int,
    exactMaximum: Boolean = false,
): DiscussionId {
    val directory = Files.createDirectories(world.rootPath.resolve(".plainbase/discussions/${id.value}"))
    val markerBytes = DiscussionCodec.encodeDiscussion(marker(world, id, corpus))
    Files.write(directory.resolve(EntryName.Marker.fileName), markerBytes)
    val bodyTail = "x".repeat(64 * 1024)
    repeat(MAX_COMMENT_ENTRIES) { index ->
        val commentId = DiscussionWorld.commentId(20_000 + corpus * MAX_COMMENT_ENTRIES + index)
        val record = CommentRecord(
                id = commentId,
                discussionId = id,
                author = world.actor,
                created = Instant.parse("2026-09-26T10:00:00Z"),
                editedAt = null,
                retraction = null,
                body = "",
                extras = FrontmatterExtras.NONE,
            )
        val body = if (exactMaximum) {
            "x".repeat(MAX_COMMENT_BYTES - DiscussionCodec.encodeComment(record).size)
        } else {
            "corpus-$corpus-comment-$index\n$bodyTail"
        }
        val bytes = DiscussionCodec.encodeComment(record.copy(body = body))
        if (exactMaximum) bytes.size shouldBe MAX_COMMENT_BYTES
        check(bytes.size <= MAX_COMMENT_BYTES)
        Files.write(directory.resolve(EntryName.Comment(commentId).fileName), bytes)
    }
    return id
}

private fun marker(world: DiscussionWorld, id: DiscussionId, index: Int) =
    DiscussionRecord(
        id = id,
        page = world.page,
        status = DiscussionStatus.OPEN,
        created = Instant.parse("2026-09-26T10:00:00.000Z"),
        startedBy = world.actor,
        statusChange = null,
        anchor = Anchor.Quote(
            contentHash = "sha256:${"0".repeat(64)}",
            commit = null,
            capture = QuoteCapture(
                quote = "retired quote $index",
                prefix = "",
                suffix = "",
                byteStart = 0,
                byteEnd = "retired quote $index".encodeToByteArray().size.toLong(),
                bodyStart = 0,
                line = 1,
                selection = AnchorSelection.NARROWED,
                headingPath = HeadingPath(listOf(HeadingPath.Entry(1, "Old section $index"))),
            ),
        ),
        reattachment = null,
        extras = FrontmatterExtras.NONE,
    )

private fun seedAdversarialFullRead(world: DiscussionWorld, id: DiscussionId) {
    val directory = Files.createDirectories(world.rootPath.resolve(".plainbase/discussions/${id.value}"))
    Files.write(directory.resolve(EntryName.Marker.fileName), ByteArray(MAX_MARKER_BYTES + 1))
    val commentBytes = ByteArray(MAX_COMMENT_BYTES + 1)
    repeat(MAX_COMMENT_ENTRIES) { index ->
        Files.write(directory.resolve(EntryName.Comment(DiscussionWorld.commentId(10_000 + index)).fileName), commentBytes)
    }
    val bytesOnDisk = Files.list(directory).use { paths ->
        paths.mapToLong { path -> Files.size(path) }.sum()
    }
    bytesOnDisk shouldBe FULL_READ_BYTES.toLong()
}

private fun usedHeap(runtime: Runtime): Long = runtime.totalMemory() - runtime.freeMemory()
