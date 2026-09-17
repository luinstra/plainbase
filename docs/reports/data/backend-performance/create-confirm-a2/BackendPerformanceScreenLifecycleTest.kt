package com.plainbase.performance

import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.DriverPropertyInfo
import java.sql.SQLException
import java.util.Properties
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BackendPerformanceScreenLifecycleTest {

    @Test
    fun `fixed profiles preserve the default schedule and attribution arm order`() {
        assertEquals("none:250,none:1000,none:3000", PerformanceProfile.SCREEN.scheduleLabel())
        assertEquals("on:1000,on:3000,off:1000,off:3000", PerformanceProfile.CREATE_ON_OFF.scheduleLabel())
        assertEquals("off:1000,off:3000,on:1000,on:3000", PerformanceProfile.CREATE_OFF_ON.scheduleLabel())
        assertEquals(246, PerformanceProfile.SCREEN.expectedInvocationCount)
        assertEquals(28, PerformanceProfile.CREATE_ON_OFF.expectedInvocationCount)
        assertEquals(28, PerformanceProfile.CREATE_OFF_ON.expectedInvocationCount)
        assertSame(PerformanceProfile.SCREEN, PerformanceProfile.parse("screen"))
        assertFailsWith<IllegalStateException> { PerformanceProfile.parse("screen-and-more") }
    }

    @Test
    fun `observation ledger distinguishes every scheduled operation tuple`() {
        val runId = "ledger-run"
        val key = ObservationKey(runId, ExecutionArm.ON, 1_000, Operation.CREATE, "measure", 0)
        val ledger = ObservationLedger(runId, setOf(key))

        ledger.record(key)
        assertFailsWith<IllegalStateException> { ledger.record(key) }
        ledger.assertComplete()
    }

    @Test
    fun `connection probe counts only active opens inside bind and preserves failures`() {
        val properties = attributionSqliteProperties()
        assertEquals("3000", properties.getProperty("busy_timeout"))

        val metrics = ConnectionMetrics()
        val acceptedUrl = "jdbc:plainbase-connect-probe:test"
        DriverManager.getConnection("jdbc:sqlite::memory:").use { returnedConnection ->
            val probe = ConnectionProbeDriver(
                acceptedUrl,
                "jdbc:sqlite:unused",
                metrics,
                ThrowingDriver(returnedConnection = returnedConnection),
            )
            assertEquals(null, probe.connect("jdbc:plainbase-connect-probe:other", properties))

            metrics.reset()
            metrics.activate()
            BindTiming.measure(metrics) {
                assertSame(returnedConnection, probe.connect(acceptedUrl, properties))
            }
            val success = metrics.deactivateAndSnapshot()
            assertEquals(1L, success.bindCount)
            assertEquals(1L, success.connectSuccessCount)
            assertEquals(1L, success.connectInsideBindSuccessCount)
            assertEquals(0L, success.connectFailureCount)
            BindTiming.clear()
        }

        val failure = SQLException("probe failure")
        val failingMetrics = ConnectionMetrics()
        val failingProbe = ConnectionProbeDriver(
            acceptedUrl,
            "jdbc:sqlite:unused",
            failingMetrics,
            ThrowingDriver(failure),
        )
        failingMetrics.reset()
        failingMetrics.activate()
        val thrown = assertFailsWith<SQLException> {
            BindTiming.measure(failingMetrics) { failingProbe.connect(acceptedUrl, properties) }
        }
        val failed = failingMetrics.deactivateAndSnapshot()
        assertSame(failure, thrown)
        assertEquals(1L, failed.bindFailures)
        assertEquals(1L, failed.connectFailureCount)
        assertEquals(1L, failed.connectInsideBindFailureCount)
        BindTiming.clear()
    }

    @Test
    fun `connection probe can be deregistered after a delegated failure`() {
        val failure = SQLException("deregister failure")
        val probe = ConnectionProbeDriver(
            "jdbc:plainbase-connect-probe:deregister",
            "jdbc:sqlite:unused",
            ConnectionMetrics(),
            ThrowingDriver(failure),
        )

        DriverManager.registerDriver(probe)
        try {
            val thrown = assertFailsWith<SQLException> {
                probe.connect("jdbc:plainbase-connect-probe:deregister", Properties())
            }
            assertSame(failure, thrown)
        } finally {
            DriverManager.deregisterDriver(probe)
        }
    }

    @Test
    fun `preparation failure keeps the current observation in the unstarted tail`() {
        val accounting = ObservationAccounting()

        accounting.beginObservation()

        assertEquals(0, accounting.failureTailStart())
    }

    @Test
    fun `failure after terminal row excludes the current observation from the unstarted tail`() {
        val accounting = ObservationAccounting()

        accounting.beginObservation()
        accounting.markTerminalRowEmitted()

        assertEquals(1, accounting.failureTailStart())
        accounting.finishObservation()
        assertEquals(1, accounting.nextObservation)
    }

    @Test
    fun `measurement failure accepts retained fixture without synthetic cleanup failure`() {
        var fixtureDeleteAttempts = 0
        var sourceStagingDeleteAttempts = 0
        val cleanup = CleanupCoordinator(
            deleteFixture = {
                fixtureDeleteAttempts += 1
                true
            },
            deleteSourceStaging = {
                sourceStagingDeleteAttempts += 1
                true
            },
        ).finish(measurementFailed = true)
        val measurementFailure = IllegalStateException("measurement")
        val fixture = FinalizerFixture(cleanupReport = cleanup)

        val returned = fixture.finalizer().finalize(measurementFailure)

        assertSame(measurementFailure, returned)
        assertEquals(0, fixtureDeleteAttempts)
        assertEquals(1, sourceStagingDeleteAttempts)
        assertTrue(fixture.events.contains("terminal:run_incomplete:measurement_failure"))
        assertEquals(0, measurementFailure.suppressed.size)
        assertEquals("incomplete", fixture.manifest["final_status"])
    }

    @Test
    fun `successful finalization closes writers before terminal status and manifest`() {
        val fixture = FinalizerFixture()

        assertEquals(null, fixture.finalizer().finalize(measurementFailure = null))

        assertEquals(
            listOf(
                "status:cleanup",
                "close-observations",
                "close-status",
                "terminal:run_complete:none",
                "manifest:final_status=complete",
                "manifest-write",
            ),
            fixture.events,
        )
        assertEquals("complete", fixture.manifest["final_status"])
        assertEquals(1, fixture.observationCloseAttempts)
        assertEquals(1, fixture.statusCloseAttempts)
    }

    @Test
    fun `cleanup-only failure reports an incomplete run with cleanup reason`() {
        val cleanupFailure = IllegalStateException("fixture cleanup")
        val fixture = FinalizerFixture(
            cleanupReport = CleanupReport(CleanupState.DELETED, CleanupState.FAILED, listOf(cleanupFailure)),
        )

        val returned = fixture.finalizer().finalize(measurementFailure = null)

        assertSame(cleanupFailure, returned)
        assertTrue(fixture.events.contains("terminal:run_incomplete:cleanup_failure"))
        assertFalse(fixture.events.contains("terminal:run_complete:none"))
        assertEquals("incomplete", fixture.manifest["final_status"])
        assertEquals(1, fixture.observationCloseAttempts)
        assertEquals(1, fixture.statusCloseAttempts)
    }

    @Test
    fun `observation close failure still attempts status close and preserves close reason`() {
        val observationFailure = IllegalStateException("observation close")
        val fixture = FinalizerFixture(observationCloseFailure = observationFailure)

        val returned = fixture.finalizer().finalize(measurementFailure = null)

        assertSame(observationFailure, returned)
        assertEquals(1, fixture.observationCloseAttempts)
        assertEquals(1, fixture.statusCloseAttempts)
        assertTrue(fixture.events.contains("terminal:run_incomplete:close_failure"))
        assertEquals("incomplete", fixture.manifest["final_status"])
    }

    @Test
    fun `status close failure still attempts observation close and preserves close reason`() {
        val statusFailure = IllegalStateException("status close")
        val fixture = FinalizerFixture(statusCloseFailure = statusFailure)

        val returned = fixture.finalizer().finalize(measurementFailure = null)

        assertSame(statusFailure, returned)
        assertEquals(1, fixture.observationCloseAttempts)
        assertEquals(1, fixture.statusCloseAttempts)
        assertTrue(fixture.events.contains("terminal:run_incomplete:close_failure"))
        assertEquals("incomplete", fixture.manifest["final_status"])
    }

    @Test
    fun `cleanup reporting failure closes both writers and preserves reporting reason`() {
        val reportingFailure = IllegalStateException("cleanup report")
        val fixture = FinalizerFixture(statusRecordFailure = reportingFailure)

        val returned = fixture.finalizer().finalize(measurementFailure = null)

        assertSame(reportingFailure, returned)
        assertEquals(1, fixture.observationCloseAttempts)
        assertEquals(1, fixture.statusCloseAttempts)
        assertTrue(fixture.events.contains("terminal:run_incomplete:reporting_failure"))
        assertEquals("incomplete", fixture.manifest["final_status"])
    }

    @Test
    fun `measurement failure remains primary when cleanup and close also fail`() {
        val measurementFailure = IllegalStateException("measurement")
        val cleanupFailure = IllegalStateException("cleanup")
        val observationFailure = IllegalStateException("observation close")
        val fixture = FinalizerFixture(
            cleanupReport = CleanupReport(CleanupState.DELETED, CleanupState.FAILED, listOf(cleanupFailure)),
            observationCloseFailure = observationFailure,
        )

        val returned = fixture.finalizer().finalize(measurementFailure)

        assertSame(measurementFailure, returned)
        assertTrue(measurementFailure.suppressed.contains(cleanupFailure))
        assertTrue(measurementFailure.suppressed.contains(observationFailure))
        assertTrue(fixture.events.contains("terminal:run_incomplete:measurement_and_cleanup_failure"))
        assertEquals("incomplete", fixture.manifest["final_status"])
        assertEquals(1, fixture.observationCloseAttempts)
        assertEquals(1, fixture.statusCloseAttempts)
    }
}

