package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionRowData
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.MAX_COMMENT_BYTES
import com.plainbase.domain.discussion.RowUpdate
import com.plainbase.domain.discussion.SelectionRequest
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.page.RootSection
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.ApiTokenRepository
import com.plainbase.domain.repository.AuditEntry
import com.plainbase.domain.repository.AuditRepository
import com.plainbase.domain.repository.Role
import com.plainbase.domain.repository.RoleRepository
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.service.AbsenceClassifier
import com.plainbase.domain.service.AccessDenied
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.DenyReason
import com.plainbase.domain.service.DiscussionAnchorRequest
import com.plainbase.domain.service.DiscussionClaim
import com.plainbase.domain.service.DiscussionCommand
import com.plainbase.domain.service.DiscussionFacts
import com.plainbase.domain.service.DiscussionFullReads
import com.plainbase.domain.service.DiscussionReads
import com.plainbase.domain.service.DiscussionWriteOutcome
import com.plainbase.domain.service.DiscussionWriter
import com.plainbase.domain.service.IdProvider
import com.plainbase.domain.service.IdResolution
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.PageRootResolver
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.ProposalAuthor
import com.plainbase.domain.service.ProposalAuthorLabeler
import com.plainbase.domain.service.RootUnavailable
import com.plainbase.frameworks.discussion.DiscussionWorld
import com.plainbase.frameworks.filesystem.LocalDiscussionStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Instant

