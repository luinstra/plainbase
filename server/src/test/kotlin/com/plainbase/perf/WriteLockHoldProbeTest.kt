package com.plainbase.perf

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.BindOutcome
import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.AtRisk
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.BindingStatus
import com.plainbase.domain.root.ProofSource
import com.plainbase.domain.root.RootBinding
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootTopology
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.PlainbaseDb
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRetirementRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRootTopologyRepository
import io.kotest.core.annotation.Ignored
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import org.sqlite.SQLiteConfig
import java.net.InetAddress
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Comparator
import java.util.Locale
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private const val BUSY_BUDGET_MS = 3_000L
private const val HOLDER_JOIN_TIMEOUT_MS = 60_000L
private const val LATCH_HANDOFF_L_NS = 8_667L
private const val FRESHNESS_SQL_FRAGMENT = "SELECT observation_id, binding_epoch"
private const val PROBE_HOLDER_THREAD = "write-lock-probe-holder"
private const val PROBE_CONTENDER_THREAD = "write-lock-probe-contender"
private const val CALIBRATION_ID_PREFIX = "b000"
private const val CONTENDER_ID_PREFIX = "a000"
private const val UNIFORM_CUT_MS = 120 * 60 * 1_000L
private const val SEED_UNIFORM_CUT_THRESHOLD_MS = 10 * 60 * 1_000L
private const val SEED_DEADLINE_MS = 20 * 60 * 1_000L
private const val UNIFORM_CUT_TARGET = 5
private val ACTIVE_N_SET = listOf(1_000, 5_000, 20_000, 100_000)
private val ACTIVE_HOLDERS = setOf("H1", "H2", "H3a", "H3b", "H4")
private const val OUTPUT_GATE_ONLY = "OFF"

private val registrationInvocations = AtomicInteger()

/**
 * DISABLED. This is a measurement instrument, not a test: it asserts nothing about product
 * behaviour and is never part of the build's pass/fail signal.
 *
 * `@Ignored` means Kotest never instantiates this spec, so nothing here runs, registers, or touches
 * the filesystem during an ordinary build. That annotation is the disable; do not rely on the
 * marker file below to keep it quiet, because a stray `.crew/perf/issue-23/RUN` would otherwise be
 * the only thing standing between `./gradlew build` and a full measurement run.
 *
 * To run it again, BOTH steps are required, which is deliberate:
 *   1. delete the `@Ignored` annotation on this class
 *   2. touch .crew/perf/issue-23/RUN
 *   3. ./gradlew :server:test --tests "com.plainbase.perf.WriteLockHoldProbeTest" --rerun-tasks
 * Then restore the annotation. The marker is CONSUMED on the registration that sees it, so the
 * probe runs exactly once per arming and cannot re-fire on a later build. Requires WARN logging
 * enabled (it fails fast if `PLAINBASE_LOG_LEVEL` suppresses WARN, because the logging side-pair
 * measures WARN emission cost inside the lock window). Evidence lands in a fresh run directory
 * under `.crew/perf/issue-23/`, which is gitignored.
 *
 * It measured issue #23: how long corpus-scaled transactions hold the app-DB write lock under the
 * forked `BEGIN IMMEDIATE` driver. Results and their reading are in
 * `docs/reports/issue-23-write-lock-hold-measurement-report.md`, with the raw run beside it. The
 * headline: nothing came within 7x of the 3000 ms busy budget at 100,000 pages, so no remedy was
 * taken. Retained in case that question is ever reopened at a larger corpus.
 *
 * MAINTENANCE WARNING. This probe pins EXACT statement counts against repository internals (H1 is
 * `n+1`, H2 is `5k+1`, H3a is exactly `k` freshness reads, H4 is 0). Those pins are what stop it
 * reporting a number for a transaction that did not do the work. They also mean a refactor of
 * `SqlDelightPageCheckpointRepository` or `SqlDelightRetirementRepository` will make it FAIL rather
 * than silently drift. If that happens and nobody needs the measurement, DELETE THIS FILE; do not
 * loosen the pins to make it compile, because a probe with loosened pins measures nothing.
 *
 * Known limitation, recorded in the report: `overlap_verified` is not gated on a valid latch
 * handoff, so a trial excluded as `contender:pre-hold-issue` could carry `overlap_verified=true`.
 * It did not occur in the recorded run (zero such exclusions).
 */
@Ignored
class WriteLockHoldProbeTest : FunSpec({
    val runRoot = markerPresent()
    val markerPresent = runRoot != null
    registrationInvocations.incrementAndGet()
    val run = runRoot?.let { ProbeRun.create(ACTIVE_N_SET, it) }

    test("CAL-LONG-HOLD busy observability")
        .config(enabled = markerPresent) {
            requireNotNull(run).runCalibration()
        }

    test("H1 control pair")
        .config(enabled = controlEnabled(markerPresent)) {
            requireNotNull(run).runH1Controls()
        }

    test("H1 replace write-lock hold")
        .config(enabled = rowEnabled(markerPresent, "H1")) {
            requireNotNull(run).runHolderRow(Holder.H1)
        }

    test("H2 surviving applyProofs write-lock hold")
        .config(enabled = rowEnabled(markerPresent, "H2")) {
            requireNotNull(run).runHolderRow(Holder.H2)
        }

    test("H3a stale applyProofs write-lock hold")
        .config(enabled = rowEnabled(markerPresent, "H3a")) {
            requireNotNull(run).runHolderRow(Holder.H3A)
        }

    test("H3b stale applyProofs write-lock hold")
        .config(enabled = rowEnabled(markerPresent, "H3b")) {
            requireNotNull(run).runHolderRow(Holder.H3B)
        }

    test("H4 witnessed applyProofs write-lock hold")
        .config(enabled = rowEnabled(markerPresent, "H4")) {
            requireNotNull(run).runHolderRow(Holder.H4)
        }

    test("C2 tombstone refused spot-check")
        .config(enabled = sideEnabled(markerPresent, "spotcheck")) {
            requireNotNull(run).runSpotcheck()
        }

    test("H3a logging pipeline side pair")
        .config(enabled = sideEnabled(markerPresent, "logging")) {
            requireNotNull(run).runLoggingSidePair()
        }

    afterSpec {
        run?.close()
        check(registrationInvocations.get() == 1) {
            "probe registration invoked ${registrationInvocations.get()} times; expected exactly once"
        }
    }
}) {
    override fun isolationMode(): IsolationMode = IsolationMode.SingleInstance
}

private enum class Holder(val label: String) {
    H1("H1"),
    H2("H2"),
    H3A("H3a"),
    H3B("H3b"),
    H4("H4"),
    CAL_LONG_HOLD("CAL-LONG-HOLD"),
    C2_REFUSED_SPOTCHECK("C2-refused-spotcheck"),
    H1_DEFERRED("H1-DEFERRED"),
    H1_IMMEDIATE_CTL("H1-IMMEDIATE-CTL"),
    H3A_WARN_CTL("H3a-WARN-CTL"),
    H3A_QUIET("H3a-QUIET"),
}

private val H1_HOLDERS = setOf(Holder.H1, Holder.H1_DEFERRED, Holder.H1_IMMEDIATE_CTL)

private enum class Contender {
    C1,
    C2,
    SPOTCHECK,
    NONE,
}

private enum class DriverMode {
    IMMEDIATE,
    DEFERRED,
}

private data class SeedSnapshot(
    val n: Int,
    val databasePath: Path,
    val bindingsByRoot: Map<RootName, List<BindingRef>>,
    val wallclockSeconds: Double,
    val outerTransactions: Int,
)

private data class HolderPlan(
    val proofs: List<AbsenceProof>,
    val witnessed: Set<RootedPageId>,
    val k: Int,
    val p: Int,
)

private data class TrialContext(
    val holderReady: CountDownLatch = CountDownLatch(1),
    val contenderParked: CountDownLatch = CountDownLatch(1),
    val startHolder: CountDownLatch = CountDownLatch(1),
    val armed: AtomicBoolean = AtomicBoolean(false),
    val holderStatements: AtomicInteger = AtomicInteger(),
    val holderFreshnessReads: AtomicInteger = AtomicInteger(),
    val contenderStatements: AtomicInteger = AtomicInteger(),
    @Volatile var holdStartNs: Long? = null,
    @Volatile var holdEndNs: Long? = null,
    @Volatile var contenderBeginIssueNs: Long? = null,
    @Volatile var contenderBeginReturnNs: Long? = null,
)

private data class TrialResult(
    val n: Int,
    val k: Int,
    val p: Int,
    val holder: Holder,
    val contender: Contender,
    val trial: Int,
    val phase: String,
    val holdStartNs: Long?,
    val holdEndNs: Long?,
    val holdMs: Double?,
    val contenderBeginIssueNs: Long?,
    val contenderBeginReturnNs: Long?,
    val contenderOutcome: String,
    val contenderArm: String,
    val contenderStatements: Int,
    val overlapVerified: Boolean,
    val completenessVerified: Boolean,
    val preconditionVerified: Boolean,
    val statementsInHold: Int,
    val freshnessReads: Int,
    val holderValid: Boolean,
    val contenderValid: Boolean,
    val excludedReason: String,
)

private data class NoContenderCell(
    val holder: Holder,
    val holderCore: Holder,
    val n: Int,
    val driverMode: DriverMode,
    val contender: Contender = Contender.NONE,
    val logLevel: Level? = null,
)

private data class RequiredCell(
    val holder: Holder,
    val n: Int,
    val p: Int,
    val contender: Contender,
    val target: Int,
)

private class CellProgress {
    var attempts: Int = 0
    var holderValid: Int = 0
    var contenderValid: Int = 0
    var holderExhausted: Boolean = false
    var contenderExhausted: Boolean = false
    var exhausted: Boolean = false
}

