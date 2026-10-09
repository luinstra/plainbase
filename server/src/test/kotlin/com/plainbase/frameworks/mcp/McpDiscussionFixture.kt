package com.plainbase.frameworks.mcp

import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionPageSource
import com.plainbase.domain.discussion.DiscussionRows
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.discussion.EntryListing
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.service.AbsenceClassifier
import com.plainbase.domain.service.AnchorMatches
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.ContentWriteMonitor
import com.plainbase.domain.service.DiscussionAnchorRequest
import com.plainbase.domain.service.DiscussionFullReads
import com.plainbase.domain.service.DiscussionIdProvider
import com.plainbase.domain.service.DiscussionPageResolver
import com.plainbase.domain.service.DiscussionReads
import com.plainbase.domain.service.DiscussionSyncState
import com.plainbase.domain.service.DiscussionWriteOutcome
import com.plainbase.domain.service.DiscussionWriter
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.PageRootResolver
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.ProposalAuthorLabeler
import com.plainbase.domain.service.SyncedDiscussionIndex
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.frameworks.discussion.DiscussionDb
import com.plainbase.frameworks.discussion.JdbcDiscussionRows
import com.plainbase.frameworks.discussion.seedTransportDiscussion
import com.plainbase.frameworks.filesystem.LocalDiscussionStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.ktor.DiscussionReadProjection
import com.plainbase.frameworks.ktor.GuardedDiscussionFacade
import com.plainbase.frameworks.protocol.DiscussionDetailDto
import com.plainbase.frameworks.protocol.DiscussionListDto
import com.plainbase.frameworks.protocol.DiscussionTransportFacade
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Instant