class GuardedDiscussionFacadeTest : FunSpec({
    val root = RootName.PRIMARY
    val otherRoot = RootName.require("shared")
    val id = DiscussionId.require("01900000-0000-7000-8000-000000000001")
    val comment = CommentId.require("01900000-0000-7000-8000-000000000002")
    val human = Principal.Human("builtin", "owner")

    test("preview checks read-only topology before page or discussion I O without an audit") {
        val fixture = fixture(listOf(localRoot(root, editable = false)))
        val pageId = PageId.require("01900000-0000-7000-8000-000000000010")
        every { fixture.indexBuilder.current } returns mockk()
        every { fixture.resolver.resolve(pageId) } returns IdResolution.One(root)

        val failure = shouldThrow<AccessDenied> {
            fixture.facade.preview(
                human, pageId, null,
                DiscussionAnchorRequest.Quote("sha256:" + "a".repeat(64), SelectionRequest.Agent("quote")),
            )
        }
        failure.reason shouldBe DenyReason.ROOT_NOT_EDITABLE
        fixture.audits shouldBe emptyList()
        verify(exactly = 0) { fixture.absence.read(any(), any()) }
        verify(exactly = 0) { fixture.reads.claim(any(), any(), any()) }
    }

    test("preview resolves a quote from the same page snapshot without an audit") {
        val fixture = fixture(listOf(localRoot(root)))
        val pageId = PageId.require("01900000-0000-7000-8000-000000000010")
        val path = TreePath.require("guides/current.md")
        val bytes = "# Current\n\nA stable sentence.\n".encodeToByteArray()
        val hash = CitationFactory().contentHash(bytes)
        val page = IndexedPage(
            pageId, root, path, "current", path, "Current", Frontmatter(emptyMap()), true,
            bytes.decodeToString(), hash, null, "", emptyList(), emptyList(), emptyList(),
        )
        val snapshot = PageIndex(listOf(RootSection(root, listOf(page), emptyList(), emptySet())))
        every { fixture.indexBuilder.current } returns snapshot
        every { fixture.resolver.resolve(pageId) } returns IdResolution.One(root)
        every { fixture.absence.requireVerifiedAbsence(root, pageId, snapshot) } returns Unit
        every { fixture.absence.read(fixture.store, RootedPath(root, path)) } returns ContentRead.Bytes(bytes)

        val preview = fixture.facade.preview(
            human, pageId, null,
            DiscussionAnchorRequest.Quote(hash, SelectionRequest.Agent("stable")),
        )
        preview.contentHash shouldBe hash
        preview.byteStart shouldBe bytes.decodeToString().indexOf("stable").toLong()
        preview.byteEnd shouldBe preview.byteStart + 6
        preview.selection shouldBe "narrowed"
        preview.quoteText shouldBe "stable"
        fixture.audits shouldBe emptyList()
        verify(exactly = 0) { fixture.writer.write(any(), any()) }
    }

    test("start audits the resolved page root and uses its current indexed path") {
        val fixture = fixture(listOf(localRoot(root)))
        val pageId = PageId.require("01900000-0000-7000-8000-000000000010")
        val currentPath = TreePath.require("guides/current.md")
        val bytes = "# Current\n".encodeToByteArray()
        val hash = CitationFactory().contentHash(bytes)
        val snapshot = mockk<PageIndex>()
        val page = mockk<IndexedPage>()
        every { fixture.indexBuilder.current } returns snapshot
        every { fixture.resolver.resolve(pageId) } returns IdResolution.One(root)
        every { snapshot.pageAt(RootedPageId(root, pageId)) } returns page
        every { page.root } returns root
        every { page.path } returns currentPath
        every { page.contentHash } returns hash
        every { page.commit } returns null
        every { fixture.absence.requireVerifiedAbsence(root, pageId, snapshot) } returns Unit
        every { fixture.absence.read(fixture.store, RootedPath(root, currentPath)) } returns ContentRead.Bytes(bytes)
        every { fixture.writer.write(any(), any()) } returns DiscussionWriteOutcome.Done(id, comment, null)

        fixture.facade.start(human, pageId, null, DiscussionAnchorRequest.Page(hash), "body")
            .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
        fixture.audits.single().resource shouldBe "${root.value}:${pageId.value}/discussions"
        verify(exactly = 1) {
            fixture.writer.write(
                any(),
                match { command ->
                command is DiscussionCommand.Start && command.page.path == currentPath && command.page.pageId == pageId
            },
            )
        }
    }

    test("missing ownership facts allow an audit but never call the writer") {
        listOf(
            DiscussionFacts.Known("ok", null, "open", null, null) to "comment_not_found",
            DiscussionFacts.Unknown to "content_unreadable",
        ).forEach { (facts, code) ->
            val fixture = fixture(listOf(localRoot(root)))
            every { fixture.reads.claim(root, id, comment) } returns DiscussionClaim.Present(facts)

            val result = fixture.facade.edit(human, id, root, comment, "changed")
                .shouldBeInstanceOf<DiscussionWriteOutcome.Refused>()
            result.refusal.code shouldBe code
            verify(exactly = 0) { fixture.writer.write(any(), any()) }
            fixture.audits.single().decision shouldBe "allowed"
            fixture.audits.single().resource shouldBe "${root.value}:discussion/${id.value}/comment/${comment.value}"
        }
    }

    test("a known holder and an uninspectable second claimant use a bare audit and refuse") {
        val fixture = fixture(listOf(localRoot(root), localRoot(otherRoot)))
        every { fixture.reads.claim(root, id, null) } returns DiscussionClaim.Present(
            DiscussionFacts.Known("ok", null, "open", null, null),
        )
        every { fixture.reads.claim(otherRoot, id, null) } returns DiscussionClaim.Unknown

        val result = fixture.facade.comment(human, id, null, "reply")
            .shouldBeInstanceOf<DiscussionWriteOutcome.Refused>()
        result.refusal.code shouldBe "content_unreadable"
        fixture.audits.single().resource shouldBe "discussion/${id.value}/comment"
        verify(exactly = 0) { fixture.writer.write(any(), any()) }
    }

    test("two confirmed holders require a pin and never choose a root by order") {
        val fixture = fixture(listOf(localRoot(root), localRoot(otherRoot)))
        val known = DiscussionClaim.Present(DiscussionFacts.Known("ok", null, "open", null, null))
        every { fixture.reads.claim(root, id, null) } returns known
        every { fixture.reads.claim(otherRoot, id, null) } returns known

        val result = fixture.facade.comment(human, id, null, "reply")
            .shouldBeInstanceOf<DiscussionWriteOutcome.Refused>()
        result.refusal.code shouldBe "ambiguous_discussion_id"
        fixture.audits.single().resource shouldBe "discussion/${id.value}/comment"
        verify(exactly = 0) { fixture.writer.write(any(), any()) }
    }

    test("an unknown third claimant keeps precedence over two confirmed holders") {
        val third = RootName.require("archive")
        val fixture = fixture(listOf(localRoot(root), localRoot(otherRoot), localRoot(third)))
        val known = DiscussionClaim.Present(DiscussionFacts.Known("ok", null, "open", null, null))
        every { fixture.reads.claim(root, id, null) } returns known
        every { fixture.reads.claim(otherRoot, id, null) } returns known
        every { fixture.reads.claim(third, id, null) } returns DiscussionClaim.Unknown

        val refused = fixture.facade.comment(human, id, null, "reply")
            .shouldBeInstanceOf<DiscussionWriteOutcome.Refused>()
        refused.refusal.code shouldBe "content_unreadable"
        fixture.audits.single().resource shouldBe "discussion/${id.value}/comment"
        verify(exactly = 0) { fixture.writer.write(any(), any()) }
    }

    test("invalid and unbound pins are refused only after a bare allowed decision") {
        listOf(
            RootName.require("missing") to "invalid_root",
            root to "discussion_not_found",
        ).forEach { (pin, code) ->
            val fixture = fixture(listOf(localRoot(root)))
            if (pin == root) every { fixture.reads.claim(root, id, null) } returns DiscussionClaim.Absent

            val result = fixture.facade.comment(human, id, pin, "reply")
                .shouldBeInstanceOf<DiscussionWriteOutcome.Refused>()
            result.refusal.code shouldBe code
            fixture.audits.single().resource shouldBe "discussion/${id.value}/comment"
            fixture.audits.single().decision shouldBe "allowed"
            verify(exactly = 0) { fixture.writer.write(any(), any()) }
        }
    }

    test("missing author and starter facts refuse after an allowed gate") {
        val fixture = fixture(listOf(localRoot(root)))
        val noDigest = DiscussionClaim.Present(DiscussionFacts.Known("ok", null, "open", null, null))
        every { fixture.reads.claim(root, id, comment) } returns noDigest
        every { fixture.reads.claim(root, id, null) } returns noDigest

        fixture.facade.edit(human, id, root, comment, "edit")
            .shouldBeInstanceOf<DiscussionWriteOutcome.Refused>().refusal.code shouldBe "comment_not_found"
        fixture.facade.resolve(human, id, root)
            .shouldBeInstanceOf<DiscussionWriteOutcome.Refused>().refusal.code shouldBe "content_unreadable"
        fixture.audits.map { it.decision } shouldBe listOf("allowed", "allowed")
        verify(exactly = 0) { fixture.writer.write(any(), any()) }
    }

    test("known unreadable and incomplete facts refuse after one allowed decision") {
        listOf("unreadable" to (409 to "discussion_unreadable"), "incomplete" to (404 to "discussion_not_found"))
            .forEach { (state, expected) ->
                val fixture = fixture(listOf(localRoot(root)))
                every { fixture.reads.claim(root, id, null) } returns DiscussionClaim.Present(
                    DiscussionFacts.Known(state, null, null, null, null),
                )

                val refused = fixture.facade.comment(human, id, root, "reply")
                    .shouldBeInstanceOf<DiscussionWriteOutcome.Refused>()
                refused.refusal.status shouldBe expected.first
                refused.refusal.code shouldBe expected.second
                fixture.audits.single().decision shouldBe "allowed"
                verify(exactly = 0) { fixture.writer.write(any(), any()) }
            }
    }

    test("enforced anonymous receives one denied audit before a target refusal") {
        val fixture = fixture(listOf(localRoot(root)))
        every { fixture.reads.claim(root, id, null) } returns DiscussionClaim.Present(DiscussionFacts.Unknown)

        val denied = shouldThrow<AccessDenied> { fixture.facade.comment(Principal.Anonymous, id, root, "reply") }
        denied.reason shouldBe DenyReason.POLICY
        fixture.audits.single().decision shouldBe "denied"
        fixture.audits.single().resource shouldBe "${root.value}:discussion/${id.value}/comment"
        verify(exactly = 0) { fixture.writer.write(any(), any()) }
    }

    test("availability is checked after the bare decision on uncertain claimants") {
        val fixture = fixture(listOf(localRoot(root), localRoot(otherRoot)))
        every { fixture.reads.claim(root, id, null) } returns DiscussionClaim.Present(DiscussionFacts.Unknown)
        every { fixture.reads.claim(otherRoot, id, null) } returns DiscussionClaim.Unknown
        fixture.availability.markUnavailable(otherRoot, UnavailableCause.VANISHED)

        shouldThrow<RootUnavailable> { fixture.facade.comment(human, id, null, "reply") }.root shouldBe otherRoot
        fixture.audits.single().resource shouldBe "discussion/${id.value}/comment"
        fixture.audits.single().decision shouldBe "allowed"
        verify(exactly = 0) { fixture.writer.write(any(), any()) }
    }

    test("a pinned failed row retains its root and a purge may use the raw writer") {
        val fixture = fixture(listOf(localRoot(root)), role = Role.ADMIN)
        every { fixture.reads.claim(root, id, comment) } returns DiscussionClaim.Present(DiscussionFacts.Unknown)
        every { fixture.writer.write(any(), any()) } returns DiscussionWriteOutcome.Done(id, comment, null)

        fixture.facade.purge(human, id, root, comment).shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
        fixture.audits.single().action shouldBe "PURGE"
        fixture.audits.single().resource shouldBe "${root.value}:discussion/${id.value}/comment/${comment.value}"
        verify(exactly = 1) { fixture.writer.write(any(), any()) }
    }

    test("read-only and object topology deny before an unavailable root or store lookup") {
        listOf(
            localRoot(root, editable = false) to DenyReason.ROOT_NOT_EDITABLE,
            Root(root, RootBackend.Object("bucket", ""), editable = true, HistoryMode.OFF) to DenyReason.DISCUSSIONS_UNSUPPORTED,
        ).forEach { (declared, expected) ->
            val fixture = fixture(listOf(declared))
            fixture.availability.markUnavailable(root, UnavailableCause.VANISHED)
            val denied = shouldThrow<AccessDenied> { fixture.facade.comment(human, id, root, "reply") }
            denied.reason shouldBe expected
            fixture.audits.single().decision shouldBe "denied"
            verify(exactly = 0) { fixture.reads.claim(any(), any(), any()) }
            verify(exactly = 0) { fixture.writer.write(any(), any()) }
        }
    }

    test("a synced malformed marker yields an audited unreadable refusal") {
        DiscussionWorld().use { world ->
            val discussion = world.startDiscussion()
            val marker = discussionFile(world, discussion, EntryName.Marker)
            val malformed = byteArrayOf(0xff.toByte())
            Files.write(marker, malformed)
            world.index.publish(DiscussionWorld.ROOT, discussion, markerChanged = true) {
                world.store.read(DiscussionWorld.ROOT, discussion)
            }
            world.rows.row(DiscussionWorld.ROOT, discussion)?.state shouldBe "unreadable"
            val fixture = realFixture(world)

            val refused = fixture.facade.comment(human, discussion, DiscussionWorld.ROOT, "reply")
                .shouldBeInstanceOf<DiscussionWriteOutcome.Refused>()
            refused.refusal.status shouldBe 409
            refused.refusal.code shouldBe "discussion_unreadable"
            fixture.audits.single().resource shouldBe "${DiscussionWorld.ROOT.value}:discussion/${discussion.value}/comment"
            fixture.audits.single().decision shouldBe "allowed"
            Files.readAllBytes(marker) shouldBe malformed
        }
    }

    test("an unsynced malformed or oversized comment can be purged through the real store") {
        listOf(byteArrayOf(0xff.toByte()), ByteArray(MAX_COMMENT_BYTES + 1) { 65 }).forEach { corrupt ->
            DiscussionWorld().use { world ->
                val discussion = world.startDiscussion()
                val commentId = DiscussionWorld.commentId(101)
                val target = discussionFile(world, discussion, EntryName.Comment(commentId))
                Files.write(target, corrupt)
                world.sync.enter(DiscussionWorld.ROOT, "force file facts")
                val fixture = realFixture(world, role = Role.ADMIN)

                val refused = fixture.facade.edit(human, discussion, DiscussionWorld.ROOT, commentId, "changed")
                    .shouldBeInstanceOf<DiscussionWriteOutcome.Refused>()
                refused.refusal.status shouldBe 409
                refused.refusal.code shouldBe "discussion_unreadable"
                Files.readAllBytes(target) shouldBe corrupt

                fixture.facade.purge(human, discussion, DiscussionWorld.ROOT, commentId)
                    .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
                Files.exists(target) shouldBe false
                fixture.audits.map { it.action to it.decision } shouldBe listOf("DISCUSS" to "allowed", "PURGE" to "allowed")
            }
        }
    }

    test("synced failed row and unsynced marker I O failure return 503") {
        DiscussionWorld().use { world ->
            val discussion = world.startDiscussion()
            val marker = discussionFile(world, discussion, EntryName.Marker)
            val before = Files.readAllBytes(marker)
            val failingStore: DiscussionStore = LocalDiscussionStore(
                mapOf(DiscussionWorld.ROOT to world.rootPath),
                readEntryBytes = { path, _ ->
                    if (path.fileName.toString() == EntryName.Marker.fileName) throw IOException("fixture marker read failed")
                    Files.readAllBytes(path)
                },
            )
            world.rows.writing {
                apply(
                    DiscussionWorld.ROOT, discussion,
                    RowUpdate.Upsert(DiscussionRowData("failed"), emptyList()), stamp = null, dropMatch = false,
                )
            }
            world.rows.row(DiscussionWorld.ROOT, discussion)?.state shouldBe "failed"
            val synced = realFixture(world)

            val rooted = synced.facade.comment(human, discussion, DiscussionWorld.ROOT, "reply")
                .shouldBeInstanceOf<DiscussionWriteOutcome.Refused>()
            rooted.refusal.status shouldBe 503
            rooted.refusal.code shouldBe "content_unreadable"
            synced.audits.single().resource shouldBe "${DiscussionWorld.ROOT.value}:discussion/${discussion.value}/comment"

            world.sync.enter(DiscussionWorld.ROOT, "force file facts")
            val fallbackReads = DiscussionReads(
                world.rows, failingStore, DiscussionFullReads(failingStore), world.sync, world.availability,
            )
            val unsynced = realFixture(world, reads = fallbackReads)
            val bare = unsynced.facade.comment(human, discussion, DiscussionWorld.ROOT, "reply")
                .shouldBeInstanceOf<DiscussionWriteOutcome.Refused>()
            bare.refusal.status shouldBe 503
            bare.refusal.code shouldBe "content_unreadable"
            unsynced.audits.single().resource shouldBe "discussion/${discussion.value}/comment"
            Files.readAllBytes(marker) shouldBe before
        }
    }
})