private class ProbeDriver(
    private val delegate: SqlDriver,
    private val context: TrialContext? = null,
    private val seedOuterTransactions: AtomicInteger? = null,
    private val seedCounterActive: AtomicBoolean? = null,
) : SqlDriver by delegate {

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        val current = context
        val outer = delegate.currentTransaction() == null
        if (seedCounterActive?.get() == true && outer) seedOuterTransactions?.incrementAndGet()

        val threadName = Thread.currentThread().name
        val contender = current != null && threadName == PROBE_CONTENDER_THREAD
        if (contender) current.contenderBeginIssueNs = System.nanoTime()

        return try {
            delegate.newTransaction().also {
                if (current != null && threadName == PROBE_HOLDER_THREAD && current.armed.get()) {
                    current.holdStartNs = System.nanoTime()
                    current.holderReady.countDown()
                }
            }
        } finally {
            if (contender) {
                current.contenderBeginReturnNs = System.nanoTime()
            }
        }
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        countStatement(sql)
        return delegate.execute(identifier, sql, parameters, binders)
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        countStatement(sql)
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }

    private fun countStatement(sql: String) {
        val current = context ?: return
        when (Thread.currentThread().name) {
            PROBE_HOLDER_THREAD -> {
                if (!current.armed.get()) return
                current.holderStatements.incrementAndGet()
                if (sql.contains(FRESHNESS_SQL_FRAGMENT)) current.holderFreshnessReads.incrementAndGet()
            }

            PROBE_CONTENDER_THREAD -> current.contenderStatements.incrementAndGet()
        }
    }
}

