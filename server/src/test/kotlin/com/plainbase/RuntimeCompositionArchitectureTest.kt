package com.plainbase

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
    val guardedFactoryPath = "frameworks/ktor/GuardedApplicationFactory.kt"
    val securityAssemblyPath = "frameworks/ktor/SecurityAssembly.kt"
    val routeContextPath = "frameworks/ktor/RouteContext.kt"
    val groupPath = "frameworks/runtime/ContentRepositories.kt"
    val files = (callerPaths + groupPath + indexRuntimePath + restModulePath + guardedFactoryPath + securityAssemblyPath + routeContextPath)
        .associateWith { mainRoot.resolve(it) }
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
        servingTokenHomes shouldBe setOf(indexRuntimePath, restModulePath, guardedFactoryPath)
        productionCode.values.any { it.contains("single<ServingRuntime>") || it.contains("get<ServingRuntime>") } shouldBe false

        val restCode = productionCode.getValue(restModulePath)
        Regex("\\bbuildRouteContext\\s*\\(").findAll(productionCode.values.joinToString("\n")).count() shouldBe 0
        val guardedFactoryCode = productionCode.getValue(guardedFactoryPath)
        Regex("\\bbuildGuardedApplication\\s*\\(").findAll(guardedFactoryCode).count() shouldBe 1
        Regex("\\bbuildGuardedApplication\\s*\\(").findAll(restCode).count() shouldBe 1
        productionCode
            .filterKeys { it != guardedFactoryPath && it != restModulePath }
            .values
            .sumOf { Regex("\\bbuildGuardedApplication\\s*\\(").findAll(it).count() } shouldBe 0
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
        Regex("\\bval serving = ServingRuntime\\s*\\(").findAll(projection).count() shouldBe 1
        Regex("\\bonServingRuntimeCollected\\s*\\(").findAll(projection).count() shouldBe 1
        val collectedIndex = normalizedProjection.indexOf("onServingRuntimeCollected(serving)")
        val securityIndex = normalizedProjection.indexOf("val security = securityAssembly(")
        val transportIndex = normalizedProjection.indexOf("val transport = transportSettings(config)")
        val guardedIndex = normalizedProjection.indexOf(
            "buildGuardedApplication( serving = serving, security = security, transport = transport",
        )
        (collectedIndex >= 0 && collectedIndex < securityIndex && securityIndex < transportIndex && transportIndex < guardedIndex) shouldBe
            true
        normalizedProjection shouldContain
            "securityAssembly( config = config, policy = get(), tokens = get(), auth = get(), proxyCsrf = get()"
        normalizedProjection shouldContain "transportSettings(config)"

        listOf(
            "val registry: RootRegistry = serving.index.registry",
            "val availability: RootAvailability = serving.index.availability",
            "val convergence: RootConvergence = serving.index.convergence",
            "val limbo: RootLimbo = serving.index.limbo",
            "val resolver: PageRootResolver = serving.resolver",
            "val absence: AbsenceClassifier = serving.absence",
            "val stores: (RootName) -> ContentStore = serving.index.stores::get",
            "val histories: (RootName) -> HistoryProvider = serving.index.histories::get",
            "val indexBuilder: IndexBuilder = serving.index.builder",
            "val pageService: PageService = serving.pageService",
            "val searchService: SearchService = serving.searchService",
            "val aliasRegistry: UrlAliasRegistry = serving.index.aliasRegistry",
            "val writePipeline: WritePipeline = serving.writePipeline",
            "val idProvider: IdProvider = serving.index.idProvider",
            "val proposalService: ProposalService = serving.proposalService",
            "val proposalLabeler: ProposalAuthorLabeler = serving.proposalLabeler",
            "val agentDirectCommitGlobs: List<CommitGlob> = serving.agentDirectCommitGlobs",
        ).forEach { guardedFactoryCode.replace(Regex("\\s+"), " ") shouldContain it.replace(Regex("\\s+"), " ") }

        val privateGroups = listOf("RootedContentInputs", "PublishedReadInputs", "MutationInputs", "SecurityInputs", "TransportInputs")
        privateGroups.forEach { group ->
            Regex("(?m)^private class $group\\b").findAll(guardedFactoryCode).count() shouldBe 1
            productionCode
                .filterKeys { it != guardedFactoryPath }
                .values
                .sumOf { Regex("\\b$group\\b").findAll(it).count() } shouldBe 0
        }

        val factorySignature = guardedFactoryCode.replace(Regex("\\s+"), " ")
        factorySignature shouldContain
            "internal fun buildGuardedApplication( serving: ServingRuntime, security: SecurityAssembly, transport: TransportSettings, ): RouteContext"
        factorySignature.substringBefore("): RouteContext") shouldNotContain "extract ="
        val securityCode = productionCode.getValue(securityAssemblyPath)
        val normalizedSecurity = securityCode.replace(Regex("\\s+"), " ")
        normalizedSecurity shouldContain
            "internal class SecurityAssembly( config: PlainbaseConfig, val policy: PolicyService, val tokens: ApiTokenService, " +
            "val auth: AuthServices, val proxyCsrf: ProxyCsrf, ) {"
        normalizedSecurity shouldContain
            "internal fun securityAssembly( config: PlainbaseConfig, policy: PolicyService, tokens: ApiTokenService, " +
            "auth: AuthServices, proxyCsrf: ProxyCsrf, ): SecurityAssembly = SecurityAssembly("
        val securityConstructorSignature = normalizedSecurity.substringAfter("internal class SecurityAssembly(").substringBefore(") {")
        val securityHelperSignature = normalizedSecurity
            .substringAfter("internal fun securityAssembly(")
            .substringBefore("): SecurityAssembly")
        listOf(
            "extract",
            "builtinAuthEnabled",
            "proxyAuthEnabled",
            "proxySecret",
            "proxyIdentityHeader",
            "trustedProxyCidrs",
        ).forEach { independentInput ->
            securityConstructorSignature shouldNotContain independentInput
            securityHelperSignature shouldNotContain independentInput
        }
        normalizedSecurity shouldContain "private val authConfig = config.auth"
        normalizedSecurity shouldContain "val extract: ApplicationCall.() -> PrincipalExtraction ="
        securityCode shouldNotContain "toString"

        val routeContextCode = productionCode.getValue(routeContextPath)
        val normalizedRouteContext = routeContextCode.replace(Regex("\\s+"), " ")
        normalizedRouteContext shouldContain "val limbo: RootLimbo,"
        normalizedRouteContext shouldContain "val extract: ApplicationCall.() -> PrincipalExtraction,"
        Regex("val limbo: RootLimbo\\s*=").findAll(routeContextCode).count() shouldBe 0
        Regex("val extract: ApplicationCall\\.\\(\\) -> PrincipalExtraction\\s*=").findAll(routeContextCode).count() shouldBe 0

        val routeContextConstructor = Regex("\\bRouteContext\\s*\\(")
        val routeContextHomes = productionCode
            .mapValues { (_, text) -> routeContextConstructor.findAll(text).count() }
            .filterValues { it != 0 }
        routeContextHomes shouldBe mapOf(routeContextPath to 1, guardedFactoryPath to 1)
        productionCode.values.sumOf { Regex("\\bfixedPrincipal\\s*\\(").findAll(it).count() } shouldBe 0
        productionCode.values.sumOf { Regex("\\bProxyCsrf\\s*\\(\\s*ByteArray\\s*\\(").findAll(it).count() } shouldBe 0
    }
})
