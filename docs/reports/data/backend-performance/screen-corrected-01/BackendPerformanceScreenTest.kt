package com.plainbase.performance

import app.cash.sqldelight.db.SqlDriver
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.page.ProposalId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.ProposalOperation
import com.plainbase.domain.repository.ProposalRow
import com.plainbase.domain.repository.ProposalStatus
import com.plainbase.domain.repository.ProposalSummaryRow
import com.plainbase.domain.repository.Role
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.CreateIntent
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.SearchIndexer
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.domain.service.WriteIntent
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.ktor.testRouteContext
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.time.Instant

private const val ENABLED_PROPERTY = "plainbase.performance.screen.enabled"
private const val OUTPUT_PROPERTY = "plainbase.performance.screen.output"
private const val FIXTURES_PROPERTY = "plainbase.performance.screen.fixtures"
private const val MAX_FIXTURE_BYTES = 512L * 1024L * 1024L
private const val MAX_EVIDENCE_BYTES = 20L * 1024L * 1024L
private const val PAGE_BYTES = 4 * 1024
private const val SIZE_DEADLINE_NS = 2L * 60L * 1_000_000_000L
private const val LINK_ROOT = "docs"
private const val LINK_PATH = "pages/page-0000.md"
private const val SUBJECT = "performance-screen"
private const val EPOCH_MILLIS = 1_700_000_000_000L
private const val PROPOSAL_EDIT_TARGET_COUNT = 10
private val PROPOSAL_STATUS_COUNTS = mapOf(
    250 to mapOf(
        ProposalStatus.APPLIED to 20,
        ProposalStatus.REJECTED to 20,
        ProposalStatus.FAILED to 20,
        ProposalStatus.PENDING to 30,
        ProposalStatus.CONFLICTED to 10,
    ),
    1_000 to mapOf(
        ProposalStatus.APPLIED to 100,
        ProposalStatus.REJECTED to 100,
        ProposalStatus.FAILED to 100,
        ProposalStatus.PENDING to 150,
        ProposalStatus.CONFLICTED to 50,
    ),
    3_000 to mapOf(
        ProposalStatus.APPLIED to 200,
        ProposalStatus.REJECTED to 200,
        ProposalStatus.FAILED to 200,
        ProposalStatus.PENDING to 300,
        ProposalStatus.CONFLICTED to 100,
    ),
)
private val PROPOSAL_EDIT_TARGET_INDICES = (0 until PROPOSAL_EDIT_TARGET_COUNT).toList()
private val SIZES = listOf(250, 1_000, 3_000)
private val LINK_SOURCE = RootedPath(RootName.PRIMARY, TreePath.require(LINK_PATH))

/** The opt-in, bounded Plan05 backend screen; this class is excluded from ordinary JVM discovery. */
class BackendPerformanceScreenTest {

    @Test
    fun `the bounded backend screen records complete observations`() {
        Screen().run()
    }
}

private class Screen {
    private val cells = listOf(
        Cell(Operation.LINK, warmups = 5, observations = 20),
        Cell(Operation.LIST, warmups = 5, observations = 20),
        Cell(Operation.SAVE, warmups = 5, observations = 20),
        Cell(Operation.CREATE, warmups = 2, observations = 5),
    )
    private val principal = Principal.Human("builtin", SUBJECT)
    private val citationFactory = CitationFactory()