private class ProbeRun private constructor(
    private val temporaryDirectory: Path,
    private val snapshots: Map<Int, SeedSnapshot>,
    private val csv: CsvWriter,
    private val seedUniformCut: Boolean,
    seedFailure: Throwable?,
) {
    private val trialResults = mutableListOf<TrialResult>()
    private var firstMeasuredTrialNs: Long? = null
    private var uniformCutReported = seedUniformCut
    private var unsafeCleanup = false
    private val loggingStatesRecorded = mutableSetOf<String>()
    private val calibrationFailure = AtomicReference<Throwable?>(seedFailure)
    private val runFatalFailure = AtomicReference<Throwable?>()
    private val acceptanceVoids = linkedMapOf<String, String>()
    private var calibrationPassed = false

    private fun refuseIfBlocked(label: String) {
        val calibration = calibrationFailure.get()
        val fatal = runFatalFailure.get()
        if (calibration == null && fatal == null) return
        val reason = calibration ?: fatal
        val kind = if (calibration != null) "calibration" else "run-fatal"
        val message = "PROBE GATE: refusing $label after $kind: ${failureText(reason)}"
        println(message)
        throw IllegalStateException(message, reason)
    }

    private fun recordCalibrationFailure(failure: Throwable) {
        if (calibrationFailure.compareAndSet(null, failure)) {
            csv.writeMarker("calibration_failed=${failureText(failure)}")
        }
    }

    private fun recordRunFatal(failure: Throwable, reason: String) {
        if (runFatalFailure.compareAndSet(null, failure)) {
            csv.writeMarker("run_fatal=$reason:${failureText(failure)}")
        }
    }

    private fun markUnsafeCleanup(holder: Holder, cell: Cell, trial: Int, reason: String) {
        if (!unsafeCleanup) {
            unsafeCleanup = true
            csv.writeMarker(
                "unsafe_cleanup=holder=${holder.label},n=${cell.n},contender=${cell.contender.name}," +
                    "trial=$trial,reason=$reason",
            )
        }
    }

    private fun recordLoggingState(state: String, logger: Logger) {
        if (loggingStatesRecorded.add(state)) {
            csv.writeMarker(
                "logging_observed=state=$state,root_level=${logger.level?.levelStr ?: "INHERITED"}," +
                    "repo_logger_warn_enabled=${repoLogger().isWarnEnabled}",
            )
        }
    }

    fun runHolderRow(holder: Holder) {
        refuseIfBlocked(holder.label)
        val ns = if (holder == Holder.H3B) ACTIVE_N_SET.filter { it == 1_000 || it == 100_000 } else ACTIVE_N_SET
        val contenders = if (holder == Holder.H4) listOf(Contender.C1) else listOf(Contender.C1, Contender.C2)
        val cells = contenders.flatMap { contender -> ns.map { n -> Cell(n, contender) } }
            .filter { cell -> OUTPUT_GATE_ONLY == "OFF" || (cell.n == 1_000 && cell.contender == Contender.C1) }
        val target = if (OUTPUT_GATE_ONLY == "OFF") 8 else 1
        val attemptCap = if (OUTPUT_GATE_ONLY == "OFF") 12 else 1
        val progress = cells.associateWith { CellProgress() }
        val warmups = if (OUTPUT_GATE_ONLY == "OFF") 2 else 0
        repeat(warmups) { warmup ->
            cells.forEach { cell ->
                val snapshot = snapshots.getValue(cell.n)
                writeTrial(holder, holder, cell, snapshot, "warmup", warmup + 1)
            }
        }
        while (cells.any { cell -> cellNeeds(progress.getValue(cell), target, holder) }) {
            cells.forEach { cell ->
                val state = progress.getValue(cell)
                if (state.exhausted) return@forEach
                val required = requiredTarget(holder, target)
                if (state.holderValid >= required && state.contenderValid >= required) return@forEach
                if (state.attempts >= attemptCap) {
                    reportMeasuredNothing(
                        holder = holder,
                        holderCore = holder,
                        cell = cell,
                        p = expectedPlanP(holder, cell.n),
                        state = state,
                        target = required,
                    )
                    return@forEach
                }
                val result = writeTrial(
                    holder = holder,
                    holderCore = holder,
                    cell = cell,
                    snapshot = snapshots.getValue(cell.n),
                    phase = "measured",
                    trial = state.attempts + 1,
                )
                state.attempts += 1
                if (result.holderValid) state.holderValid += 1
                if (result.contenderValid) state.contenderValid += 1
            }
        }
        cells.filter { cell -> !progress.getValue(cell).exhausted }.forEach { cell ->
            reportNoVerifiedContention(holder, holder, cell, expectedPlanP(holder, cell.n))
        }
        if (holder == Holder.H3A && OUTPUT_GATE_ONLY == "OFF" && 100_000 in ns) {
            checkH3aScalingGate(progress)
        }
    }

    private fun cellNeeds(state: CellProgress, defaultTarget: Int, holder: Holder): Boolean {
        if (state.exhausted) return false
        val target = requiredTarget(holder, defaultTarget)
        return state.holderValid < target || state.contenderValid < target
    }

    private fun requiredTarget(holder: Holder, defaultTarget: Int): Int = when {
        holder == Holder.C2_REFUSED_SPOTCHECK -> defaultTarget
        uniformCutReported -> minOf(defaultTarget, UNIFORM_CUT_TARGET)
        else -> defaultTarget
    }

    private fun reportUniformCutBoundary(result: TrialResult) {
        if (seedUniformCut || uniformCutReported) return
        val start = firstMeasuredTrialNs ?: return
        if (System.nanoTime() - start >= UNIFORM_CUT_MS * 1_000_000L) {
            uniformCutReported = true
            val boundary = "holder=${result.holder.label},n=${result.n},contender=${result.contender.name}," +
                "phase=${result.phase},trial=${result.trial}"
            csv.writeMarker("uniform_cut_boundary=$boundary")
            println("PROBE UNIFORM CUT: measured quotas now capped at $UNIFORM_CUT_TARGET at $boundary")
        }
    }

    private fun expectedPlanP(holder: Holder, n: Int): Int = when (holder) {
        Holder.H2 -> 1
        Holder.H3A,
        Holder.H3A_WARN_CTL,
        Holder.H3A_QUIET,
        -> n / 10

        Holder.H3B,
        Holder.H4,
        -> CORPUS_ROOTS.size

        Holder.CAL_LONG_HOLD,
        Holder.H1,
        Holder.H1_DEFERRED,
        Holder.H1_IMMEDIATE_CTL,
        -> 0

        Holder.C2_REFUSED_SPOTCHECK -> error("spot-check holder must provide holderCore")
    }

    private fun reportMeasuredNothing(
        holder: Holder,
        holderCore: Holder,
        cell: Cell,
        p: Int,
        state: CellProgress,
        target: Int,
    ) {
        val groups = buildList {
            if (state.holderValid < target) add("holder-side=${state.holderValid}/$target")
            if (cell.contender != Contender.NONE && state.contenderValid < target) {
                add("contender-side=${state.contenderValid}/$target")
            }
        }.joinToString(",")
        val exclusions = trialResults.count {
            it.phase == "measured" && it.holder == holder && it.n == cell.n && it.contender == cell.contender &&
                it.p == p && it.excludedReason.isNotEmpty()
        }
        state.holderExhausted = state.holderValid < target
        state.contenderExhausted = cell.contender != Contender.NONE && state.contenderValid < target
        state.exhausted = true
        val marker = "holder=${holder.label},holderCore=${holderCore.label},n=${cell.n},p=$p," +
            "contender=${cell.contender.name},short=$groups,exclusions=$exclusions"
        csv.writeMarker("measured_nothing=$marker")
        println("PROBE MEASURED NOTHING: $marker")
    }

    private fun reportNoVerifiedContention(holder: Holder, holderCore: Holder, cell: Cell, p: Int) {
        if (cell.contender == Contender.NONE) return
        val verified = trialResults.any {
            it.phase == "measured" && it.holder == holder && it.n == cell.n &&
                it.p == p && it.contender == cell.contender && it.overlapVerified
        }
        if (!verified) {
            csv.writeMarker(
                "no_verified_contention=holder=${holder.label},holderCore=${holderCore.label}," +
                    "n=${cell.n},p=$p,contender=${cell.contender.name}",
            )
        }
    }

    private fun checkH3aScalingGate(progress: Map<Cell, CellProgress>) {
        val failures = mutableListOf<Pair<Contender, String>>()
        progress.keys.map { it.contender }.distinct().forEach { contender ->
            val endpointValues = listOf(1_000, 100_000).associateWith { n ->
                trialResults.filter {
                    it.holder == Holder.H3A && it.phase == "measured" && it.n == n &&
                        it.contender == contender && it.holderValid
                }.mapNotNull { it.holdMs }
            }
            val low = endpointValues.getValue(1_000)
            val high = endpointValues.getValue(100_000)
            if (low.isEmpty() || high.isEmpty()) {
                failures += contender to "missing endpoint samples n=1000=${low.size},n=100000=${high.size}"
            } else {
                val lowMedian = median(low)
                val highMedian = median(high)
                if (highMedian < lowMedian * 3.0) {
                    failures += contender to "n=100000 median=$highMedian is below 3x n=1000 median=$lowMedian"
                }
            }
        }
        failures.forEach { (contender, detail) ->
            listOf(1_000, 100_000).forEach { n ->
                val state = progress.getValue(Cell(n, contender))
                val reason = "holder:h3a_scaling_gate:pairwise_endpoint_void:contender=" +
                    "${contender.name}:$detail"
                val exclusions = trialResults.count {
                    it.phase == "measured" && it.holder == Holder.H3A && it.n == n &&
                        it.contender == contender && it.excludedReason.isNotEmpty()
                }
                val p = trialResults.firstOrNull {
                    it.phase == "measured" && it.holder == Holder.H3A && it.n == n &&
                        it.contender == contender
                }?.p ?: expectedPlanP(Holder.H3A, n)
                val marker = "holder=H3a,holderCore=H3a,n=$n,p=$p,contender=${contender.name}," +
                    "reason=$reason,exclusions=$exclusions"
                csv.writeVoidRows(Holder.H3A, n, contender, reason)
                acceptanceVoids[requiredCellKey(Holder.H3A, n, p, contender)] = reason
                if (!state.holderExhausted) {
                    csv.writeMarker("measured_nothing=$marker")
                    println("PROBE MEASURED NOTHING: $marker")
                    state.holderExhausted = true
                }
            }
        }
    }

    private fun requiredCells(): List<RequiredCell> = buildList {
        fun addHolderRow(holder: Holder) {
            if (holder.label !in ACTIVE_HOLDERS) return
            val ns = if (holder == Holder.H3B) {
                ACTIVE_N_SET.filter { it == 1_000 || it == 100_000 }
            } else {
                ACTIVE_N_SET
            }
            val contenders = if (holder == Holder.H4) {
                listOf(Contender.C1)
            } else {
                listOf(Contender.C1, Contender.C2)
            }
            ns.forEach { n ->
                contenders.forEach { contender ->
                    add(RequiredCell(holder, n, expectedPlanP(holder, n), contender, 8))
                }
            }
        }

        if (OUTPUT_GATE_ONLY == "OFF") {
            addHolderRow(Holder.H1)
            addHolderRow(Holder.H2)
            addHolderRow(Holder.H3A)
            addHolderRow(Holder.H3B)
            addHolderRow(Holder.H4)
            if ("H1" in ACTIVE_HOLDERS) {
                ACTIVE_N_SET.filter { it == 1_000 || it == 100_000 }.forEach { n ->
                    add(RequiredCell(Holder.H1_DEFERRED, n, 0, Contender.NONE, 16))
                    add(RequiredCell(Holder.H1_IMMEDIATE_CTL, n, 0, Contender.NONE, 16))
                }
            }
            listOf(Holder.H2, Holder.H3A).filter { it.label in ACTIVE_HOLDERS }.forEach { core ->
                ACTIVE_N_SET.filter { it == 1_000 || it == 100_000 }.forEach { n ->
                    add(RequiredCell(Holder.C2_REFUSED_SPOTCHECK, n, expectedPlanP(core, n), Contender.SPOTCHECK, 4))
                }
            }
            if ("H3a" in ACTIVE_HOLDERS) {
                ACTIVE_N_SET.filter { it == 1_000 || it == 100_000 }.forEach { n ->
                    add(RequiredCell(Holder.H3A_WARN_CTL, n, expectedPlanP(Holder.H3A_WARN_CTL, n), Contender.NONE, 8))
                    add(RequiredCell(Holder.H3A_QUIET, n, expectedPlanP(Holder.H3A_QUIET, n), Contender.NONE, 8))
                }
            }
        } else if (OUTPUT_GATE_ONLY == "QUIET" && "H3a" in ACTIVE_HOLDERS) {
            add(RequiredCell(Holder.H3A_QUIET, 1_000, expectedPlanP(Holder.H3A_QUIET, 1_000), Contender.NONE, 1))
        }
    }

    private fun requiredCellKey(holder: Holder, n: Int, p: Int, contender: Contender): String =
        "holder=${holder.label},n=$n,p=$p,contender=${contender.name}"

    private fun requiredCellFailures(): List<String> {
        val failures = linkedMapOf<String, String>()
        if (!calibrationPassed) failures["holder=CAL-LONG-HOLD"] = "missing_or_voided_calibration"
        requiredCells().forEach { cell ->
            val key = requiredCellKey(cell.holder, cell.n, cell.p, cell.contender)
            val target = requiredTarget(cell.holder, cell.target)
            val rows = trialResults.filter {
                it.phase == "measured" && it.holder == cell.holder && it.n == cell.n &&
                    it.p == cell.p && it.contender == cell.contender
            }
            val holderValid = rows.count { it.holderValid }
            val contenderValid = rows.count { it.contenderValid }
            val deficiencies = buildList {
                if (holderValid < target) add("holder-valid=$holderValid/$target")
                if (cell.contender != Contender.NONE && contenderValid < target) {
                    add("contender-valid=$contenderValid/$target")
                }
                acceptanceVoids[key]?.let { reason -> add("voided=$reason") }
            }
            if (deficiencies.isNotEmpty()) failures[key] = deficiencies.joinToString(",")
        }
        acceptanceVoids.forEach { (key, reason) ->
            failures.putIfAbsent(key, "voided=$reason")
        }
        return failures.map { (key, reason) -> "$key:$reason" }
    }

    @Suppress("LongMethod", "ThrowsCount")
    private fun writeTrial(
        holder: Holder,
        holderCore: Holder,
        cell: Cell,
        snapshot: SeedSnapshot,
        phase: String,
        trial: Int,
        driverMode: DriverMode = DriverMode.IMMEDIATE,
    ): TrialResult {
        if (phase != "calibration" && firstMeasuredTrialNs == null) {
            firstMeasuredTrialNs = System.nanoTime()
        }
        try {
            val live = temporaryDirectory.resolve("live.db")
            lockProbe(live)
            Files.copy(snapshot.databasePath, live, StandardCopyOption.REPLACE_EXISTING)
            deleteSidecars(live)

            val context = TrialContext()
            val rawDriver = when (driverMode) {
                DriverMode.IMMEDIATE -> DatabaseFactory.createDriver(live)
                DriverMode.DEFERRED -> JdbcSqliteDriver("jdbc:sqlite:$live", sqliteProperties())
            }
            val driver = ProbeDriver(rawDriver, context)
            val db = DatabaseFactory.createDatabase(driver)
            val preconditionVerified = driver.queryLong("PRAGMA busy_timeout") == BUSY_BUDGET_MS
            val idMap = SqlDelightIdMapRepository(db)
            val checkpoints = SqlDelightPageCheckpointRepository(db)
            val retirements = SqlDelightRetirementRepository(db)
            val topology = SqlDelightRootTopologyRepository(db)
            val baselineScratchCount = driver.countRoot(SCRATCH)
            val replacementMap = if (holderCore in H1_HOLDERS) {
                db.pageCheckpointQueries.selectAll().executeAsList()
                    .associate { row -> RootedPageId(row.root, row.id) to row.url_path }
            } else {
                null
            }
            val plan = prepareHolderPlan(holderCore, snapshot, retirements)
            val holderFailure = AtomicReference<Throwable?>()
            val holderRetired = AtomicReference<Set<RootedPageId>?>(null)
            val contenderFailure = AtomicReference<Throwable?>()
            val fatalFailure = AtomicReference<Error?>()
            val contenderBindOutcome = AtomicReference<BindOutcome?>()
            val contenderTopology = AtomicReference<RootTopology?>()
            val observedArm = AtomicReference(if (cell.contender == Contender.NONE) "none" else "not-dispatched")

            val contenderThread = startContenderThread(
                cell = cell,
                context = context,
                idMap = idMap,
                topology = topology,
                failure = contenderFailure,
                fatalFailure = fatalFailure,
                bindOutcome = contenderBindOutcome,
                contenderTopology = contenderTopology,
                observedArm = observedArm,
            )
            val holderThread = startHolderThread(
                context = context,
                holderCore = holderCore,
                snapshot = snapshot,
                plan = plan,
                checkpoints = checkpoints,
                replacementMap = replacementMap,
                retirements = retirements,
                db = db,
                failure = holderFailure,
                fatalFailure = fatalFailure,
                retired = holderRetired,
            )

            var inFlightFailure: Throwable? = null
            try {
                contenderThread?.start()
                if (contenderThread != null) {
                    check(context.contenderParked.await(30, TimeUnit.SECONDS)) { "contender did not park" }
                }
                holderThread.start()
                context.startHolder.countDown()
                holderThread.join(HOLDER_JOIN_TIMEOUT_MS)
                if (holderThread.isAlive) {
                    val timeout = IllegalStateException(
                        "holder thread did not finish within $HOLDER_JOIN_TIMEOUT_MS ms",
                    )
                    markUnsafeCleanup(holder, cell, trial, "holder_join_timeout")
                    recordRunFatal(timeout, "holder_join_timeout")
                    throw timeout
                }
                contenderThread?.join(60_000)
                if (contenderThread?.isAlive == true) {
                    val timeout = IllegalStateException("contender thread did not finish within 60000 ms")
                    markUnsafeCleanup(holder, cell, trial, "contender_join_timeout")
                    recordRunFatal(timeout, "contender_join_timeout")
                    throw timeout
                }
                fatalFailure.get()?.let { throw it }

                val result = buildTrialResult(
                    holder = holder,
                    holderCore = holderCore,
                    cell = cell,
                    snapshot = snapshot,
                    phase = phase,
                    trial = trial,
                    context = context,
                    driver = driver,
                    preconditionVerified = preconditionVerified,
                    plan = plan,
                    holderFailure = holderFailure,
                    holderRetired = holderRetired,
                    contenderFailure = contenderFailure,
                    contenderBindOutcome = contenderBindOutcome,
                    contenderTopology = contenderTopology,
                    observedArm = observedArm.get(),
                    baselineScratchCount = baselineScratchCount,
                )
                csv.write(result)
                trialResults += result
                reportUniformCutBoundary(result)
                return result
            } catch (failure: Throwable) {
                inFlightFailure = failure
                throw failure
            } finally {
                cleanupTrial(holder, cell, trial, context, holderThread, contenderThread, driver, inFlightFailure)
            }
        } catch (failure: Throwable) {
            recordRunFatal(failure, "trial_infrastructure")
            throw failure
        }
    }

    private fun cleanupTrial(
        holder: Holder,
        cell: Cell,
        trial: Int,
        context: TrialContext,
        holderThread: Thread,
        contenderThread: Thread?,
        driver: ProbeDriver,
        inFlightFailure: Throwable?,
    ) {
        context.startHolder.countDown()
        context.holderReady.countDown()
        val holderStopped = stopSurvivor(holderThread)
        val contenderStopped = contenderThread?.let { stopSurvivor(it) } ?: true
        if (holderStopped && contenderStopped) {
            driver.close()
            return
        }
        markUnsafeCleanup(holder, cell, trial, "thread_survivor")
        csv.flush()
        val cleanupFailure = IllegalStateException(
            "probe thread survivor after interrupt: " +
                listOfNotNull(
                    holderThread.takeIf { it.isAlive }?.name,
                    contenderThread?.takeIf { it.isAlive }?.name,
                ).joinToString(",") +
                " armed=${context.armed.get()} hold_start_ns=${context.holdStartNs} " +
                "contender_begin_issue_ns=${context.contenderBeginIssueNs}",
        )
        inFlightFailure?.let(cleanupFailure::addSuppressed)
        recordRunFatal(cleanupFailure, "thread_survivor")
        throw cleanupFailure
    }

    private fun buildTrialResult(
        holder: Holder,
        holderCore: Holder,
        cell: Cell,
        snapshot: SeedSnapshot,
        phase: String,
        trial: Int,
        context: TrialContext,
        driver: ProbeDriver,
        preconditionVerified: Boolean,
        plan: HolderPlan,
        holderFailure: AtomicReference<Throwable?>,
        holderRetired: AtomicReference<Set<RootedPageId>?>,
        contenderFailure: AtomicReference<Throwable?>,
        contenderBindOutcome: AtomicReference<BindOutcome?>,
        contenderTopology: AtomicReference<RootTopology?>,
        observedArm: String,
        baselineScratchCount: Long,
    ): TrialResult {
        val holdStart = context.holdStartNs
        val holdEnd = context.holdEndNs
        val holdMs = if (holdStart != null && holdEnd != null) {
            (holdEnd - holdStart) / 1_000_000.0
        } else {
            null
        }
        val holderReason = holderFailure.get()?.let { failure ->
            "holder:failure:${failure::class.java.name}:${failure.message ?: "<null>"}"
        } ?: if (!preconditionVerified) {
            "holder:precondition_busy_timeout:${BUSY_BUDGET_MS}"
        } else {
            checkHolder(
                holder = holderCore,
                holdMs = holdMs,
                n = snapshot.n,
                plan = plan,
                returned = holderRetired.get(),
                statements = context.holderStatements.get(),
                freshnessReads = context.holderFreshnessReads.get(),
                db = driver,
            )
        }
        val contenderOutcome = contenderOutcome(
            cell.contender,
            contenderFailure.get(),
            contenderBindOutcome.get(),
            contenderTopology.get(),
        )
        val contenderArm = classifyContenderArm(
            contender = cell.contender,
            outcome = contenderOutcome,
            dispatchedArm = observedArm,
            statements = context.contenderStatements.get(),
            topology = contenderTopology.get(),
            bindOutcome = contenderBindOutcome.get(),
        )
        val contenderReason = checkContender(
            contender = cell.contender,
            outcome = contenderOutcome,
            arm = contenderArm,
            statements = context.contenderStatements.get(),
            baselineScratchCount = baselineScratchCount,
            scratchCount = driver.countRoot(SCRATCH),
            topology = contenderTopology.get(),
            bindOutcome = contenderBindOutcome.get(),
        )
        val handoffReason = context.contenderBeginIssueNs?.let { issue ->
            if (holdStart != null && issue <= holdStart) "contender:pre-hold-issue" else null
        }
        val contenderExclusion = contenderReason ?: handoffReason
        val holderValid = holderReason == null
        val contenderValid = holderValid && contenderExclusion == null
        return TrialResult(
            n = snapshot.n,
            k = plan.k,
            p = plan.p,
            holder = holder,
            contender = cell.contender,
            trial = trial,
            phase = phase,
            holdStartNs = holdStart,
            holdEndNs = holdEnd,
            holdMs = holdMs,
            contenderBeginIssueNs = context.contenderBeginIssueNs,
            contenderBeginReturnNs = context.contenderBeginReturnNs,
            contenderOutcome = contenderOutcome,
            contenderArm = contenderArm,
            contenderStatements = context.contenderStatements.get(),
            overlapVerified = contenderOutcome == "busy",
            completenessVerified = holderValid,
            preconditionVerified = preconditionVerified,
            statementsInHold = context.holderStatements.get(),
            freshnessReads = context.holderFreshnessReads.get(),
            holderValid = holderValid,
            contenderValid = contenderValid,
            excludedReason = listOfNotNull(holderReason, contenderExclusion).joinToString("|"),
        )
    }

    private fun classifyContenderArm(
        contender: Contender,
        outcome: String,
        dispatchedArm: String,
        statements: Int,
        topology: RootTopology?,
        bindOutcome: BindOutcome?,
    ): String = when {
        contender == Contender.NONE -> "none"
        outcome == "busy" -> normalizedDispatchedArm(contender, dispatchedArm)
        contender == Contender.C1 -> "write"
        contender == Contender.C2 && c2ArmChanged(statements, topology) -> "change"
        contender == Contender.SPOTCHECK && bindOutcome == BindOutcome.Bound -> "write"
        else -> normalizedDispatchedArm(contender, dispatchedArm)
    }

    private fun normalizedDispatchedArm(contender: Contender, dispatchedArm: String): String =
        dispatchedArm.takeIf { it != "not-dispatched" } ?: when (contender) {
            Contender.C1 -> "write"
            Contender.C2 -> "unchanged"
            Contender.SPOTCHECK -> "refused-tombstone"
            Contender.NONE -> "none"
        }

    private fun c2ArmChanged(statements: Int, topology: RootTopology?): Boolean {
        if (statements > 1) return true
        return topology != null && (
            topology.binding != SCRATCH_BINDING ||
                topology.status != BindingStatus.UNRESOLVED ||
                topology.atRisk != SCRATCH_AT_RISK
            )
    }

    private fun startContenderThread(
        cell: Cell,
        context: TrialContext,
        idMap: SqlDelightIdMapRepository,
        topology: SqlDelightRootTopologyRepository,
        failure: AtomicReference<Throwable?>,
        fatalFailure: AtomicReference<Error?>,
        bindOutcome: AtomicReference<BindOutcome?>,
        contenderTopology: AtomicReference<RootTopology?>,
        observedArm: AtomicReference<String>,
    ): Thread? {
        if (cell.contender == Contender.NONE) return null
        return thread(
            start = false,
            isDaemon = true,
            name = PROBE_CONTENDER_THREAD,
        ) {
            context.contenderParked.countDown()
            try {
                check(context.holderReady.await(30, TimeUnit.SECONDS)) { "holder did not arm" }
                when (cell.contender) {
                    Contender.C1 -> {
                        observedArm.set("write")
                        val path = RootedPath(SCRATCH, TreePath.require("contender/page-000.md"))
                        val id = PageId.require("00000000-0000-$CONTENDER_ID_PREFIX-8000-000000000000")
                        bindOutcome.set(idMap.bind(path, id, materialized = true))
                    }

                    Contender.C2 -> {
                        observedArm.set("unchanged")
                        contenderTopology.set(topology.observeBinding(SCRATCH, SCRATCH_BINDING))
                    }
                    Contender.SPOTCHECK -> {
                        observedArm.set("refused-tombstone")
                        bindOutcome.set(idMap.bind(TOMBSTONE_PATH_2, TOMBSTONE_X, materialized = true))
                    }

                    Contender.NONE -> error("NONE contender was not supposed to start")
                }
            } catch (contenderFailure: Throwable) {
                if (contenderFailure is Error) fatalFailure.set(contenderFailure) else failure.set(contenderFailure)
            }
        }
    }

    private fun startHolderThread(
        context: TrialContext,
        holderCore: Holder,
        @Suppress("UNUSED_PARAMETER") snapshot: SeedSnapshot,
        plan: HolderPlan,
        checkpoints: SqlDelightPageCheckpointRepository,
        replacementMap: Map<RootedPageId, TreePath?>?,
        retirements: SqlDelightRetirementRepository,
        db: PlainbaseDb,
        failure: AtomicReference<Throwable?>,
        fatalFailure: AtomicReference<Error?>,
        retired: AtomicReference<Set<RootedPageId>?>,
    ): Thread = thread(
        start = false,
        isDaemon = true,
        name = PROBE_HOLDER_THREAD,
    ) {
        try {
            check(context.startHolder.await(30, TimeUnit.SECONDS)) { "holder start was not released" }
            context.armed.set(true)
            when (holderCore) {
                Holder.H1,
                Holder.H1_DEFERRED,
                Holder.H1_IMMEDIATE_CTL,
                -> checkpoints.replace(
                    checkNotNull(replacementMap) { "H1 replacement map was not read from restored DB" },
                )

                Holder.H2,
                Holder.C2_REFUSED_SPOTCHECK,
                Holder.H3A,
                Holder.H3A_WARN_CTL,
                Holder.H3A_QUIET,
                Holder.H3B,
                Holder.H4,
                -> retired.set(
                    retirements.applyProofs(
                        proofs = plan.proofs,
                        witnessed = plan.witnessed,
                        unavailableNow = { emptySet() },
                        advances = emptyList(),
                    ),
                )

                Holder.CAL_LONG_HOLD -> db.transaction {
                    val deadline = System.nanoTime() + 8_000_000_000L
                    var index = 0
                    while (System.nanoTime() < deadline) {
                        val id = PageId.require("00000000-0000-$CALIBRATION_ID_PREFIX-8000-%012x".format(index.toLong()))
                        db.pageCheckpointQueries.insertRow(
                            id = id,
                            root = SCRATCH,
                            urlPath = TreePath.require("cal/page-%06d.md".format(index)),
                        )
                        index += 1
                    }
                }
            }
        } catch (holderFailure: Throwable) {
            if (holderFailure is Error) fatalFailure.set(holderFailure) else failure.set(holderFailure)
        } finally {
            context.holdEndNs = System.nanoTime()
            context.armed.set(false)
            context.holderReady.countDown()
        }
    }

    private fun stopSurvivor(thread: Thread): Boolean {
        if (!thread.isAlive) return true
        thread.interrupt()
        thread.join(1_000)
        return !thread.isAlive
    }

    private fun expectedHolderStatements(holder: Holder, n: Int, plan: HolderPlan): Int = when (holder) {
        Holder.H1,
        Holder.H1_DEFERRED,
        Holder.H1_IMMEDIATE_CTL,
        -> n + 1

        Holder.H2,
        -> 5 * plan.k + 1

        Holder.H3A,
        Holder.H3A_WARN_CTL,
        Holder.H3A_QUIET,
        -> plan.k

        Holder.H3B -> CORPUS_ROOTS.size

        Holder.H4 -> 0

        Holder.CAL_LONG_HOLD -> 0

        else -> error("unreachable holder in expected statement count: $holder")
    }

    private fun prepareHolderPlan(
        holder: Holder,
        snapshot: SeedSnapshot,
        retirements: SqlDelightRetirementRepository,
    ): HolderPlan {
        val k = snapshot.n / 10
        return when (holder) {
            Holder.H1,
            Holder.H1_DEFERRED,
            Holder.H1_IMMEDIATE_CTL,
            Holder.CAL_LONG_HOLD,
            -> HolderPlan(emptyList(), emptySet(), if (holder == Holder.CAL_LONG_HOLD) 0 else snapshot.n, 0)

            Holder.H2,
            -> {
                val observation = retirements.observation(GUIDES)
                val epoch = retirements.bindingEpoch(GUIDES)
                HolderPlan(
                    proofs = listOf(
                        AbsenceProof(
                            root = GUIDES,
                            source = ProofSource.EPOCH,
                            observationId = observation,
                            bindingEpoch = epoch,
                            covers = snapshot.bindingsByRoot.getValue(GUIDES).take(k).toSet(),
                        ),
                    ),
                    witnessed = emptySet(),
                    k = k,
                    p = 1,
                )
            }

            Holder.H3A,
            Holder.H3A_WARN_CTL,
            Holder.H3A_QUIET,
            -> {
                val observation = retirements.observation(GUIDES)
                val epoch = retirements.bindingEpoch(GUIDES)
                val proofs = snapshot.bindingsByRoot.getValue(GUIDES).take(k).map { ref ->
                    AbsenceProof(GUIDES, ProofSource.EPOCH, observation, epoch, setOf(ref))
                }
                retirements.revoke(GUIDES)
                check(retirements.observation(GUIDES) != observation) { "H3a refutation precondition did not change observation" }
                HolderPlan(proofs, emptySet(), k, k)
            }

            Holder.H3B,
            Holder.H4,
            -> {
                val observations = CORPUS_ROOTS.associateWith { root -> retirements.observation(root) }
                val proofs = CORPUS_ROOTS.map { root ->
                    val observation = observations.getValue(root)
                    val epoch = retirements.bindingEpoch(root)
                    AbsenceProof(
                        root = root,
                        source = ProofSource.EPOCH,
                        observationId = observation,
                        bindingEpoch = epoch,
                        covers = snapshot.bindingsByRoot.getValue(root).take(k / CORPUS_ROOTS.size).toSet(),
                    )
                }
                if (holder == Holder.H3B) {
                    CORPUS_ROOTS.forEach { root ->
                        retirements.revoke(root)
                    }
                    CORPUS_ROOTS.forEach { root ->
                        check(retirements.observation(root) != observations.getValue(root)) {
                            "H3b refutation precondition did not change observation"
                        }
                    }
                    HolderPlan(proofs, emptySet(), k, CORPUS_ROOTS.size)
                } else {
                    val witnessed = proofs.flatMap { proof ->
                        proof.covers.map { ref -> RootedPageId(proof.root, ref.id) }
                    }.toSet()
                    check(proofs.all { it.source.inferred }) { "H4 source is not inferred" }
                    HolderPlan(proofs, witnessed, k, CORPUS_ROOTS.size)
                }
            }

            else -> error("unreachable holder in prepare plan: $holder")
        }
    }

    private fun checkHolder(
        holder: Holder,
        holdMs: Double?,
        n: Int,
        plan: HolderPlan,
        returned: Set<RootedPageId>?,
        statements: Int,
        freshnessReads: Int,
        db: SqlDriver,
    ): String? {
        if (holder == Holder.CAL_LONG_HOLD) {
            return if (holdMs == null || holdMs < 8_000.0) "holder:calibration_hold" else null
        }
        if (holdMs == null) return "holder:no_hold"
        val expectedStatements = expectedHolderStatements(holder, n, plan)
        return when {
            statements != expectedStatements -> "holder:statements:$statements!=$expectedStatements"
            holder == Holder.H1 || holder == Holder.H1_DEFERRED || holder == Holder.H1_IMMEDIATE_CTL ->
                checkH1Shape(n, db)
            holder == Holder.H2 ->
                checkH2Shape(n, plan, returned, freshnessReads, db)
            else -> checkApplyProofsShape(holder, n, plan, returned, freshnessReads, db)
        }
    }

    private fun checkH1Shape(n: Int, db: SqlDriver): String? = when {
        db.queryLong("SELECT COUNT(*) FROM page_checkpoint") != n.toLong() -> "holder:page_checkpoint_count"
        else -> null
    }

    private fun checkH2Shape(
        n: Int,
        plan: HolderPlan,
        returned: Set<RootedPageId>?,
        freshnessReads: Int,
        db: SqlDriver,
    ): String? {
        val corpusCount = CORPUS_ROOTS.sumOf { db.countRoot(it) }
        return when {
            freshnessReads != 1 -> "holder:freshness_reads:$freshnessReads!=1"
            returned?.size != plan.k -> "holder:retired_count:${returned?.size}!=${plan.k}"
            corpusCount != n - plan.k.toLong() -> "holder:corpus_count:$corpusCount!=${n - plan.k}"
            else -> null
        }
    }

    private fun checkApplyProofsShape(
        holder: Holder,
        n: Int,
        plan: HolderPlan,
        returned: Set<RootedPageId>?,
        freshnessReads: Int,
        db: SqlDriver,
    ): String? {
        val expectedFreshnessReads = when (holder) {
            Holder.H3A,
            Holder.H3A_WARN_CTL,
            Holder.H3A_QUIET,
            -> plan.k

            Holder.H3B -> CORPUS_ROOTS.size
            Holder.H4 -> 0
            else -> 0
        }
        val corpusCount = CORPUS_ROOTS.sumOf { db.countRoot(it) }
        return when {
            returned == null -> "holder:no_returned_set"
            freshnessReads != expectedFreshnessReads -> "holder:freshness_reads:$freshnessReads!=$expectedFreshnessReads"
            returned.isNotEmpty() -> "holder:retired_count:${returned.size}!=0"
            corpusCount != n.toLong() -> "holder:corpus_count:$corpusCount!=$n"
            else -> null
        }
    }

    private fun checkContender(
        contender: Contender,
        outcome: String,
        arm: String,
        statements: Int,
        baselineScratchCount: Long,
        scratchCount: Long,
        topology: RootTopology?,
        bindOutcome: BindOutcome?,
    ): String? {
        if (contender == Contender.NONE) {
            return if (scratchCount == baselineScratchCount) null else "contender:scratch_delta"
        }
        if (outcome == "busy") return checkBusyOutcome(statements, baselineScratchCount, scratchCount)
        return when (contender) {
            Contender.C1 -> checkC1Outcome(outcome, statements, baselineScratchCount, scratchCount)
            Contender.C2 -> checkC2Outcome(outcome, arm, statements, baselineScratchCount, scratchCount, topology)
            Contender.SPOTCHECK -> checkSpotcheckOutcome(
                outcome,
                arm,
                statements,
                baselineScratchCount,
                scratchCount,
                contenderBindOutcome = bindOutcome,
            )
            Contender.NONE -> null
        }
    }

    private fun checkBusyOutcome(statements: Int, baselineScratchCount: Long, scratchCount: Long): String? = when {
        statements != 0 -> "contender:busy_statements:$statements!=0"
        scratchCount != baselineScratchCount -> "contender:busy_scratch_delta"
        else -> null
    }

    private fun checkC1Outcome(
        outcome: String,
        statements: Int,
        baselineScratchCount: Long,
        scratchCount: Long,
    ): String? = when {
        outcome != "bound" -> "contender:outcome:$outcome"
        statements != 6 -> "contender:statements:$statements!=6"
        scratchCount != baselineScratchCount + 1 -> "contender:scratch_delta"
        else -> null
    }

    private fun checkSpotcheckOutcome(
        outcome: String,
        arm: String,
        statements: Int,
        baselineScratchCount: Long,
        scratchCount: Long,
        contenderBindOutcome: BindOutcome?,
    ): String? = when {
        outcome != "refused" -> "contender:outcome:$outcome"
        arm != "refused-tombstone" -> "contender:arm:$arm"
        contenderBindOutcome != expectedTombstoneRefusal() -> "contender:refused_value:$contenderBindOutcome"
        statements != 1 -> "contender:statements:$statements!=1"
        scratchCount != baselineScratchCount -> "contender:scratch_delta"
        else -> null
    }

    private fun checkC2Outcome(
        outcome: String,
        arm: String,
        statements: Int,
        baselineScratchCount: Long,
        scratchCount: Long,
        topology: RootTopology?,
    ): String? = when {
        outcome != "returned" -> "contender:outcome:$outcome"
        arm != "unchanged" -> "contender:arm:$arm"
        statements != 1 -> "contender:statements:$statements!=1"
        topology == null || topology.binding != SCRATCH_BINDING ||
            topology.status != BindingStatus.UNRESOLVED || topology.atRisk != SCRATCH_AT_RISK ->
            "contender:topology_value"
        scratchCount != baselineScratchCount -> "contender:scratch_delta"
        else -> null
    }

    private fun contenderOutcome(
        contender: Contender,
        failure: Throwable?,
        bindOutcome: BindOutcome?,
        topology: RootTopology?,
    ): String = when {
        contender == Contender.NONE -> "none"
        failure != null && failure.isBusy() -> "busy"
        bindOutcome == BindOutcome.Bound -> "bound"
        bindOutcome is BindOutcome.Refused -> "refused"
        topology != null -> "returned"
        else -> "error"
    }

    fun runH1Controls() {
        refuseIfBlocked("H1 controls")
        val cells = ACTIVE_N_SET.filter { it == 1_000 || it == 100_000 }.flatMap { n ->
            listOf(
                NoContenderCell(Holder.H1_DEFERRED, Holder.H1, n, DriverMode.DEFERRED),
                NoContenderCell(Holder.H1_IMMEDIATE_CTL, Holder.H1, n, DriverMode.IMMEDIATE),
            )
        }
        runScheduledNoContenderCells(cells, quota = 16, attemptCap = 20, warmups = 2)
    }

    fun runSpotcheck() {
        refuseIfBlocked("C2 tombstone spot-check")
        val cells = listOf(Holder.H2, Holder.H3A).flatMap { core ->
            ACTIVE_N_SET.filter { it == 1_000 || it == 100_000 }.map { n ->
                NoContenderCell(
                    holder = Holder.C2_REFUSED_SPOTCHECK,
                    holderCore = core,
                    n = n,
                    driverMode = DriverMode.IMMEDIATE,
                    contender = Contender.SPOTCHECK,
                )
            }
        }
        runScheduledNoContenderCells(cells, quota = 4, attemptCap = 12, warmups = 1)
    }

    fun runLoggingSidePair() {
        refuseIfBlocked("H3a logging side pair")
        val rootLogger = rootLogger()
        val previousLevel = rootLogger.level
        try {
            if (OUTPUT_GATE_ONLY == "QUIET") {
                runScheduledNoContenderCells(
                    cells = listOf(
                        NoContenderCell(
                            holder = Holder.H3A_QUIET,
                            holderCore = Holder.H3A,
                            n = 1_000,
                            driverMode = DriverMode.IMMEDIATE,
                            logLevel = Level.ERROR,
                        ),
                    ),
                    quota = 1,
                    attemptCap = 1,
                    warmups = 0,
                )
                return
            }
            val cells = ACTIVE_N_SET.filter { it == 1_000 || it == 100_000 }.flatMap { n ->
                listOf(
                    NoContenderCell(Holder.H3A_WARN_CTL, Holder.H3A, n, DriverMode.IMMEDIATE),
                    NoContenderCell(Holder.H3A_QUIET, Holder.H3A, n, DriverMode.IMMEDIATE, logLevel = Level.ERROR),
                )
            }
            runScheduledNoContenderCells(cells, quota = 8, attemptCap = 12, warmups = 2)
        } finally {
            setRootLevel(rootLogger, previousLevel)
        }
    }

    fun runCalibration() {
        calibrationFailure.get()?.let { failure ->
            throw IllegalStateException("calibration blocked before execution by seed failure", failure)
        }
        try {
            val result = writeTrial(
                holder = Holder.CAL_LONG_HOLD,
                holderCore = Holder.CAL_LONG_HOLD,
                cell = Cell(1_000, Contender.C1),
                snapshot = snapshots.getValue(1_000),
                phase = "calibration",
                trial = 1,
            )
            check(result.holderValid && result.contenderValid && result.excludedReason.isEmpty()) {
                "CAL-LONG-HOLD was excluded: ${result.excludedReason}"
            }
            check(result.holdMs != null && result.holdMs >= 8_000.0) {
                "CAL-LONG-HOLD did not hold for 8 seconds: ${result.holdMs}"
            }
            check(result.contenderOutcome == "busy") {
                "CAL-LONG-HOLD did not observe SQLITE_BUSY: ${result.contenderOutcome}"
            }
            calibrationPassed = true
        } catch (failure: Throwable) {
            if (failure is Error) recordRunFatal(failure, "calibration_error") else recordCalibrationFailure(failure)
            throw failure
        }
    }

    private fun runScheduledNoContenderCells(
        cells: List<NoContenderCell>,
        quota: Int,
        attemptCap: Int,
        warmups: Int,
    ) {
        val progress = cells.associateWith { CellProgress() }
        repeat(warmups) { warmup ->
            cells.forEach { cell ->
                runNoContenderTrial(cell, "warmup", warmup + 1)
            }
        }
        while (cells.any { cell -> noContenderCellNeeds(progress.getValue(cell), cell, quota) }) {
            cells.forEach { cell ->
                val state = progress.getValue(cell)
                if (state.exhausted) return@forEach
                val target = requiredTarget(cell.holder, quota)
                if (state.holderValid >= target && (cell.contender == Contender.NONE || state.contenderValid >= target)) {
                    return@forEach
                }
                if (state.attempts >= attemptCap) {
                    reportMeasuredNothing(
                        holder = cell.holder,
                        holderCore = cell.holderCore,
                        cell = Cell(cell.n, cell.contender),
                        p = expectedPlanP(cell.holderCore, cell.n),
                        state = state,
                        target = target,
                    )
                    return@forEach
                }
                val result = runNoContenderTrial(cell, "measured", state.attempts + 1)
                state.attempts += 1
                if (result.holderValid) state.holderValid += 1
                if (result.contenderValid) state.contenderValid += 1
            }
        }
        cells.filter { cell -> !progress.getValue(cell).exhausted }.forEach { cell ->
            reportNoVerifiedContention(
                holder = cell.holder,
                holderCore = cell.holderCore,
                cell = Cell(cell.n, cell.contender),
                p = expectedPlanP(cell.holderCore, cell.n),
            )
        }
    }

    private fun noContenderCellNeeds(state: CellProgress, cell: NoContenderCell, quota: Int): Boolean {
        if (state.exhausted) return false
        val target = requiredTarget(cell.holder, quota)
        return state.holderValid < target || (cell.contender != Contender.NONE && state.contenderValid < target)
    }

    private fun runNoContenderTrial(
        cell: NoContenderCell,
        phase: String,
        trial: Int,
    ): TrialResult {
        val logger = rootLogger()
        val previous = logger.level
        if (cell.logLevel != null) setRootLevel(logger, cell.logLevel)
        check(logger.level == (cell.logLevel ?: previous)) { "logging level changed before measured call" }
        if (cell.holder == Holder.H3A_WARN_CTL) {
            check(repoLogger().isWarnEnabled) { "WARN arm did not enable the repository logger" }
            recordLoggingState("WARN", logger)
        }
        if (cell.holder == Holder.H3A_QUIET) {
            check(!repoLogger().isWarnEnabled) { "QUIET arm did not disable the repository logger" }
            recordLoggingState("QUIET", logger)
        }
        return try {
            writeTrial(
                holder = cell.holder,
                holderCore = cell.holderCore,
                cell = Cell(cell.n, cell.contender),
                snapshot = snapshots.getValue(cell.n),
                phase = phase,
                trial = trial,
                driverMode = cell.driverMode,
            )
        } finally {
            if (cell.logLevel != null) setRootLevel(logger, previous)
            if (cell.holder == Holder.H3A_WARN_CTL || cell.holder == Holder.H3A_QUIET) {
                recordLoggingState("RESTORED", logger)
            }
        }
    }

    fun close() {
        val observedRegistrations = registrationInvocations.get()
        val acceptanceFailures = requiredCellFailures()
        if (acceptanceFailures.isNotEmpty()) {
            csv.writeMarker("acceptance_failed=required_cells:${acceptanceFailures.joinToString("|")}")
        }
        csv.writeRegistrationInvocations(observedRegistrations)
        csv.close()
        if (!unsafeCleanup) {
            deleteTree(temporaryDirectory)
        } else {
            println("PROBE cleanup skipped because a timed-out thread may retain the temp tree at $temporaryDirectory")
        }
        check(observedRegistrations == 1) {
            "probe registration invoked $observedRegistrations times; expected exactly once"
        }
        check(acceptanceFailures.isEmpty()) {
            "required cell acceptance failed: " + acceptanceFailures.joinToString("; ")
        }
    }

    companion object {
        fun create(activeN: List<Int>, root: Path): ProbeRun {
            val evidenceRoot = root.resolve(".crew/perf/issue-23")
            val runDirectory = createRunDirectory(evidenceRoot)
            Files.writeString(evidenceRoot.resolve("claimed-by"), runDirectory.fileName.toString())
            println("PROBE RUN DIR: ${runDirectory.fileName}")
            val source = root.resolve("server/src/test/kotlin/com/plainbase/perf/WriteLockHoldProbeTest.kt")
            val snapshot = runDirectory.resolve("probe-snapshot.kt")
            Files.copy(source, snapshot, StandardCopyOption.REPLACE_EXISTING)
            val probeSha = sha256(snapshot)
            val temporaryDirectory = Files.createTempDirectory("pb-write-lock-probe")
            val snapshots = linkedMapOf<Int, SeedSnapshot>()
            var seedFailure: Throwable? = null
            try {
                activeN.forEach { n ->
                    snapshots[n] = seed(temporaryDirectory, n)
                }
            } catch (failure: Throwable) {
                seedFailure = failure
            }
            val totalSeedSeconds = snapshots.values.sumOf { it.wallclockSeconds }
            val hasLargeSeed = seedFailure == null && snapshots.containsKey(100_000)
            val seedUniformCut = hasLargeSeed &&
                totalSeedSeconds * 1_000.0 > SEED_UNIFORM_CUT_THRESHOLD_MS
            val csv = CsvWriter(runDirectory.resolve("results.csv"))
            csv.writeHeader(
                probeSha = probeSha,
                activeN = activeN,
                snapshots = snapshots,
                seedUniformCut = seedUniformCut,
                baseCommit = observedBaseCommit(root),
                environment = environmentIdentity(root),
            )
            seedFailure?.let { csv.writeMarker("seed_failure=${failureText(it)}") }
            val run = ProbeRun(temporaryDirectory, snapshots, csv, seedUniformCut, seedFailure)
            if (seedFailure is Error) run.recordRunFatal(seedFailure, "seed_failure")
            return run
        }

        private fun seed(temporaryDirectory: Path, n: Int): SeedSnapshot {
            val seedStartNs = System.nanoTime()
            val seedDeadlineNs = seedStartNs + SEED_DEADLINE_MS * 1_000_000L
            val path = temporaryDirectory.resolve("seed-$n.db")
            val outerTransactions = AtomicInteger()
            val counterActive = AtomicBoolean(true)
            val rawDriver = DatabaseFactory.createDriver(path)
            val driver = ProbeDriver(rawDriver, seedOuterTransactions = outerTransactions, seedCounterActive = counterActive)
            val db = DatabaseFactory.createDatabase(driver)
            val idMap = SqlDelightIdMapRepository(db)
            val checkpoints = SqlDelightPageCheckpointRepository(db)
            val retirements = SqlDelightRetirementRepository(db)
            val topology = SqlDelightRootTopologyRepository(db)
            val checkpoint = linkedMapOf<RootedPageId, TreePath?>()
            val bindingsByRoot = linkedMapOf<RootName, MutableList<BindingRef>>()
            var ordinal = 0
            var driverClosed = false
            try {
                db.transaction {
                    CORPUS_ROOTS.forEach { corpusRoot ->
                        val refs = bindingsByRoot.getOrPut(corpusRoot) { mutableListOf() }
                        repeat(n / CORPUS_ROOTS.size) { index ->
                            if (index % 128 == 0) checkSeedDeadline(seedDeadlineNs, n)
                            val pathValue = TreePath.require(
                                "section-%03d/page-%03d.md".format(index / 1_000, index % 1_000),
                            )
                            val id = PageId.require("00000000-0000-4000-8000-%012x".format(ordinal.toLong()))
                            val rootedPath = RootedPath(corpusRoot, pathValue)
                            idMap.bind(rootedPath, id, materialized = true) shouldBe BindOutcome.Bound
                            refs += BindingRef(pathValue, id)
                            checkpoint[RootedPageId(corpusRoot, id)] = pathValue
                            ordinal += 1
                        }
                    }
                }
                counterActive.set(false)
                checkSeedDeadline(seedDeadlineNs, n)
                outerTransactions.get() shouldBe 1

                idMap.bind(TOMBSTONE_PATH, TOMBSTONE_X, materialized = true) shouldBe BindOutcome.Bound
                idMap.bind(TOMBSTONE_PATH, TOMBSTONE_Y, materialized = true) shouldBe BindOutcome.Bound
                idMap.bind(TOMBSTONE_PATH_2, TOMBSTONE_X, materialized = true) shouldBe
                    expectedTombstoneRefusal()

                checkpoints.replace(checkpoint)
                ROOT_BINDINGS.forEach { (rootName, binding) ->
                    topology.observeBinding(rootName, binding)
                }
                CORPUS_ROOTS.forEach { rootName ->
                    retirements.observation(rootName)
                    retirements.bindingEpoch(rootName)
                }
                retirements.observation(SCRATCH)
                retirements.bindingEpoch(SCRATCH)
                driver.queryLong("PRAGMA busy_timeout") shouldBe BUSY_BUDGET_MS
                checkSeedDeadline(seedDeadlineNs, n)
                driver.close()
                driverClosed = true
                check(!Files.exists(path.resolveSibling("${path.fileName}-journal"))) {
                    "seed journal remains: ${path.resolveSibling("${path.fileName}-journal")}"
                }
                check(!Files.exists(path.resolveSibling("${path.fileName}-wal"))) {
                    "seed wal remains: ${path.resolveSibling("${path.fileName}-wal")}"
                }
                check(!Files.exists(path.resolveSibling("${path.fileName}-shm"))) {
                    "seed shm remains: ${path.resolveSibling("${path.fileName}-shm")}"
                }
                val wallclockSeconds = (System.nanoTime() - seedStartNs) / 1_000_000_000.0
                return SeedSnapshot(n, path, bindingsByRoot, wallclockSeconds, outerTransactions.get())
            } finally {
                counterActive.set(false)
                if (!driverClosed) runCatching { driver.close() }
            }
        }
    }
}

