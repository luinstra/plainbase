package com.plainbase.domain.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.plainbase.domain.content.ContentFile
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.ScanResult
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.IdentityIssue
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.repository.BindOutcome
import com.plainbase.domain.repository.IdBinding
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.PageCheckpointRepository
import com.plainbase.domain.repository.Supersession
import com.plainbase.domain.repository.replaceFrom
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootLimbo
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.Witness
import com.plainbase.frameworks.filesystem.LocalContentStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.markdown.FlexmarkRenderer
import com.plainbase.frameworks.markdown.FrontmatterReader
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
import com.plainbase.frameworks.sqldelight.SqlDelightUrlAliasRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Instant

/** In-place identity-resolution characterizations, followed by the extracted helper's direct ordering oracle. */
class IndexIdentityAssignmentsTest : FunSpec({

    test("registered but unscanned root keeps its incumbent and reassigns a competing claim") {
        withIdentityTrees { primaryDir, extraDir ->
            val extra = RootName.require("extra")
            val registry = RootRegistry.of(listOf(localRoot("docs", primaryDir), localRoot("extra", extraDir)))
            val extraStore = IdentityPartialScanStore(LocalContentStore(extraDir), TreePath.require("a.md"))
            val graph = IdentityAssignmentsGraph(
                registry = registry,
                sources = listOf(
                    IndexBuilder.Source(registry.primary, LocalContentStore(primaryDir), NoOpHistoryProvider),
                    IndexBuilder.Source(requireNotNull(registry.byName(extra)), extraStore, NoOpHistoryProvider),
                ),
            )
            graph.use {
                writeIdentityPage(primaryDir, "anchor.md", ID_PARTIAL_PRIMARY, "Primary")
                writeIdentityPage(extraDir, "a.md", ID_PARTIAL_EXTRA, "Incumbent")
                graph.builder.rebuild()
                graph.realIdMap.bindings() shouldContainExactly listOf(
                    IdBinding(RootedPath(RootName.PRIMARY, TreePath.require("anchor.md")), ID_PARTIAL_PRIMARY, true),
                    IdBinding(RootedPath(extra, TreePath.require("a.md")), ID_PARTIAL_EXTRA, true),
                )

                writeIdentityPage(extraDir, "b.md", ID_PARTIAL_EXTRA, "Competing")
                extraStore.armed = true
                val snapshot = graph.builder.rebuild()
                val observed = extraStore.observed.shouldNotBeNull()
                observed.complete shouldBe false
                observed.files.map { it.path.value } shouldContainExactly listOf("b.md")
                Files.exists(extraDir.resolve("a.md")) shouldBe true

                val bPath = RootedPath(extra, TreePath.require("b.md"))
                val page = snapshot.byPath.getValue(bPath)
                page.id shouldNotBe ID_PARTIAL_EXTRA
                graph.realIdMap.find(RootedPath(extra, TreePath.require("a.md"))) shouldBe
                    IdBinding(RootedPath(extra, TreePath.require("a.md")), ID_PARTIAL_EXTRA, true)
                graph.realIdMap.find(bPath) shouldBe IdBinding(bPath, page.id, materialized = false)
                snapshot.byPath shouldNotContainKey RootedPath(extra, TreePath.require("a.md"))
                graph.limbo.current()[extra] shouldBe setOf(BindingRef(TreePath.require("a.md"), ID_PARTIAL_EXTRA))

                val issue = IdentityIssue.DuplicateId(
                    id = ID_PARTIAL_EXTRA,
                    root = extra,
                    keptPath = TreePath.require("a.md"),
                    reassignedPath = TreePath.require("b.md"),
                )
                graph.recordedIssues shouldContainExactly listOf(issue)
                graph.realIdMap.issues() shouldContainExactly listOf(issue)
            }
        }
    }

    test("pre-pass id-map loser keeps its duplicate issue while binds replay in order") {
        withTempTree(seed = { root -> writeIdentityPage(root, "a.md", ID_PREPASS, "Warm") }) { root ->
            val registry = RootRegistry.of(listOf(localRoot("docs", root)))
            val graph = IdentityAssignmentsGraph.single(root, registry)
            graph.use {
                graph.builder.rebuild()
                graph.realIdMap.find(RootedPath(RootName.PRIMARY, TreePath.require("a.md"))) shouldBe
                    IdBinding(RootedPath(RootName.PRIMARY, TreePath.require("a.md")), ID_PREPASS, true)
                graph.bindAttempts.clear()
                graph.recordedIssues.clear()
                graph.events.clear()

                writeIdentityPageWithoutId(root, "a.md", "Stripped")
                writeIdentityPage(root, "b.md", ID_PREPASS, "Claimant")
                val snapshot = graph.builder.rebuild()
                val aPath = RootedPath(RootName.PRIMARY, TreePath.require("a.md"))
                val bPath = RootedPath(RootName.PRIMARY, TreePath.require("b.md"))
                val reassigned = snapshot.byPath.getValue(aPath)
                snapshot.byPath.getValue(bPath).id shouldBe ID_PREPASS
                reassigned.id shouldNotBe ID_PREPASS
                reassigned.materialized shouldBe false
                graph.bindAttempts.map { it.path } shouldContainExactly listOf(bPath, aPath)

                val issue = IdentityIssue.DuplicateId(
                    id = ID_PREPASS,
                    root = RootName.PRIMARY,
                    keptPath = TreePath.require("b.md"),
                    reassignedPath = TreePath.require("a.md"),
                )
                val redirect = IdentityIssue.RedirectConflict(
                    root = RootName.PRIMARY,
                    path = TreePath.require("a"),
                    message = "move alias for page $ID_PREPASS dropped: shadowed by a live canonical path",
                )
                graph.recordedIssues shouldContainExactly listOf(issue, redirect)
                graph.realIdMap.issues() shouldContainExactly listOf(issue, redirect)
                graph.events shouldContainExactly listOf(
                    "bound:b.md",
                    "bound:a.md",
                    "record:$issue",
                    "record:$redirect",
                )
            }
        }
    }

    test("later bind refusal leaves earlier durable effects and the old publication holder") {
        withTempTree(seed = { root ->
            writeIdentityPage(root, "a.md", ID_REFUSAL_OLD, "A")
            writeIdentityPage(root, "z-anchor.md", ID_REFUSAL_HELD, "Anchor")
        }) { root ->
            val registry = RootRegistry.of(listOf(localRoot("docs", root)))
            val graph = IdentityAssignmentsGraph.single(root, registry)
            graph.use {
                val warm = graph.builder.rebuild()
                val oldCheckpoint = graph.checkpoints.load()
                graph.bindAttempts.clear()
                graph.events.clear()
                graph.observedSnapshots.clear()
                graph.observedCheckpoints.clear()

                writeIdentityPage(root, "a.md", ID_REFUSAL_NEW, "A re-identified")
                writeIdentityPage(root, "b.md", ID_REFUSAL_HELD, "Copied anchor")
                writeIdentityPage(root, "c.md", ID_REFUSAL_THIRD, "C")
                graph.refusalPath = RootedPath(RootName.PRIMARY, TreePath.require("b.md"))
                graph.refusalHolder = RootedPath(RootName.PRIMARY, TreePath.require("z-anchor.md"))

                val thrown = shouldThrow<IllegalStateException> { graph.builder.rebuild() }
                val refused = graph.bindAttempts.single { it.path.path == TreePath.require("b.md") }
                thrown.message shouldBe refusalMessage(refused.id, refused.path, graph.refusalHolder)
                graph.bindAttempts.map { it.path } shouldContainExactly listOf(
                    RootedPath(RootName.PRIMARY, TreePath.require("a.md")),
                    RootedPath(RootName.PRIMARY, TreePath.require("b.md")),
                )
                graph.realIdMap.find(RootedPath(RootName.PRIMARY, TreePath.require("a.md"))) shouldBe
                    IdBinding(RootedPath(RootName.PRIMARY, TreePath.require("a.md")), ID_REFUSAL_NEW, true)
                graph.realIdMap.retiredAt(RootName.PRIMARY, ID_REFUSAL_OLD)?.path shouldBe
                    RootedPath(RootName.PRIMARY, TreePath.require("a.md"))
                graph.realIdMap.find(RootedPath(RootName.PRIMARY, TreePath.require("b.md"))) shouldBe null
                graph.realIdMap.find(RootedPath(RootName.PRIMARY, TreePath.require("c.md"))) shouldBe null
                graph.builder.current shouldBeSameInstanceAs warm
                graph.checkpoints.load() shouldBe oldCheckpoint
                graph.observedSnapshots shouldBe emptyList()
                graph.observedCheckpoints shouldBe emptyList()

                graph.refusalPath = null
                graph.refusalHolder = null
                graph.bindAttempts.clear()
                graph.recordedIssues.clear()
                graph.events.clear()
                graph.observedSnapshots.clear()
                graph.observedCheckpoints.clear()

                val retried = graph.builder.rebuild()
                graph.builder.current shouldBeSameInstanceAs retried
                graph.observedSnapshots.single() shouldBeSameInstanceAs retried
                graph.observedCheckpoints.single() shouldBe graph.checkpoints.load()
                retried.byPath.keys shouldContainExactlyInAnyOrder listOf(
                    RootedPath(RootName.PRIMARY, TreePath.require("a.md")),
                    RootedPath(RootName.PRIMARY, TreePath.require("b.md")),
                    RootedPath(RootName.PRIMARY, TreePath.require("c.md")),
                    RootedPath(RootName.PRIMARY, TreePath.require("z-anchor.md")),
                )
                val retryA = RootedPath(RootName.PRIMARY, TreePath.require("a.md"))
                val retryB = RootedPath(RootName.PRIMARY, TreePath.require("b.md"))
                val retryC = RootedPath(RootName.PRIMARY, TreePath.require("c.md"))
                val retryAnchor = RootedPath(RootName.PRIMARY, TreePath.require("z-anchor.md"))
                val retryBId = retried.byPath.getValue(retryB).id
                graph.realIdMap.find(retryA) shouldBe IdBinding(retryA, ID_REFUSAL_NEW, true)
                graph.realIdMap.find(retryB) shouldBe IdBinding(retryB, retryBId, false)
                graph.realIdMap.find(retryC) shouldBe IdBinding(retryC, ID_REFUSAL_THIRD, true)
                graph.realIdMap.find(retryAnchor) shouldBe IdBinding(retryAnchor, ID_REFUSAL_HELD, true)
                retryBId shouldNotBe ID_REFUSAL_HELD
                graph.realIdMap.retiredAt(RootName.PRIMARY, ID_REFUSAL_OLD)?.path shouldBe retryA
                graph.realIdMap.issues() shouldContainExactly listOf(
                    IdentityIssue.DuplicateId(
                        id = ID_REFUSAL_HELD,
                        root = RootName.PRIMARY,
                        keptPath = TreePath.require("z-anchor.md"),
                        reassignedPath = TreePath.require("b.md"),
                    ),
                )
            }
        }
    }

    test("mixed scan identity and alias issues retain their exact persisted order") {
        withTempTree(seed = { root -> writeIdentityPage(root, "anchor.md", ID_MIXED_ANCHOR, "Anchor") }) { root ->
            val registry = RootRegistry.of(listOf(localRoot("docs", root)))
            val graph = IdentityAssignmentsGraph.single(root, registry)
            graph.use {
                graph.builder.rebuild()
                graph.recordedIssues.clear()
                graph.bindAttempts.clear()
                graph.events.clear()
                writeIdentityPage(root, "a b.md", ID_MIXED_U, "Spaced")
                writeIdentityPage(root, "a-b.md", ID_MIXED_V, "Hyphenated")
                writeIdentityPage(root, "copy-a.md", ID_MIXED_ANCHOR, "Copy A", redirectFrom = "anchor.md")
                writeIdentityPage(root, "copy-b.md", ID_MIXED_ANCHOR, "Copy B")

                val rootLogger = LoggerFactory.getLogger(ROOT_LOGGER_NAME) as Logger
                val appender = ListAppender<ILoggingEvent>().apply { start() }
                rootLogger.addAppender(appender)
                try {
                    val snapshot = graph.builder.rebuild()
                    val copyA = snapshot.byPath.getValue(RootedPath(RootName.PRIMARY, TreePath.require("copy-a.md")))
                    val copyB = snapshot.byPath.getValue(RootedPath(RootName.PRIMARY, TreePath.require("copy-b.md")))
                    copyA.id shouldNotBe ID_MIXED_ANCHOR
                    copyB.id shouldNotBe ID_MIXED_ANCHOR
                    copyA.id shouldNotBe copyB.id
                    copyA.materialized shouldBe false
                    copyB.materialized shouldBe false
                } finally {
                    rootLogger.detachAppender(appender)
                }

                val scanIssue = IdentityIssue.PathSlugCollision(
                    root = RootName.PRIMARY,
                    keptPath = TreePath.require("a b.md"),
                    loserPath = TreePath.require("a-b.md"),
                )
                val firstDuplicate = IdentityIssue.DuplicateId(
                    id = ID_MIXED_ANCHOR,
                    root = RootName.PRIMARY,
                    keptPath = TreePath.require("anchor.md"),
                    reassignedPath = TreePath.require("copy-a.md"),
                )
                val secondDuplicate = firstDuplicate.copy(reassignedPath = TreePath.require("copy-b.md"))
                val redirect = IdentityIssue.RedirectConflict(
                    root = RootName.PRIMARY,
                    path = TreePath.require("anchor"),
                    message = "redirect_from of copy-a.md ignored: a live canonical path claims it",
                )
                val expected = listOf(scanIssue, firstDuplicate, secondDuplicate, redirect)
                graph.recordedIssues shouldContainExactly expected
                graph.realIdMap.issues() shouldContainExactlyInAnyOrder expected
                graph.events shouldContainExactly listOf(
                    "record:$scanIssue",
                    "bound:a b.md",
                    "bound:a-b.md",
                    "bound:anchor.md",
                    "bound:copy-a.md",
                    "record:$firstDuplicate",
                    "bound:copy-b.md",
                    "record:$secondDuplicate",
                    "record:$redirect",
                )

                val identityWarning = appender.list
                    .filter { it.level == Level.WARN }
                    .map { it.formattedMessage }
                    .single { "identity issue(s) this pass, unresolved until the tree changes" in it }
                identityWarning shouldContain "4 identity issue(s) this pass"
                identityWarning shouldNotContain "(+"
                val warningSummaries = listOf(
                    "path_slug_collision docs:a b.md owns the URL, a-b.md is id-only",
                    "duplicate_id $ID_MIXED_ANCHOR: docs:anchor.md resolved first, copy-a.md reassigned",
                    "duplicate_id $ID_MIXED_ANCHOR: docs:anchor.md resolved first, copy-b.md reassigned",
                    "redirect_conflict docs:anchor: redirect_from of copy-a.md ignored: a live canonical path claims it",
                )
                warningSummaries.forEach { identityWarning shouldContain it }
                warningSummaries.zipWithNext().forEach { (earlier, later) ->
                    (identityWarning.indexOf(earlier) < identityWarning.indexOf(later)) shouldBe true
                }
            }
        }
    }

    test("direct helper: identity issues are persisted before immediate ordered append") {
        withTempTree(seed = {}) { root ->
            val registry = RootRegistry.of(listOf(localRoot("docs", root)))
            val graph = IdentityAssignmentsGraph.single(root, registry)
            graph.use {
                val scanIssue = IdentityIssue.PathSlugCollision(
                    root = RootName.PRIMARY,
                    keptPath = TreePath.require("anchor.md"),
                    loserPath = TreePath.require("copy-a.md"),
                )
                val firstDuplicate = IdentityIssue.DuplicateId(
                    id = ID_MIXED_ANCHOR,
                    root = RootName.PRIMARY,
                    keptPath = TreePath.require("anchor.md"),
                    reassignedPath = TreePath.require("copy-a.md"),
                )
                val secondDuplicate = firstDuplicate.copy(reassignedPath = TreePath.require("copy-b.md"))
                val raised = IdentityIssueAccumulator(graph.idMap, graph.events).apply { seed(scanIssue) }
                graph.issueAccumulator = { raised }
                val paths = listOf("anchor.md", "copy-a.md", "copy-b.md").map(TreePath::require)
                val scans = listOf(
                    SourceScan(
                        root = RootName.PRIMARY,
                        drafts = paths.map { path -> identityDraft(path, ID_MIXED_ANCHOR) },
                        folders = emptyList(),
                        assets = emptySet(),
                        urls = CanonicalUrlBuilder.Result(byPage = emptyMap(), issues = emptyList()),
                        commits = emptyMap(),
                        issues = emptyList(),
                        complete = true,
                        pageReadsComplete = true,
                        unread = emptySet(),
                    ),
                )
                val rootedPaths = paths.map { path -> RootedPath(RootName.PRIMARY, path) }
                val witnessed = rootedPaths.associateWith { Witness(ID_MIXED_ANCHOR) }

                val assignments = IndexIdentityAssignments(graph.idMap, graph.identity, graph.patcher)
                    .resolveIdentities(
                        scans = scans,
                        witnessed = witnessed,
                        scannedRoots = setOf(RootName.PRIMARY),
                        registeredRoots = setOf(RootName.PRIMARY),
                        raised = raised,
                    )

                raised shouldContainExactly listOf(scanIssue, firstDuplicate, secondDuplicate)
                graph.realIdMap.issues() shouldContainExactly listOf(firstDuplicate, secondDuplicate)
                assignments.keys.map { it.path.value } shouldContainExactlyInAnyOrder paths.map(TreePath::value)
                assignments.getValue(RootedPath(RootName.PRIMARY, TreePath.require("anchor.md"))).id shouldBe ID_MIXED_ANCHOR
                val loserIds = listOf("copy-a.md", "copy-b.md").map { path ->
                    assignments.getValue(RootedPath(RootName.PRIMARY, TreePath.require(path))).id
                }
                loserIds.contains(ID_MIXED_ANCHOR) shouldBe false
                loserIds.distinct() shouldHaveSize 2
                graph.events shouldContainExactly listOf(
                    "bound:anchor.md",
                    "bound:copy-a.md",
                    "record-start:$firstDuplicate:accumulator=false",
                    "record-complete:$firstDuplicate:sql=true",
                    "append:$firstDuplicate:sql=true",
                    "bound:copy-b.md",
                    "record-start:$secondDuplicate:accumulator=false",
                    "record-complete:$secondDuplicate:sql=true",
                    "append:$secondDuplicate:sql=true",
                )
            }
        }
    }
})