    fun run() {
        check(System.getProperty(ENABLED_PROPERTY) == "true") {
            "backend performance screen requires the dedicated Gradle task"
        }
        val inputArguments = ManagementFactory.getRuntimeMXBean().inputArguments
        val agents = inputArguments.filter { argument ->
            argument.contains("-javaagent") || argument.contains("-agentpath") || argument.contains("-agentlib")
        }
        check(agents.isEmpty()) { "performance screen refuses instrumentation agents: ${agents.joinToString()}" }

        val outputRoot = Path.of(requireNotNull(System.getProperty(OUTPUT_PROPERTY)))
        val fixtureRoot = Path.of(requireNotNull(System.getProperty(FIXTURES_PROPERTY)))
        val sourceStagingRoot = Path.of(requireNotNull(System.getProperty("plainbase.performance.screen.sourceStaging")))
        check(Files.notExists(outputRoot)) { "performance evidence directory already exists: $outputRoot" }
        check(Files.notExists(fixtureRoot)) { "performance fixture directory already exists: $fixtureRoot" }
        val stagedPatch = sourceStagingRoot.resolve("tracked.patch")
        val stagedProbe = sourceStagingRoot.resolve("BackendPerformanceScreenTest.kt")
        check(Files.isRegularFile(stagedPatch) && Files.isRegularFile(stagedProbe)) {
            "performance source staging is incomplete"
        }
        Files.createDirectories(outputRoot)
        Files.copy(stagedPatch, outputRoot.resolve("tracked.patch"))
        Files.copy(stagedProbe, outputRoot.resolve("BackendPerformanceScreenTest.kt"))
        check(
            sha256(Files.readAllBytes(outputRoot.resolve("tracked.patch"))) ==
                requireNotNull(System.getProperty("plainbase.performance.screen.patch")),
        ) {
            "tracked patch snapshot hash mismatch"
        }

        val manifest = Manifest(outputRoot.resolve("manifest.json"))
        manifest.put("run_id", requireNotNull(System.getProperty("plainbase.performance.screen.run")))
        manifest.put("git_head", requireNotNull(System.getProperty("plainbase.performance.screen.head")))
        manifest.put("tracked_patch_sha256", requireNotNull(System.getProperty("plainbase.performance.screen.patch")))
        manifest.put("java_command", System.getProperty("sun.java.command").orEmpty())
        manifest.put("java_home", System.getenv("JAVA_HOME").orEmpty())
        manifest.put("graalvm_home", System.getenv("GRAALVM_HOME").orEmpty())
        manifest.put(
            "gradle_start_parameters",
            requireNotNull(System.getProperty("plainbase.performance.screen.gradleStartParameters")),
        )
        manifest.put("tracked_patch_file", "tracked.patch")
        manifest.put("probe_source_snapshot", "BackendPerformanceScreenTest.kt")
        manifest.put("tracked_patch_file_sha256", sha256(Files.readAllBytes(outputRoot.resolve("tracked.patch"))))
        manifest.put(
            "probe_source_snapshot_sha256",
            sha256(Files.readAllBytes(outputRoot.resolve("BackendPerformanceScreenTest.kt"))),
        )
        manifest.put("java_version", System.getProperty("java.version"))
        manifest.put("java_runtime", System.getProperty("java.runtime.version"))
        manifest.put("os", "${System.getProperty("os.name")} ${System.getProperty("os.version")}")
        manifest.put("cpu", "${System.getProperty("os.arch")};processors=${Runtime.getRuntime().availableProcessors()}")
        manifest.put("heap", "max=${Runtime.getRuntime().maxMemory()};configured=1g")
        manifest.put("input_arguments", inputArguments.joinToString(" "))
        manifest.put("resolved_versions", requireNotNull(System.getProperty("plainbase.performance.screen.versions")))
        manifest.put("source_sha256s", sourceHashes())
        manifest.put("sizes", SIZES.joinToString(","))
        manifest.put("page_bytes_target", PAGE_BYTES.toString())
        manifest.put("generator", "deterministic-id-frontmatter-v1;two-headings;eight-links;utf8")
        manifest.put("link_root", LINK_ROOT)
        manifest.put("link_path", LINK_PATH)
        manifest.put("link_oracle", expectedLinks(0, SIZES.first()).joinToString("|") { it.toString() })
        manifest.put("proposal_totals", SIZES.joinToString(",") { "$it:${proposalTotal(it)}" })
        manifest.put("proposal_edit_target_set", proposalEditTargetSet())
        manifest.put("cell_schedule", "LINK:5+20,LIST:5+20,SAVE:5+20,CREATE:2+5")
        manifest.put("size_deadline_ms", "120000")
            manifest.put("task_timeout_ms", "600000")
            manifest.write()
            checkEvidenceBudget(outputRoot.resolve("manifest.json"))

        val status = JsonlWriter(outputRoot.resolve("status.jsonl"))
        val observations = CsvWriter(outputRoot.resolve("observations.csv"))
        observations.row(
            listOf(
                "run_id",
                "size",
                "operation",
                "phase",
                "sample",
                "status",
                "page_count_before",
                "duration_ns",
                "outcome",
                "detail",
            ),
        )
        var successful = false
        var nextSize = 0
        try {
            status.record("run_start", mapOf("agent_count" to agents.size.toString()))
            for ((sizeIndex, size) in SIZES.withIndex()) {
                nextSize = sizeIndex
                if (!runSize(size, manifest, status, observations, fixtureRoot)) {
                    throw IllegalStateException("screening size $size was incomplete")
                }
            }
            successful = true
            status.record("run_complete", mapOf("sizes" to SIZES.size.toString()))
        } catch (failure: Throwable) {
            SIZES.drop(nextSize + 1).forEach { future -> markUnstarted(future, "worker_failed", status, observations) }
            status.record("run_failed", mapOf("type" to (failure::class.simpleName ?: "unknown")))
            throw failure
        } finally {
            manifest.put("final_status", if (successful) "complete" else "incomplete")
            manifest.write()
            observations.close()
            status.close()
            if (successful) {
                fixtureRoot.toFile().deleteRecursively()
                sourceStagingRoot.toFile().deleteRecursively()
            }
        }
    }

