package com.plainbase.frameworks.discussion

import com.plainbase.domain.content.ContentPathPolicy
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.StoreRead
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.discussion.Anchor
import com.plainbase.domain.discussion.AnchorMatch
import com.plainbase.domain.discussion.AnchorSelection
import com.plainbase.domain.discussion.Decoded
import com.plainbase.domain.discussion.DiscussionCodec
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionRowData
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.discussion.EntriesRead
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.EntryPath
import com.plainbase.domain.discussion.IdentityDigest
import com.plainbase.domain.discussion.MatchRange
import com.plainbase.domain.discussion.Placement
import com.plainbase.domain.discussion.QuoteCapture
import com.plainbase.domain.discussion.RowUpdate
import com.plainbase.domain.discussion.StoreWrite
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.page.RootSection
import com.plainbase.domain.principal.SubjectKey
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.AbsenceClassifier
import com.plainbase.domain.service.AnchorMatches
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.DiscussionFullReads
import com.plainbase.domain.service.DiscussionPageResolution
import com.plainbase.domain.service.DiscussionPageResolver
import com.plainbase.domain.service.DiscussionReads
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.ktor.DiscussionReadProjection
import com.plainbase.frameworks.protocol.DiscussionListDto
import com.plainbase.frameworks.protocol.RestJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class DiscussionWireGoldenTest : FunSpec({
    test("page-level list and detail preserve null wire fields and raw Markdown safety") {
        DiscussionWorld().use { world ->
            val markdown = "<script>alert(1)</script>\n\n[guide](../other.md)\n"
            val id = world.startDiscussion(body = markdown)
            val page = indexedPage(world)
            val linkedPath = TreePath.require("other.md")
            val linked = page.copy(id = world.otherPage.pageId, path = linkedPath, urlPath = linkedPath)
            val snapshot = PageIndex(listOf(RootSection(DiscussionWorld.ROOT, listOf(page, linked), emptyList(), emptySet())))
            val absence = AbsenceClassifier(
                mockk<IdMapRepository>(relaxed = true),
                mapOf(DiscussionWorld.ROOT to ContentPathPolicy.ALL),
            )
            val projector = DiscussionReadProjection(
                world.reads,
                DiscussionPageResolver(world.sync, world.availability, absence),
                AnchorMatches(world.rows, world.store, world.fullReads, world.sync), absence,
                { error("page-level matching must not request page bytes") },
            )

            val listed = projector.pageList(DiscussionWorld.ROOT, page, snapshot, null, 7)
            val key = IdentityDigest.of(SubjectKey("discussion-world", "writer"))
            val expected = """
                {"discussions":[{
                  "id":"${id.value}",
                  "page":{"id":"${world.page.pageId.value}","path":"${world.page.path.value}","resolution":"by_id"},
                  "status":"open","state":"page_level","reason":null,"range":null,"candidates":null,
                  "placement":null,"quote":null,"comment_count":1,
                  "starter":{"key":"$key","kind":"human","label":"World writer"},
                  "created":"2026-09-26T10:00:00.000Z","updated":"2026-09-26T10:00:00.000Z"
                }],"next":null,"discussions_available":true,"reason":null}
            """.trimIndent()
            Json.parseToJsonElement(RestJson.encodeToString(DiscussionListDto.serializer(), listed)) shouldBe
                Json.parseToJsonElement(expected)

            val detail = projector.detail(DiscussionWorld.ROOT, id, snapshot, null, 1)
            detail.comments.single().markdown shouldBe markdown
            detail.comments.single().html.shouldNotContain("<script>")
            detail.comments.single().html.shouldContain("&lt;script&gt;")
            detail.comments.single().html.shouldContain("href=\"/docs/other.md\"")
            detail.discussion?.get("anchor") shouldBe Json.parseToJsonElement(
                """{"kind":"page","content_hash":"${world.pageAnchor.contentHash}","commit":null}""",
            )
        }
    }

    test("quote matching reads a page once on miss and zero times on cache hit") {
        DiscussionWorld().use { world ->
            val start = world.pageBytes.decodeToString().indexOf("stable")
            val quote = QuoteCapture.at(world.pageBytes, start, start + 6, emptyList(), AnchorSelection.NARROWED)
            val ids = List(2) { world.startDiscussion() }
            ids.forEach { id ->
                val read = world.store.read(DiscussionWorld.ROOT, id, setOf(EntryName.Marker))
                val raw = (read as EntriesRead.Present).entries.single()
                val record = (DiscussionCodec.decodeDiscussion(raw.take()) as Decoded.Ok).value
                world.store.replace(
                    DiscussionWorld.ROOT, EntryPath(id, EntryName.Marker), raw.version,
                    DiscussionCodec.encodeDiscussion(record.copy(anchor = Anchor.Quote(world.pageAnchor.contentHash, null, quote))),
                )
                    .shouldBeInstanceOf<StoreWrite.Written>()
                world.index.publish(DiscussionWorld.ROOT, id, markerChanged = true) {
                    world.store.read(DiscussionWorld.ROOT, id)
                }
            }
            val file = world.rootPath.resolve(world.page.path.value)
            Files.createDirectories(file.parent)
            Files.write(file, world.pageBytes)
            val content = LocalContentStore(world.rootPath).also { it.scan() }
            val calls = AtomicInteger()
            val counted = object : ContentStore by content {
                override fun readClassified(path: TreePath): StoreRead {
                    calls.incrementAndGet()
                    return content.readClassified(path)
                }
            }
            val absence = AbsenceClassifier(
                mockk<IdMapRepository>(relaxed = true),
                mapOf(DiscussionWorld.ROOT to ContentPathPolicy.ALL),
            )
            val projector = DiscussionReadProjection(
                world.reads,
                DiscussionPageResolver(world.sync, world.availability, absence),
                AnchorMatches(world.rows, world.store, world.fullReads, world.sync), absence, { counted },
            )
            val page = indexedPage(world)
            val snapshot = PageIndex(listOf(RootSection(DiscussionWorld.ROOT, listOf(page), emptyList(), emptySet())))

            projector.pageList(DiscussionWorld.ROOT, page, snapshot, null, 2).discussions.map { it.state } shouldBe
                listOf("exact", "exact")
            calls.get() shouldBe 1
            projector.pageList(DiscussionWorld.ROOT, page, snapshot, null, 2).discussions.map { it.state } shouldBe
                listOf("exact", "exact")
            calls.get() shouldBe 1
        }
    }

    test("incomplete and failed rows retain required null fields in root listing") {
        DiscussionWorld().use { world ->
            val incomplete = DiscussionWorld.discussionId(10)
            val failed = DiscussionWorld.discussionId(11)
            world.rows.writing {
                apply(
                    DiscussionWorld.ROOT, incomplete,
                    RowUpdate.Upsert(DiscussionRowData("incomplete", commentCount = 3), emptyList()), null, false,
                )
                apply(
                    DiscussionWorld.ROOT, failed,
                    RowUpdate.Upsert(
                        DiscussionRowData(
                            "ok",
                        pageId = world.page.pageId, pagePath = world.page.path, commentCount = 2,
                        ),
                        emptyList(),
                    ),
                    null, false,
                )
                apply(DiscussionWorld.ROOT, failed, RowUpdate.Failed("Access denied: /private/content/discussion.md"), null, false)
            }
            val absence = AbsenceClassifier(
                mockk<IdMapRepository>(relaxed = true),
                mapOf(DiscussionWorld.ROOT to ContentPathPolicy.ALL),
            )
            val projector = DiscussionReadProjection(
                world.reads,
                DiscussionPageResolver(world.sync, world.availability, absence),
                AnchorMatches(world.rows, world.store, world.fullReads, world.sync), absence,
                { error("unreadable rows cannot read page bytes") },
            )
            val snapshot = PageIndex(emptyList())
            val listed = projector.rootList(DiscussionWorld.ROOT, snapshot, null, 50, null)

            listed.discussions.map { it.state } shouldBe listOf("incomplete", "unreadable")
            listed.discussions[0].page.id shouldBe null
            listed.discussions[0].page.path shouldBe null
            listed.discussions[0].page.resolution shouldBe "unknown"
            listed.discussions[0].commentCount shouldBe 3
            listed.discussions[0].reason shouldBe null
            listed.discussions[1].page.id shouldBe world.page.pageId.value
            listed.discussions[1].reason shouldBe "read_failure"
            listed.discussions[1].status shouldBe null
            listed.discussions[1].starter shouldBe null
            projector.rootList(DiscussionWorld.ROOT, snapshot, null, 50, "unreadable")
                .discussions.map { it.id } shouldBe listOf(failed.value)
            val detail = projector.detail(DiscussionWorld.ROOT, failed, snapshot, null, 7)
            detail.comments shouldBe emptyList()
            detail.discussion?.get("anchor") shouldBe JsonNull
        }
    }

    test("actual failed reparse and degraded file failure expose one stable wire reason") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion()
            val path = world.rootPath.resolve(".plainbase/discussions/${id.value}/discussion.md")
            val failing = object : DiscussionStore by world.store {
                override fun read(root: RootName, discussion: DiscussionId, only: Set<EntryName>?): EntriesRead {
                    if (discussion == id && only == null) throw IOException("Access denied: $path")
                    return world.store.read(root, discussion, only)
                }
            }
            world.reparser(failing).settle(DiscussionWorld.ROOT, id)
            requireNotNull(world.rows.row(DiscussionWorld.ROOT, id)?.reason).shouldContain(path.toString())
            val indexedProjection = DiscussionReadProjection(world.reads, mockk(), mockk(), mockk()) {
                error("failed rows cannot read page bytes")
            }
            val snapshot = PageIndex(emptyList())
            val indexed = indexedProjection.rootList(DiscussionWorld.ROOT, snapshot, null, 50, null).discussions.single()
            indexed.reason shouldBe "read_failure"
            indexedProjection.detail(DiscussionWorld.ROOT, id, snapshot, null, 1)
                .discussion?.get("reason") shouldBe Json.parseToJsonElement("\"read_failure\"")

            world.sync.enter(DiscussionWorld.ROOT, "force file fallback")
            val degradedReads = DiscussionReads(
                world.rows, failing, DiscussionFullReads(failing), world.sync, world.availability,
            )
            val degradedProjection = DiscussionReadProjection(degradedReads, mockk(), mockk(), mockk()) {
                error("failed summaries cannot read page bytes")
            }
            degradedProjection.rootList(DiscussionWorld.ROOT, snapshot, null, 50, null)
                .discussions.single().reason shouldBe "read_failure"
        }
    }

    test("matching states project bounded ranges candidates placement and nulls") {
        DiscussionWorld().use { world ->
            val page = indexedPage(world)
            val snapshot = PageIndex(listOf(RootSection(DiscussionWorld.ROOT, listOf(page), emptyList(), emptySet())))
            val anchorHash = "sha256:" + "a".repeat(64)
            val matches = listOf(
                AnchorMatch.Exact(MatchRange(1, 3)),
                AnchorMatch.Moved(MatchRange(5, 7)),
                AnchorMatch.Ambiguous(2, listOf(MatchRange(9, 11), MatchRange(13, 15)), false),
                AnchorMatch.Changed(Placement.Line(12)),
            )
            world.rows.writing {
                matches.forEachIndexed { index, match ->
                    val id = DiscussionWorld.discussionId(20 + index)
                    apply(
                        DiscussionWorld.ROOT, id,
                        RowUpdate.Upsert(
                            DiscussionRowData(
                                "ok",
                                pageId = world.page.pageId, pagePath = world.page.path, status = "open",
                                anchorKind = "quote", anchorHash = anchorHash, starterKey = "digest",
                                starterKind = "agent", starterLabel = "Agent", quotePreview = "quoted",
                            ),
                            emptyList(),
                        ),
                        null, false,
                    )
                    storeMatch(DiscussionWorld.ROOT, id, anchorHash, page.contentHash, match)
                }
            }
            val absence = AbsenceClassifier(
                mockk<IdMapRepository>(relaxed = true),
                mapOf(DiscussionWorld.ROOT to ContentPathPolicy.ALL),
            )
            val projector = DiscussionReadProjection(
                world.reads,
                DiscussionPageResolver(world.sync, world.availability, absence),
                AnchorMatches(world.rows, world.store, world.fullReads, world.sync), absence,
                { error("cache hits must not request page bytes") },
            )

            val items = projector.pageList(DiscussionWorld.ROOT, page, snapshot, null, 7).discussions
            items.map { it.state } shouldBe listOf("exact", "moved", "ambiguous", "changed")
            items[0].range?.byteStart shouldBe 1
            items[1].range?.byteEnd shouldBe 7
            items[2].range shouldBe null
            items[2].candidates?.items?.map { it.byteStart } shouldBe listOf(9, 13)
            items[3].placement?.kind shouldBe "line"
            items[3].placement?.line shouldBe 12
            items[3].candidates shouldBe null
            projector.rootList(DiscussionWorld.ROOT, snapshot, null, 50, "ambiguous")
                .discussions.map { it.id } shouldBe listOf(DiscussionWorld.discussionId(22).value)
        }
    }

    test("missing anchor matches from absent and incomplete markers use a truthful public reason") {
        DiscussionWorld().use { world ->
            val absent = DiscussionWorld.discussionId(40)
            val incomplete = DiscussionWorld.discussionId(41)
            val folder = world.rootPath.resolve(".plainbase/discussions/${incomplete.value}")
            Files.createDirectories(folder)
            Files.write(folder.resolve(EntryName.Comment(DiscussionWorld.commentId(901)).fileName), byteArrayOf(0xff.toByte()))
            world.rows.writing {
                listOf(absent, incomplete).forEach { id ->
                    apply(
                        DiscussionWorld.ROOT, id,
                        RowUpdate.Upsert(
                            DiscussionRowData(
                                "ok", pageId = world.page.pageId, pagePath = world.page.path,
                                status = "open", anchorKind = "quote", anchorHash = "sha256:" + "a".repeat(64),
                            ),
                            emptyList(),
                        ),
                        null, false,
                    )
                }
            }
            val file = world.rootPath.resolve(world.page.path.value)
            Files.createDirectories(file.parent)
            Files.write(file, world.pageBytes)
            val content = LocalContentStore(world.rootPath).also { it.scan() }
            val absence = AbsenceClassifier(
                mockk<IdMapRepository>(relaxed = true),
                mapOf(DiscussionWorld.ROOT to ContentPathPolicy.ALL),
            )
            val page = indexedPage(world)
            val snapshot = PageIndex(listOf(RootSection(DiscussionWorld.ROOT, listOf(page), emptyList(), emptySet())))
            val projector = DiscussionReadProjection(
                world.reads,
                DiscussionPageResolver(world.sync, world.availability, absence),
                AnchorMatches(world.rows, world.store, world.fullReads, world.sync), absence, { content },
            )

            val items = projector.rootList(DiscussionWorld.ROOT, snapshot, null, 50, null).discussions
            items.map { it.id } shouldBe listOf(absent.value, incomplete.value)
            items.map { it.state } shouldBe listOf("unreadable", "unreadable")
            items.map { it.reason } shouldBe listOf("anchor_unavailable", "anchor_unavailable")
        }
    }

    test("orphaned and unavailable rows keep page identity without a live range") {
        DiscussionWorld().use { world ->
            val orphan = DiscussionWorld.discussionId(30)
            val unavailable = DiscussionWorld.discussionId(31)
            world.rows.writing {
                apply(
                    DiscussionWorld.ROOT, orphan,
                    RowUpdate.Upsert(
                        DiscussionRowData(
                            "ok",
                            pageId = world.page.pageId, pagePath = world.page.path, status = "open",
                            anchorKind = "page", commentCount = 1,
                        ),
                        emptyList(),
                    ),
                    null, false,
                )
                apply(
                    DiscussionWorld.ROOT, unavailable,
                    RowUpdate.Upsert(
                        DiscussionRowData(
                            "ok",
                            pageId = world.otherPage.pageId, pagePath = world.otherPage.path, status = "resolved",
                            anchorKind = "page", commentCount = 2,
                        ),
                        emptyList(),
                    ),
                    null, false,
                )
            }
            val snapshot = PageIndex(emptyList())
            val pageResolver = mockk<DiscussionPageResolver> {
                every { resolve(DiscussionWorld.ROOT, world.page.pageId, world.page.path, snapshot) } returns
                    DiscussionPageResolution.Orphaned
                every { resolve(DiscussionWorld.ROOT, world.otherPage.pageId, world.otherPage.path, snapshot) } returns
                    DiscussionPageResolution.Unavailable
            }
            val projector = DiscussionReadProjection(
                world.reads, pageResolver, mockk(), mockk(),
                { error("no live page may be read") },
            )

            val items = projector.rootList(DiscussionWorld.ROOT, snapshot, null, 50, null).discussions
            items.map { it.state } shouldBe listOf("orphaned", "unavailable")
            items.map { it.page.resolution } shouldBe listOf("orphaned", "unavailable")
            items.map { it.page.id } shouldBe listOf(world.page.pageId.value, world.otherPage.pageId.value)
            items.map { it.range } shouldBe listOf(null, null)
            items.map { it.status } shouldBe listOf("open", "resolved")
        }
    }

    test("fresh unselected symlink masks a synced ok detail row") {
        DiscussionWorld().use { world ->
            val id = world.startDiscussion()
            val folder = world.rootPath.resolve(".plainbase/discussions/${id.value}")
            Files.createSymbolicLink(
                folder.resolve(EntryName.Comment(DiscussionWorld.commentId(999)).fileName),
                folder.resolve("absent.md"),
            )
            val snapshot = PageIndex(listOf(RootSection(DiscussionWorld.ROOT, listOf(indexedPage(world)), emptyList(), emptySet())))
            val absence = AbsenceClassifier(
                mockk<IdMapRepository>(relaxed = true),
                mapOf(DiscussionWorld.ROOT to ContentPathPolicy.ALL),
            )
            val projector = DiscussionReadProjection(
                world.reads,
                DiscussionPageResolver(world.sync, world.availability, absence),
                AnchorMatches(world.rows, world.store, world.fullReads, world.sync), absence,
                { error("unreadable detail cannot read page bytes") },
            )

            val detail = projector.detail(DiscussionWorld.ROOT, id, snapshot, null, 1)
            detail.comments shouldBe emptyList()
            detail.discussion?.get("state") shouldBe Json.parseToJsonElement("\"unreadable\"")
            detail.discussion?.get("status") shouldBe JsonNull
            detail.discussion?.get("anchor") shouldBe JsonNull
        }
    }
})

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