private data class Cell(val n: Int, val contender: Contender)

private fun rowEnabled(markerPresent: Boolean, holder: String): Boolean =
    markerPresent && holder in ACTIVE_HOLDERS && when (OUTPUT_GATE_ONLY) {
        "OFF" -> true
        "WARN" -> holder == "H3a"
        "QUIET" -> false
        else -> error("unknown OUTPUT_GATE_ONLY=$OUTPUT_GATE_ONLY")
    }

private fun controlEnabled(markerPresent: Boolean): Boolean = markerPresent && "H1" in ACTIVE_HOLDERS && OUTPUT_GATE_ONLY == "OFF"

private fun sideEnabled(markerPresent: Boolean, side: String): Boolean = markerPresent && when (side) {
    "spotcheck" -> OUTPUT_GATE_ONLY == "OFF" && ("H2" in ACTIVE_HOLDERS || "H3a" in ACTIVE_HOLDERS)
    "logging" -> "H3a" in ACTIVE_HOLDERS && (OUTPUT_GATE_ONLY == "OFF" || OUTPUT_GATE_ONLY == "QUIET")
    else -> error("unknown side=$side")
}

private fun rootLogger(): Logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger

private fun repoLogger(): Logger = LoggerFactory.getLogger(SqlDelightRetirementRepository::class.java.name) as Logger