    private fun runSize(
        size: Int,
        manifest: Manifest,
        status: JsonlWriter,
        observations: CsvWriter,
        fixtureRoot: Path,
    ): Boolean {
        val sizeDir = fixtureRoot.resolve(size.toString())
        val contentDir = sizeDir.resolve("content")
        val dataDir = sizeDir.resolve("data")
        val appDbPath = dataDir.resolve("plainbase.db")
        val searchDbPath = sizeDir.resolve("search.db")
        val deadline = System.nanoTime() + SIZE_DEADLINE_NS
        val schedule = fullSchedule(size)
        var nextObservation = 0
        var observationStarted = false
        status.record("size_start", mapOf("size" to size.toString(), "deadline_ms" to "120000"))
        try {
            check(System.nanoTime() < deadline) { "size deadline expired before fixture setup: $size" }
            seedContent(contentDir, size)
            manifest.put("size_${size}_page_count", size.toString())
            manifest.put("size_${size}_content_bytes", contentBytes(contentDir).toString())
            manifest.put("size_${size}_link_count", (size * 8).toString())
            manifest.put("size_${size}_expected_broken_links", (size * 4).toString())
            manifest.put("size_${size}_content_sha256", digestFiles(contentDir))
            checkFixtureBudget(fixtureRoot)

            SearchDb(searchDbPath).use { searchDb ->
                val provider = Fts5SearchProvider(searchDb)
                lateinit var authority: IdMapRepository
                val indexer = SearchIndexer(
                    provider,
                    SectionSplitter(),
                    { authority.retiredUnboundIds() },
                    { authority.isRetiredUnbound(it) },
                )
                val driverFactory: () -> SqlDriver = { DatabaseFactory.createDriver(appDbPath) }
                IndexHarness(
                    contentDir,
                    history = NoOpHistoryProvider,
                    listeners = listOf(
                        IndexBuilder.PublicationListener { snapshot, _ -> indexer.sync(snapshot) },
                    ),
                    searchIndexer = indexer,
                    driverFactory = driverFactory,
                ).use { harness ->
                    authority = harness.idMap
                    harness.builder.rebuild()
                    seedProposals(harness, size)
                    harness.roleRepository.upsert(
                        "builtin",
                        SUBJECT,
                        Role.VIEWER,
                        Instant.fromEpochMilliseconds(EPOCH_MILLIS),
                    )
                    val pipeline = harness.writePipeline()
                    val context = harness.testRouteContext(
                        writePipeline = pipeline,
                        searchProvider = provider,
                        enforced = true,
                    )
                    checkFixtureBudget(fixtureRoot)
                    manifest.put("size_${size}_fixture_bytes_before_timing", fixtureBytes(fixtureRoot).toString())
                    val proposalRows = harness.proposalRepository.all()
                    assertProposalContract(proposalRows, size)
                    manifest.put("size_${size}_proposal_counts", proposalCounts(proposalRows))
                    manifest.put("size_${size}_proposal_total", proposalRows.size.toString())
                    manifest.put(
                        "size_${size}_proposal_distinct_edit_targets",
                        proposalRows.filter { it.operation == ProposalOperation.EDIT }.map { it.targetPath }.toSet().size.toString(),
                    )
                    manifest.put(
                        "size_${size}_proposal_distinct_create_targets",
                        proposalRows
                            .filter { it.operation == ProposalOperation.CREATE }
                            .map { it.targetPath }
                            .toSet()
                            .size
                            .toString(),
                    )
                    manifest.put("size_${size}_proposal_bytes", proposalBytes(harness).toString())
                    manifest.put("size_${size}_proposal_expected", proposalExpectationDigest(size))
                    manifest.write()

                    for (entry in schedule) {
                        if (System.nanoTime() >= deadline) throw Deadline(nextObservation)
                        observationStarted = true
                        perform(
                            size,
                            entry,
                            harness,
                            context,
                            provider,
                            pipeline,
                            contentDir,
                            observations,
                            status,
                        )
                        observationStarted = false
                        nextObservation += 1
                    }
                }
            }
            status.record("size_complete", mapOf("size" to size.toString(), "observations" to schedule.size.toString()))
            return true
        } catch (deadlineFailure: Deadline) {
            schedule.drop(deadlineFailure.nextIndex).forEach { entry ->
                unstartedRow(size, entry, "size_deadline_exceeded", status, observations)
            }
            status.record("size_incomplete", mapOf("size" to size.toString(), "reason" to "deadline"))
            return false
        } catch (failure: Throwable) {
            val start = if (observationStarted) nextObservation + 1 else nextObservation
            schedule.drop(start).forEach { entry -> unstartedRow(size, entry, "worker_failed", status, observations) }
            status.record(
                "size_failed",
                mapOf("size" to size.toString(), "type" to (failure::class.simpleName ?: "unknown")),
            )
            throw failure
        }
    }

