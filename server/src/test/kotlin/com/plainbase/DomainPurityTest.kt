package com.plainbase

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines

/**
 * The hexagonal floor, checked structurally (§5.8 / S1 acceptance): `domain/` imports NO framework
 * — no flexmark (the single-renderer rule; the `SectionSplitter` MUST stay a pure consumer of
 * snapshot data), no ktor, no SQL, no DI, no wire serializer — and never reaches into
 * `frameworks/`. The kotlin-logging facade and the JDK are the only sanctioned non-domain imports.
 *
 * Source-scanning on purpose: a bytecode/classpath check cannot fail on an import the compiler
 * optimized away, and the rule we enforce is about SOURCE dependencies.
 */
class DomainPurityTest : FunSpec({

    val forbidden = listOf(
        "com.vladsch.", // flexmark — renderer adapter only (§5.8 single-renderer rule)
        "io.ktor.",
        "app.cash.sqldelight.",
        "org.sqlite.",
        "org.eclipse.jgit.",
        "org.koin.",
        "org.bouncycastle.",
        "io.modelcontextprotocol.",
        "kotlinx.serialization.",
        "com.plainbase.frameworks.", // domain depends on nothing above it
    )

    val domainSources = domainSourceRoot()
    val files = Files.walk(domainSources).use { stream ->
        stream.filter { it.isRegularFile() && it.extension == "kt" }.toList()
    }

    test("the scan actually sees the domain tree (anti-vacuous floor, S1 files included)") {
        files.size shouldBeGreaterThan 20
        val names = files.map { it.name }.toSet()
        names.containsAll(
            setOf("SectionSplitter.kt", "SearchIndexer.kt", "SearchProvider.kt", "IndexBuilder.kt", "Principal.kt"),
        ).shouldBeTrue()
    }

    test("no domain source imports a framework") {
        val violations = files.flatMap { file ->
            file.readLines()
                .filter { line -> line.startsWith("import ") && forbidden.any { line.removePrefix("import ").startsWith(it) } }
                .map { "${domainSources.relativize(file)}: $it" }
        }
        violations.shouldBeEmpty()
    }
})

/** Locates `server/src/main/kotlin/com/plainbase/domain` by walking up from the test CWD (Fixtures pattern). */
private fun domainSourceRoot(): Path {
    var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    while (dir != null) {
        for (candidate in listOf("src/main/kotlin/com/plainbase/domain", "server/src/main/kotlin/com/plainbase/domain")) {
            val resolved = dir.resolve(candidate)
            if (Files.isDirectory(resolved)) return resolved
        }
        dir = dir.parent
    }
    error("Could not locate the domain source tree from ${System.getProperty("user.dir")}")
}