/** A small real file/index/writer graph for the CIO + SSE discussion surface. */
internal class McpDiscussionFixture(
    private val harness: IndexHarness,
    private val rootPaths: Map<RootName, Path>,
    resolver: PageRootResolver,
    absence: AbsenceClassifier,
    enforced: Boolean,
    authClock: Clock = Clock.System,
) : AutoCloseable {
    private val dataDir = Files.createTempDirectory("plainbase-mcp-discussions")
    private val db = DiscussionDb(dataDir.resolve("discussions.db"))
    private val rows: DiscussionRows = JdbcDiscussionRows(db)
    private val scope = harness.rootRegistry.roots.filter { it.supportsDiscussions }.map { it.name }.toSet()
    private val sync = DiscussionSyncState(scope)
    private val localStore = LocalDiscussionStore(rootPaths.filterKeys { it in scope })
    private val failedListing = AtomicReference<DiscussionId?>(null)
    private val unexpectedDetail = AtomicBoolean(false)
    val storeCalls = AtomicInteger()
    private val store: DiscussionStore = object : DiscussionStore by localStore {
        override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?) =
            localStore.read(root, id, only).also { storeCalls.incrementAndGet() }

        override fun listEntries(root: RootName, id: DiscussionId) =
            (if (id == failedListing.get()) EntryListing.Failed("private disk diagnostics") else localStore.listEntries(root, id))
                .also { storeCalls.incrementAndGet() }

        override fun visit(root: RootName, visitor: (DiscussionId, Boolean) -> Unit) =
            localStore.visit(root, visitor).also { storeCalls.incrementAndGet() }
    }
    private val fullReads = DiscussionFullReads(store)
    private val index = SyncedDiscussionIndex(rows, store, fullReads, sync)
    private val reads = DiscussionReads(rows, store, fullReads, sync, harness.availability)
    private val ids = object : DiscussionIdProvider {
        private val discussion = AtomicInteger(1)
        private val comment = AtomicInteger(101)
        override fun nextDiscussion() = DiscussionId.require(uuid(discussion.getAndIncrement()))
        override fun nextComment() = CommentId.require(uuid(comment.getAndIncrement()))
    }
    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-09-28T12:00:00Z")
    }

    val facade: GuardedDiscussionFacade
    val calls = AtomicInteger()
    val transport: DiscussionTransportFacade

    init {
        val source = DiscussionPageSource { root, ref ->
            val page = harness.builder.current.pageAt(RootedPageId(root, ref.pageId))
            if (page == null) {
                ContentRead.ConfirmedAbsent
            } else {
                absence.read(harness.stores(root), RootedPath(root, page.path))
            }
        }
        val writer = DiscussionWriter(ContentWriteMonitor(), store, source, { NoOpHistoryProvider }, index, ids, clock)
        val policy = PolicyService(
            roles = harness.roleRepository,
            apiTokens = harness.apiTokenRepository,
            audit = harness.auditRepository,
            idProvider = UuidV7IdProvider(),
            clock = authClock,
            enforced = enforced,
            editableOf = { harness.rootRegistry.byName(it)?.editable == true },
            objectBackendOf = { harness.rootRegistry.byName(it)?.backend is RootBackend.Object },
            discussionsEnabledOf = { harness.rootRegistry.byName(it)?.discussionsEnabled == true },
        )
        val projection = DiscussionReadProjection(
            reads, DiscussionPageResolver(sync, harness.availability, absence),
            AnchorMatches(rows, store, fullReads, sync), absence, harness.stores,
        )
        facade = GuardedDiscussionFacade(
            policy, writer, reads, harness.rootRegistry, harness.availability, resolver,
            absence, harness.builder, harness.stores,
            ProposalAuthorLabeler(harness.apiTokenRepository, harness.userRepository), CitationFactory(), projection,
        )
        transport = object : DiscussionTransportFacade by facade {
            override fun pageList(
                principal: Principal, pageId: PageId, pin: RootName?, after: DiscussionId?, limit: Int,
            ): DiscussionListDto {
                calls.incrementAndGet()
                return facade.pageList(principal, pageId, pin, after, limit)
            }

            override fun rootList(
                principal: Principal, root: RootName, after: DiscussionId?, limit: Int, state: String?,
            ): DiscussionListDto {
                calls.incrementAndGet()
                return facade.rootList(principal, root, after, limit, state)
            }

            override fun detail(
                principal: Principal, id: DiscussionId, pin: RootName?, after: CommentId?, limit: Int,
            ): DiscussionDetailDto {
                calls.incrementAndGet()
                if (unexpectedDetail.getAndSet(false)) error("private test diagnostics")
                return facade.detail(principal, id, pin, after, limit)
            }

            override fun start(
                principal: Principal, pageId: PageId, root: RootName?, anchor: DiscussionAnchorRequest, body: String,
            ): DiscussionWriteOutcome {
                calls.incrementAndGet()
                return facade.start(principal, pageId, root, anchor, body)
            }

            override fun comment(principal: Principal, id: DiscussionId, root: RootName?, body: String): DiscussionWriteOutcome {
                calls.incrementAndGet()
                return facade.comment(principal, id, root, body)
            }
        }
    }

    fun auditRows() = harness.auditRepository.recent(100)

    fun seedDisabledThread(id: DiscussionId, comment: CommentId): Map<String, List<Byte>> {
        val root = requireNotNull(rootPaths[RootName.PRIMARY])
        seedTransportDiscussion(root, harness.builder.current.pages.single(), id, comment)
        return threadBytes(id)
    }

    fun threadBytes(id: DiscussionId): Map<String, List<Byte>> {
        val directory = requireNotNull(rootPaths[RootName.PRIMARY]).resolve(".plainbase/discussions/${id.value}")
        return Files.walk(directory).use { paths ->
            paths.filter(Files::isRegularFile).toList().associate { it.fileName.toString() to Files.readAllBytes(it).toList() }
        }
    }

    fun failDetailListing(id: DiscussionId) {
        failedListing.set(id)
    }

    fun failNextDetailUnexpectedly() {
        unexpectedDetail.set(true)
    }

    fun duplicateTo(id: DiscussionId, destination: RootName) {
        val source = requireNotNull(rootPaths[RootName.PRIMARY]).resolve(".plainbase/discussions/${id.value}")
        val target = requireNotNull(rootPaths[destination]).resolve(".plainbase/discussions/${id.value}")
        Files.createDirectories(target)
        Files.list(source).use { entries ->
            entries.forEach { path -> Files.copy(path, target.resolve(path.fileName), StandardCopyOption.REPLACE_EXISTING) }
        }
        index.publish(destination, id, markerChanged = true) { store.read(destination, id) }
    }

    fun pageId(): PageId = harness.builder.current.pages.single().id

    private fun uuid(number: Int) = "01900000-0000-7000-8000-${number.toString(16).padStart(12, '0')}"

    override fun close() {
        db.close()
        dataDir.toFile().deleteRecursively()
    }
}
