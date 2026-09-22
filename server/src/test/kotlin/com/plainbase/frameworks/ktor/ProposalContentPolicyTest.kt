package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.ContentPathPolicy
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.principal.approveGrantForTests
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.repository.BindOutcome
import com.plainbase.domain.repository.ProposalRow
import com.plainbase.domain.repository.ProposalStatus
import com.plainbase.domain.repository.Supersession
import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.ProofSource
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.service.AbsenceClassifier
import com.plainbase.domain.service.ApplyOutcome
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.CreateIntent
import com.plainbase.domain.service.CreateOutcome
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.PageRootResolver
import com.plainbase.domain.service.PermalinkResolution
import com.plainbase.domain.service.ProposalApprover
import com.plainbase.domain.service.ProposalContentWriter
import com.plainbase.domain.service.ProposalService
import com.plainbase.domain.service.ProposeCommand
import com.plainbase.domain.service.ProposeOutcome
import com.plainbase.domain.service.RebaseOutcome
import com.plainbase.domain.service.RejectOutcome
import com.plainbase.domain.service.SaveRequest
import com.plainbase.domain.service.SaveResult
import com.plainbase.domain.service.UuidV7ProposalIdProvider
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.filesystem.LocalContentStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import java.nio.file.Files
import kotlin.time.Clock

