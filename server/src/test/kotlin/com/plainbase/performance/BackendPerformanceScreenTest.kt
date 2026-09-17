package com.plainbase.performance

import app.cash.sqldelight.db.QueryResult.Value
import app.cash.sqldelight.db.SqlDriver
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.page.IndexedPage
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.page.ProposalId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.principal.createGrantForTests
import com.plainbase.domain.principal.grantForTests
import com.plainbase.domain.repository.BindOutcome
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.ProposalOperation
import com.plainbase.domain.repository.ProposalRow
import com.plainbase.domain.repository.ProposalStatus
import com.plainbase.domain.repository.ProposalSummaryRow
import com.plainbase.domain.repository.Role
import com.plainbase.domain.repository.Supersession
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.CreateIntent
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.LinkReport
import com.plainbase.domain.service.ProposalSummaryView
import com.plainbase.domain.service.SearchIndexer
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.domain.service.WriteIntent
import com.plainbase.domain.service.WritePipeline
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.testRouteContext
import com.plainbase.frameworks.search.Fts5SearchProvider
import com.plainbase.frameworks.search.SearchDb
import com.plainbase.frameworks.sqldelight.BeginImmediateSqliteDriver
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import org.sqlite.SQLiteConfig
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.DriverPropertyInfo
import java.sql.SQLException
import java.util.Properties
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.time.Instant

private const val ENABLED_PROPERTY = "plainbase.performance.screen.enabled"
private const val OUTPUT_PROPERTY = "plainbase.performance.screen.output"
private const val FIXTURES_PROPERTY = "plainbase.performance.screen.fixtures"
private const val PROFILE_PROPERTY = "plainbase.performance.screen.profile"
private const val MAX_FIXTURE_BYTES = 512L * 1024L * 1024L
private const val MAX_EVIDENCE_BYTES = 20L * 1024L * 1024L
private const val PAGE_BYTES = 4 * 1024
private const val SIZE_DEADLINE_PROPERTY = "plainbase.performance.screen.sizeDeadlineMs"
private const val TASK_TIMEOUT_PROPERTY = "plainbase.performance.screen.taskTimeoutMs"
private const val CONFIGURED_HEAP_PROPERTY = "plainbase.performance.screen.configuredHeap"
private const val SOURCE_KEYS_PROPERTY = "plainbase.performance.screen.sourceKeys"
private const val EXPECTED_SOURCE_HASHES_PROPERTY = "plainbase.performance.screen.expectedSourceHashes"
private const val LINK_ROOT = "docs"
private const val LINK_PATH = "pages/page-0000.md"
private const val SUBJECT = "performance-screen"
private const val EPOCH_MILLIS = 1_700_000_000_000L
private const val PROPOSAL_EDIT_TARGET_COUNT = 10
private const val SQLITE_BUSY_TIMEOUT_MS = 3_000
private const val PROBE_URL_PREFIX = "jdbc:plainbase-connect-probe:"
private val REQUIRED_SOURCE_FILES = setOf(
    "server/src/main/kotlin/com/plainbase/frameworks/sqldelight/DatabaseFactory.kt",
    "server/src/main/kotlin/com/plainbase/frameworks/sqldelight/BeginImmediateSqliteDriver.kt",
    "server/src/main/kotlin/com/plainbase/frameworks/sqldelight/SqlDelightIdMapRepository.kt",
)
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
private val ATTRIBUTION_SIZES = listOf(1_000, 3_000)
private val LINK_SOURCE = RootedPath(RootName.PRIMARY, TreePath.require(LINK_PATH))

private val SCREEN_CELLS = listOf(
    PerformanceCell("LINK", warmups = 5, observations = 20),
    PerformanceCell("LIST", warmups = 5, observations = 20),
    PerformanceCell("SAVE", warmups = 5, observations = 20),
    PerformanceCell("CREATE", warmups = 2, observations = 5),
)
private val ATTRIBUTION_CELLS = listOf(PerformanceCell("CREATE", warmups = 2, observations = 5))

internal enum class ExecutionArm(val wireValue: String) {
    NONE("none"),
    ON("on"),
    OFF("off"),
}

internal enum class PerformanceProfile(
    val wireValue: String,
    val arms: List<ExecutionArm>,
    val sizes: List<Int>,
    val cells: List<PerformanceCell>,
) {
    SCREEN("screen", listOf(ExecutionArm.NONE), SIZES, SCREEN_CELLS),
    CREATE_ON_OFF("create-on-off", listOf(ExecutionArm.ON, ExecutionArm.OFF), ATTRIBUTION_SIZES, ATTRIBUTION_CELLS),
    CREATE_OFF_ON("create-off-on", listOf(ExecutionArm.OFF, ExecutionArm.ON), ATTRIBUTION_SIZES, ATTRIBUTION_CELLS),
    ;

    val isAttribution: Boolean
        get() = this != SCREEN

    val expectedInvocationCount: Int
        get() = arms.size * sizes.size * cells.sumOf { it.warmups + it.observations }

    fun scheduleLabel(): String = arms.flatMap { arm -> sizes.map { size -> "${arm.wireValue}:$size" } }.joinToString(",")

    companion object {
        fun parse(value: String): PerformanceProfile =
            values().firstOrNull { it.wireValue == value }
                ?: error("unsupported performance profile: $value")
    }
}

/** Opt-in bounded backend screen; kotlin.test/JUnit keeps this probe off the Kotest engine. */
class BackendPerformanceScreenTest {

    @Test
    fun `the bounded backend screen records complete observations`() {
        Screen().run()
    }
}

private class Screen {
    private val principal = Principal.Human("builtin", SUBJECT)
    private val citationFactory = CitationFactory()

