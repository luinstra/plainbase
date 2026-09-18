package com.plainbase.frameworks.git

import org.junit.platform.engine.TestSource
import org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import java.io.PrintWriter

private const val G3Z_SELECTED_METHOD =
    "com.plainbase.frameworks.git.GitExecutorZombieNativeTest#reparentedZombieCompletesInvocation"

/** Small JUnit API launcher for the Linux PID1 JVM gate; validates fixed selection and execution counts. */
object G3zJvmLauncher {

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty()) { "G3zJvmLauncher accepts no arguments; its method selector is fixed" }
        val summaryListener = SummaryGeneratingListener()
        val methodSources = mutableListOf<MethodSource>()
        val selectionListener = object : TestExecutionListener {
            override fun executionStarted(testIdentifier: TestIdentifier) {
                if (testIdentifier.isTest) {
                    val source: TestSource? = testIdentifier.source.orElse(null)
                    if (source is MethodSource) methodSources += source
                }
            }
        }
        val request = LauncherDiscoveryRequestBuilder.request()
            .selectors(selectMethod(G3Z_SELECTED_METHOD))
            .build()
        LauncherFactory.create().execute(request, summaryListener, selectionListener)
        val summary = summaryListener.summary
        summary.printTo(PrintWriter(System.out, true))
        summary.failures.forEach { failure ->
            failure.exception.printStackTrace(System.out)
        }
        require(
            methodSources.size == 1 &&
                methodSources.single().className == G3Z_SELECTED_METHOD.substringBefore('#') &&
                methodSources.single().methodName == G3Z_SELECTED_METHOD.substringAfter('#'),
        ) {
            "G3z JVM executed MethodSource does not match the fixed selector: $methodSources"
        }
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
        ) { "G3z JVM selection/count contract failed" }
    }
}