private val ID_PARTIAL_PRIMARY = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b51")
private val ID_PARTIAL_EXTRA = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b52")
private val ID_PREPASS = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b53")
private val ID_REFUSAL_OLD = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b54")
private val ID_REFUSAL_HELD = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b55")
private val ID_REFUSAL_NEW = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b56")
private val ID_REFUSAL_THIRD = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b57")
private val ID_MIXED_ANCHOR = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b58")
private val ID_MIXED_U = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b59")
private val ID_MIXED_V = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")

private fun writeIdentityPage(root: Path, path: String, id: PageId, title: String, redirectFrom: String? = null) {
    val redirect = redirectFrom?.let { "redirect_from: $it\n" }.orEmpty()
    writePage(root, path, "---\nid: ${id.value}\ntitle: $title\n$redirect---\n\n# $title\n")
}

private fun writeIdentityPageWithoutId(root: Path, path: String, title: String) {
    writePage(root, path, "---\ntitle: $title\n---\n\n# $title\n")
}

private fun identityDraft(path: TreePath, id: PageId): Draft {
    val bytes = "---\nid: ${id.value}\n---\n".toByteArray()
    return Draft(ContentFile(path = path, rawName = path.value), bytes, Frontmatter.EMPTY)
}

private fun <T> withIdentityTrees(block: (Path, Path) -> T): T {
    val primary = Files.createTempDirectory("plainbase-identity-primary")
    val extra = Files.createTempDirectory("plainbase-identity-extra")
    return try {
        block(primary, extra)
    } finally {
        primary.toFile().deleteRecursively()
        extra.toFile().deleteRecursively()
    }
}