private fun setRootLevel(logger: Logger, level: Level?) {
    logger.level = level
}

private class CsvWriter(private val path: Path) {
    private val runId = requireNotNull(path.parent).fileName.toString()
    private var writer = Files.newBufferedWriter(path)

    fun writeHeader(
        probeSha: String,
        activeN: List<Int>,
        snapshots: Map<Int, SeedSnapshot>,
        seedUniformCut: Boolean,
        baseCommit: String,
        environment: Map<String, String>,
    ) {
        writer.appendLine("# run_id=$runId")
        writer.appendLine("# base_commit=$baseCommit")
        writer.appendLine("# base_commit_kind=observed_runtime_head")
        writer.appendLine("# probe_sha256=$probeSha")
        writer.appendLine("# registration_invocations=deferred_until_close")
        writer.appendLine("# duplicate_header_key_policy=last_occurrence_wins")
        writer.appendLine(
            "# phase_enumeration=warmup|measured|calibration " +
                "(deviation from plan:1395, calibration row must not aggregate as measured)",
        )
        writer.appendLine("# void_rows_supersede_per_row_validity=true")
        writer.appendLine("# utc_date=${Instant.now()}")
        writer.appendLine("# busy_timeout_ms=$BUSY_BUDGET_MS")
        writer.appendLine("# output_gate_only=$OUTPUT_GATE_ONLY")
        writer.appendLine("# effective_log_level=${rootLogger().level?.levelStr ?: "INHERITED"}")
        writer.appendLine("# repo_logger_warn_enabled=${repoLogger().isWarnEnabled}")
        writer.appendLine("# quiet_run_log_level=ERROR")
        writer.appendLine("# latch_handoff_l_ns=$LATCH_HANDOFF_L_NS")
        listOf("machine", "os_version", "cpu_model", "mem_gb", "jvm_version", "gradle_version").forEach { key ->
            writer.appendLine("# $key=${environment.getValue(key)}")
        }
        writer.appendLine("# active_n_set=${activeN.joinToString(",")}")
        writer.appendLine("# active_holders=${ACTIVE_HOLDERS.joinToString(",")}")
        writer.appendLine("# scheduler=round_robin_one_measured_trial_per_cell_per_round")
        writer.appendLine("# validity=contender_group_gated_by_holder_valid_with_separate_replenishment")
        writer.appendLine("# uniform_cut_ms=$UNIFORM_CUT_MS")
        writer.appendLine("# seed_uniform_cut_threshold_ms=$SEED_UNIFORM_CUT_THRESHOLD_MS")
        writer.appendLine("# seed_uniform_cut=$seedUniformCut")
        writer.appendLine("# uniform_cut_boundary=${if (seedUniformCut) "seed_complete" else "not_reached"}")
        writer.appendLine("# byte_gate_trial_denominator=1_H3a_N1000_WARN_trial")
        writer.appendLine("# byte_gate_n_scaling_assumption=warn_bytes_scale_linearly_with_N_over_1000")
        writer.appendLine("# byte_gate_projected_gate_trial_equivalents=6258")
        writer.appendLine("# byte_gate_projected_gate_trial_equivalents_derivation=3528+1414+1313+3=6258")
        writer.appendLine("# c2_refused_spotcheck_discriminator=p_1_H2_p_equals_k_H3a")
        snapshots.forEach { (n, snapshot) ->
            writer.appendLine("# seed_wallclock_s_$n=${snapshot.wallclockSeconds}")
            writer.appendLine("# seed_outer_txn_count_$n=${snapshot.outerTransactions}")
            writer.appendLine("# seed_file_bytes_$n=${Files.size(snapshot.databasePath)}")
        }
        writer.appendLine(
            "run_id,holder,n,k,p,contender,trial,phase,hold_start_ns,hold_end_ns,hold_ms," +
                "contender_begin_issue_ns,contender_begin_return_ns,contender_outcome,contender_arm," +
                "contender_statements,overlap_verified,completeness_verified," +
                "precondition_verified," +
                "statements_in_hold,freshness_reads,excluded_reason",
        )
        writer.flush()
    }