private class ThrowingDriver(
    private val failure: SQLException? = null,
    private val returnedConnection: Connection? = null,
) : Driver {
    override fun connect(url: String?, info: Properties?): Connection? {
        if (!acceptsURL(url)) return null
        failure?.let { throw it }
        return returnedConnection
    }

    override fun acceptsURL(url: String?): Boolean = url == "jdbc:sqlite:unused"

    override fun getPropertyInfo(url: String?, info: Properties?): Array<DriverPropertyInfo> = emptyArray()

    override fun getMajorVersion(): Int = 1

    override fun getMinorVersion(): Int = 0

    override fun jdbcCompliant(): Boolean = false

    override fun getParentLogger(): Logger = Logger.getGlobal()
}

private class FinalizerFixture(
    private val cleanupReport: CleanupReport = CleanupReport(CleanupState.DELETED, CleanupState.DELETED, emptyList()),
    private val statusRecordFailure: Throwable? = null,
    private val observationCloseFailure: Throwable? = null,
    private val statusCloseFailure: Throwable? = null,
) {
    val events = mutableListOf<String>()
    val manifest = mutableMapOf<String, String>()
    var observationCloseAttempts = 0
    var statusCloseAttempts = 0

    fun finalizer(): ScreenFinalizer = ScreenFinalizer(
        cleanup = { cleanupReport },
        recordStatus = { event, _ ->
            events += "status:$event"
            if (event == "cleanup") statusRecordFailure?.let { throw it }
        },
        putManifest = { key, value ->
            manifest[key] = value
            if (key == "final_status") events += "manifest:$key=$value"
        },
        writeManifest = { events += "manifest-write" },
        closeObservations = {
            events += "close-observations"
            observationCloseAttempts += 1
            observationCloseFailure?.let { throw it }
        },
        closeStatus = {
            events += "close-status"
            statusCloseAttempts += 1
            statusCloseFailure?.let { throw it }
        },
        appendTerminalStatus = { event, fields ->
            events += "terminal:$event:${fields["reason"] ?: "none"}"
        },
    )
}
