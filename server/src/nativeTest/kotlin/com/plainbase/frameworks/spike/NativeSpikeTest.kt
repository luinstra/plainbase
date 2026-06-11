package com.plainbase.frameworks.spike

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs the full-stack dependency spike as part of the regular test suite,
 * so `./gradlew test` (JVM) and `nativeTest` (CI native gate) both enforce it.
 *
 * @Tag("native"): this is THE native gate's dependency proof (jgit, sqlite, flexmark, MCP, …).
 * Kept on kotlin.test so it runs identically on the JVM and inside the native image.
 */
@Tag("native")
class NativeSpikeTest {

    @Test
    fun `all spike checks pass`() {
        val results = NativeSpike.runAll()
        results.forEach { println("${if (it.passed) "PASS" else "FAIL"}  ${it.name}: ${it.detail}") }
        val failures = results.filterNot { it.passed }
        assertTrue(
            failures.isEmpty(),
            "Spike failures: ${failures.joinToString { "${it.name} (${it.detail})" }}",
        )
    }
}