class ProposalContentPolicyTest : FunSpec({
    test("hidden live and retired content stays an ordinary REST miss, including assets") {
        withTempTree(seed = { root ->
            writePage(root, "private/page.md", "# Private\n")
            Files.createDirectories(root.resolve("private"))
            Files.writeString(root.resolve("private/diagram.bin"), "private asset")
        }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val page = harness.builder.current.pages.single()
                val hiddenPolicy = ContentPathPolicy.create(
                    fileEligibility = { !it.value.startsWith("private/") },
                    traversalEligibility = { true },
                    metadataEligibility = { true },
                )
                val hidden = harness.testRouteContext(
                    searchProvider = mockk(relaxed = true),
                    policies = mapOf(RootName.PRIMARY to hiddenPolicy),
                )

                fun assertHiddenWire() {
                    testApplication {
                        application { plainbaseModule(hidden) }
                        for (path in listOf(
                            "/api/v1/pages/${page.id.value}",
                            "/api/v1/pages/${page.id.value}?root=docs",
                            "/p/${page.id.value}",
                            "/p/docs/${page.id.value}",
                            "/assets/docs/private/diagram.bin",
                        )) {
                            client.get(path).status shouldBe HttpStatusCode.NotFound
                        }
                    }
                }

                assertHiddenWire()
                harness.retirements.applyProofs(
                    proofs = listOf(
                        AbsenceProof.accepted(
                            root = page.root,
                            source = ProofSource.OPERATOR,
                            observationId = harness.retirements.observation(page.root),
                            bindingEpoch = harness.retirements.bindingEpoch(page.root),
                            covers = setOf(BindingRef(page.path, page.id)),
                        ),
                    ),
                    witnessed = emptySet(),
                    unavailableNow = { emptySet() },
                )
                hidden.read.permalink(Principal.Anonymous, page.id) shouldBe PermalinkResolution.Unknown
                hidden.read.permalinkAt(Principal.Anonymous, page.root, page.id) shouldBe PermalinkResolution.Unknown
                assertHiddenWire()
            }
        }
    }

    test("hidden proposals disclose nothing and remain pending until the target is re-included") {
        withTempTree(seed = { root -> writePage(root, "private/page.md", "# Private\n\nold\n") }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val search = mockk<SearchProvider>(relaxed = true)
                val visible = harness.testRouteContext(searchProvider = search)
                val page = harness.builder.current.pages.single()
                val alias = TreePath.require("old-private")
                harness.registry.register(RootedPath(page.root, alias), page.rooted)
                val principal = Principal.Anonymous
                val created = visible.proposals.propose(
                    principal,
                    ProposeCommand.Edit(
                        pageId = page.id,
                        root = page.root,
                        baseHash = page.contentHash,
                        clientTargetPath = null,
                        proposedContent = "# Private\n\nnew\n".encodeToByteArray(),
                        rationale = "update",
                    ),
                ).shouldBeInstanceOf<ProposeOutcome.Created>()
                val hiddenPolicy = ContentPathPolicy.create(
                    fileEligibility = { it != page.path },
                    traversalEligibility = { true },
                    metadataEligibility = { true },
                )
                val hidden = harness.testRouteContext(
                    searchProvider = search,
                    policies = mapOf(RootName.PRIMARY to hiddenPolicy),
                )

                hidden.proposals.list(principal).shouldBeEmpty()
                hidden.proposals.get(principal, created.id).shouldBeNull()
                hidden.proposals.reject(principal, created.id, "no") shouldBe RejectOutcome.NotFound
                hidden.proposals.approve(principal, created.id) shouldBe ApplyOutcome.NotFound
                hidden.proposals.rebase(principal, created.id) shouldBe RebaseOutcome.NotFound
                harness.proposalRepository.findById(created.id)?.status shouldBe ProposalStatus.PENDING
                hidden.read.pageById(principal, page.id, null).shouldBeNull()
                hidden.read.pageById(principal, page.id, page.root).shouldBeNull()
                hidden.read.permalink(principal, page.id) shouldBe PermalinkResolution.Unknown
                hidden.read.permalinkAt(principal, page.root, page.id) shouldBe PermalinkResolution.Unknown
                hidden.read.resolveRootContentRedirect(principal, page.root, alias).shouldBeNull()
                hidden.read.pageByUrlPath(principal, page.root, alias).shouldBeNull()
                hidden.mutate.save(
                    principal,
                    SaveRequest(
                        pageId = page.id,
                        baseHash = page.contentHash,
                        bytes = "# Private\n\nforbidden\n".encodeToByteArray(),
                        expectedRoot = page.root,
                    ),
                ) shouldBe SaveResult.PageNotFound

                visible.proposals.get(principal, created.id).shouldNotBeNull()
                harness.proposalRepository.findById(created.id)?.status shouldBe ProposalStatus.PENDING
            }
        }
    }

    test("deleted eligible targets retain proposal decisions and terminal history") {
        withTempTree(seed = { root -> writePage(root, "page.md", "# Page\n\nold\n") }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val context = harness.testRouteContext(searchProvider = mockk(relaxed = true))
                val principal = Principal.Anonymous
                val page = harness.builder.current.pages.single()
                val target = root.resolve(page.path.value)
                val applied = context.proposals.propose(
                    principal,
                    ProposeCommand.Edit(
                        pageId = page.id,
                        root = page.root,
                        baseHash = page.contentHash,
                        clientTargetPath = null,
                        proposedContent = Files.readAllBytes(target) + "\napplied\n".encodeToByteArray(),
                        rationale = "apply before deletion",
                    ),
                ).shouldBeInstanceOf<ProposeOutcome.Created>()
                context.proposals.approve(principal, applied.id).shouldBeInstanceOf<ApplyOutcome.Applied>()

                val current = harness.builder.current.pages.single()
                val currentBytes = Files.readAllBytes(target)
                val conflicted = context.proposals.propose(
                    principal,
                    ProposeCommand.Edit(
                        pageId = current.id,
                        root = current.root,
                        baseHash = current.contentHash,
                        clientTargetPath = null,
                        proposedContent = currentBytes + "\nconflict\n".encodeToByteArray(),
                        rationale = "conflict after deletion",
                    ),
                ).shouldBeInstanceOf<ProposeOutcome.Created>()
                val rejected = context.proposals.propose(
                    principal,
                    ProposeCommand.Edit(
                        pageId = current.id,
                        root = current.root,
                        baseHash = current.contentHash,
                        clientTargetPath = null,
                        proposedContent = currentBytes + "\nreject\n".encodeToByteArray(),
                        rationale = "reject after deletion",
                    ),
                ).shouldBeInstanceOf<ProposeOutcome.Created>()

                Files.delete(target)
                harness.retirements.applyProofs(
                    proofs = listOf(
                        AbsenceProof.accepted(
                            root = current.root,
                            source = ProofSource.OPERATOR,
                            observationId = harness.retirements.observation(current.root),
                            bindingEpoch = harness.retirements.bindingEpoch(current.root),
                            covers = setOf(BindingRef(current.path, current.id)),
                        ),
                    ),
                    witnessed = emptySet(),
                    unavailableNow = { emptySet() },
                )
                harness.builder.rebuild()

                context.proposals.approve(principal, conflicted.id).shouldBeInstanceOf<ApplyOutcome.Conflicted>()
                harness.proposalRepository.findById(conflicted.id)?.status shouldBe ProposalStatus.CONFLICTED
                context.proposals.rebase(principal, conflicted.id) shouldBe RebaseOutcome.Gone
                harness.proposalRepository.findById(conflicted.id)?.let { row ->
                    row.status shouldBe ProposalStatus.FAILED
                    row.statusReason shouldBe "rebase_target_gone"
                }
                context.proposals.reject(principal, rejected.id, "deleted")
                    .shouldBeInstanceOf<RejectOutcome.Rejected>()

                val history = context.proposals.list(principal).associate { it.row.id to it.row.status }
                history[applied.id] shouldBe ProposalStatus.APPLIED
                history[conflicted.id] shouldBe ProposalStatus.FAILED
                history[rejected.id] shouldBe ProposalStatus.REJECTED
                context.proposals.get(principal, applied.id).shouldNotBeNull().row.status shouldBe ProposalStatus.APPLIED
                context.proposals.get(principal, conflicted.id).shouldNotBeNull().row.status shouldBe ProposalStatus.FAILED
                context.proposals.get(principal, rejected.id).shouldNotBeNull().row.status shouldBe ProposalStatus.REJECTED
            }
        }
    }

    test("new proposals outside configured scope are rejected without persistence") {
        withTempTree(seed = { root -> writePage(root, "visible.md", "# Visible\n") }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val policy = ContentPathPolicy.create(
                    fileEligibility = { it.value.startsWith("visible/") },
                    traversalEligibility = { true },
                    metadataEligibility = { true },
                )
                val context = harness.testRouteContext(
                    searchProvider = mockk(relaxed = true),
                    policies = mapOf(RootName.PRIMARY to policy),
                )

                context.proposals.propose(
                    Principal.Anonymous,
                    ProposeCommand.Create(
                        root = RootName.PRIMARY,
                        targetPath = TreePath.require("private/new.md"),
                        proposedContent = "# New\n".encodeToByteArray(),
                        rationale = "new",
                        pageId = null,
                    ),
                ) shouldBe ProposeOutcome.InvalidRequest("target_path is excluded by the root content policy.")

                val pageId = harness.identityProvider.next()
                val degraded = context.mutate.create(
                    Principal.Agent(harness.apiTokens.mint(label = "ci", mode = AgentMode.COMMIT).id),
                    CreateIntent(
                        pageId = pageId,
                        root = RootName.PRIMARY,
                        path = TreePath.require("private/degraded.md"),
                        bytes = "---\nid: ${pageId.value}\ntitle: New\n---\n\n# New\n".encodeToByteArray(),
                    ),
                )
                degraded.shouldBeInstanceOf<CreateOutcome.DirectCreated>().outcome shouldBe
                    WriteOutcome.InvalidLocation("target_path is excluded by the root content policy.")
                harness.proposalRepository.all().shouldBeEmpty()
            }
        }
    }

    test("standalone Mermaid files are refused by both proposal and direct create guards") {
        withTempTree(seed = {}) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val context = harness.testRouteContext(searchProvider = mockk(relaxed = true))
                val path = TreePath.require("diagrams/flow.mmd")

                context.proposals.propose(
                    Principal.Anonymous,
                    ProposeCommand.Create(
                        root = RootName.PRIMARY,
                        targetPath = path,
                        proposedContent = "graph TD\n".encodeToByteArray(),
                        rationale = "diagram",
                        pageId = null,
                    ),
                ) shouldBe ProposeOutcome.InvalidRequest("standalone Mermaid diagram document writes are unsupported")

                val pageId = harness.identityProvider.next()
                context.mutate.create(
                    Principal.Agent(harness.apiTokens.mint(label = "ci", mode = AgentMode.COMMIT).id),
                    CreateIntent(
                        pageId = pageId,
                        root = RootName.PRIMARY,
                        path = path,
                        bytes = "graph TD\n".encodeToByteArray(),
                    ),
                ).shouldBeInstanceOf<CreateOutcome.DirectCreated>().outcome shouldBe
                    WriteOutcome.InvalidLocation("standalone Mermaid diagram document writes are unsupported")
                harness.proposalRepository.all().shouldBeEmpty()
                Files.exists(root.resolve(path.value)) shouldBe false
            }
        }
    }

    test("an edit proposal is hidden when its current durable binding moves outside scope") {
        withTempTree(seed = { root -> writePage(root, "visible/page.md", "# Page\n\nold\n") }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val search = mockk<SearchProvider>(relaxed = true)
                val all = mapOf(RootName.PRIMARY to ContentPathPolicy.ALL)
                val visible = harness.testRouteContext(searchProvider = search, policies = all)
                val page = harness.builder.current.pages.single()
                val principal = Principal.Anonymous
                val created = visible.proposals.propose(
                    principal,
                    ProposeCommand.Edit(
                        pageId = page.id,
                        root = page.root,
                        baseHash = page.contentHash,
                        clientTargetPath = null,
                        proposedContent = "# Page\n\nnew\n".encodeToByteArray(),
                        rationale = "update",
                    ),
                ).shouldBeInstanceOf<ProposeOutcome.Created>()

                val hiddenPath = TreePath.require("private/page.md")
                harness.idMap.bind(
                    path = RootedPath(page.root, hiddenPath),
                    id = page.id,
                    materialized = true,
                    supersession = Supersession(
                        witnessed = setOf(RootedPath(page.root, page.path)),
                        scannedRoots = setOf(page.root),
                        registeredRoots = setOf(page.root),
                    ),
                ) shouldBe BindOutcome.Bound
                val storedTargetOnly = ContentPathPolicy.create(
                    fileEligibility = { it.value.startsWith("visible/") },
                    traversalEligibility = { true },
                    metadataEligibility = { true },
                )
                val hidden = harness.testRouteContext(
                    searchProvider = search,
                    policies = mapOf(RootName.PRIMARY to storedTargetOnly),
                )

                fun assertHiddenWithoutMutation() {
                    hidden.proposals.list(principal).shouldBeEmpty()
                    hidden.proposals.get(principal, created.id).shouldBeNull()
                    hidden.proposals.reject(principal, created.id, "no") shouldBe RejectOutcome.NotFound
                    hidden.proposals.approve(principal, created.id) shouldBe ApplyOutcome.NotFound
                    hidden.proposals.rebase(principal, created.id) shouldBe RebaseOutcome.NotFound
                    harness.proposalRepository.findById(created.id)?.status shouldBe ProposalStatus.PENDING
                }

                assertHiddenWithoutMutation()
                harness.retirements.applyProofs(
                    proofs = listOf(
                        AbsenceProof.accepted(
                            root = page.root,
                            source = ProofSource.OPERATOR,
                            observationId = harness.retirements.observation(page.root),
                            bindingEpoch = harness.retirements.bindingEpoch(page.root),
                            covers = setOf(BindingRef(hiddenPath, page.id)),
                        ),
                    ),
                    witnessed = emptySet(),
                    unavailableNow = { emptySet() },
                )
                assertHiddenWithoutMutation()
            }
        }
    }

    test("interrupted hidden edit and create stay APPLYING without reads and recover after reinclude") {
        withTempTree(seed = { root -> writePage(root, "private/edit.md", "# Edit\n\nold\n") }) { root ->
            val store = ReadCountingStore(LocalContentStore(root))
            IndexHarness(root, contentStore = store).use { harness ->
                harness.builder.rebuild()
                val visible = harness.testRouteContext(searchProvider = mockk(relaxed = true))
                val page = harness.builder.current.pages.single()
                val editTarget = root.resolve(page.path.value)
                val edit = visible.proposals.propose(
                    Principal.Anonymous,
                    ProposeCommand.Edit(
                        pageId = page.id,
                        root = page.root,
                        baseHash = page.contentHash,
                        clientTargetPath = null,
                        proposedContent = Files.readAllBytes(editTarget) + "\nlanded edit\n".encodeToByteArray(),
                        rationale = "edit",
                    ),
                ).shouldBeInstanceOf<ProposeOutcome.Created>()
                val createPath = TreePath.require("private/create.md")
                val create = visible.proposals.propose(
                    Principal.Anonymous,
                    ProposeCommand.Create(
                        root = RootName.PRIMARY,
                        targetPath = createPath,
                        proposedContent = "# Created\n".encodeToByteArray(),
                        rationale = "create",
                        pageId = null,
                    ),
                ).shouldBeInstanceOf<ProposeOutcome.Created>()
                harness.proposalRepository.claimApplying(edit.id) shouldBe true
                harness.proposalRepository.claimApplying(create.id) shouldBe true
                Files.write(editTarget, harness.proposalRepository.findById(edit.id)!!.proposedContent)
                Files.write(root.resolve(createPath.value), harness.proposalRepository.findById(create.id)!!.proposedContent)
                harness.builder.rebuild()

                val hiddenPolicy = ContentPathPolicy.create(
                    fileEligibility = { !it.value.startsWith("private/") },
                    traversalEligibility = { true },
                    metadataEligibility = { true },
                )
                val hiddenPolicies = mapOf(RootName.PRIMARY to hiddenPolicy)
                val beforeEdit = harness.proposalRepository.findById(edit.id)!!.snapshot()
                val beforeCreate = harness.proposalRepository.findById(create.id)!!.snapshot()
                store.resetReads()

                harness.recoveryService(hiddenPolicies).reconcileApplying()

                harness.proposalRepository.findById(edit.id)!!.snapshot() shouldBe beforeEdit
                harness.proposalRepository.findById(create.id)!!.snapshot() shouldBe beforeCreate
                store.classifiedReads shouldBe 0

                harness.recoveryService(mapOf(RootName.PRIMARY to ContentPathPolicy.ALL)).reconcileApplying()

                harness.proposalRepository.findById(edit.id)!!.status shouldBe ProposalStatus.APPLIED
                harness.proposalRepository.findById(create.id)!!.status shouldBe ProposalStatus.APPLIED
                store.classifiedReads shouldBe 2
            }
        }
    }

    test("interrupted eligible deletion still returns to PENDING") {
        withTempTree(seed = { root -> writePage(root, "gone.md", "# Gone\n\nold\n") }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val context = harness.testRouteContext(searchProvider = mockk(relaxed = true))
                val page = harness.builder.current.pages.single()
                val created = context.proposals.propose(
                    Principal.Anonymous,
                    ProposeCommand.Edit(
                        pageId = page.id,
                        root = page.root,
                        baseHash = page.contentHash,
                        clientTargetPath = null,
                        proposedContent = "# Gone\n\nnew\n".encodeToByteArray(),
                        rationale = "edit",
                    ),
                ).shouldBeInstanceOf<ProposeOutcome.Created>()
                harness.proposalRepository.claimApplying(created.id) shouldBe true
                Files.delete(root.resolve(page.path.value))
                harness.retirements.applyProofs(
                    proofs = listOf(
                        AbsenceProof.accepted(
                            root = page.root,
                            source = ProofSource.OPERATOR,
                            observationId = harness.retirements.observation(page.root),
                            bindingEpoch = harness.retirements.bindingEpoch(page.root),
                            covers = setOf(BindingRef(page.path, page.id)),
                        ),
                    ),
                    witnessed = emptySet(),
                    unavailableNow = { emptySet() },
                )
                harness.builder.rebuild()

                harness.recoveryService(mapOf(RootName.PRIMARY to ContentPathPolicy.ALL)).reconcileApplying()

                harness.proposalRepository.findById(created.id)!!.status shouldBe ProposalStatus.PENDING
            }
        }
    }

    test("post-claim recovery leaves a moved-to-hidden edit APPLYING without reading content") {
        withTempTree(seed = { root -> writePage(root, "visible/page.md", "# Page\n\nold\n") }) { root ->
            val store = ReadCountingStore(LocalContentStore(root))
            IndexHarness(root, contentStore = store).use { harness ->
                harness.builder.rebuild()
                val context = harness.testRouteContext(searchProvider = mockk(relaxed = true))
                val page = harness.builder.current.pages.single()
                val created = context.proposals.propose(
                    Principal.Anonymous,
                    ProposeCommand.Edit(
                        pageId = page.id,
                        root = page.root,
                        baseHash = page.contentHash,
                        clientTargetPath = null,
                        proposedContent = "# Page\n\nnew\n".encodeToByteArray(),
                        rationale = "edit",
                    ),
                ).shouldBeInstanceOf<ProposeOutcome.Created>()
                val scopedPolicy = ContentPathPolicy.create(
                    fileEligibility = { !it.value.startsWith("private/") },
                    traversalEligibility = { true },
                    metadataEligibility = { true },
                )
                val service = harness.recoveryService(mapOf(RootName.PRIMARY to scopedPolicy))
                val hiddenPath = RootedPath(page.root, TreePath.require("private/page.md"))
                store.resetReads()

                shouldThrow<IllegalStateException> {
                    service.apply(
                        approveGrantForTests(),
                        created.id,
                        ProposalApprover("human", "u1", "Reviewer"),
                        ProposalContentWriter { _, _, _ ->
                            harness.idMap.bind(
                                path = hiddenPath,
                                id = page.id,
                                materialized = true,
                                supersession = Supersession(
                                    witnessed = setOf(RootedPath(page.root, page.path)),
                                    scannedRoots = setOf(page.root),
                                    registeredRoots = setOf(page.root),
                                ),
                            ) shouldBe BindOutcome.Bound
                            throw IllegalStateException("write failed after the durable binding moved")
                        },
                    )
                }

                harness.proposalRepository.findById(created.id)!!.status shouldBe ProposalStatus.APPLYING
                store.classifiedReads shouldBe 0
            }
        }
    }

    test("a missing root policy hides durable proposals without mutation until configuration returns") {
        withTempTree(seed = { root -> writePage(root, "page.md", "# Page\n\nold\n") }) { root ->
            IndexHarness(root).use { harness ->
                harness.builder.rebuild()
                val visible = harness.testRouteContext(searchProvider = mockk(relaxed = true))
                val page = harness.builder.current.pages.single()
                val created = visible.proposals.propose(
                    Principal.Anonymous,
                    ProposeCommand.Edit(
                        pageId = page.id,
                        root = page.root,
                        baseHash = page.contentHash,
                        clientTargetPath = null,
                        proposedContent = "# Page\n\nnew\n".encodeToByteArray(),
                        rationale = "edit",
                    ),
                ).shouldBeInstanceOf<ProposeOutcome.Created>()
                harness.proposalRepository.claimApplying(created.id) shouldBe true
                val before = harness.proposalRepository.findById(created.id)!!.snapshot()
                val missing = harness.testRouteContext(
                    searchProvider = mockk(relaxed = true),
                    policies = emptyMap(),
                )

                missing.proposals.list(Principal.Anonymous).shouldBeEmpty()
                missing.proposals.get(Principal.Anonymous, created.id).shouldBeNull()
                missing.proposals.reject(Principal.Anonymous, created.id, "no") shouldBe RejectOutcome.NotFound
                missing.proposals.approve(Principal.Anonymous, created.id) shouldBe ApplyOutcome.NotFound
                missing.proposals.rebase(Principal.Anonymous, created.id) shouldBe RebaseOutcome.NotFound
                harness.recoveryService(emptyMap()).reconcileApplying()
                harness.proposalRepository.findById(created.id)!!.snapshot() shouldBe before

                visible.proposals.get(Principal.Anonymous, created.id).shouldNotBeNull()
                harness.recoveryService(mapOf(RootName.PRIMARY to ContentPathPolicy.ALL)).reconcileApplying()
                harness.proposalRepository.findById(created.id)!!.status shouldBe ProposalStatus.PENDING
                visible.proposals.reject(Principal.Anonymous, created.id, "configured")
                    .shouldBeInstanceOf<RejectOutcome.Rejected>()
            }
        }
    }
})

