package com.plainbase.frameworks.git

import org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import org.junit.platform.launcher.listeners.TestExecutionSummary
import java.nio.file.Files
import java.nio.file.Path

private const val G3Z_SELECTED_METHOD =
    "com.plainbase.frameworks.git.GitExecutorZombieNativeTest#reparentedZombieCompletesInvocation"

/** Small JUnit API launcher for the Linux PID1 JVM gate; it deliberately has no ConsoleLauncher dependency. */
object G3zJvmLauncher {

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty()) { "G3zJvmLauncher accepts no arguments; its method selector is fixed" }
        val evidence =
            System.getProperty("plainbase.test.g3z.evidence")?.takeIf { it.isNotBlank() }?.let(Path::of)
                ?: error("plainbase.test.g3z.evidence is required")
        Files.createDirectories(evidence)

        val request = LauncherDiscoveryRequestBuilder.request()
            .selectors(selectMethod(G3Z_SELECTED_METHOD))
            .build()
        val listener = SummaryGeneratingListener()
        LauncherFactory.create().execute(request, listener)
        val summary = listener.summary
        val report = summaryReport(summary)
        Files.writeString(evidence.resolve("launcher-receipt.txt"), report)
        Files.writeString(evidence.resolve("TEST-com.plainbase.frameworks.git.GitExecutorZombieNativeTest.xml"), junitXml(summary))
        println(report)
        require(
            summary.testsFoundCount == 1L &&
                summary.testsStartedCount == 1L &&
                summary.testsSucceededCount == 1L &&
                summary.testsFailedCount == 0L &&
                summary.testsAbortedCount == 0L &&
                summary.testsSkippedCount == 0L &&
                summary.containersFoundCount > 0L &&
                summary.containersStartedCount == summary.containersFoundCount &&
                summary.containersSucceededCount == summary.containersFoundCount &&
                summary.containersAbortedCount == 0L &&
                summary.containersSkippedCount == 0L &&
                summary.containersFailedCount == 0L,
        ) { "G3z JVM selection/count contract failed: $report" }
    }
}

private fun summaryReport(summary: TestExecutionSummary): String = buildString {
    appendLine("method=com.plainbase.frameworks.git.GitExecutorZombieNativeTest.reparentedZombieCompletesInvocation")
    appendLine("runtime=${System.getProperty("org.graalvm.nativeimage.imagecode") ?: "jvm"}")
    appendLine("testsFound=${summary.testsFoundCount}")
    appendLine("testsStarted=${summary.testsStartedCount}")
    appendLine("testsSucceeded=${summary.testsSucceededCount}")
    appendLine("testsFailed=${summary.testsFailedCount}")
    appendLine("testsAborted=${summary.testsAbortedCount}")
    appendLine("testsSkipped=${summary.testsSkippedCount}")
    appendLine("containersFound=${summary.containersFoundCount}")
    appendLine("containersStarted=${summary.containersStartedCount}")
    appendLine("containersSucceeded=${summary.containersSucceededCount}")
    appendLine("containersAborted=${summary.containersAbortedCount}")
    appendLine("containersSkipped=${summary.containersSkippedCount}")
    appendLine("containersFailed=${summary.containersFailedCount}")
    summary.failures.forEachIndexed { index, failure ->
        appendLine("failure-$index=${failure.testIdentifier.displayName}: ${failure.exception}")
    }
}

private fun junitXml(summary: TestExecutionSummary): String {
    val failure = summary.failures.firstOrNull()?.let {
        "<failure message=\"${xmlEscape(it.exception.message ?: it.exception.toString())}\">" +
            xmlEscape(it.exception.stackTraceToString()) +
            "</failure>"
    } ?: ""
    val errorCount = summary.containersFailedCount
    return buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append(
            "<testsuite name=\"$G3Z_SELECTED_METHOD\" tests=\"${summary.testsFoundCount}\" " +
                "failures=\"${summary.testsFailedCount}\" errors=\"$errorCount\" " +
                "skipped=\"${summary.testsSkippedCount + summary.testsAbortedCount}\">",
        )
        append(
            "<testcase classname=\"com.plainbase.frameworks.git.GitExecutorZombieNativeTest\" " +
                "name=\"reparentedZombieCompletesInvocation()\">$failure</testcase>",
        )
        append("</testsuite>")
    }
}

private fun xmlEscape(value: String): String =
    value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