    private fun perform(
        size: Int,
        entry: ScheduledObservation,
        harness: IndexHarness,
        context: com.plainbase.frameworks.ktor.RouteContext,
        provider: Fts5SearchProvider,
        pipeline: com.plainbase.domain.service.WritePipeline,
        contentDir: Path,
        observations: CsvWriter,
        status: JsonlWriter,
    ) {
        val before = harness.builder.current
        val pageCountBefore = before.pages.size
        val prepared = when (entry.operation) {
            Operation.LINK, Operation.LIST -> Prepared.NoInput
            Operation.SAVE -> {
                val current = requireNotNull(harness.builder.current.pageAt(RootedPageId(RootName.PRIMARY, screenPageId(0))))
                Prepared.Save(current, saveBytes(size, entry), before, snapshotFingerprint(before))
            }
            Operation.CREATE -> Prepared.Create(createIntent(size, entry), before, snapshotFingerprint(before))
        }
        status.record(
            "operation_start",
            mapOf(
                "size" to size.toString(),
                "operation" to entry.operation.name,
                "phase" to entry.phase,
                "sample" to entry.sample.toString(),
                "page_count_before" to pageCountBefore.toString(),
            ),
        )
        val started = System.nanoTime()
        var result: Any? = null
        var failure: Throwable? = null
        try {
            result = when (entry.operation) {
                Operation.LINK -> context.read.validateLinks(principal, screenPageId(0), RootName.PRIMARY)
                Operation.LIST -> context.proposals.list(principal)
                Operation.SAVE -> {
                    val save = prepared as Prepared.Save
                    pipeline.write(
                        com.plainbase.domain.principal.grantForTests(),
                        WriteIntent(save.page.id, RootName.PRIMARY, save.page.path, save.page.contentHash, save.bytes),
                    )
                }
                Operation.CREATE -> pipeline.create(
                    com.plainbase.domain.principal.createGrantForTests(),
                    (prepared as Prepared.Create).intent,
                )
            }
        } catch (thrown: Throwable) {
            failure = thrown
        }
        val duration = System.nanoTime() - started
        if (failure == null) {
            try {
                when (entry.operation) {
                    Operation.LINK -> verifyLinks(result, size)
                    Operation.LIST -> verifyProposals(result, harness, size)
                    Operation.SAVE ->
                        verifySave(result, prepared as Prepared.Save, harness, provider, contentDir, pageCountBefore, size, entry)
                    Operation.CREATE ->
                        verifyCreate(result, prepared as Prepared.Create, harness, provider, contentDir, pageCountBefore, size)
                }
            } catch (thrown: Throwable) {
                failure = thrown
            }
        }
        val outcome = if (failure == null) "success" else "failure"
        observations.row(
            listOf(
                requireNotNull(System.getProperty("plainbase.performance.screen.run")),
                size.toString(),
                entry.operation.name,
                entry.phase,
                entry.sample.toString(),
                "completed",
                pageCountBefore.toString(),
                duration.toString(),
                outcome,
                failure?.let { it::class.simpleName ?: "unknown" } ?: "",
            ),
        )
        status.record(
            "operation_complete",
            mapOf(
                "size" to size.toString(),
                "operation" to entry.operation.name,
                "phase" to entry.phase,
                "sample" to entry.sample.toString(),
                "duration_ns" to duration.toString(),
                "outcome" to outcome,
            ),
        )
        checkEvidenceBudget(observations.path)
        checkEvidenceBudget(status.path)
        failure?.let { throw it }
    }

    private fun verifyLinks(result: Any?, size: Int) {
        val report = result as? com.plainbase.domain.service.LinkReport
        check(report != null) { "LINK returned a partial/null result" }
        check(report.broken.all { it.page == LINK_SOURCE }) { "LINK oracle source page mismatch" }
        val actual = report.broken.map { link -> BrokenExpectation(link.page, link.target, link.text, link.reason.wireValue) }
        val expected = expectedLinks(0, size)
        check(actual == expected) { "LINK oracle mismatch: expected=$expected actual=$actual" }
    }

    private fun verifyProposals(result: Any?, harness: IndexHarness, size: Int) {
        @Suppress("UNCHECKED_CAST")
        val views = result as? List<com.plainbase.domain.service.ProposalSummaryView>
        check(views != null) { "LIST returned a partial/null result" }
        val expected = proposalExpectations(size).reversed()
        val actual = views.map { view ->
            ProposalExpectation(
                view.row.id,
                view.row.operation,
                view.row.status,
                view.row.root,
                view.row.targetPath,
                view.row.baseHash,
                view.baseDrifted,
            )
        }
        check(actual == expected) { "LIST oracle mismatch" }
    }