private class ReadCountingStore(private val delegate: ContentStore) : ContentStore by delegate {
    var classifiedReads: Int = 0
        private set

    override fun readClassified(path: TreePath) = delegate.readClassified(path).also { classifiedReads += 1 }

    fun resetReads() {
        classifiedReads = 0
    }
}

private fun IndexHarness.recoveryService(policies: Map<RootName, ContentPathPolicy>): ProposalService {
    val resolver = PageRootResolver(idMap, rootRegistry, policies)
    return ProposalService(
        repository = proposalRepository,
        citations = CitationFactory(),
        baseReader = IndexProposalBaseReader(builder, stores, AbsenceClassifier(idMap, policies), policies),
        proposalIdProvider = UuidV7ProposalIdProvider(),
        clock = Clock.System,
        rootStatus = { root -> resolver.statusOf(root, availability.current()) },
        proposalEligibility = resolver::proposalEligible,
    )
}

private fun ProposalRow.snapshot(): List<Any?> = listOf(
    id,
    operation,
    pageId,
    root,
    baseHash,
    targetPath,
    proposedContent.toList(),
    rationale,
    diffArtifact,
    status,
    authorIssuer,
    authorExternalId,
    authorLabel,
    approverIssuer,
    approverExternalId,
    decisionComment,
    createdAt,
    decidedAt,
    appliedCommit,
    statusReason,
)
