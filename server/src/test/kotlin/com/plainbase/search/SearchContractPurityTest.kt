package com.plainbase.search

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines

/**
 * The S3 engine-blindness floor, checked structurally (the DomainPurityTest pattern): NOTHING in
 * this test package — the abstract [SearchProviderContract], its fixtures, the criterion-4
 * harness — may import an engine. Zero `frameworks.` (FTS5 or any successor), zero SQL, zero
 * driver. This is the standing readiness proof for a second engine (§Verification, master
 * criterion 5's deferred-but-provable mechanism): a future adapter subclasses the contract from
 * `frameworks/`, never the other way around.
 *
 * Source-scanning on purpose, like DomainPurityTest: the rule is about SOURCE dependencies, and a
 * classpath check cannot fail on an import the compiler optimized away.
 */
class SearchContractPurityTest : FunSpec({

    val forbidden = listOf(
        "com.plainbase.frameworks.", // every engine adapter lives here — Fts5SearchProvider included
        "java.sql.",
        "javax.sql.",
        "org.sqlite.",
        "app.cash.sqldelight.",
        "io.ktor.",
        "org.koin.",
    )

    val contractSources = contractSourceRoot()
    val files = Files.walk(contractSources).use { stream ->
        stream.filter { it.isRegularFile() && it.extension == "kt" }.toList()
    }

    test("the scan actually sees the contract package (anti-vacuous floor)") {
        files.size shouldBeGreaterThanOrEqual 4
        val names = files.map { it.name }.toSet()
        names.containsAll(setOf("SearchProviderContract.kt", "ContractFixtures.kt", "ReindexEquivalence.kt")).shouldBeTrue()
    }

    test("the abstract contract suite is engine-blind: no source in this package imports an engine, a framework, or SQL") {
        val violations = files.flatMap { file ->
            file.readLines()
                .filter { line -> line.startsWith("import ") && forbidden.any { line.removePrefix("import ").startsWith(it) } }
                .map { "${contractSources.relativize(file)}: $it" }
        }
        violations.shouldBeEmpty()
    }
})

/** Locates `server/src/test/kotlin/com/plainbase/search` by walking up from the test CWD (DomainPurityTest pattern). */
private fun contractSourceRoot(): Path {
    var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    while (dir != null) {
        for (candidate in listOf("src/test/kotlin/com/plainbase/search", "server/src/test/kotlin/com/plainbase/search")) {
            val resolved = dir.resolve(candidate)
            if (Files.isDirectory(resolved)) return resolved
        }
        dir = dir.parent
    }
    error("Could not locate the contract test sources from ${System.getProperty("user.dir")}")
}
