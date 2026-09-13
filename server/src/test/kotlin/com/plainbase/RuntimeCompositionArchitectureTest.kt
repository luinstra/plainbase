package com.plainbase

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.readText

/** Keeps the six content adapters owned by the shared repository group rather than individual CLI callers. */
class RuntimeCompositionArchitectureTest : FunSpec({

    val mainRoot = mainSourceRoot()
    val callerPaths = listOf(
        "frameworks/cli/ReindexCommand.kt",
        "frameworks/cli/AdoptCommand.kt",
        "frameworks/cli/AdminCommand.kt",
        "frameworks/koin/RepositoryModule.kt",
    )
    val groupPath = "frameworks/runtime/ContentRepositories.kt"
    val files = (callerPaths + groupPath).associateWith { mainRoot.resolve(it) }
    val concreteAdapters = listOf(
        "SqlDelightIdMapRepository",
        "SqlDelightUrlAliasRepository",
        "SqlDelightPageCheckpointRepository",
        "SqlDelightDirtyPageRepository",
        "SqlDelightRetirementRepository",
        "SqlDelightRootTopologyRepository",
    )
    val constructorPattern = { name: String ->
        Regex("(?<![A-Za-z0-9_])${Regex.escape(name)}\\s*\\(")
    }
    val source = { relative: String ->
        files.getValue(relative).takeIf { Files.isRegularFile(it) }?.readText().orEmpty()
    }
    val code = { relative: String -> stripComments(source(relative)) }

    test("the shared group and every caller are present with one non-vacuous construction inventory") {
        val invalid = files.filterValues { !Files.isRegularFile(it) || it.readText().isBlank() }.keys
        invalid.shouldBeEmpty()

        val groupCode = code(groupPath)
        val adapterCounts = concreteAdapters.associateWith { constructorPattern(it).findAll(groupCode).count() }
        withClue("ContentRepositories.kt must own exactly one constructor for each concrete adapter") {
            adapterCounts.values.sum() shouldBe concreteAdapters.size
            adapterCounts.values.forEach { it shouldBe 1 }
        }

        callerPaths.forEach { relative ->
            withClue("$relative must construct exactly one shared group") {
                constructorPattern("ContentRepositories").findAll(code(relative)).count() shouldBe 1
            }
        }
    }

    test("CLI callers do not construct concrete content adapters directly") {
        val violations = callerPaths.flatMap { relative ->
            concreteAdapters.flatMap { adapter ->
                constructorPattern(adapter).findAll(code(relative)).map {
                    "$relative: direct construction of $adapter belongs in ContentRepositories.kt"
                }
            }
        }
        violations.shouldBeEmpty()
    }
})