    fun write(result: TrialResult) {
        writer.appendLine(resultLine(result))
        writer.flush()
    }

    fun writeVoidRows(holder: Holder, n: Int, contender: Contender, reason: String) {
        writeMarker(
            "void_rows=holder=${holder.label},n=$n,contender=${contender.name},reason=$reason",
        )
    }

    private fun resultLine(result: TrialResult): String = listOf(
        runId,
        result.holder.label,
        result.n,
        result.k,
        result.p,
        result.contender.name,
        result.trial,
        result.phase,
        result.holdStartNs ?: "",
        result.holdEndNs ?: "",
        result.holdMs ?: "",
        result.contenderBeginIssueNs ?: "",
        result.contenderBeginReturnNs ?: "",
        result.contenderOutcome,
        result.contenderArm,
        result.contenderStatements,
        result.overlapVerified,
        result.completenessVerified,
        result.preconditionVerified,
        result.statementsInHold,
        result.freshnessReads,
        result.excludedReason.replace(',', ';').replace('\r', ' ').replace('\n', ' '),
    ).joinToString(",")

    fun flush() = writer.flush()

    fun writeMarker(marker: String) {
        val safeMarker = marker.replace(',', ';').replace('\r', ' ').replace('\n', ' ')
        writer.appendLine("# $safeMarker")
        writer.flush()
    }

