package com.plainbase.frameworks.koin

import app.cash.sqldelight.db.SqlDriver
import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.discussion.DiscussionPageSource
import com.plainbase.domain.discussion.PageRef
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.ProposalId
import com.plainbase.domain.repository.ProposalOperation
import com.plainbase.domain.repository.ProposalRepository
import com.plainbase.domain.repository.ProposalRow
import com.plainbase.domain.repository.ProposalStatus
import com.plainbase.domain.root.BindingLatch
import com.plainbase.domain.root.ObservationEpoch
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootConvergence
import com.plainbase.domain.root.RootLimbo
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.service.AbsenceClassifier
import com.plainbase.domain.service.ContentWriteMonitor
import com.plainbase.domain.service.DiscussionFacade
import com.plainbase.domain.service.DiscussionWriter
import com.plainbase.domain.service.IdProvider
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.PageIdentityService
import com.plainbase.domain.service.PageReindexListener
import com.plainbase.domain.service.PageRootResolver
import com.plainbase.domain.service.PageService
import com.plainbase.domain.service.ProposalAuthorLabeler
import com.plainbase.domain.service.ProposalService
import com.plainbase.domain.service.SearchService
import com.plainbase.domain.service.TestIdProvider
import com.plainbase.domain.service.UrlAliasRegistry
import com.plainbase.domain.service.WritePipeline
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.config.ConfigLoader
import com.plainbase.frameworks.config.ConfigValuePolicy
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.lifecycle.ServerResourceOwner
import com.plainbase.frameworks.runtime.HistoryProviders
import com.plainbase.frameworks.runtime.ObservedIndexRuntime
import com.plainbase.frameworks.runtime.RootStores
import com.plainbase.frameworks.runtime.ServerOpeners
import com.plainbase.frameworks.runtime.ServingRuntime
import com.plainbase.frameworks.runtime.prepareRootBootInputs
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.spyk
import io.mockk.verify
import org.koin.dsl.module
import kotlin.time.Instant

