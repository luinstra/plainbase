package com.plainbase.frameworks.objectstore

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * [MirrorState]'s structural choke point (M1), checked the [com.plainbase.DomainPurityTest] way:
 * source-scanning, because the rule is about SOURCE seams, not runtime behavior a unit test could
 * accidentally miss. Two properties:
 *
 * 1. Exactly two seams mutate the in-memory entries map ([MirrorState.recordConfirmed] /
 *    [MirrorState.invalidate]) - a third map-write seam must be UNREPRESENTABLE, not merely absent
 *    today. `persist()` is a FILE operation only; it must never touch `entries[...] =` / `.remove(`.
 * 2. R9 no-eager-entropy: no `frameworks/objectstore/` main source constructs `SecureRandom` /
 *    `getInstanceStrong` - the hybrid never needs its own entropy source (etags are provider-opaque).
 */
class MirrorStateChokePointTest : FunSpec({

    val sourceRoot = objectstoreSourceRoot()
    val files = Files.walk(sourceRoot).use { stream ->
        stream.filter { it.isRegularFile() && it.extension == "kt" }.toList()
    }

    test("the scan actually sees the objectstore source tree (anti-vacuous floor)") {
        files.size shouldBeGreaterThan 5
        files.map { it.name }.contains("MirrorState.kt").shouldBeTrue()
    }

    test("MirrorState's entries map has exactly two write seams: recordConfirmed and invalidate") {
        val mirrorState = sourceRoot.resolve("MirrorState.kt")
        val text = mirrorState.readText()
        // Every direct mutation of `entries` (assignment or a mutating collection call) must appear
        // only inside recordConfirmed/invalidate - grep-verifiable, not merely true today.
        val mutationSites = MUTATION_PATTERN.findAll(text).map { it.range.first }.toList()
        val bodies = mapOf(
            "recordConfirmed" to methodBodyRange(text, "recordConfirmed"),
            "invalidate" to methodBodyRange(text, "invalidate"),
        )
        val outside = mutationSites.filterNot { offset -> bodies.values.any { offset in it } }
        outside.shouldBeEmpty()
    }

    test("persist() never mutates the entries map - a FILE operation only") {
        val text = sourceRoot.resolve("MirrorState.kt").readText()
        val range = methodBodyRange(text, "persist")
        val persistBody = text.substring(range.first, range.last)
        MUTATION_PATTERN.findAll(persistBody).toList().shouldBeEmpty()
    }

    test("R9: no frameworks/objectstore/ main source constructs its own entropy source (SecureRandom)") {
        val violations = files.flatMap { file ->
            file.readText().lineSequence()
                .filter { "SecureRandom" in it || "getInstanceStrong" in it }
                .map { "${sourceRoot.relativize(file)}: ${it.trim()}" }
        }
        violations.shouldBeEmpty()
    }
})

/** Direct mutation of the `entries` map: an assignment (`entries[...] =`) or a mutating call. */
private val MUTATION_PATTERN = Regex("""entries\[[^]]*]\s*=|entries\.(remove|clear|put|putAll)\(""")

/** The `{ ... }` byte range of a top-level method body named [name] in [source] (brace-balanced). */
private fun methodBodyRange(source: String, name: String): IntRange {
    val signature = Regex("""fun $name\(""")
    val match = requireNotNull(signature.find(source)) { "no 'fun $name(' found" }
    val open = source.indexOf('{', match.range.first)
    require(open >= 0) { "no body found for fun $name" }
    var depth = 0
    for (i in open until source.length) {
        when (source[i]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return (open + 1) until i
            }
        }
    }
    error("unbalanced braces scanning fun $name")
}

/** Locates `server/src/main/kotlin/com/plainbase/frameworks/objectstore` (the [com.plainbase.DomainPurityTest] pattern). */
private fun objectstoreSourceRoot(): Path {
    var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    while (dir != null) {
        for (candidate in listOf(
            "src/main/kotlin/com/plainbase/frameworks/objectstore",
            "server/src/main/kotlin/com/plainbase/frameworks/objectstore",
        )) {
            val resolved = dir.resolve(candidate)
            if (Files.isDirectory(resolved)) return resolved
        }
        dir = dir.parent
    }
    error("Could not locate the objectstore source tree from ${System.getProperty("user.dir")}")
}