    fun writeRegistrationInvocations(observed: Int) {
        writer.appendLine("# registration_invocations=$observed")
        writer.flush()
    }

    fun close() = writer.close()
}

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    } else {
        sorted[middle]
    }
}

private fun failureText(failure: Throwable?): String = failure?.let {
    "${it::class.java.name}:${it.message ?: "<null>"}"
}.orEmpty().replace('\r', ' ').replace('\n', ' ')

private fun checkSeedDeadline(deadlineNs: Long, n: Int) {
    check(System.nanoTime() <= deadlineNs) {
        "seed deadline exceeded while preparing N=$n after ${SEED_DEADLINE_MS / 60_000} minutes"
    }
}

private fun commandOutput(vararg command: String): String = runCatching {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val completed = process.waitFor(60, TimeUnit.SECONDS)
    if (!completed) {
        process.destroyForcibly()
        return@runCatching ""
    }
    if (process.exitValue() != 0) return@runCatching ""
    process.inputStream.bufferedReader().use { it.readText().trim() }
}.getOrDefault("")

private fun observedBaseCommit(root: Path): String =
    commandOutput("git", "-C", root.toString(), "rev-parse", "HEAD").ifBlank { "unavailable" }

private fun environmentIdentity(root: Path): Map<String, String> {
    val memoryBytes = commandOutput("sysctl", "-n", "hw.memsize").toLongOrNull()
    val memoryGb = memoryBytes?.let { bytes -> "%.1f".format(Locale.ROOT, bytes / 1_073_741_824.0) } ?: "unavailable"
    val cpuModel = commandOutput("sysctl", "-n", "machdep.cpu.brand_string")
        .ifBlank { System.getProperty("os.arch") ?: "unavailable" }
    val gradleOutput = commandOutput(root.resolve("gradlew").toString(), "--version")
    val gradleVersion = Regex("Gradle\\s+([^\\s]+)").find(gradleOutput)?.groupValues?.get(1) ?: "unavailable"
    return linkedMapOf(
        "machine" to runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unavailable"),
        "os_version" to listOf(System.getProperty("os.name"), System.getProperty("os.version"))
            .filterNotNull()
            .joinToString(" "),
        "cpu_model" to cpuModel,
        "mem_gb" to memoryGb,
        "jvm_version" to (System.getProperty("java.runtime.version") ?: "unavailable"),
        "gradle_version" to gradleVersion,
    ).mapValues { (_, value) -> value.replace('\n', ' ').replace('\r', ' ') }
}