    private fun verifySave(
        result: Any?,
        prepared: Prepared.Save,
        harness: IndexHarness,
        provider: Fts5SearchProvider,
        contentDir: Path,
        pageCountBefore: Int,
        size: Int,
        entry: ScheduledObservation,
    ) {
        val written = result as? WriteOutcome.Written
        check(written != null) { "SAVE returned ${result ?: "null"}" }
        val expectedHash = citationFactory.contentHash(prepared.bytes)
        check(written.newHash == expectedHash) { "SAVE hash mismatch" }
        check(Files.readAllBytes(contentDir.resolve(prepared.page.path.value)).contentEquals(prepared.bytes)) { "SAVE disk bytes mismatch" }
        val current = requireNotNull(harness.builder.current.pageAt(RootedPageId(RootName.PRIMARY, prepared.page.id)))
        check(current.id == prepared.page.id && current.path == prepared.page.path) { "SAVE identity/path mismatch" }
        check(current.markdown == String(prepared.bytes, StandardCharsets.UTF_8) && current.contentHash == expectedHash) {
            "SAVE published content/hash mismatch"
        }
        check(snapshotFingerprint(prepared.beforeSnapshot) == prepared.beforeFingerprint) { "SAVE prior snapshot changed" }
        check(harness.builder.current.pages.size == pageCountBefore) { "SAVE changed page count" }
        checkSearch(provider, saveToken(size, entry), prepared.page.id)
    }

    private fun verifyCreate(
        result: Any?,
        prepared: Prepared.Create,
        harness: IndexHarness,
        provider: Fts5SearchProvider,
        contentDir: Path,
        pageCountBefore: Int,
        size: Int,
    ) {
        val written = result as? WriteOutcome.Written
        check(written != null) { "CREATE returned ${result ?: "null"}" }
        val expectedHash = citationFactory.contentHash(prepared.intent.bytes)
        check(written.newHash == expectedHash) { "CREATE hash mismatch" }
        check(Files.readAllBytes(contentDir.resolve(prepared.intent.path.value)).contentEquals(prepared.intent.bytes)) {
            "CREATE disk bytes mismatch"
        }
        val current = requireNotNull(harness.builder.current.pageAt(RootedPageId(RootName.PRIMARY, prepared.intent.pageId)))
        check(current.id == prepared.intent.pageId && current.path == prepared.intent.path) { "CREATE identity/path mismatch" }
        check(current.markdown == String(prepared.intent.bytes, StandardCharsets.UTF_8) && current.contentHash == expectedHash) {
            "CREATE published content/hash mismatch"
        }
        check(snapshotFingerprint(prepared.beforeSnapshot) == prepared.beforeFingerprint) { "CREATE prior snapshot changed" }
        check(harness.builder.current.pages.size == pageCountBefore + 1) { "CREATE page count mismatch" }
        checkSearch(provider, createToken(size, prepared.intent.path), prepared.intent.pageId)
    }

    private fun checkSearch(provider: Fts5SearchProvider, token: String, expectedPageId: PageId) {
        val results = provider.search(SearchQuery(token, limit = 20, offset = 0))
        check(results.hits.any { it.root == RootName.PRIMARY && it.pageId == expectedPageId }) {
            "search did not reflect mutation token=$token page=${expectedPageId.value}"
        }
    }

    private fun seedContent(contentDir: Path, size: Int) {
        Files.createDirectories(contentDir)
        repeat(size) { index ->
            com.plainbase.domain.service.writePage(
                contentDir,
                "pages/page-%04d.md".format(index),
                pageDocument(screenPageId(index), index, size, "seed%04d".format(index)),
            )
        }
    }

    private fun proposalPlan(size: Int): List<ProposalGroup> =
        proposalStatusCounts(size).map { (status, count) -> ProposalGroup(status, count, count * 80 / 100) }

    private fun proposalStatusCounts(size: Int): Map<ProposalStatus, Int> =
        requireNotNull(PROPOSAL_STATUS_COUNTS[size]) { "unsupported proposal screen size: $size" }

    private fun proposalTotal(size: Int): Int = proposalStatusCounts(size).values.sum()

    private fun proposalEditTarget(groupIndex: Int): TreePath =
        TreePath.require("pages/page-%04d.md".format(PROPOSAL_EDIT_TARGET_INDICES[groupIndex % PROPOSAL_EDIT_TARGET_COUNT]))

    private fun proposalEditTargetSet(): String =
        PROPOSAL_EDIT_TARGET_INDICES.joinToString(",") { index -> "pages/page-%04d.md".format(index) }

