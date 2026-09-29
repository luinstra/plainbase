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
import com.plainbase.domain.discussion.EntriesRead
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.EntryPath
import com.plainbase.domain.discussion.MatchRange
import com.plainbase.domain.discussion.PageRef
import com.plainbase.domain.discussion.QuoteCapture
import com.plainbase.domain.discussion.StoreWrite
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.page.RootSection
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.service.AbsenceClassifier
import com.plainbase.domain.service.AnchorMatches
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.DiscussionPageResolver
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.ktor.DiscussionReadProjection
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class DiscussionPageSupplierCacheTest : FunSpec({
    test("should reload an evicted page and cache a match under its fresh byte hash") {
        DiscussionWorld().use { world ->
            val ids = listOf(world.page, world.page, world.otherPage, world.page).map { world.startQuoted(it) }
            val first = world.writePages()
            val revised = "# Discussion page\n\nA changed page body.\n".encodeToByteArray()
            val content = LocalContentStore(world.rootPath).also { it.scan() }
            val calls = mutableListOf<TreePath>()
            val counted = object : ContentStore by content {
                override fun readClassified(path: TreePath): StoreRead {
                    calls += path
                    if (path == world.otherPage.path) Files.write(first, revised)
                    return content.readClassified(path)
                }
            }
            val listed = world.projector(counted).rootList(world.root(), world.snapshot(), null, 50, null)

            listed.discussions.map { it.id } shouldBe ids.map { it.value }
            listed.discussions.last().state shouldBe "changed"
            calls shouldBe listOf(world.page.path, world.otherPage.path, world.page.path)
            world.rows.cached(world.root(), ids.last())?.pageHash shouldBe CitationFactory().contentHash(revised)
        }
    }

    test("should keep the loaded page when an intervening quote uses a persistent match") {
        DiscussionWorld().use { world ->
            val ids = listOf(world.page, world.otherPage, world.page).map { world.startQuoted(it) }
            world.writePages()
            val snapshot = world.snapshot()
            val other = snapshot.pageAt(RootedPageId(world.root(), world.otherPage.pageId))
            val anchorHash = requireNotNull(world.rows.row(world.root(), ids[1])?.anchorHash)
            world.rows.writing {
                storeMatch(world.root(), ids[1], anchorHash, requireNotNull(other).contentHash, AnchorMatch.Exact(MatchRange(0, 1)))
            }
            val content = LocalContentStore(world.rootPath).also { it.scan() }
            val calls = mutableListOf<TreePath>()
            val counted = object : ContentStore by content {
                override fun readClassified(path: TreePath): StoreRead {
                    calls += path
                    return content.readClassified(path)
                }
            }

            val listed = world.projector(counted).rootList(world.root(), snapshot, null, 50, null)
            listed.discussions.map { it.id } shouldBe ids.map { it.value }
            listed.discussions.map { it.state } shouldBe listOf("exact", "exact", "exact")
            calls shouldBe listOf(world.page.path)
        }
    }

    test("should propagate a replacement read failure") {
        DiscussionWorld().use { world ->
            listOf(world.page, world.otherPage).forEach { world.startQuoted(it) }
            world.writePages()
            val content = LocalContentStore(world.rootPath).also { it.scan() }
            val calls = mutableListOf<TreePath>()
            val failure = IOException("replacement page unavailable")
            val counted = object : ContentStore by content {
                override fun readClassified(path: TreePath): StoreRead {
                    calls += path
                    if (path == world.otherPage.path) throw failure
                    return content.readClassified(path)
                }
            }

            shouldThrow<IOException> {
                world.projector(counted).rootList(world.root(), world.snapshot(), null, 50, null)
            } shouldBe failure
            calls shouldBe listOf(world.page.path, world.otherPage.path)
        }
    }
})

private fun DiscussionWorld.root() = DiscussionWorld.ROOT

private fun DiscussionWorld.startQuoted(pageRef: PageRef): DiscussionId {
    val id = startDiscussionOn(pageRef, pageAnchor, "First comment from the real writer.\n")
    val start = pageBytes.decodeToString().indexOf("stable")
    val quote = QuoteCapture.at(pageBytes, start, start + 6, emptyList(), AnchorSelection.NARROWED)
    val raw = (store.read(root(), id, setOf(EntryName.Marker)) as EntriesRead.Present).entries.single()
    val marker = (DiscussionCodec.decodeDiscussion(raw.take()) as Decoded.Ok).value
    store.replace(
        root(), EntryPath(id, EntryName.Marker), raw.version,
        DiscussionCodec.encodeDiscussion(marker.copy(anchor = Anchor.Quote(pageAnchor.contentHash, null, quote))),
    ).shouldBeInstanceOf<StoreWrite.Written>()
    index.publish(root(), id, markerChanged = true) { store.read(root(), id) }
    return id
}

private fun DiscussionWorld.writePages(): Path {
    val first = rootPath.resolve(page.path.value)
    val second = rootPath.resolve(otherPage.path.value)
    Files.createDirectories(first.parent)
    Files.createDirectories(second.parent)
    Files.write(first, pageBytes)
    Files.write(second, pageBytes)
    return first
}

private fun DiscussionWorld.snapshot(): PageIndex {
    val pages = listOf(page, otherPage).map { ref ->
        IndexedPage(
            id = ref.pageId,
            root = root(),
            path = ref.path,
            slug = "discussion",
            urlPath = ref.path,
            title = "Discussion page",
            frontmatter = Frontmatter(emptyMap()),
            materialized = true,
            markdown = pageBytes.decodeToString(),
            contentHash = CitationFactory().contentHash(pageBytes),
            commit = null,
            html = "",
            headings = emptyList(),
            links = emptyList(),
            sections = emptyList(),
        )
    }
    return PageIndex(listOf(RootSection(root(), pages, emptyList(), emptySet())))
}

private fun DiscussionWorld.projector(content: ContentStore): DiscussionReadProjection {
    val absence = AbsenceClassifier(mockk<IdMapRepository>(relaxed = true), mapOf(root() to ContentPathPolicy.ALL))
    return DiscussionReadProjection(
        reads,
        DiscussionPageResolver(sync, availability, absence),
        AnchorMatches(rows, store, fullReads, sync),
        absence,
        { content },
    )
}