private fun refusalMessage(id: PageId, path: RootedPath, heldBy: RootedPath?): String =
    "identity resolution awarded ${id.value} to ${path.path.value} in '${path.root}', and the bind REFUSED it: " +
        "held by $heldBy. The resolver and the bind gate disagree about who owns that id - no supersession is safe under that."

private class IdentityAssignmentsFixedClock : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)
}

private data class BindAttempt(val path: RootedPath, val id: PageId)

private class IdentityAssignmentsGraph(
    val registry: RootRegistry,
    sources: List<IndexBuilder.Source>,
) : AutoCloseable {
    private val driver = DatabaseFactory.createInMemoryDriver()
    private val database = DatabaseFactory.createDatabase(driver)
    private val fixedClock = IdentityAssignmentsFixedClock()
    val identity = PageIdentityService(TestIdProvider())
    val patcher = FrontmatterPatcher()

    val realIdMap = SqlDelightIdMapRepository(database)
    val recordedIssues = mutableListOf<IdentityIssue>()
    val events = mutableListOf<String>()
    val bindAttempts = mutableListOf<BindAttempt>()
    var issueAccumulator: (() -> List<IdentityIssue>)? = null
    var refusalPath: RootedPath? = null
    var refusalHolder: RootedPath? = null
    val idMap: IdMapRepository = IdentityAssignmentsRecordingIdMap(
        delegate = realIdMap,
        recordedIssues = recordedIssues,
        events = events,
        bindAttempts = bindAttempts,
        refusalPath = { refusalPath },
        refusalHolder = { refusalHolder },
        issueAccumulator = { issueAccumulator?.invoke() },
    )
    val checkpoints: PageCheckpointRepository = SqlDelightPageCheckpointRepository(database)
    val aliases = UrlAliasRegistry(SqlDelightUrlAliasRepository(database))
    val availability = RootAvailability(fixedClock)
    val limbo = RootLimbo()
    val observedSnapshots = mutableListOf<PageIndex>()
    val observedCheckpoints = mutableListOf<Map<RootedPageId, TreePath?>>()
    val builder: IndexBuilder

    init {
        builder = IndexBuilder(
            sources = sources,
            frontmatterParser = FrontmatterReader(),
            rendererFactory = { view -> FlexmarkRenderer(view) },
            identity = identity,
            patcher = patcher,
            idMap = idMap,
            aliasRegistry = aliases,
            checkpoint = checkpoints,
            citations = CitationFactory(),
            rootRank = registry::rank,
            registeredRoots = registry.roots.map { it.name }.toSet(),
            listeners = listOf(
                IndexBuilder.PublicationListener(checkpoints::replaceFrom),
                IndexBuilder.PublicationListener { snapshot, _ ->
                    observedSnapshots += snapshot
                    observedCheckpoints += checkpoints.load()
                },
            ),
            availability = availability,
            limbo = limbo,
        )
    }

    override fun close() = driver.close()

    companion object {
        fun single(root: Path, registry: RootRegistry): IdentityAssignmentsGraph = IdentityAssignmentsGraph(
            registry = registry,
            sources = listOf(IndexBuilder.Source(registry.primary, LocalContentStore(root), NoOpHistoryProvider)),
        )
    }
}