    private fun seedProposals(harness: IndexHarness, size: Int) {
        var ordinal = 0
        for (group in proposalPlan(size)) {
            repeat(group.count) { groupIndex ->
                val operation = if (groupIndex < group.editCount) ProposalOperation.EDIT else ProposalOperation.CREATE
                val target = if (operation == ProposalOperation.EDIT) {
                    proposalEditTarget(groupIndex)
                } else {
                    TreePath.require("proposal-target/%04d.md".format(ordinal))
                }
                val pageIndex = if (operation == ProposalOperation.EDIT) {
                    PROPOSAL_EDIT_TARGET_INDICES[groupIndex % PROPOSAL_EDIT_TARGET_COUNT]
                } else {
                    0
                }
                val pageId = if (operation == ProposalOperation.EDIT) screenPageId(pageIndex) else null
                val currentHash = pageId?.let {
                    citationFactory.contentHash(
                        pageDocument(it, pageIndex, size, "seed%04d".format(pageIndex)).toByteArray(),
                    )
                }
                val drifted = (group.status == ProposalStatus.PENDING || group.status == ProposalStatus.CONFLICTED) && ordinal % 2 == 1
                val proposed = "synthetic proposal $size $ordinal\n".toByteArray()
                harness.proposalRepository.insert(
                    ProposalRow(
                        id = screenProposalId(ordinal),
                        operation = operation,
                        pageId = pageId,
                        root = RootName.PRIMARY,
                        baseHash = if (operation == ProposalOperation.EDIT) {
                            if (drifted) "sha256:" + "0".repeat(64) else currentHash
                        } else {
                            null
                        },
                        targetPath = target,
                        proposedContent = proposed,
                        rationale = "synthetic-screen-$ordinal",
                        diffArtifact = "synthetic-diff-$ordinal",
                        status = group.status,
                        authorIssuer = "synthetic",
                        authorExternalId = "screen-$ordinal",
                        authorLabel = "screen",
                        approverIssuer = null,
                        approverExternalId = null,
                        decisionComment = null,
                        createdAt = Instant.fromEpochMilliseconds(EPOCH_MILLIS + ordinal),
                        decidedAt = null,
                        appliedCommit = null,
                        statusReason = null,
                    ),
                )
                ordinal += 1
            }
        }
        check(ordinal == proposalTotal(size)) { "proposal generator count mismatch" }
    }

    private fun assertProposalContract(rows: List<ProposalSummaryRow>, size: Int) {
        val expectedStatusCounts = proposalStatusCounts(size)
        val expectedTotal = proposalTotal(size)
        check(rows.size == expectedTotal) { "proposal total mismatch: expected=$expectedTotal actual=${rows.size}" }
        check(rows.groupingBy { it.status }.eachCount() == expectedStatusCounts) { "proposal status counts mismatch" }
        val expectedOperationCounts = mapOf(
            ProposalOperation.EDIT to expectedTotal * 80 / 100,
            ProposalOperation.CREATE to expectedTotal * 20 / 100,
        )
        check(rows.groupingBy { it.operation }.eachCount() == expectedOperationCounts) { "proposal operation counts mismatch" }
        val editTargets = rows.filter { it.operation == ProposalOperation.EDIT }.map { it.targetPath }.toSet()
        val expectedEditTargets = PROPOSAL_EDIT_TARGET_INDICES.map { index ->
            TreePath.require("pages/page-%04d.md".format(index))
        }.toSet()
        check(editTargets.size == PROPOSAL_EDIT_TARGET_COUNT) { "proposal edit target count mismatch" }
        check(editTargets == expectedEditTargets) { "proposal edit target set mismatch" }
        val createTargets = rows.filter { it.operation == ProposalOperation.CREATE }.map { it.targetPath }.toSet()
        check(createTargets.size == expectedOperationCounts.getValue(ProposalOperation.CREATE)) {
            "proposal create target count mismatch"
        }
        check(rows.map { it.targetPath }.toSet().size == PROPOSAL_EDIT_TARGET_COUNT + createTargets.size) {
            "proposal total distinct target count mismatch"
        }
    }

    private fun proposalExpectations(size: Int): List<ProposalExpectation> {
        val expected = mutableListOf<ProposalExpectation>()
        var ordinal = 0
        for (group in proposalPlan(size)) {
            repeat(group.count) { groupIndex ->
                val operation = if (groupIndex < group.editCount) ProposalOperation.EDIT else ProposalOperation.CREATE
                val target = if (operation == ProposalOperation.EDIT) {
                    proposalEditTarget(groupIndex)
                } else {
                    TreePath.require("proposal-target/%04d.md".format(ordinal))
                }
                val pageIndex = if (operation == ProposalOperation.EDIT) {
                    PROPOSAL_EDIT_TARGET_INDICES[groupIndex % PROPOSAL_EDIT_TARGET_COUNT]
                } else {
                    0
                }
                val pageId = if (operation == ProposalOperation.EDIT) screenPageId(pageIndex) else null
                val drifted = (group.status == ProposalStatus.PENDING || group.status == ProposalStatus.CONFLICTED) &&
                    operation == ProposalOperation.EDIT && ordinal % 2 == 1
                val baseHash = if (operation == ProposalOperation.EDIT) {
                    val current = pageDocument(
                        requireNotNull(pageId),
                        pageIndex,
                        size,
                        "seed%04d".format(pageIndex),
                    ).toByteArray(StandardCharsets.UTF_8)
                    if (drifted) "sha256:" + "0".repeat(64) else citationFactory.contentHash(current)
                } else {
                    null
                }
                expected += ProposalExpectation(
                    screenProposalId(ordinal),
                    operation,
                    group.status,
                    RootName.PRIMARY,
                    target,
                    baseHash,
                    drifted,
                )
                ordinal += 1
            }
        }
        return expected
    }