private fun markerPresent(): Path? {
    val root = runCatching { findRepoRoot() }.getOrNull() ?: return null
    val marker = root.resolve(".crew/perf/issue-23/RUN")
    if (!Files.deleteIfExists(marker)) return null
    check(repoLogger().isWarnEnabled) {
        "repository WARN logging is disabled by PLAINBASE_LOG_LEVEL; the probe requires WARN visibility"
    }
    return root
}

private fun findRepoRoot(): Path {
    var current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
    while (true) {
        if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) return current
        current = requireNotNull(current.parent) { "settings.gradle.kts not found from ${current.toAbsolutePath()}" }
    }
}

private fun createRunDirectory(evidenceRoot: Path): Path {
    val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss'Z'").withZone(ZoneOffset.UTC)
    val base = "run-${formatter.format(Instant.now())}"
    return try {
        Files.createDirectory(evidenceRoot.resolve(base))
    } catch (_: FileAlreadyExistsException) {
        Files.createDirectory(evidenceRoot.resolve("$base-${System.nanoTime()}"))
    }
}

private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256")
    .digest(Files.readAllBytes(path))
    .joinToString("") { byte -> "%02x".format(byte) }

private fun lockProbe(path: Path) {
    val properties = sqliteProperties()
    DriverManager.getConnection("jdbc:sqlite:$path", properties).use { connection ->
        connection.createStatement().use { statement ->
            statement.execute("BEGIN IMMEDIATE")
            statement.execute("ROLLBACK")
        }
    }
}

private fun sqliteProperties(): Properties =
    SQLiteConfig().apply { setBusyTimeout(BUSY_BUDGET_MS.toInt()) }.toProperties()

private fun deleteSidecars(path: Path) {
    val journal = path.resolveSibling("${path.fileName}-journal")
    val wal = path.resolveSibling("${path.fileName}-wal")
    val shm = path.resolveSibling("${path.fileName}-shm")
    Files.deleteIfExists(journal)
    Files.deleteIfExists(wal)
    Files.deleteIfExists(shm)
    check(!Files.exists(journal)) { "sidecar remains: $journal" }
    check(!Files.exists(wal)) { "sidecar remains: $wal" }
    check(!Files.exists(shm)) { "sidecar remains: $shm" }
}

private fun deleteTree(path: Path) {
    if (Files.notExists(path)) return
    Files.walk(path).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}

private fun SqlDriver.queryLong(sql: String): Long = executeQuery(
    identifier = null,
    sql = sql,
    mapper = { cursor ->
        cursor.next()
        QueryResult.Value(requireNotNull(cursor.getLong(0)))
    },
    parameters = 0,
).value

private fun SqlDriver.countRoot(root: RootName): Long = executeQuery(
    identifier = null,
    sql = "SELECT COUNT(*) FROM id_map WHERE root = ?",
    mapper = { cursor ->
        cursor.next()
        QueryResult.Value(requireNotNull(cursor.getLong(0)))
    },
    parameters = 1,
    binders = { bindString(0, root.value) },
).value

private fun Throwable.isBusy(): Boolean = generateSequence(this) { it.cause }
    .any { throwable ->
        throwable.message.orEmpty().contains("SQLITE_BUSY") || throwable.message.orEmpty().contains("database is locked")
    }

private val DOCS = RootName.PRIMARY
private val GUIDES = RootName.require("guides")
private val RUNBOOKS = RootName.require("runbooks")
private val ARCHIVE = RootName.require("archive")
private val SCRATCH = RootName.require("scratch")
private val TOMBSTONE_X = PageId.require("00000000-0000-9000-8000-000000000000")
private val TOMBSTONE_Y = PageId.require("00000000-0000-9000-8000-000000000001")
private val TOMBSTONE_PATH = RootedPath(SCRATCH, TreePath.require("tomb-p1.md"))
private val TOMBSTONE_PATH_2 = RootedPath(SCRATCH, TreePath.require("tomb-p2.md"))
private val CORPUS_ROOTS = listOf(DOCS, GUIDES, RUNBOOKS, ARCHIVE)
private val ROOT_BINDINGS = linkedMapOf(
    DOCS to RootBinding("/probe/docs"),
    GUIDES to RootBinding("/probe/guides"),
    RUNBOOKS to RootBinding("/probe/runbooks"),
    ARCHIVE to RootBinding("/probe/archive"),
    SCRATCH to RootBinding("/probe/scratch"),
)
private val SCRATCH_BINDING = ROOT_BINDINGS.getValue(SCRATCH)
private val SCRATCH_AT_RISK = AtRisk.Bindings(
    setOf(
        BindingRef(TOMBSTONE_PATH.path, TOMBSTONE_Y),
    ),
)

private fun expectedTombstoneRefusal(): BindOutcome.Refused =
    BindOutcome.Refused(TOMBSTONE_X, TOMBSTONE_PATH, retired = true)