private class IdentityAssignmentsRecordingIdMap(
    private val delegate: IdMapRepository,
    private val recordedIssues: MutableList<IdentityIssue>,
    private val events: MutableList<String>,
    private val bindAttempts: MutableList<BindAttempt>,
    private val refusalPath: () -> RootedPath?,
    private val refusalHolder: () -> RootedPath?,
    private val issueAccumulator: () -> List<IdentityIssue>?,
) : IdMapRepository by delegate {
    override fun bind(path: RootedPath, id: PageId, materialized: Boolean, supersession: Supersession): BindOutcome {
        bindAttempts += BindAttempt(path, id)
        val outcome = if (path == refusalPath()) {
            BindOutcome.Refused(id, requireNotNull(refusalHolder()), retired = false)
        } else {
            delegate.bind(path, id, materialized, supersession)
        }
        if (outcome is BindOutcome.Bound) events += "bound:${path.path.value}"
        return outcome
    }

    override fun record(issue: IdentityIssue) {
        val accumulatorContains = issueAccumulator()?.contains(issue)
        if (accumulatorContains == null) {
            delegate.record(issue)
            recordedIssues += issue
            events += "record:$issue"
        } else {
            events += "record-start:$issue:accumulator=$accumulatorContains"
            delegate.record(issue)
            events += "record-complete:$issue:sql=${issue in delegate.issues()}"
            recordedIssues += issue
        }
    }
}

private class IdentityIssueAccumulator(
    private val idMap: IdMapRepository,
    private val events: MutableList<String>,
) : AbstractMutableList<IdentityIssue>() {
    private val values = mutableListOf<IdentityIssue>()

    fun seed(issue: IdentityIssue) {
        values += issue
    }

    override val size: Int get() = values.size

    override fun get(index: Int): IdentityIssue = values[index]

    override fun add(index: Int, element: IdentityIssue) {
        events += "append:$element:sql=${element in idMap.issues()}"
        values.add(index, element)
    }

    override fun set(index: Int, element: IdentityIssue): IdentityIssue = values.set(index, element)

    override fun removeAt(index: Int): IdentityIssue = values.removeAt(index)
}

private class IdentityPartialScanStore(
    private val delegate: ContentStore,
    private val omitted: TreePath,
) : ContentStore by delegate {
    var armed = false
    var observed: ScanResult? = null

    override fun scan(): ScanResult {
        val real = delegate.scan()
        val result = if (armed) {
            real.copy(files = real.files.filterNot { it.path == omitted }, complete = false)
        } else {
            real
        }
        observed = result
        return result
    }
}