    private fun proposalExpectationDigest(size: Int): String =
        sha256(proposalExpectations(size).joinToString("|") { it.toString() }.toByteArray())

    private fun proposalCounts(rows: List<com.plainbase.domain.repository.ProposalSummaryRow>): String =
        rows.groupingBy { "${it.status}:${it.operation}" }.eachCount().entries.sortedBy { it.key }
            .joinToString(",") { "${it.key}=${it.value}" }

    private fun proposalBytes(harness: IndexHarness): Int =
        harness.proposalRepository.all().sumOf { row -> requireNotNull(harness.proposalRepository.findById(row.id)).proposedContent.size }

    private fun fullSchedule(size: Int): List<ScheduledObservation> = cells.flatMap { cell ->
        buildList {
            repeat(cell.warmups) { add(ScheduledObservation(size, cell.operation, "warmup", it)) }
            repeat(cell.observations) { add(ScheduledObservation(size, cell.operation, "measure", it)) }
        }
    }

    private fun markUnstarted(size: Int, reason: String, status: JsonlWriter, observations: CsvWriter) {
        fullSchedule(size).forEach { entry -> unstartedRow(size, entry, reason, status, observations) }
    }

    private fun unstartedRow(
        size: Int,
        entry: ScheduledObservation,
        reason: String,
        status: JsonlWriter,
        observations: CsvWriter,
    ) {
        observations.row(
            listOf(
                requireNotNull(System.getProperty("plainbase.performance.screen.run")),
                size.toString(),
                entry.operation.name,
                entry.phase,
                entry.sample.toString(),
                "unstarted",
                "",
                "",
                reason,
                "",
            ),
        )
        status.record(
            "operation_unstarted",
            mapOf(
                "size" to size.toString(),
                "operation" to entry.operation.name,
                "phase" to entry.phase,
                "sample" to entry.sample.toString(),
                "reason" to reason,
            ),
        )
    }

    private fun saveBytes(size: Int, entry: ScheduledObservation): ByteArray {
        val marker = if (entry.phase == "warmup") {
            "warmup%02d".format(entry.sample)
        } else {
            "save${if (entry.sample % 2 == 0) 'A' else 'B'}%02d".format(entry.sample)
        }
        return pageDocument(screenPageId(0), 0, size, "zzscreen${size}save${marker}zz").toByteArray(StandardCharsets.UTF_8)
    }

    private fun createIntent(size: Int, entry: ScheduledObservation): CreateIntent {
        val ordinal = if (entry.phase == "warmup") entry.sample else 2 + entry.sample
        val id = screenCreateId(size, ordinal)
        val path = TreePath.require("created/screen-%04d-%02d.md".format(size, ordinal))
        val token = "zzscreen${size}create${ordinal}zz"
        return CreateIntent(id, RootName.PRIMARY, path, pageDocument(id, 0, size, token).toByteArray(StandardCharsets.UTF_8))
    }

    private fun saveToken(size: Int, entry: ScheduledObservation): String {
        val marker = if (entry.phase == "warmup") {
            "warmup%02d".format(entry.sample)
        } else {
            "save${if (entry.sample % 2 == 0) 'A' else 'B'}%02d".format(entry.sample)
        }
        return "zzscreen${size}save${marker}zz"
    }

    private fun createToken(size: Int, path: TreePath): String {
        val ordinal = path.value.substringAfterLast('-').removeSuffix(".md").toInt()
        return "zzscreen${size}create${ordinal}zz"
    }

    private fun sourceHashes(): String =
        requireNotNull(System.getProperty("plainbase.performance.screen.sourceFiles")).split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .joinToString(";") { path -> "$path=${sha256(Files.readAllBytes(Path.of(path)))}" }

    private fun checkFixtureBudget(root: Path) {
        check(fixtureBytes(root) <= MAX_FIXTURE_BYTES) { "fixture budget exceeded" }
    }

    private fun checkEvidenceBudget(path: Path) {
        check(fileBytes(path.parent) <= MAX_EVIDENCE_BYTES) { "raw evidence budget exceeded" }
    }

    private fun fixtureBytes(root: Path): Long = fileBytes(root)

    private fun fileBytes(root: Path): Long {
        if (Files.notExists(root)) return 0L
        Files.walk(root).use { paths ->
            return paths.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum()
        }
    }

    private fun contentBytes(root: Path): Long = fileBytes(root)

