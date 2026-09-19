package com.plainbase.domain.root

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * A spelling and field-list tripwire around the inferred-proof mint boundary. It is a fence, not a proof: it catches
 * the enumerated source opt-in, propagating marker, fully-qualified reference, Gradle flag, and suppression spellings,
 * plus instance fields on the pass and its Git projection. A suppression or compiler flag spelled outside the scanned
 * literals and files, a companion/delegated authority shape, and reflection remain review responsibilities. The
 * Compiler probe diagnostics observed on 2026-08-08:
 *  - Copy: `Cannot access 'fun copy(...)': it is private in 'com.plainbase.domain.root.AbsenceProof'`.
 *  - Opt-in: `Constructing an INFERRED absence proof outside the pass boundary re-opens the revoke-before-stamp bug class.`
 * The exact-four marker count also means the two authority files must never name `InferredProofMint` in prose, so a
 * future documentation edit fails for a readable reason.
 */
class InferredMintTripwireTest : FunSpec({

    val sourceRoot = mainKotlinRoot()
    val sourceFiles = kotlinFiles(sourceRoot)
    val absenceProofSource = sourceRoot.resolve("com/plainbase/domain/root/AbsenceProof.kt")
    val indexBuilderSource = sourceRoot.resolve("com/plainbase/domain/service/IndexBuilder.kt")

    test("the marker token occurs exactly four times in the two authority files") {
        val counts = sourceFiles.associateWith { path -> TOKEN.findAll(Files.readString(path)).count() }
            .filterValues { it > 0 }
        val rendered = counts.entries.joinToString("\n") { (path, count) -> "${sourceRoot.relativize(path)}=$count" }
        withClue("marker occurrences by file:\n$rendered") {
            counts.values.sum() shouldBe 4
            counts.mapKeys { (path, _) -> sourceRoot.relativize(path).toString() } shouldBe mapOf(
                "com/plainbase/domain/root/AbsenceProof.kt" to 2,
                "com/plainbase/domain/service/IndexBuilder.kt" to 2,
            )
        }
    }

    test("no Gradle compiler option grants the inferred mint marker") {
        val offenders = gradleConfigurationFiles(repoRoot()).filter { TOKEN.containsMatchIn(Files.readString(it)) }
        withClue("Gradle files naming the marker: ${offenders.joinToString()}") {
            offenders.shouldBeEmpty()
        }
    }

    test("the inferred factory directly declares the marker") {
        val source = Files.readString(absenceProofSource)
        withClue("the marker must directly precede fun inferred(") {
            Regex("""@InferredProofMint\s+fun inferred\(""").containsMatchIn(source) shouldBe true
        }
    }

    test("the marker annotation declaration exists") {
        Files.readString(absenceProofSource) shouldContain "annotation class InferredProofMint"
    }

    test("the pass and Git projection field lists match the reviewed token-free surface") {
        val source = Files.readString(indexBuilderSource).normalizedWhitespace()
        val constructorGolden =
            """
            private class AbsencePass private constructor(
                private val proven: (RootName, ObjectManifest, Map<RootedPath, Witness>) -> Set<BindingRef>,
                private val gitCheckpoint: (RootName) -> String?,
                private val histories: Map<RootName, GitReads>,
                private val observationStamps: Map<RootName, ObservationId>,
                private val bindingEpochs: Map<RootName, BindingEpoch>,
                private val headsBefore: Map<RootName, String>,
                private val allDurable: () -> List<IdBinding>,
                private val eligible: (RootedPath) -> Boolean,
                private val onHiddenGitStall: (RootName, Set<RootedPath>) -> Unit,
            )
            """.normalizedWhitespace()
        assertSoftly {
            withClue("AbsencePass constructor layout drift OR new member") {
                source shouldContain constructorGolden
            }

            val passFields = Class.forName("com.plainbase.domain.service.IndexBuilder\$AbsencePass").instanceFieldNames()
            withClue("AbsencePass extra or missing fields: $passFields") {
                passFields shouldBe setOf(
                    "proven",
                    "gitCheckpoint",
                    "histories",
                    "observationStamps",
                    "bindingEpochs",
                    "headsBefore",
                    "allDurable",
                    "eligible",
                    "onHiddenGitStall",
                )
            }

            val gitFields = Class.forName("com.plainbase.domain.service.IndexBuilder\$AbsencePass\$GitReads").instanceFieldNames()
            withClue("GitReads extra or missing fields: $gitFields") {
                gitFields shouldBe setOf("currentHead", "isAncestor", "deletedIn")
            }
        }
    }

    test("the sole production opt-in sits directly on AbsencePass") {
        val source = Files.readString(indexBuilderSource)
        val adjacent = Regex("""@OptIn\(InferredProofMint::class\)\s+private class AbsencePass""")
        withClue("the sole opt-in must directly annotate private class AbsencePass") {
            adjacent.findAll(source).count() shouldBe 1
        }
    }

    test("production sources contain no opt-in diagnostic suppression literal") {
        val offenders = sourceFiles.filter { path ->
            val source = Files.readString(path)
            "OPT_IN_USAGE" in source || "OPT_IN_USAGE_ERROR" in source
        }
        withClue("production suppression literals found in: ${offenders.joinToString()}") {
            offenders.shouldBeEmpty()
        }
    }
})

private val TOKEN = Regex("""\bInferredProofMint\b""")

private fun String.normalizedWhitespace(): String = replace(Regex("""\s+"""), " ").trim()

private fun Class<*>.instanceFieldNames(): Set<String> = declaredFields
    .filterNot { field -> Modifier.isStatic(field.modifiers) || field.isSynthetic }
    .mapTo(mutableSetOf()) { it.name }

private val WALK_EXCLUDED_DIRECTORIES = setOf("node_modules", "build")

private fun Path.isWalkExcluded(): Boolean = any { segment ->
    val name = segment.toString()
    name.startsWith(".") || name in WALK_EXCLUDED_DIRECTORIES
}

private fun kotlinFiles(root: Path): List<Path> = Files.walk(root).use { paths ->
    paths.filter { path ->
        !path.isWalkExcluded() && Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt")
    }.sorted().toList()
}

private fun mainKotlinRoot(): Path {
    var directory: Path? = Paths.get("").toAbsolutePath()
    while (directory != null) {
        for (candidate in listOf("src/main/kotlin", "server/src/main/kotlin")) {
            val resolved = directory.resolve(candidate)
            if (Files.isDirectory(resolved)) return resolved
        }
        directory = directory.parent
    }
    error("src/main/kotlin not found while walking up from ${Paths.get("").toAbsolutePath()}")
}

private fun repoRoot(): Path {
    var directory: Path? = Paths.get("").toAbsolutePath()
    while (directory != null) {
        if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))) return directory
        directory = directory.parent
    }
    error("settings.gradle.kts not found while walking up from ${Paths.get("").toAbsolutePath()}")
}

private fun gradleConfigurationFiles(root: Path): List<Path> = Files.walk(root).use { paths ->
    paths.filter { path ->
        if (path.isWalkExcluded() || !Files.isRegularFile(path)) return@filter false
        val name = path.fileName.toString()
        name.endsWith(".gradle.kts") || name == "gradle.properties"
    }.sorted().toList()
}