private fun discussionFile(world: DiscussionWorld, id: DiscussionId, name: EntryName): Path =
    world.rootPath.resolve(".plainbase/discussions/${id.value}/${name.fileName}")

private fun realFixture(world: DiscussionWorld, role: Role = Role.VIEWER, reads: DiscussionReads = world.reads): DiscussionFacadeFixture =
    fixture(
        listOf(localRoot(DiscussionWorld.ROOT, path = world.rootPath)), role,
        reads = reads, writer = world.writer(), availability = world.availability,
    )

private fun localRoot(root: RootName, editable: Boolean = true, path: Path = Path.of("/private/tmp")) =
    Root(root, RootBackend.Local(path), editable, HistoryMode.OFF)

private class DiscussionFacadeFixture(
    roots: List<Root>,
    role: Role,
    actualReads: DiscussionReads? = null,
    actualWriter: DiscussionWriter? = null,
    actualAvailability: RootAvailability? = null,
) {
    val registry = RootRegistry.of(roots)
    val reads = actualReads ?: mockk<DiscussionReads>()
    val writer = actualWriter ?: mockk<DiscussionWriter>()
    val resolver = mockk<PageRootResolver>()
    val absence = mockk<AbsenceClassifier>()
    val indexBuilder = mockk<IndexBuilder>()
    val store = mockk<ContentStore>()
    val availability = actualAvailability ?: RootAvailability(object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)
    })
    val audits = mutableListOf<AuditEntry>()
    private val roleRepository = mockk<RoleRepository> {
        every { roleOf("builtin", "owner") } returns role
    }
    private val auditRepository = mockk<AuditRepository> {
        every { record(any()) } answers {
            audits += firstArg<AuditEntry>()
        }
    }
    private val labeler = mockk<ProposalAuthorLabeler> {
        every { resolve(any()) } returns ProposalAuthor("builtin", "owner", "Owner")
    }
    private var nextId = 0
    private val policy = PolicyService(
        roles = roleRepository,
        apiTokens = mockk<ApiTokenRepository>(),
        audit = auditRepository,
        idProvider = IdProvider { PageId.require("0190aaaa-bbbb-7ccc-8ddd-%012d".format(nextId++)) },
        clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)
        },
        enforced = true,
        editableOf = { registry.byName(it)?.editable == true },
        objectBackendOf = { registry.byName(it)?.backend is RootBackend.Object },
    )
    val facade = GuardedDiscussionFacade(
        policy = policy,
        writer = writer,
        reads = reads,
        registry = registry,
        availability = availability,
        resolver = resolver,
        absence = absence,
        indexBuilder = indexBuilder,
        stores = { store },
        labeler = labeler,
    )
}

private fun fixture(
    roots: List<Root>,
    role: Role = Role.VIEWER,
    reads: DiscussionReads? = null,
    writer: DiscussionWriter? = null,
    availability: RootAvailability? = null,
): DiscussionFacadeFixture = DiscussionFacadeFixture(roots, role, reads, writer, availability)