/** Verifies index-only resolution and the serving projection's shared runtime provenance. */
class IndexModuleWiringTest : FunSpec({

    test("IndexModule passes page reindex listeners") {
        withTempTree(seed = { root -> writePage(root, "docs/reindex.md", "# Reindex\n\nlistener wiring\n") }) { root ->
            withTempTree(seed = {}) { dataDir ->
                val config = ConfigLoader.fromEnv(
                    mapOf("CONTENT_DIR" to root.toString(), "DATA_DIR" to dataDir.toString()),
                )
                val openers = ServerOpeners()
                val inputs = prepareRootBootInputs(config, openers.openLocal)
                val owner = ServerResourceOwner()
                val observed = mutableListOf<Pair<RootName, String>>()
                val listener = PageReindexListener { pageRoot, page -> observed += pageRoot to page.id.value }
                val app = createOwnedTestKoinApplication(
                    owner,
                    listOf(
                        module {
                            single { config }
                            single<PageReindexListener> { listener }
                        },
                        createContentModule(config, inputs, openers.openObject, { it.close() }, owner),
                        repositoryModule(owner),
                        securityModule,
                        createHistoryModule(config, inputs.history, owner),
                        indexModule,
                        module { single<SqlDriver> { DatabaseFactory.createInMemoryDriver() } },
                    ),
                )
                try {
                    inputs.signals.arm(app.koin.get<ObservationEpoch>()::broke)
                    val builder = app.koin.get<IndexBuilder>()
                    val snapshot = builder.rebuild()
                    val expected = snapshot.pages.single { it.path == TreePath.require("docs/reindex.md") }

                    builder.reindex(RootedPath(RootName.PRIMARY, expected.path))

                    observed shouldBe listOf(RootName.PRIMARY to expected.id.value)
                } finally {
                    owner.close()
                }
            }
        }
    }

    test("the production module set resolves IndexBuilder (indexModule is installed)") {
        val config = ConfigLoader.fromEnv(emptyMap())
        val openers = ServerOpeners()
        val inputs = prepareRootBootInputs(config, openers.openLocal)
        val owner = ServerResourceOwner()
        val app = createOwnedTestKoinApplication(
            owner,
            listOf(
                module { single { config } },
                createContentModule(config, inputs, openers.openObject, { it.close() }, owner),
                repositoryModule(owner),
                securityModule,
                createHistoryModule(config, inputs.history, owner),
                indexModule,
                module { single<SqlDriver> { DatabaseFactory.createInMemoryDriver() } },
            ),
        )
        try {
            app.koin.get<IndexBuilder>().shouldBeInstanceOf<IndexBuilder>()
        } finally {
            owner.close()
        }
    }

    test("the serving projection keeps the observed graph shared and defers request-time root status") {
        withTempTree(seed = { root -> writePage(root, "docs/runtime.md", "# Runtime\n\nserving graph\n") }) { root ->
            withTempTree(seed = {}) { dataDir ->
                val config = ConfigLoader.fromEnv(
                    mapOf("CONTENT_DIR" to root.toString(), "DATA_DIR" to dataDir.toString()),
                )
                val openers = ServerOpeners()
                val inputs = prepareRootBootInputs(config, openers.openLocal)
                val owner = ServerResourceOwner()
                val deterministicIds = TestIdProvider()
                val collected = mutableListOf<ServingRuntime>()
                val app = createOwnedTestKoinApplication(
                    owner,
                    listOf(
                        module { single { config } },
                        createContentModule(config, inputs, openers.openObject, { it.close() }, owner),
                        repositoryModule(owner),
                        securityModule,
                        createHistoryModule(config, inputs.history, owner),
                        indexModule,
                        module { single<IdProvider> { deterministicIds } },
                        checkpointModule,
                        searchModule(owner),
                        createDiscussionModule(owner),
                        createRestModule(
                            resourceOwner = owner,
                            onServingRuntimeCollected = collected::add,
                        ),
                        module {
                            single<PageRootResolver> { spyk(PageRootResolver(get(), get())) }
                        },
                    ),
                )
                try {
                    val observed = app.koin.get<ObservedIndexRuntime>()
                    inputs.signals.arm(app.koin.get<ObservationEpoch>()::broke)
                    val snapshot = observed.builder.rebuild()
                    val seeded = snapshot.pages.single { it.path == TreePath.require("docs/runtime.md") }
                    seeded.rooted shouldBe RootedPageId(
                        RootName.PRIMARY,
                        PageId.require("01900000-0000-7000-8000-000000000001"),
                    )
                    val planted = PageRef(seeded.id, TreePath.require("planted/marker-path.md"))
                    val discussionPage = app.koin.get<DiscussionPageSource>().read(seeded.root, planted)
                        .shouldBeInstanceOf<ContentRead.Bytes>()
                    discussionPage.bytes.decodeToString() shouldBe "# Runtime\n\nserving graph\n"

                    val context = app.koin.get<RouteContext>()
                    app.koin.get<RouteContext>() shouldBeSameInstanceAs context
                    collected.size shouldBe 1

                    val serving = collected.single()
                    serving.index shouldBeSameInstanceAs observed
                    serving.index.idProvider shouldBeSameInstanceAs deterministicIds
                    app.koin.get<IdProvider>() shouldBeSameInstanceAs deterministicIds
                    serving.index.identity shouldBeSameInstanceAs app.koin.get<PageIdentityService>()
                    serving.absence shouldBeSameInstanceAs app.koin.get<AbsenceClassifier>()
                    serving.agentDirectCommitGlobs shouldBe ConfigValuePolicy.agentDirectCommitGlobs(config)
                    serving.index.builder shouldBeSameInstanceAs app.koin.get<IndexBuilder>()
                    serving.index.registry shouldBeSameInstanceAs app.koin.get<RootRegistry>()
                    serving.index.stores shouldBeSameInstanceAs app.koin.get<RootStores>()
                    serving.index.histories shouldBeSameInstanceAs app.koin.get<HistoryProviders>()
                    serving.index.availability shouldBeSameInstanceAs app.koin.get<RootAvailability>()
                    serving.index.convergence shouldBeSameInstanceAs app.koin.get<RootConvergence>()
                    serving.index.limbo shouldBeSameInstanceAs app.koin.get<RootLimbo>()
                    serving.index.epochs shouldBeSameInstanceAs app.koin.get<ObservationEpoch>()
                    serving.index.bindings shouldBeSameInstanceAs app.koin.get<BindingLatch>()
                    serving.index.aliasRegistry shouldBeSameInstanceAs app.koin.get<UrlAliasRegistry>()
                    serving.pageService shouldBeSameInstanceAs app.koin.get<PageService>()
                    serving.searchService shouldBeSameInstanceAs app.koin.get<SearchService>()
                    serving.writePipeline shouldBeSameInstanceAs app.koin.get<WritePipeline>()
                    serving.resolver shouldBeSameInstanceAs app.koin.get<PageRootResolver>()
                    serving.proposalService shouldBeSameInstanceAs app.koin.get<ProposalService>()
                    serving.proposalLabeler shouldBeSameInstanceAs app.koin.get<ProposalAuthorLabeler>()
                    serving.discussionFacade shouldBeSameInstanceAs app.koin.get<DiscussionFacade>()
                    context.discussions shouldBeSameInstanceAs serving.discussionFacade
                    val monitor = app.koin.get<ContentWriteMonitor>()
                    DiscussionWriter::class.java.getDeclaredField("monitor").apply { isAccessible = true }
                        .get(app.koin.get<DiscussionWriter>()) shouldBeSameInstanceAs monitor
                    WritePipeline::class.java.getDeclaredField("monitor").apply { isAccessible = true }
                        .get(serving.writePipeline) shouldBeSameInstanceAs monitor
                    context.registry shouldBeSameInstanceAs serving.index.registry
                    context.availability shouldBeSameInstanceAs serving.index.availability
                    context.convergence shouldBeSameInstanceAs serving.index.convergence
                    context.limbo shouldBeSameInstanceAs serving.index.limbo
                    context.idProvider shouldBeSameInstanceAs serving.index.idProvider

                    val resolver = app.koin.get<PageRootResolver>()
                    verify(exactly = 0) { resolver.statusOf(any(), any()) }

                    val detached = ProposalRow(
                        id = ProposalId.require("01900000-0000-7000-8000-000000000701"),
                        operation = ProposalOperation.CREATE,
                        pageId = PageId.require("01900000-0000-7000-8000-000000000702"),
                        root = RootName.require("ghost"),
                        baseHash = null,
                        targetPath = TreePath.require("ghost.md"),
                        proposedContent = "# Ghost\n".toByteArray(),
                        rationale = "runtime wiring",
                        diffArtifact = "",
                        status = ProposalStatus.PENDING,
                        authorIssuer = "agent",
                        authorExternalId = "runtime-test",
                        authorLabel = "runtime-test",
                        approverIssuer = null,
                        approverExternalId = null,
                        decisionComment = null,
                        createdAt = Instant.fromEpochMilliseconds(0),
                        decidedAt = null,
                        appliedCommit = null,
                        statusReason = null,
                    )
                    val proposals = app.koin.get<ProposalRepository>()
                    proposals.insert(detached)
                    proposals.claimApplying(detached.id) shouldBe true
                    app.koin.get<ProposalService>().reconcileApplying()
                    verify(exactly = 1) { resolver.statusOf(RootName.require("ghost"), any()) }
                    proposals.findById(detached.id)?.status shouldBe ProposalStatus.APPLYING
                } finally {
                    owner.close()
                }
            }
        }
    }
})