    fun run() {
        val profile = PerformanceProfile.parse(requireNotNull(System.getProperty(PROFILE_PROPERTY)))
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
        val sizeDeadlineMs = positiveLong(SIZE_DEADLINE_PROPERTY)
        val taskTimeoutMs = positiveLong(TASK_TIMEOUT_PROPERTY)
        val configuredHeap = requireNotNull(System.getProperty(CONFIGURED_HEAP_PROPERTY))
        val sizeDeadlineNs = Math.multiplyExact(sizeDeadlineMs, 1_000_000L)
        val runId = requireNotNull(System.getProperty("plainbase.performance.screen.run"))
        val schedule = profile.schedule()
        val ledger = ObservationLedger(runId, profile.expectedKeys(runId))
        check(Files.notExists(outputRoot)) { "performance evidence directory already exists: $outputRoot" }
        check(Files.notExists(fixtureRoot)) { "performance fixture directory already exists: $fixtureRoot" }
        val stagedPatch = sourceStagingRoot.resolve("tracked.patch")
        val stagedProbe = sourceStagingRoot.resolve("BackendPerformanceScreenTest.kt")
        val stagedLifecycleTest = sourceStagingRoot.resolve("BackendPerformanceScreenLifecycleTest.kt")
        check(Files.isRegularFile(stagedPatch) && Files.isRegularFile(stagedProbe) && Files.isRegularFile(stagedLifecycleTest)) {
            "performance source staging is incomplete"
        }
        val sourceSha256s = sourceHashes()
        check(sourceSha256s == System.getProperty(EXPECTED_SOURCE_HASHES_PROPERTY)) {
            "performance source hashes changed after task capture"
        }
        check(REQUIRED_SOURCE_FILES.all { required -> sourceSha256s.contains("$required=") }) {
            "performance source identity is missing a required implementation input"
        }
        Files.createDirectories(outputRoot)
        Files.copy(stagedPatch, outputRoot.resolve("tracked.patch"))
        Files.copy(stagedProbe, outputRoot.resolve("BackendPerformanceScreenTest.kt"))
        Files.copy(stagedLifecycleTest, outputRoot.resolve("BackendPerformanceScreenLifecycleTest.kt"))
        check(
            sha256(Files.readAllBytes(outputRoot.resolve("tracked.patch"))) ==
                requireNotNull(System.getProperty("plainbase.performance.screen.patch")),
        ) {
            "tracked patch snapshot hash mismatch"
        }

        val manifest = Manifest(outputRoot.resolve("manifest.json"))
        manifest.put("run_id", runId)
        manifest.put("profile", profile.wireValue)
        manifest.put("git_head", requireNotNull(System.getProperty("plainbase.performance.screen.head")))
        manifest.put("tracked_patch_sha256", requireNotNull(System.getProperty("plainbase.performance.screen.patch")))
        manifest.put("java_command", System.getProperty("sun.java.command").orEmpty())
        manifest.put("java_home", "normalized:omitted")
        manifest.put("graalvm_home", "normalized:omitted")
        manifest.put(
            "gradle_start_parameters",
            requireNotNull(System.getProperty("plainbase.performance.screen.gradleStartParameters")),
        )
        manifest.put("tracked_patch_file", "tracked.patch")
        manifest.put("probe_source_snapshot", "BackendPerformanceScreenTest.kt")
        manifest.put("lifecycle_test_snapshot", "BackendPerformanceScreenLifecycleTest.kt")
        manifest.put("tracked_patch_file_sha256", sha256(Files.readAllBytes(outputRoot.resolve("tracked.patch"))))
        manifest.put(
            "probe_source_snapshot_sha256",
            sha256(Files.readAllBytes(outputRoot.resolve("BackendPerformanceScreenTest.kt"))),
        )
        manifest.put(
            "lifecycle_test_snapshot_sha256",
            sha256(Files.readAllBytes(outputRoot.resolve("BackendPerformanceScreenLifecycleTest.kt"))),
        )
        manifest.put("java_version", System.getProperty("java.version"))
        manifest.put("java_vendor", System.getProperty("java.vendor").orEmpty())
        manifest.put("java_vm", System.getProperty("java.vm.name").orEmpty())
        manifest.put("java_runtime", System.getProperty("java.runtime.version"))
        manifest.put("os", "${System.getProperty("os.name")} ${System.getProperty("os.version")}")
        manifest.put("cpu", "${System.getProperty("os.arch")};processors=${Runtime.getRuntime().availableProcessors()}")
        manifest.put("heap", "max=${Runtime.getRuntime().maxMemory()};configured=$configuredHeap")
        manifest.put("instrumentation_agents", agents.joinToString("|").ifEmpty { "none" })
        manifest.put("metadata_paths", "normalized;local path values omitted")
        manifest.put("input_arguments", sanitizedInputArguments(inputArguments))
        manifest.put("resolved_versions", requireNotNull(System.getProperty("plainbase.performance.screen.versions")))
        manifest.put("source_sha256s", sourceSha256s)
        manifest.put("arms", profile.arms.joinToString(",") { it.wireValue })
        manifest.put("sizes", profile.sizes.joinToString(","))
        manifest.put("page_bytes_target", PAGE_BYTES.toString())
        manifest.put("generator", "deterministic-id-frontmatter-v1;two-headings;eight-links;utf8")
        manifest.put("link_root", LINK_ROOT)
        manifest.put("link_path", LINK_PATH)
        manifest.put("link_oracle", expectedLinks(0, SIZES.first()).joinToString("|") { it.toString() })
        manifest.put("proposal_totals", profile.sizes.joinToString(",") { "$it:${proposalTotal(it)}" })
        manifest.put("proposal_edit_target_set", proposalEditTargetSet())
        manifest.put("cell_schedule", profile.scheduleLabel())
        manifest.put("expected_invocations", profile.expectedInvocationCount.toString())
        manifest.put("size_deadline_ms", sizeDeadlineMs.toString())
        manifest.put("task_timeout_ms", taskTimeoutMs.toString())
        manifest.put("final_status", "incomplete")

        var status: JsonlWriter? = null
        var observations: CsvWriter? = null
        var metrics: CsvWriter? = null
        var measurementFailure: Throwable? = null
        var nextCell = -1
        val cellFingerprints = mutableMapOf<Int, CellFingerprints>()
        val armSettings = mutableMapOf<Int, SqliteSettings>()
        try {
            manifest.write()
            checkEvidenceBudget(outputRoot.resolve("manifest.json"))
            val openedStatus = JsonlWriter(outputRoot.resolve("status.jsonl"))
            status = openedStatus
            val openedObservations = CsvWriter(outputRoot.resolve("observations.csv"))
            observations = openedObservations
            openedObservations.row(
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
                    "arm",
                ),
            )
            if (profile.isAttribution) {
                val openedMetrics = CsvWriter(outputRoot.resolve("metrics.csv"))
                metrics = openedMetrics
                openedMetrics.row(
                    listOf(
                        "run_id",
                        "arm",
                        "size",
                        "operation",
                        "phase",
                        "sample",
                        "outcome",
                        "bind_count",
                        "bind_ns",
                        "bind_failures",
                        "bind_failure_ns",
                        "connect_success_count",
                        "connect_success_ns",
                        "connect_failure_count",
                        "connect_failure_ns",
                        "connect_inside_bind_success_count",
                        "connect_inside_bind_success_ns",
                        "connect_inside_bind_failure_count",
                        "connect_inside_bind_failure_ns",
                    ),
                )
            }
            try {
                openedStatus.record(
                    "run_start",
                    mapOf(
                        "agent_count" to agents.size.toString(),
                        "profile" to profile.wireValue,
                        "arms" to profile.arms.joinToString(",") { it.wireValue },
                        "cell_schedule" to profile.scheduleLabel(),
                        "expected_invocations" to profile.expectedInvocationCount.toString(),
                        "started_epoch_ms" to System.currentTimeMillis().toString(),
                    ),
                )
                for ((cellIndex, cell) in schedule.withIndex()) {
                    nextCell = cellIndex
                    if (!runSize(
                            cell.size,
                            cell.arm,
                            profile,
                            manifest,
                            openedStatus,
                            openedObservations,
                            metrics,
                            fixtureRoot,
                            sizeDeadlineMs,
                            sizeDeadlineNs,
                            ledger,
                            cellFingerprints,
                            armSettings,
                        )
                    ) {
                        throw IllegalStateException("screening cell ${cell.arm.wireValue}:${cell.size} was incomplete")
                    }
                }
                ledger.assertComplete()
            } catch (caught: Throwable) {
                measurementFailure = caught
                measurementFailure = withFailure(measurementFailure) {
                    schedule.drop(nextCell + 1).forEach { future ->
                        markUnstarted(
                            future.size,
                            future.arm,
                            profile,
                            "worker_failed",
                            openedStatus,
                            openedObservations,
                            ledger,
                        )
                    }
                }
                measurementFailure = withFailure(measurementFailure) {
                    openedStatus.record(
                        "run_failed",
                        mapOf(
                            "type" to (caught::class.simpleName ?: "unknown"),
                            "profile" to profile.wireValue,
                            "cell_schedule" to profile.scheduleLabel(),
                            "failed_epoch_ms" to System.currentTimeMillis().toString(),
                        ),
                    )
                }
            }
        } catch (caught: Throwable) {
            measurementFailure = combineFailure(measurementFailure, caught)
        }

