package com.plainbase.acceptance

import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import org.junit.platform.launcher.listeners.TestExecutionSummary
import kotlin.reflect.KClass

/**
 * Suite-by-SELECTION: runs existing test classes in-process through the JUnit Platform launcher and
 * returns the execution summary. This is how the chunk-8 suites (`Phase1AcceptanceTest`,
 * `ForeverApiGoldenSuite`) include the chunk-2/3/6 tests without duplicating a single assertion —
 * the selected classes remain the one source of truth and ALSO keep running standalone.
 *
 * Chosen over a `@Suite` annotation class deliberately: the launcher summary lets each suite assert
 * a per-class executed-test FLOOR, so a selection that silently discovers nothing (the vacuous-green
 * failure mode) fails loudly instead of passing empty.
 */
object SelectedSuite {

    fun run(vararg specs: KClass<*>): TestExecutionSummary {
        val request = LauncherDiscoveryRequestBuilder.request()
            .selectors(specs.map { selectClass(it.java) })
            .build()
        val listener = SummaryGeneratingListener()
        LauncherFactory.create().execute(request, listener)
        return listener.summary
    }
}

/**
 * The suite gate on one selection run: zero failures AND at least [atLeastTests] tests actually
 * executed (the anti-vacuous-green floor — corpora are append-only, so floors only ever rise).
 */
fun TestExecutionSummary.shouldHavePassed(selection: String, atLeastTests: Long) {
    val failureReport = failures.joinToString("\n") { "  ${it.testIdentifier.displayName}: ${it.exception}" }
    withClue("$selection failed inside the suite:\n$failureReport") {
        totalFailureCount shouldBe 0L
    }
    withClue(
        "$selection executed $testsSucceededCount test(s) — below the floor of $atLeastTests; an empty selection must fail, never pass",
    ) {
        testsSucceededCount shouldBeGreaterThanOrEqual atLeastTests
    }
}
