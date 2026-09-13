package com.plainbase

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.readText

/** Verifies shared content/index ownership and the serving projection. */
class RuntimeCompositionArchitectureTest : FunSpec({

    val mainRoot = mainSourceRoot()
    val callerPaths = listOf(
        "frameworks/cli/ReindexCommand.kt",
        "frameworks/cli/AdoptCommand.kt",
        "frameworks/cli/AdminCommand.kt",
        "frameworks/koin/RepositoryModule.kt",
    )
    val indexRuntimePath = "frameworks/runtime/IndexRuntimeFactory.kt"
    val restModulePath = "frameworks/koin/RestModule.kt"
    val groupPath = "frameworks/runtime/ContentRepositories.kt"
    val files = (callerPaths + groupPath + indexRuntimePath + restModulePath).associateWith { mainRoot.resolve(it) }
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
    val productionCode = Files.walk(mainRoot).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
            .toList()
            .associate { file ->
                mainRoot.relativize(file).toString().replace('\\', '/') to stripComments(file.readText())
            }
    }
    val indexBuilderConstructor = Regex("(?<![A-Za-z0-9_])IndexBuilder\\s*\\(")
    val servingRuntimeConstructor = Regex("\\bServingRuntime\\s*\\(")

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

    test("the shared index runtime owns the sole production IndexBuilder construction") {
        val constructorHomes = productionCode
            .filterKeys { it != "domain/service/IndexBuilder.kt" }
            .mapValues { (_, text) -> indexBuilderConstructor.findAll(text).count() }
            .filterValues { it != 0 }
        constructorHomes shouldBe mapOf(indexRuntimePath to 1)

        val observedCalls =
            Regex("IndexRuntimeFactory\\.observed\\s*\\(")
                .findAll(productionCode.getValue("frameworks/koin/IndexModule.kt"))
                .count()
        val offlineCalls =
            Regex("IndexRuntimeFactory\\.offlineReindex\\s*\\(")
                .findAll(productionCode.getValue("frameworks/cli/ReindexCommand.kt"))
                .count()
        observedCalls shouldBe 1
        offlineCalls shouldBe 1

        val factoryFile = files.getValue(indexRuntimePath)
        Files.isRegularFile(factoryFile) shouldBe true
        val factoryCode = productionCode.getValue(indexRuntimePath)
        factoryCode.isNotBlank() shouldBe true
        Regex("(?m)^\\s*import\\s+org\\.koin(?:\\.|$)").findAll(factoryCode).count() shouldBe 0
        val koinLookupPatterns = listOf(
            Regex("\\bget\\s*\\(\\s*[^)]*\\)"),
            Regex("\\bget\\s*<\\s*[^>]+\\s*>\\s*\\(\\s*[^)]*\\)"),
            Regex("\\bgetAll\\s*\\(\\s*[^)]*\\)"),
            Regex("\\bgetAll\\s*<\\s*[^>]+\\s*>\\s*\\(\\s*[^)]*\\)"),
            Regex("\\bgetOrNull\\s*\\(\\s*[^)]*\\)"),
            Regex("\\bgetOrNull\\s*<\\s*[^>]+\\s*>\\s*\\(\\s*[^)]*\\)"),
        )
        koinLookupPatterns.flatMap { pattern ->
            pattern.findAll(factoryCode).map { "${pattern.pattern}: ${it.value}" }.toList()
        }.shouldBeEmpty()
    }

    test("ServingRuntime has one declaration, one scoped construction, and a concrete projection") {
        val constructorHomes = productionCode
            .mapValues { (_, text) -> servingRuntimeConstructor.findAll(text).count() }
            .filterValues { it != 0 }
        constructorHomes shouldBe mapOf(indexRuntimePath to 1, restModulePath to 1)

        val servingTokenHomes = productionCode
            .filterValues { Regex("\\bServingRuntime\\b").containsMatchIn(it) }
            .keys
        servingTokenHomes shouldBe setOf(indexRuntimePath, restModulePath)
        productionCode.values.any { it.contains("single<ServingRuntime>") || it.contains("get<ServingRuntime>") } shouldBe false

        val restCode = productionCode.getValue(restModulePath)
        Regex("\\bbuildRouteContext\\s*\\(").findAll(restCode).count() shouldBe 1
        val projectionStart = "val context = routeContextBuilder"
        val projectionEnd = "afterRouteContextBuilt(context)"
        (restCode.split(projectionStart).size - 1) shouldBe 1
        (restCode.split(projectionEnd).size - 1) shouldBe 1
        val projectionStartIndex = restCode.indexOf(projectionStart)
        val projectionEndIndex = restCode.indexOf(projectionEnd)
        (projectionStartIndex < projectionEndIndex) shouldBe true
        val projection = restCode.substring(projectionStartIndex, projectionEndIndex)
        val normalizedProjection = projection.replace(Regex("\\s+"), " ")
        normalizedProjection shouldContain "val serving = ServingRuntime("
        listOf(
            "indexBuilder = serving.index.builder",
            "aliasRegistry = serving.index.aliasRegistry",
            "registry = serving.index.registry",
            "availability = serving.index.availability",
            "convergence = serving.index.convergence",
            "limbo = serving.index.limbo",
            "stores = serving.index.stores::get",
            "histories = serving.index.histories::get",
            "idProvider = serving.index.idProvider",
            "pageService = serving.pageService",
            "searchService = serving.searchService",
            "writePipeline = serving.writePipeline",
            "resolver = serving.resolver",
            "absence = serving.absence",
            "proposalService = serving.proposalService",
            "proposalLabeler = serving.proposalLabeler",
            "agentDirectCommitGlobs = serving.agentDirectCommitGlobs",
        ).forEach { normalizedProjection shouldContain it.replace(Regex("\\s+"), " ") }
    }
})