        val finalizationFailure = ScreenFinalizer(
            cleanup = {
                CleanupCoordinator(
                    deleteFixture = { Files.notExists(fixtureRoot) || fixtureRoot.toFile().deleteRecursively() },
                    deleteSourceStaging = { Files.notExists(sourceStagingRoot) || sourceStagingRoot.toFile().deleteRecursively() },
                ).finish(measurementFailed = measurementFailure != null)
            },
            recordStatus = { event, fields ->
                requireNotNull(status).record(
                    event,
                    if (event == "cleanup") {
                        fields + mapOf(
                            "profile" to profile.wireValue,
                            "arms" to profile.arms.joinToString(",") { it.wireValue },
                            "cell_schedule" to profile.scheduleLabel(),
                        )
                    } else {
                        fields
                    },
                )
            },
            putManifest = manifest::put,
            writeManifest = manifest::write,
            closeObservations = { observations?.close() },
            closeStatus = { status?.close() },
            closeMetrics = { metrics?.close() },
            appendTerminalStatus = { event, fields -> appendJsonl(outputRoot.resolve("status.jsonl"), event, fields) },
            terminalFields = mapOf(
                "profile" to profile.wireValue,
                "arms" to profile.arms.joinToString(",") { it.wireValue },
                "sizes" to profile.sizes.size.toString(),
                "cell_schedule" to profile.scheduleLabel(),
                "expected_invocations" to profile.expectedInvocationCount.toString(),
                "completed_epoch_ms" to System.currentTimeMillis().toString(),
            ),
        ).finalize(measurementFailure)
        if (finalizationFailure != null) throw finalizationFailure
    }

    private fun runSize(
        size: Int,
        arm: ExecutionArm,
        profile: PerformanceProfile,
        manifest: Manifest,
        status: JsonlWriter,
        observations: CsvWriter,
        metricsWriter: CsvWriter?,
        fixtureRoot: Path,
        sizeDeadlineMs: Long,
        sizeDeadlineNs: Long,
        ledger: ObservationLedger,
        cellFingerprints: MutableMap<Int, CellFingerprints>,
        armSettings: MutableMap<Int, SqliteSettings>,
    ): Boolean {
        val sizeDir = fixtureRoot.resolve("$size-${arm.wireValue}")
        val contentDir = sizeDir.resolve("content")
        val dataDir = sizeDir.resolve("data")
        val appDbPath = dataDir.resolve("plainbase.db")
        val searchDbPath = sizeDir.resolve("search.db")
        val deadline = System.nanoTime() + sizeDeadlineNs
        val schedule = profile.schedule(size, arm)
        val accounting = ObservationAccounting()
        val manifestPrefix = manifestPrefix(size, arm)
        val metrics = if (arm == ExecutionArm.ON) ConnectionMetrics() else null
        val probeUrl =
            "$PROBE_URL_PREFIX${requireNotNull(System.getProperty("plainbase.performance.screen.run"))}:${arm.wireValue}:$size"
        val probeProperties = writableSqliteProperties()
        val probeDriver = metrics?.let {
            ConnectionProbeDriver(
                acceptedUrl = probeUrl,
                realUrl = "jdbc:sqlite:${appDbPath.toAbsolutePath()}",
                metrics = it,
            )
        }
        var driverRegistered = false
        var harness: IndexHarness? = null
        var capturedDriver: SqlDriver? = null
        var primaryFailure: Throwable? = null
        var completed = false
        try {
            status.record(
                "size_start",
                mapOf(
                    "size" to size.toString(),
                    "arm" to arm.wireValue,
                    "deadline_ms" to sizeDeadlineMs.toString(),
                ),
            )
            check(System.nanoTime() < deadline) { "size deadline expired before fixture setup: $size" }
            check(Files.notExists(sizeDir)) { "performance cell directory already exists: $sizeDir" }
            probeDriver?.let {
                DriverManager.registerDriver(it)
                driverRegistered = true
                Files.createDirectories(dataDir)
            }
            seedContent(contentDir, size)
            val contentDigest = digestFiles(contentDir)
            val configurationDigest = configurationDigest(size)
            manifest.put("${manifestPrefix}page_count", size.toString())
            manifest.put("${manifestPrefix}content_bytes", contentBytes(contentDir).toString())
            manifest.put("${manifestPrefix}expected_broken_links", (size * 4).toString())
            manifest.put("${manifestPrefix}content_sha256", contentDigest)
            manifest.put("${manifestPrefix}configuration_sha256", configurationDigest)
            manifest.put("${manifestPrefix}driver_mode", if (arm == ExecutionArm.ON) "probe" else "database_factory")
            manifest.put("${manifestPrefix}driver_properties", sortedProperties(probeProperties))
            manifest.put("${manifestPrefix}driver_source_identity", REQUIRED_SOURCE_FILES.sorted().joinToString("|"))
            if (arm == ExecutionArm.ON) manifest.put("${manifestPrefix}probe_url", probeUrl)
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
                val driverFactory: () -> SqlDriver = {
                    val opened = if (arm == ExecutionArm.ON) {
                        DatabaseFactory.migrateOrClose(BeginImmediateSqliteDriver(probeUrl, probeProperties))
                    } else {
                        DatabaseFactory.createDriver(appDbPath)
                    }
                    capturedDriver = opened
                    opened
                }
                val decorateIdMap: (IdMapRepository) -> IdMapRepository = if (metrics == null) {
                    { it }
                } else {
                    { repository -> TimedIdMapRepository(repository, requireNotNull(metrics)) }
                }
                val createdHarness = IndexHarness(
                    contentDir,
                    history = NoOpHistoryProvider,
                    listeners = listOf(
                        IndexBuilder.PublicationListener { snapshot, _ -> indexer.sync(snapshot) },
                    ),
                    searchIndexer = indexer,
                    driverFactory = driverFactory,
                    decorateIdMap = decorateIdMap,
                )
                harness = createdHarness
                createdHarness.use { currentHarness ->
                    check(capturedDriver != null) { "performance driver factory did not return an app driver" }
                    val settings = readSqliteSettings(requireNotNull(capturedDriver))
                    manifest.put("${manifestPrefix}pragma_settings", settings.encode())
                    val previousFingerprint = cellFingerprints[size]
                    val currentFingerprint = CellFingerprints(contentDigest, configurationDigest)
                    if (previousFingerprint != null) {
                        check(previousFingerprint == currentFingerprint) {
                            "corresponding arm fixture fingerprint mismatch for size=$size"
                        }
                    }
                    cellFingerprints[size] = currentFingerprint
                    val previousSettings = armSettings[size]
                    if (previousSettings != null) {
                        check(previousSettings == settings) {
                            "OFF/ON SQLite settings mismatch for size=$size"
                        }
                    }
                    armSettings[size] = settings

                    authority = currentHarness.idMap
                    currentHarness.builder.rebuild()
                    val actualLinkCount = currentHarness.builder.current.pages.sumOf { page -> page.links.size }
                    check(actualLinkCount == size * 8) {
                        "fixture link count mismatch: expected=${size * 8} actual=$actualLinkCount"
                    }
                    manifest.put("${manifestPrefix}link_count", actualLinkCount.toString())
                    seedProposals(currentHarness, size)
                    currentHarness.roleRepository.upsert(
                        "builtin",
                        SUBJECT,
                        Role.VIEWER,
                        Instant.fromEpochMilliseconds(EPOCH_MILLIS),
                    )
                    val pipeline = currentHarness.writePipeline()
                    val context = currentHarness.testRouteContext(
                        writePipeline = pipeline,
                        searchProvider = provider,
                        enforced = true,
                    )
                    checkFixtureBudget(fixtureRoot)
                    manifest.put("${manifestPrefix}fixture_bytes_before_timing", fixtureBytes(fixtureRoot).toString())
                    val proposalRows = currentHarness.proposalRepository.all()
                    assertProposalContract(proposalRows, size)
                    manifest.put("${manifestPrefix}proposal_counts", proposalCounts(proposalRows))
                    manifest.put("${manifestPrefix}proposal_total", proposalRows.size.toString())
                    manifest.put(
                        "${manifestPrefix}proposal_distinct_edit_targets",
                        proposalRows.filter { it.operation == ProposalOperation.EDIT }.map { it.targetPath }.toSet().size.toString(),
                    )
                    manifest.put(
                        "${manifestPrefix}proposal_distinct_create_targets",
                        proposalRows
                            .filter { it.operation == ProposalOperation.CREATE }
                            .map { it.targetPath }
                            .toSet()
                            .size
                            .toString(),
                    )
                    manifest.put("${manifestPrefix}proposal_bytes", proposalBytes(currentHarness).toString())
                    manifest.put("${manifestPrefix}proposal_expected", proposalExpectationDigest(size))
                    manifest.write()

                    for (entry in schedule) {
                        if (System.nanoTime() >= deadline) throw Deadline(accounting.nextObservation)
                        accounting.beginObservation()
                        perform(
                            size,
                            entry,
                            currentHarness,
                            context,
                            provider,
                            pipeline,
                            contentDir,
                            observations,
                            status,
                            metricsWriter,
                            metrics,
                            ledger,
                            onTerminalRowEmitted = accounting::markTerminalRowEmitted,
                        )
                        finishTimedObservation(accounting, System.nanoTime(), deadline)
                    }
                }
            }
            status.record(
                "size_complete",
                mapOf(
                    "size" to size.toString(),
                    "arm" to arm.wireValue,
                    "observations" to schedule.size.toString(),
                ),
            )
            completed = true
        } catch (deadlineFailure: Deadline) {
            var reportingFailure: Throwable? = null
            reportingFailure = withFailure(reportingFailure) {
                schedule.drop(deadlineFailure.nextIndex).forEach { entry ->
                    unstartedRow(size, entry, "size_deadline_exceeded", status, observations, ledger)
                }
            }
            reportingFailure = withFailure(reportingFailure) {
                status.record(
                    "size_incomplete",
                    mapOf("size" to size.toString(), "arm" to arm.wireValue, "reason" to "deadline"),
                )
            }
            if (reportingFailure != null) {
                primaryFailure = combineFailure(deadlineFailure, reportingFailure)
            }
        } catch (failure: Throwable) {
            var combinedFailure: Throwable? = failure
            combinedFailure = withFailure(combinedFailure) {
                schedule.drop(accounting.failureTailStart()).forEach { entry ->
                    unstartedRow(size, entry, "worker_failed", status, observations, ledger)
                }
            }
            combinedFailure = withFailure(combinedFailure) {
                status.record(
                    "size_failed",
                    mapOf(
                        "size" to size.toString(),
                        "arm" to arm.wireValue,
                        "type" to (failure::class.simpleName ?: "unknown"),
                    ),
                )
            }
            primaryFailure = combinedFailure
        } finally {
            var cleanupFailure: Throwable? = null
            cleanupFailure = withFailure(cleanupFailure) {
                if (harness == null) capturedDriver?.close()
            }
            cleanupFailure = withFailure(cleanupFailure) {
                if (driverRegistered) DriverManager.deregisterDriver(requireNotNull(probeDriver))
            }
            BindTiming.clear()
            if (cleanupFailure != null) {
                primaryFailure = combineFailure(primaryFailure, cleanupFailure)
            }
        }
        val finalFailure = primaryFailure
        if (finalFailure != null) throw finalFailure
        return completed
    }

    private fun perform(
        size: Int,
        entry: ScheduledObservation,
        harness: IndexHarness,
        context: RouteContext,
        provider: Fts5SearchProvider,
        pipeline: WritePipeline,
        contentDir: Path,
        observations: CsvWriter,
        status: JsonlWriter,
        metricsWriter: CsvWriter?,
        metrics: ConnectionMetrics?,
        ledger: ObservationLedger,
        onTerminalRowEmitted: () -> Unit,
    ) {
        val runId = requireNotNull(System.getProperty("plainbase.performance.screen.run"))
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
                "arm" to entry.arm.wireValue,
                "operation" to entry.operation.name,
                "phase" to entry.phase,
                "sample" to entry.sample.toString(),
                "page_count_before" to pageCountBefore.toString(),
            ),
        )
        val createMetrics = if (entry.operation == Operation.CREATE) metrics else null
        createMetrics?.reset()
        createMetrics?.activate()
        val started = System.nanoTime()
        var result: Any? = null
        var failure: Throwable? = null
        var duration = 0L
        var metricsSnapshot = ConnectionMetricsSnapshot.ZERO
        try {
            result = when (entry.operation) {
                Operation.LINK -> context.read.validateLinks(principal, screenPageId(0), RootName.PRIMARY)
                Operation.LIST -> context.proposals.list(principal)
                Operation.SAVE -> {
                    val save = prepared as Prepared.Save
                    pipeline.write(
                        grantForTests(),
                        WriteIntent(save.page.id, RootName.PRIMARY, save.page.path, save.page.contentHash, save.bytes),
                    )
                }
                Operation.CREATE -> pipeline.create(
                    createGrantForTests(),
                    (prepared as Prepared.Create).intent,
                )
            }
        } catch (thrown: Throwable) {
            failure = thrown
        } finally {
            duration = System.nanoTime() - started
            metricsSnapshot = createMetrics?.deactivateAndSnapshot() ?: ConnectionMetricsSnapshot.ZERO
        }
        if (failure == null && entry.operation == Operation.CREATE && entry.arm == ExecutionArm.ON) {
            try {
                check(metricsSnapshot.bindFailures == 0L) { "CREATE bind failure count was ${metricsSnapshot.bindFailures}" }
                check(metricsSnapshot.connectFailureCount == 0L) {
                    "CREATE connection failure count was ${metricsSnapshot.connectFailureCount}"
                }
                check(metricsSnapshot.connectInsideBindFailureCount == 0L) {
                    "CREATE inside-bind connection failure count was ${metricsSnapshot.connectInsideBindFailureCount}"
                }
                check(metricsSnapshot.connectInsideBindSuccessCount == metricsSnapshot.bindCount) {
                    "CREATE inside-bind connection count ${metricsSnapshot.connectInsideBindSuccessCount} " +
                        "did not match bind count ${metricsSnapshot.bindCount}"
                }
            } catch (thrown: Throwable) {
                failure = thrown
            }
        }
        if (failure == null) {
            try {
                when (entry.operation) {
                    Operation.LINK -> verifyLinks(result, size)
                    Operation.LIST -> verifyProposals(result, size)
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
        if (metricsWriter != null && entry.operation == Operation.CREATE) {
            metricsWriter.row(
                listOf(
                    runId,
                    entry.arm.wireValue,
                    size.toString(),
                    entry.operation.name,
                    entry.phase,
                    entry.sample.toString(),
                    outcome,
                    metricsSnapshot.bindCount.toString(),
                    metricsSnapshot.bindNs.toString(),
                    metricsSnapshot.bindFailures.toString(),
                    metricsSnapshot.bindFailureNs.toString(),
                    metricsSnapshot.connectSuccessCount.toString(),
                    metricsSnapshot.connectSuccessNs.toString(),
                    metricsSnapshot.connectFailureCount.toString(),
                    metricsSnapshot.connectFailureNs.toString(),
                    metricsSnapshot.connectInsideBindSuccessCount.toString(),
                    metricsSnapshot.connectInsideBindSuccessNs.toString(),
                    metricsSnapshot.connectInsideBindFailureCount.toString(),
                    metricsSnapshot.connectInsideBindFailureNs.toString(),
                ),
            )
            status.record(
                "operation_metrics",
                buildMap {
                    put("size", size.toString())
                    put("arm", entry.arm.wireValue)
                    put("operation", entry.operation.name)
                    put("phase", entry.phase)
                    put("sample", entry.sample.toString())
                    put("outcome", outcome)
                    putAll(metricsSnapshot.asFields())
                },
            )
        }
        ledger.record(entry.key(runId))
        observations.row(
            listOf(
                runId,
                size.toString(),
                entry.operation.name,
                entry.phase,
                entry.sample.toString(),
                "completed",
                pageCountBefore.toString(),
                duration.toString(),
                outcome,
                failure?.let { it::class.simpleName ?: "unknown" } ?: "",
                entry.arm.wireValue,
            ),
        )
        onTerminalRowEmitted()
        status.record(
            "operation_complete",
            mapOf(
                "size" to size.toString(),
                "arm" to entry.arm.wireValue,
                "operation" to entry.operation.name,
                "phase" to entry.phase,
                "sample" to entry.sample.toString(),
                "duration_ns" to duration.toString(),
                "outcome" to outcome,
            ),
        )
        checkEvidenceBudget(observations.path)
        checkEvidenceBudget(status.path)
        metricsWriter?.let { checkEvidenceBudget(it.path) }
        failure?.let { throw it }
    }

    private fun verifyLinks(result: Any?, size: Int) {
        val report = result as? LinkReport
        check(report != null) { "LINK returned a partial/null result" }
        check(report.broken.all { it.page == LINK_SOURCE }) { "LINK oracle source page mismatch" }
        val actual = report.broken.map { link -> BrokenExpectation(link.page, link.target, link.text, link.reason.wireValue) }
        val expected = expectedLinks(0, size)
        check(actual == expected) { "LINK oracle mismatch: expected=$expected actual=$actual" }
    }

    private fun verifyProposals(result: Any?, size: Int) {
        @Suppress("UNCHECKED_CAST")
        val views = result as? List<ProposalSummaryView>
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
            writePage(
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

    private fun proposalCounts(rows: List<ProposalSummaryRow>): String =
        rows.groupingBy { "${it.status}:${it.operation}" }.eachCount().entries.sortedBy { it.key }
            .joinToString(",") { "${it.key}=${it.value}" }

    private fun proposalBytes(harness: IndexHarness): Int =
        harness.proposalRepository.all().sumOf { row -> requireNotNull(harness.proposalRepository.findById(row.id)).proposedContent.size }

    private fun fullSchedule(size: Int, arm: ExecutionArm, profile: PerformanceProfile): List<ScheduledObservation> =
        profile.cells.flatMap { cell ->
        buildList {
            val operation = Operation.valueOf(cell.operation)
            repeat(cell.warmups) { add(ScheduledObservation(size, arm, operation, "warmup", it)) }
            repeat(cell.observations) { add(ScheduledObservation(size, arm, operation, "measure", it)) }
        }
        }

    private fun markUnstarted(
        size: Int,
        arm: ExecutionArm,
        profile: PerformanceProfile,
        reason: String,
        status: JsonlWriter,
        observations: CsvWriter,
        ledger: ObservationLedger,
    ) {
        fullSchedule(size, arm, profile).forEach { entry ->
            unstartedRow(size, entry, reason, status, observations, ledger)
        }
    }

    private fun unstartedRow(
        size: Int,
        entry: ScheduledObservation,
        reason: String,
        status: JsonlWriter,
        observations: CsvWriter,
        ledger: ObservationLedger,
    ) {
        ledger.record(entry.key(requireNotNull(System.getProperty("plainbase.performance.screen.run"))))
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
                entry.arm.wireValue,
            ),
        )
        status.record(
            "operation_unstarted",
            mapOf(
                "size" to size.toString(),
                "arm" to entry.arm.wireValue,
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

    private fun sourceHashes(): String {
        val files = requireNotNull(System.getProperty("plainbase.performance.screen.sourceFiles"))
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
        val keys = requireNotNull(System.getProperty(SOURCE_KEYS_PROPERTY))
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
        check(files.size == keys.size) { "performance source identity key count mismatch" }
        return files.zip(keys).joinToString(";") { (path, key) ->
            "$key=${sha256(Files.readAllBytes(Path.of(path)))}"
        }
    }

    private fun sanitizedInputArguments(arguments: List<String>): String =
        arguments
            .filterNot { it.startsWith("-Dplainbase.test.mainRuntimeClasspath=") }
            .joinToString(" ") { argument ->
                val key = argument.substringBefore('=')
                if (key in NORMALIZED_ARGUMENT_KEYS) "$key=<normalized>" else argument
            }

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

    private fun configurationDigest(size: Int): String =
        sha256(
            listOf(
                "page_bytes=$PAGE_BYTES",
                "generator=deterministic-id-frontmatter-v1;two-headings;eight-links;utf8",
                "size=$size",
                "proposal_total=${proposalTotal(size)}",
                "proposal_expected=${proposalExpectationDigest(size)}",
            ).joinToString("|").toByteArray(StandardCharsets.UTF_8),
        )
}

private val NORMALIZED_ARGUMENT_KEYS = setOf(
    "-Dorg.gradle.internal.worker.tmpdir",
    "-Dplainbase.performance.screen.fixtures",
    "-Dplainbase.performance.screen.output",
    "-Dplainbase.performance.screen.sourceFiles",
    "-Dplainbase.performance.screen.sourceStaging",
)

private fun positiveLong(property: String): Long =
    requireNotNull(System.getProperty(property)).toLong().also { value ->
        require(value > 0) { "$property must be positive" }
    }

private fun PerformanceProfile.schedule(): List<ScheduledCell> =
    arms.flatMap { arm -> sizes.map { size -> ScheduledCell(size, arm) } }

private fun PerformanceProfile.expectedKeys(runId: String): Set<ObservationKey> =
    schedule().flatMap { cell ->
        cells.flatMap { definition ->
            val operation = Operation.valueOf(definition.operation)
            buildList {
                repeat(definition.warmups) {
                    add(ObservationKey(runId, cell.arm, cell.size, operation, "warmup", it))
                }
                repeat(definition.observations) {
                    add(ObservationKey(runId, cell.arm, cell.size, operation, "measure", it))
                }
            }
        }
    }.toSet()

private fun PerformanceProfile.schedule(size: Int, arm: ExecutionArm): List<ScheduledObservation> =
    cells.flatMap { definition ->
        val operation = Operation.valueOf(definition.operation)
        buildList {
            repeat(definition.warmups) { add(ScheduledObservation(size, arm, operation, "warmup", it)) }
            repeat(definition.observations) { add(ScheduledObservation(size, arm, operation, "measure", it)) }
        }
    }

private fun manifestPrefix(size: Int, arm: ExecutionArm): String =
    if (arm == ExecutionArm.NONE) "size_${size}_" else "size_${size}_${arm.wireValue}_"

internal fun attributionSqliteProperties(): Properties = SQLiteConfig().apply {
    setBusyTimeout(SQLITE_BUSY_TIMEOUT_MS)
}.toProperties()

private fun writableSqliteProperties(): Properties = attributionSqliteProperties()

private fun sortedProperties(properties: Properties): String =
    properties.stringPropertyNames().sorted().joinToString(";") { key -> "$key=${properties.getProperty(key)}" }

internal data class SqliteSettings(
    val busyTimeout: Long,
    val journalMode: String,
    val synchronous: Long,
    val schemaVersion: Long,
) {
    fun encode(): String =
        "busy_timeout=$busyTimeout;journal_mode=$journalMode;synchronous=$synchronous;user_version=$schemaVersion"
}

private fun readSqliteSettings(driver: SqlDriver): SqliteSettings = SqliteSettings(
    busyTimeout = driver.readLongPragma("busy_timeout"),
    journalMode = driver.readStringPragma("journal_mode"),
    synchronous = driver.readLongPragma("synchronous"),
    schemaVersion = driver.readLongPragma("user_version"),
)

private fun SqlDriver.readLongPragma(name: String): Long = executeQuery(
    identifier = null,
    sql = "PRAGMA $name;",
    mapper = { cursor ->
        check(cursor.next().value) { "PRAGMA $name returned no row" }
        Value(requireNotNull(cursor.getLong(0)))
    },
    parameters = 0,
).value

private fun SqlDriver.readStringPragma(name: String): String = executeQuery(
    identifier = null,
    sql = "PRAGMA $name;",
    mapper = { cursor ->
        check(cursor.next().value) { "PRAGMA $name returned no row" }
        Value(requireNotNull(cursor.getString(0)))
    },
    parameters = 0,
).value

internal data class CellFingerprints(val content: String, val configuration: String)

internal data class ObservationKey(
    val runId: String,
    val arm: ExecutionArm,
    val size: Int,
    val operation: Operation,
    val phase: String,
    val sample: Int,
)

internal class ObservationLedger(
    private val runId: String,
    private val expected: Set<ObservationKey>,
) {
    private val actual = linkedSetOf<ObservationKey>()

    fun record(key: ObservationKey) {
        check(key.runId == runId) { "observation run identity mismatch" }
        check(key in expected) { "observation was not scheduled: $key" }
        check(actual.add(key)) { "duplicate observation: $key" }
    }

    fun assertComplete() {
        check(actual == expected) {
            "observation completeness mismatch: expected=${expected.size} actual=${actual.size} " +
                "missing=${expected - actual} unexpected=${actual - expected}"
        }
    }
}

private fun ScheduledObservation.key(runId: String): ObservationKey =
    ObservationKey(runId, arm, size, operation, phase, sample)

internal data class ConnectionMetricsSnapshot(
    val bindCount: Long,
    val bindNs: Long,
    val bindFailures: Long,
    val bindFailureNs: Long,
    val connectSuccessCount: Long,
    val connectSuccessNs: Long,
    val connectFailureCount: Long,
    val connectFailureNs: Long,
    val connectInsideBindSuccessCount: Long,
    val connectInsideBindSuccessNs: Long,
    val connectInsideBindFailureCount: Long,
    val connectInsideBindFailureNs: Long,
) {
    fun asFields(): Map<String, String> = mapOf(
        "bind_count" to bindCount.toString(),
        "bind_ns" to bindNs.toString(),
        "bind_failures" to bindFailures.toString(),
        "bind_failure_ns" to bindFailureNs.toString(),
        "connect_success_count" to connectSuccessCount.toString(),
        "connect_success_ns" to connectSuccessNs.toString(),
        "connect_failure_count" to connectFailureCount.toString(),
        "connect_failure_ns" to connectFailureNs.toString(),
        "connect_inside_bind_success_count" to connectInsideBindSuccessCount.toString(),
        "connect_inside_bind_success_ns" to connectInsideBindSuccessNs.toString(),
        "connect_inside_bind_failure_count" to connectInsideBindFailureCount.toString(),
        "connect_inside_bind_failure_ns" to connectInsideBindFailureNs.toString(),
    )

    companion object {
        val ZERO = ConnectionMetricsSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    }
}

internal class ConnectionMetrics {
    var active: Boolean = false
        private set

    private var bindCount = 0L
    private var bindNs = 0L
    private var bindFailures = 0L
    private var bindFailureNs = 0L
    private var connectSuccessCount = 0L
    private var connectSuccessNs = 0L
    private var connectFailureCount = 0L
    private var connectFailureNs = 0L
    private var connectInsideBindSuccessCount = 0L
    private var connectInsideBindSuccessNs = 0L
    private var connectInsideBindFailureCount = 0L
    private var connectInsideBindFailureNs = 0L

    fun reset() {
        check(!active) { "cannot reset active connection metrics" }
        bindCount = 0L
        bindNs = 0L
        bindFailures = 0L
        bindFailureNs = 0L
        connectSuccessCount = 0L
        connectSuccessNs = 0L
        connectFailureCount = 0L
        connectFailureNs = 0L
        connectInsideBindSuccessCount = 0L
        connectInsideBindSuccessNs = 0L
        connectInsideBindFailureCount = 0L
        connectInsideBindFailureNs = 0L
    }

    fun activate() {
        check(!active) { "connection metrics already active" }
        active = true
    }

    fun deactivateAndSnapshot(): ConnectionMetricsSnapshot {
        active = false
        return snapshot()
    }

    fun recordBind(durationNs: Long, failed: Boolean) {
        if (!active) return
        bindCount += 1
        bindNs += durationNs
        if (failed) {
            bindFailures += 1
            bindFailureNs += durationNs
        }
    }

    fun recordConnect(durationNs: Long, insideBind: Boolean, failed: Boolean) {
        if (!active) return
        if (failed) {
            connectFailureCount += 1
            connectFailureNs += durationNs
            if (insideBind) {
                connectInsideBindFailureCount += 1
                connectInsideBindFailureNs += durationNs
            }
        } else {
            connectSuccessCount += 1
            connectSuccessNs += durationNs
            if (insideBind) {
                connectInsideBindSuccessCount += 1
                connectInsideBindSuccessNs += durationNs
            }
        }
    }

    fun snapshot(): ConnectionMetricsSnapshot = ConnectionMetricsSnapshot(
        bindCount,
        bindNs,
        bindFailures,
        bindFailureNs,
        connectSuccessCount,
        connectSuccessNs,
        connectFailureCount,
        connectFailureNs,
        connectInsideBindSuccessCount,
        connectInsideBindSuccessNs,
        connectInsideBindFailureCount,
        connectInsideBindFailureNs,
    )
}

internal object BindTiming {
    private val depth = ThreadLocal.withInitial { 0 }

    fun isInsideBind(): Boolean = depth.get() > 0

    fun <T> measure(metrics: ConnectionMetrics, action: () -> T): T {
        if (!metrics.active) return action()
        val previousDepth = depth.get()
        depth.set(previousDepth + 1)
        val started = System.nanoTime()
        var failed = false
        try {
            return action()
        } catch (thrown: Throwable) {
            failed = true
            throw thrown
        } finally {
            metrics.recordBind(System.nanoTime() - started, failed)
            depth.set(previousDepth)
        }
    }

    fun clear() = depth.remove()
}

internal class TimedIdMapRepository(
    private val delegate: IdMapRepository,
    private val metrics: ConnectionMetrics,
) : IdMapRepository by delegate {
    override fun bind(path: RootedPath, id: PageId, materialized: Boolean, supersession: Supersession): BindOutcome =
        BindTiming.measure(metrics) { delegate.bind(path, id, materialized, supersession) }
}

internal class ConnectionProbeDriver(
    private val acceptedUrl: String,
    private val realUrl: String,
    private val metrics: ConnectionMetrics,
    private val delegate: Driver = org.sqlite.JDBC(),
) : Driver {
    override fun connect(url: String?, info: Properties?): Connection? {
        if (url != acceptedUrl) return null
        if (!metrics.active) return delegate.connect(realUrl, info)
        val insideBind = BindTiming.isInsideBind()
        val started = System.nanoTime()
        return try {
            val connection = delegate.connect(realUrl, info) ?: throw SQLException("xerial rejected its real SQLite URL")
            metrics.recordConnect(System.nanoTime() - started, insideBind, failed = false)
            connection
        } catch (thrown: Throwable) {
            metrics.recordConnect(System.nanoTime() - started, insideBind, failed = true)
            throw thrown
        }
    }

    override fun acceptsURL(url: String?): Boolean = url == acceptedUrl

    override fun getPropertyInfo(url: String?, info: Properties?): Array<DriverPropertyInfo> =
        delegate.getPropertyInfo(realUrl, info)

    override fun getMajorVersion(): Int = delegate.majorVersion

    override fun getMinorVersion(): Int = delegate.minorVersion

    override fun jdbcCompliant(): Boolean = delegate.jdbcCompliant()

    override fun getParentLogger(): Logger = delegate.parentLogger
}

internal class ObservationAccounting {
    private var terminalRowEmitted = false

    var nextObservation: Int = 0
        private set

    fun beginObservation() {
        terminalRowEmitted = false
    }

    fun markTerminalRowEmitted() {
        terminalRowEmitted = true
    }

    fun finishObservation() {
        check(terminalRowEmitted) { "observation finished without a terminal row" }
        nextObservation += 1
    }

    fun failureTailStart(): Int = nextObservation + if (terminalRowEmitted) 1 else 0
}

internal enum class CleanupState(val wireValue: String) {
    DELETED("deleted"),
    RETAINED_MEASUREMENT_FAILURE("retained_measurement_failure"),
    FAILED("failed"),
}

internal data class CleanupReport(
    val fixture: CleanupState,
    val sourceStaging: CleanupState,
    val failures: List<Throwable>,
) {
    val succeeded: Boolean
        get() = fixture == CleanupState.DELETED && sourceStaging == CleanupState.DELETED && failures.isEmpty()
}

internal class CleanupCoordinator(
    private val deleteFixture: () -> Boolean,
    private val deleteSourceStaging: () -> Boolean,
) {
    fun finish(measurementFailed: Boolean): CleanupReport {
        val fixture = if (measurementFailed) {
            CleanupAttempt(CleanupState.RETAINED_MEASUREMENT_FAILURE, null)
        } else {
            attempt("fixture", deleteFixture)
        }
        val sourceStaging = attempt("source staging", deleteSourceStaging)
        return CleanupReport(
            fixture.state,
            sourceStaging.state,
            listOfNotNull(fixture.failure, sourceStaging.failure),
        )
    }

    private fun attempt(label: String, action: () -> Boolean): CleanupAttempt =
        try {
            if (action()) {
                CleanupAttempt(CleanupState.DELETED, null)
            } else {
                CleanupAttempt(CleanupState.FAILED, IllegalStateException("$label cleanup returned false"))
            }
        } catch (failure: Throwable) {
            CleanupAttempt(CleanupState.FAILED, failure)
        }
}

private data class CleanupAttempt(val state: CleanupState, val failure: Throwable?)

internal class ScreenFinalizer(
    private val cleanup: () -> CleanupReport,
    private val recordStatus: (String, Map<String, String>) -> Unit,
    private val putManifest: (String, String) -> Unit,
    private val writeManifest: () -> Unit,
    private val closeObservations: () -> Unit,
    private val closeStatus: () -> Unit,
    private val appendTerminalStatus: (String, Map<String, String>) -> Unit,
    private val terminalFields: Map<String, String> = emptyMap(),
    private val closeMetrics: () -> Unit = {},
) {
    fun finalize(measurementFailure: Throwable?): Throwable? {
        var failure = measurementFailure
        var reason = if (measurementFailure == null) null else "measurement_failure"

        fun capture(stage: String, action: () -> Unit) {
            try {
                action()
            } catch (secondary: Throwable) {
                if (failure == null) {
                    reason = stage
                } else if (reason == "measurement_failure" && stage == "cleanup_failure") {
                    reason = "measurement_and_cleanup_failure"
                }
                failure = combineFailure(failure, secondary)
            }
        }

        var cleanupReport: CleanupReport? = null
        capture("cleanup_failure") { cleanupReport = cleanup() }
        cleanupReport?.let { report ->
            report.failures.forEach { cleanupFailure ->
                if (failure == null) {
                    reason = "cleanup_failure"
                } else if (reason == "measurement_failure") {
                    reason = "measurement_and_cleanup_failure"
                }
                failure = combineFailure(failure, cleanupFailure)
            }
            val expectedRetention =
                measurementFailure != null &&
                    report.fixture == CleanupState.RETAINED_MEASUREMENT_FAILURE &&
                    report.sourceStaging == CleanupState.DELETED
            if (!report.succeeded && !expectedRetention && report.failures.isEmpty()) {
                capture("cleanup_failure") { error("cleanup did not complete") }
            }
            capture("reporting_failure") {
                putManifest("cleanup_fixture", report.fixture.wireValue)
                putManifest("cleanup_source_staging", report.sourceStaging.wireValue)
                putManifest(
                    "cleanup_failures",
                    report.failures.joinToString("|") { it::class.simpleName ?: "unknown" }.ifEmpty { "none" },
                )
            }
            capture("reporting_failure") {
                recordStatus(
                    "cleanup",
                    mapOf(
                        "fixture" to report.fixture.wireValue,
                        "source_staging" to report.sourceStaging.wireValue,
                    ),
                )
            }
        }

        capture("close_failure") { closeObservations() }
        capture("close_failure") { closeMetrics() }
        capture("close_failure") { closeStatus() }

        val terminalEvent = if (failure == null) "run_complete" else "run_incomplete"
        val terminalEventFields = if (failure == null) {
            terminalFields
        } else {
            terminalFields + mapOf("reason" to (reason ?: "unknown_failure"))
        }
        capture("reporting_failure") { appendTerminalStatus(terminalEvent, terminalEventFields) }
        capture("reporting_failure") {
            putManifest("final_status", if (failure == null) "complete" else "incomplete")
            writeManifest()
        }
        return failure
    }
}

private fun withFailure(primary: Throwable?, action: () -> Unit): Throwable? =
    try {
        action()
        primary
    } catch (secondary: Throwable) {
        combineFailure(primary, secondary)
    }

private fun combineFailure(primary: Throwable?, secondary: Throwable): Throwable {
    if (primary == null) return secondary
    if (primary !== secondary) primary.addSuppressed(secondary)
    return primary
}

internal enum class Operation { LINK, LIST, SAVE, CREATE }

internal data class PerformanceCell(val operation: String, val warmups: Int, val observations: Int)

private data class ScheduledCell(val size: Int, val arm: ExecutionArm)

private data class ProposalGroup(val status: ProposalStatus, val count: Int, val editCount: Int)

private data class ScheduledObservation(
    val size: Int,
    val arm: ExecutionArm,
    val operation: Operation,
    val phase: String,
    val sample: Int,
)

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

    class Save(
        val page: IndexedPage,
        val bytes: ByteArray,
        val beforeSnapshot: PageIndex,
        val beforeFingerprint: String,
    ) : Prepared

    data class Create(val intent: CreateIntent, val beforeSnapshot: PageIndex, val beforeFingerprint: String) : Prepared
}

internal class Deadline(val nextIndex: Int) : RuntimeException()

internal fun checkObservationDeadline(now: Long, deadline: Long, nextIndex: Int) {
    if (now >= deadline) throw Deadline(nextIndex)
}

internal fun finishTimedObservation(accounting: ObservationAccounting, now: Long, deadline: Long) {
    accounting.finishObservation()
    checkObservationDeadline(now, deadline, accounting.nextObservation)
}

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

private fun appendJsonl(path: Path, event: String, fields: Map<String, String>) {
    Files.newBufferedWriter(
        path,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND,
    ).use { writer ->
        val values = linkedMapOf("event" to event)
        values.putAll(fields)
        writer.append(
            values.entries.joinToString(prefix = "{", postfix = "}\n", separator = ",") { (key, value) ->
                "\"${jsonEscape(key)}\":\"${jsonEscape(value)}\""
            },
        )
        writer.flush()
    }
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