    private fun digestFiles(root: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) }.sorted().forEach { path ->
                digest.update(root.relativize(path).toString().toByteArray(StandardCharsets.UTF_8))
                digest.update(0.toByte())
                digest.update(Files.readAllBytes(path))
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun pageDocument(id: PageId, index: Int, pageCount: Int, token: String): String {
        val next = (index + 1) % pageCount
        val nextTwo = (index + 2) % pageCount
        val body = buildString {
            appendLine("---")
            appendLine("id: ${id.value}")
            appendLine("title: Screen Page %04d".format(index))
            appendLine("---")
            appendLine()
            appendLine("# Screen Page %04d".format(index))
            appendLine()
            appendLine("$token deterministic backend screening content.")
            appendLine("[same-anchor](#details)")
            appendLine("[same-missing-anchor](#missing-anchor-$index)")
            appendLine("[cross-anchor](page-%04d.md#details)".format(next))
            appendLine("[cross-missing-anchor](page-%04d.md#missing-heading-$index)".format(nextTwo))
            appendLine("[missing-target](missing-target-$index.md)")
            appendLine("[missing-target-anchor](missing-anchor-target-$index.md#details)")
            appendLine("[external](https://example.com/screen/$index)")
            appendLine("[cross-page](page-%04d.md)".format(nextTwo))
            appendLine()
            appendLine("## Details")
            appendLine()
            appendLine("Screen filler.")
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        return if (bytes.size >= PAGE_BYTES) body else body + "x".repeat(PAGE_BYTES - bytes.size)
    }

    private fun expectedLinks(index: Int, size: Int): List<BrokenExpectation> {
        val nextTwo = (index + 2) % size
        return listOf(
            BrokenExpectation(LINK_SOURCE, "#missing-anchor-$index", "same-missing-anchor", "broken_anchor"),
            BrokenExpectation(
                LINK_SOURCE,
                "page-%04d.md#missing-heading-$index".format(nextTwo),
                "cross-missing-anchor",
                "broken_anchor",
            ),
            BrokenExpectation(LINK_SOURCE, "missing-target-$index.md", "missing-target", "broken_missing"),
            BrokenExpectation(
                LINK_SOURCE,
                "missing-anchor-target-$index.md#details",
                "missing-target-anchor",
                "broken_missing",
            ),
        )
    }

    private fun screenPageId(index: Int): PageId = PageId.require("0197aaaa-0000-7000-8000-%012x".format(index))

    private fun screenProposalId(index: Int): ProposalId = ProposalId.require("0197bbbb-0000-7000-8000-%012x".format(index))

    private fun screenCreateId(size: Int, ordinal: Int): PageId = screenPageId(100_000 + size * 10 + ordinal)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }

    private fun snapshotFingerprint(snapshot: PageIndex): String =
        sha256(snapshot.pages.joinToString("|") { "${it.root.value}:${it.path.value}:${it.id.value}:${it.contentHash}" }.toByteArray())
}

private enum class Operation { LINK, LIST, SAVE, CREATE }

private data class Cell(val operation: Operation, val warmups: Int, val observations: Int)

private data class ProposalGroup(val status: ProposalStatus, val count: Int, val editCount: Int)

private data class ScheduledObservation(val size: Int, val operation: Operation, val phase: String, val sample: Int)

private data class BrokenExpectation(val page: RootedPath, val target: String, val text: String, val reason: String)

private data class ProposalExpectation(
    val id: ProposalId,
    val operation: ProposalOperation,
    val status: ProposalStatus,
    val root: RootName,
    val targetPath: TreePath,
    val baseHash: String?,
    val drifted: Boolean,
)

private sealed interface Prepared {
    data object NoInput : Prepared

    data class Save(
        val page: com.plainbase.domain.page.IndexedPage,
        val bytes: ByteArray,
        val beforeSnapshot: PageIndex,
        val beforeFingerprint: String,
    ) : Prepared

    data class Create(val intent: CreateIntent, val beforeSnapshot: PageIndex, val beforeFingerprint: String) : Prepared
}

private class Deadline(val nextIndex: Int) : RuntimeException()

private class Manifest(private val path: Path) {
    private val values = linkedMapOf<String, String>()

    fun put(key: String, value: String) {
        values[key] = value
    }

    fun write() {
        Files.writeString(
            path,
            values.entries.joinToString(prefix = "{\n", postfix = "\n}\n", separator = ",\n") { (key, value) ->
                "  \"${jsonEscape(key)}\": \"${jsonEscape(value)}\""
            },
            StandardCharsets.UTF_8,
        )
    }
}

private class JsonlWriter(val path: Path) : AutoCloseable {
    private val writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)

    fun record(event: String, fields: Map<String, String> = emptyMap()) {
        val values = linkedMapOf("event" to event)
        values.putAll(fields)
        writer.append(
            values.entries.joinToString(prefix = "{", postfix = "}\n", separator = ",") { (key, value) ->
                "\"${jsonEscape(key)}\":\"${jsonEscape(value)}\""
            },
        )
        writer.flush()
    }

    override fun close() = writer.close()
}

private class CsvWriter(val path: Path) : AutoCloseable {
    private val writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)

    fun row(fields: List<String>) {
        writer.appendLine(fields.joinToString(",") { field -> csvEscape(field) })
        writer.flush()
    }

    override fun close() = writer.close()
}

private fun jsonEscape(value: String): String = buildString {
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
        }
    }
}

private fun csvEscape(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${value.replace("\"", "\"\"")}\"" else value
