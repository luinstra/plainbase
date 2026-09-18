package com.plainbase.frameworks.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

private const val LEDGER_RESOURCE = "server/src/test/resources/protocol-ownership.json"
private const val ORIGINAL_DTO_PACKAGE = "com.plainbase.frameworks.ktor.dto"
private const val ORIGINAL_ROUTE_PACKAGE = "com.plainbase.frameworks.ktor.routes"
private const val FINAL_PROTOCOL_PACKAGE = "com.plainbase.frameworks.protocol"

private val B2_PROTOCOL_EXPECTED_FILES = setOf(
    "ErrorDtos.kt",
    "WriteConflictReason.kt",
    "RestJson.kt",
    "RestDtos.kt",
    "ReadDtos.kt",
    "SearchDtos.kt",
    "ProposalDtos.kt",
)
private val B3_PROTOCOL_EXPECTED_FILES = setOf(
    "CanonicalIds.kt",
    "ProposeCommandParse.kt",
    "ProposeCommandParser.kt",
)
private val B1_PROTOCOL_SERIALIZATION_IMPORTS = setOf("kotlinx.serialization.Serializable")
private val B2_PROTOCOL_DOMAIN_IMPORTS = setOf(
    "com.plainbase.domain.page.Citation",
    "com.plainbase.domain.page.Frontmatter",
    "com.plainbase.domain.page.FrontmatterValue",
    "com.plainbase.domain.page.Heading",
    "com.plainbase.domain.page.IndexedPage",
    "com.plainbase.domain.search.Highlight",
    "com.plainbase.domain.service.BrokenLink",
    "com.plainbase.domain.service.LinkReport",
    "com.plainbase.domain.service.PagePayload",
    "com.plainbase.domain.service.ProposalSummaryView",
    "com.plainbase.domain.service.ProposalView",
    "com.plainbase.domain.service.SearchHitPayload",
    "com.plainbase.domain.service.SearchPayload",
    "com.plainbase.domain.repository.ProposalOperation",
    "com.plainbase.domain.repository.ProposalStatus",
)
private val B1_PROTOCOL_JDK_IMPORTS = emptySet<String>()
private val B2_PROTOCOL_SERIALIZATION_IMPORTS = B1_PROTOCOL_SERIALIZATION_IMPORTS + "kotlinx.serialization.SerialName"
private val B2_PROTOCOL_JSON_IMPORTS = setOf(
    "kotlinx.serialization.json.Json",
    "kotlinx.serialization.json.JsonArray",
    "kotlinx.serialization.json.JsonObject",
    "kotlinx.serialization.json.JsonPrimitive",
    "kotlinx.serialization.json.buildJsonObject",
    "kotlinx.serialization.json.put",
)
private val B2_PROTOCOL_KOTLIN_IMPORTS = setOf(
    "kotlin.String",
    "kotlin.Int",
    "kotlin.Long",
    "kotlin.Double",
    "kotlin.Boolean",
    "kotlin.collections.List",
    "kotlin.collections.Set",
    "kotlin.collections.setOf",
)
private val B3_PROTOCOL_DOMAIN_IMPORTS = setOf(
    "com.plainbase.domain.content.TreePath",
    "com.plainbase.domain.page.PageId",
    "com.plainbase.domain.root.RootName",
    "com.plainbase.domain.service.ProposeCommand",
)
private val B2_PROTOCOL_ALLOWED_IMPORTS =
    B2_PROTOCOL_SERIALIZATION_IMPORTS +
        B2_PROTOCOL_JSON_IMPORTS +
        B2_PROTOCOL_DOMAIN_IMPORTS +
        B1_PROTOCOL_JDK_IMPORTS +
        B2_PROTOCOL_KOTLIN_IMPORTS +
        B3_PROTOCOL_DOMAIN_IMPORTS
private val B1_PROTOCOL_BANNED_DEFAULT_NAMES = setOf(
    "ProcessBuilder",
    "Process",
    "ProcessHandle",
    "Runtime",
    "System",
    "Thread",
    "ThreadGroup",
    "Class",
    "ClassLoader",
    "print",
    "println",
    "readLine",
    "readln",
    "readlnOrNull",
)
private val MCP_SOURCE_FILES = listOf(
    "server/src/main/kotlin/com/plainbase/frameworks/mcp/McpAmbiguousDtos.kt",
    "server/src/main/kotlin/com/plainbase/frameworks/mcp/McpTools.kt",
    "server/src/main/kotlin/com/plainbase/frameworks/mcp/McpMount.kt",
    "server/src/main/kotlin/com/plainbase/frameworks/mcp/PlainbaseMcpServer.kt",
)

@Serializable
private data class OwnershipLedger(
    val schemaVersion: Int,
    val symbols: List<SymbolRow>,
    val seedImports: List<SeedImport>,
)

@Serializable
private data class SymbolRow(
    val memberId: String,
    val originalFqn: String,
    val declarationKind: String,
    val signature: String,
    val currentPath: String,
    val finalPath: String,
    val moveChunk: String,
    val status: String,
    val declarationHead: String,
)

@Serializable
private data class SeedImport(
    val consumerPath: String,
    val importedFqn: String,
    val alias: String?,
    val memberIds: List<String>,
    val exceptionId: String?,
)

private data class ImportRef(val consumerPath: String, val importedFqn: String, val alias: String?)
private data class SourceFile(val relativePath: String, val packageName: String, val maskedText: String)
private data class DeclarationOccurrence(val source: SourceFile)

private val strictJson = Json {
    ignoreUnknownKeys = false
    isLenient = false
    explicitNulls = true
}

/** The approved ownership ledger plus B1 final-home and neutral-dependency checks. */
class ProtocolOwnershipArchitectureTest : FunSpec({
    val repositoryRoot = repositoryRoot()
    val ledger by lazy { strictJson.decodeFromString<OwnershipLedger>(repositoryRoot.resolve(LEDGER_RESOURCE).readText()) }

    test("the ownership ledger has the frozen schema, source heads, IDs, and mandatory families") {
        validateSymbols(ledger.symbols, repositoryRoot)
        ledger.schemaVersion shouldBe 1
        ledger.symbols shouldNotBe emptyList<SymbolRow>()

        val mandatoryFamilies = setOf(
            "com.plainbase.frameworks.ktor.dto.ErrorCodes",
            "com.plainbase.frameworks.ktor.dto.ErrorBody",
            "com.plainbase.frameworks.ktor.dto.ErrorEnvelope",
            "com.plainbase.frameworks.ktor.dto.WriteConflictReason",
            "com.plainbase.frameworks.ktor.dto.RestJson",
            "com.plainbase.frameworks.ktor.dto.CitationDto",
            "com.plainbase.frameworks.ktor.dto.PageResponse",
            "com.plainbase.frameworks.ktor.dto.HeadingDto",
            "com.plainbase.frameworks.ktor.dto.ValidateLinksResponse",
            "com.plainbase.frameworks.ktor.dto.BrokenLinkDto",
            "com.plainbase.frameworks.ktor.dto.PageMetadataResponse",
            "com.plainbase.frameworks.ktor.dto.SearchResponse",
            "com.plainbase.frameworks.ktor.dto.SearchHitDto",
            "com.plainbase.frameworks.ktor.dto.HighlightDto",
            "com.plainbase.frameworks.ktor.dto.ProposeChangeRequest",
            "com.plainbase.frameworks.ktor.dto.ProposeChangeResponse",
            "com.plainbase.frameworks.ktor.dto.ChangeSummary",
            "com.plainbase.frameworks.ktor.dto.ListChangesResponse",
            "com.plainbase.frameworks.ktor.dto.ChangeDetail",
            "com.plainbase.frameworks.ktor.dto.ProposalStatusWire",
            "com.plainbase.frameworks.ktor.dto.ProposalOperationWire",
            "com.plainbase.frameworks.ktor.dto.McpAmbiguousCandidate",
            "com.plainbase.frameworks.ktor.dto.McpAmbiguousResponse",
            "com.plainbase.frameworks.ktor.routes.ProposeCommandParse",
            "com.plainbase.frameworks.ktor.routes.ProposeCommandParse.Ok",
            "com.plainbase.frameworks.ktor.routes.ProposeCommandParse.Invalid",
            "com.plainbase.frameworks.ktor.routes.parseProposeCommand",
            "com.plainbase.frameworks.ktor.routes.parseEditCommand",
            "com.plainbase.frameworks.ktor.routes.parseCreateCommand",
            "com.plainbase.frameworks.ktor.routes.CONTENT_HASH",
            "com.plainbase.frameworks.ktor.routes.isContentHash",
            "com.plainbase.frameworks.ktor.routes.CANONICAL_PAGE_ID",
            "com.plainbase.frameworks.ktor.routes.CANONICAL_PROPOSAL_ID",
        )
        ledger.symbols.map { it.originalFqn }.toSet() shouldContainAll mandatoryFamilies
    }

    test("the frozen ID digest and import seed match the current approved baseline") {
        val ids = ledger.symbols.map { it.memberId }
        ids.size shouldBe ids.toSet().size
        sha256(ids.sorted().joinToString("\n")) shouldBe EXPECTED_INVENTORY_ID_DIGEST
        validateSeedImports(ledger, repositoryRoot)
    }

    test("duplicate IDs, staged statuses, and invalid paths are rejected by the same validator") {
        val first = ledger.symbols.first()
        val duplicateId = shouldThrow<IllegalStateException> {
            validateSymbols(listOf(first, first), repositoryRoot)
        }
        duplicateId.message shouldBe "duplicate ownership memberId"

        val unknownStatus = shouldThrow<IllegalStateException> {
            validateSymbols(listOf(first.copy(status = "unknown")), repositoryRoot)
        }
        unknownStatus.message shouldBe "unknown status: unknown"

        val invalidFinalPath = shouldThrow<IllegalStateException> {
            validateSymbols(listOf(first.copy(finalPath = "../escape.kt")), repositoryRoot)
        }
        invalidFinalPath.message shouldBe "final path traverses: ../escape.kt"

        val mappedSeed = ledger.seedImports.first { it.memberIds.isNotEmpty() }
        val wrongMemberId = ledger.symbols.first { it.originalFqn != mappedSeed.importedFqn }.memberId
        val wrongMapping = shouldThrow<IllegalStateException> {
            validateSeedImports(
                ledger.copy(
                    seedImports = ledger.seedImports.map { seed ->
                        if (seed == mappedSeed) seed.copy(memberIds = listOf(wrongMemberId)) else seed
                    },
                ),
                repositoryRoot,
            )
        }
        wrongMapping.message.orEmpty() shouldStartWith "seed FQN mismatch:"

        val duplicateSeedMember = shouldThrow<IllegalStateException> {
            validateSeedImports(
                ledger.copy(
                    seedImports = ledger.seedImports.map { seed ->
                        if (seed == mappedSeed) seed.copy(memberIds = seed.memberIds + seed.memberIds.first()) else seed
                    },
                ),
                repositoryRoot,
            )
        }
        duplicateSeedMember.message shouldBe "duplicate mapped seed memberId"
    }

    test("staged rows reject rollback and premature move statuses before owner checks") {
        val b1 = ledger.symbols.first { it.moveChunk == "B1" }
        val b2 = ledger.symbols.first { it.moveChunk == "B2" }
        val b3 = ledger.symbols.first { it.moveChunk == "B3" }

        val b1Status = shouldThrow<IllegalStateException> {
            validateSymbols(ledger.symbols.map { if (it == b1) it.copy(status = "planned") else it }, repositoryRoot)
        }
        b1Status.message shouldBe "unexpected status for B1: expected moved, got planned"

        val b2Status = shouldThrow<IllegalStateException> {
            validateSymbols(ledger.symbols.map { if (it == b2) it.copy(status = "planned") else it }, repositoryRoot)
        }
        b2Status.message shouldBe "unexpected status for B2: expected moved, got planned"

        val b3Status = shouldThrow<IllegalStateException> {
            validateSymbols(ledger.symbols.map { if (it == b3) it.copy(status = "planned") else it }, repositoryRoot)
        }
        b3Status.message shouldBe "unexpected status for B3: expected moved, got planned"

        val oldCurrentPath = "server/src/main/kotlin/com/plainbase/frameworks/ktor/dto/RestDtos.kt"
        val movedPath = shouldThrow<IllegalStateException> {
            validateSymbols(
                ledger.symbols.map { if (it == b1) it.copy(currentPath = oldCurrentPath) else it },
                repositoryRoot,
            )
        }
        movedPath.message shouldBe "moved currentPath differs from finalPath: " + b1.memberId
    }

    test("protocol sources stay neutral and MCP sources contain no old moved FQNs") {
        validateProtocolSources(ledger, repositoryRoot)

        val banned = ledger.symbols.filter { it.status == "moved" }.map { it.originalFqn }.toSet() + ORIGINAL_DTO_PACKAGE
        val violations = MCP_SOURCE_FILES.flatMap { relativePath ->
            forbiddenMovedFqns(repositoryRoot.resolve(relativePath).readText(), banned)
                .map { relativePath + ": forbidden old FQN " + it }
        }
        violations.shouldBeEmpty()
    }

    test("old moved-FQN scan distinguishes live code from strings and new protocol imports") {
        val oldErrorCodes = ledger.symbols.first { it.originalFqn.endsWith(".ErrorCodes") }.originalFqn
        forbiddenMovedFqns("import " + oldErrorCodes + "\n", setOf(oldErrorCodes)) shouldBe setOf(oldErrorCodes)
        forbiddenMovedFqns("val code = " + oldErrorCodes + ".INTERNAL_ERROR\n", setOf(oldErrorCodes)) shouldBe setOf(oldErrorCodes)
        forbiddenMovedFqns("val text = \"" + oldErrorCodes + "\"\n", setOf(oldErrorCodes)) shouldBe emptySet()
        forbiddenMovedFqns(
            "import com.plainbase.frameworks.protocol.ErrorCodes\n",
            setOf(oldErrorCodes),
        ) shouldBe emptySet()
    }

    test("function ownership identity ignores a changed return type") {
        val headingMapper = ledger.symbols.first {
            it.memberId.startsWith("com.plainbase.frameworks.ktor.dto.toDto#function:receiver(com.plainbase.domain.page.Heading)")
        }
        val changedReturn = """
            package com.plainbase.frameworks.ktor.dto

            import com.plainbase.domain.page.Heading

            fun Heading.toDto(): Any = error("return-type duplicate probe")
        """.trimIndent()

        topLevelDeclarations(changedReturn, headingMapper, ledger.symbols).size shouldBe 1
    }

    test("nested ownership stays within its direct owner and preserves moved FQNs") {
        val owner = ledger.symbols.single { it.originalFqn.endsWith(".ProposeCommandParse") }
        val ok = ledger.symbols.single { it.originalFqn.endsWith(".ProposeCommandParse.Ok") }
        val invalid = ledger.symbols.single { it.originalFqn.endsWith(".ProposeCommandParse.Invalid") }
        val source = """
            package com.plainbase.frameworks.protocol

            sealed interface ProposeCommandParse {
                data class Ok : ProposeCommandParse
                data class Invalid : ProposeCommandParse
            }

            sealed interface OtherOwner {
                data class Ok : OtherOwner
                data class Invalid : OtherOwner
            }

            data class Ok(val value: String)
            data class Invalid(val value: String)
        """.trimIndent()

        topLevelDeclarations(source, owner, ledger.symbols).size shouldBe 1
        typeDeclarations(source, ok).size shouldBe 1
        typeDeclarations(source, invalid).size shouldBe 1
        currentFqn(ok) shouldBe "com.plainbase.frameworks.protocol.ProposeCommandParse.Ok"
        currentFqn(invalid) shouldBe "com.plainbase.frameworks.protocol.ProposeCommandParse.Invalid"

        val withoutTargetChildren = """
            package com.plainbase.frameworks.protocol

            sealed interface ProposeCommandParse {}

            sealed interface OtherOwner {
                data class Ok : OtherOwner
                data class Invalid : OtherOwner
            }

            data class Ok(val value: String)
            data class Invalid(val value: String)
        """.trimIndent()
        typeDeclarations(withoutTargetChildren, ok).size shouldBe 0
        typeDeclarations(withoutTargetChildren, invalid).size shouldBe 0
    }

    test("protocol guard masks literals and preserves template expressions") {
        val qualifiedReference = "com.plainbase.frameworks.ktor.RouteContext::class"
        val authorityReference = "ProcessBuilder(\"unused\")"
        val safeSources = listOf(
            "val literal = \"" + qualifiedReference + "\"",
            "val escaped = \"\\$" + qualifiedReference + "\"",
            "val rawEscaped = \"\"\"" + "\${'$'}" + qualifiedReference + "\"\"\"",
            "val authorityLiteral = \"ProcessBuilder\"",
        )
        safeSources.forEach { source -> validateProtocolSourceText(source, emptySet()) }

        val qualifiedTemplates = listOf(
            "val regular = \"${'$'}{" + qualifiedReference + "}\"",
            "val raw = \"\"\"${'$'}{" + qualifiedReference + "}\"\"\"",
            "val regularLiteralDollar = \"" + '$' + '$' + "{" + qualifiedReference + "}\"",
            "val rawLiteralDollar = \"\"\"" + '$' + '$' + "{" + qualifiedReference + "}\"\"\"",
            "val nestedRegular = \"${'$'}{\"quoted\" + " + qualifiedReference + "}\"",
            "val nestedRaw = \"\"\"${'$'}{\"quoted\" + " + qualifiedReference + "}\"\"\"",
        )
        qualifiedTemplates.forEach { source ->
            val failure = shouldThrow<IllegalStateException> {
                validateProtocolSourceText(source, emptySet())
            }
            failure.message shouldBe "forbidden protocol reference: com.plainbase.frameworks.ktor.RouteContext"
        }

        val authorityTemplates = listOf(
            "val regular = \"${'$'}{" + authorityReference + "}\"",
            "val raw = \"\"\"${'$'}{" + authorityReference + "}\"\"\"",
        )
        authorityTemplates.forEach { source ->
            val failure = shouldThrow<IllegalStateException> {
                validateProtocolSourceText(source, emptySet())
            }
            failure.message shouldBe "forbidden protocol default authority: ProcessBuilder"
        }
    }

    test("canonical IDs use one private Regex implementation") {
        val source = repositoryRoot.resolve(
            "server/src/main/kotlin/com/plainbase/frameworks/protocol/CanonicalIds.kt",
        ).readText()
        val masked = maskKotlinSource(source)
        Regex("(?m)^\\s*private\\s+val\\s+CANONICAL_UUID\\s*=\\s*Regex\\(").findAll(masked).count() shouldBe 1
        Regex("(?m)^\\s*internal\\s+val\\s+CANONICAL_PAGE_ID\\s*=\\s*CANONICAL_UUID").findAll(masked).count() shouldBe 1
        Regex("(?m)^\\s*internal\\s+val\\s+CANONICAL_PROPOSAL_ID\\s*=\\s*CANONICAL_UUID").findAll(masked).count() shouldBe 1
        (CANONICAL_PAGE_ID === CANONICAL_PROPOSAL_ID) shouldBe true
    }
})

private fun validateSymbols(rows: List<SymbolRow>, root: Path) {
    check(rows.isNotEmpty()) { "ownership ledger must not be empty" }
    val ids = rows.map { it.memberId }
    check(ids.size == ids.toSet().size) { "duplicate ownership memberId" }

    val allowedKinds = setOf("class", "interface", "object", "function", "property")
    val allowedChunks = setOf("B1", "B2", "B3")
    val allowedStatuses = setOf("planned", "moved")
    rows.forEach { row ->
        check(row.declarationKind in allowedKinds) { "unknown declaration kind: " + row.declarationKind }
        check(row.moveChunk in allowedChunks) { "unknown move chunk: " + row.moveChunk }
        check(row.status in allowedStatuses) { "unknown status: " + row.status }
        listOf(row.memberId, row.originalFqn, row.signature, row.currentPath, row.finalPath, row.declarationHead).forEach {
            check(it.isNotBlank()) { "ownership row contains an empty field" }
        }
        validateKotlinSourcePath(row.currentPath, "current")
        validateKotlinSourcePath(row.finalPath, "final")
        check(row.memberId == row.originalFqn + "#" + row.declarationKind + ":" + row.signature) {
            "memberId encoding drift: " + row.memberId
        }
        val expectedStatus = if (row.moveChunk in setOf("B1", "B2", "B3")) "moved" else "planned"
        check(row.status == expectedStatus) {
            "unexpected status for " + row.moveChunk + ": expected " + expectedStatus + ", got " + row.status
        }
    }

    val sourceFiles = productionSourceFiles(root)
    rows.forEach { row ->
        if (row.status == "moved") {
            check(row.currentPath == row.finalPath) {
                "moved currentPath differs from finalPath: " + row.memberId
            }
            validateMovedOwner(row, sourceFiles, rows)
        } else {
            val source = root.resolve(row.currentPath).normalize()
            check(source.startsWith(root) && source.isRegularFile()) { "missing current source: " + row.currentPath }
            check(normalize(maskKotlinSource(source.readText())).contains(normalize(maskKotlinSource(row.declarationHead)))) {
                "declaration head not found for " + row.memberId
            }
        }
    }
}

private fun validateMovedOwner(row: SymbolRow, sourceFiles: List<SourceFile>, rows: List<SymbolRow>) {
    val finalSource = sourceFiles.singleOrNull { it.relativePath == row.finalPath }
    check(finalSource != null && finalSource.maskedText.isNotBlank()) { "missing final source: " + row.finalPath }
    val expectedPackage = assignedPackage(row.finalPath)
    check(finalSource.packageName == expectedPackage) {
        "wrong final package for " + row.memberId + ": " + finalSource.packageName
    }
    check(normalize(finalSource.maskedText).contains(normalize(maskKotlinSource(row.declarationHead)))) {
        "declaration head not found for " + row.memberId
    }

    val originalPackage = originalPackage(row.originalFqn)
    val matches = sourceFiles
        .filter { it.packageName == originalPackage || it.packageName == expectedPackage }
        .flatMap { source ->
            topLevelDeclarations(source.maskedText, row, rows)
                .map { DeclarationOccurrence(source) }
        }
    check(matches.size == 1) { "expected one owner for " + row.memberId + ", found " + matches.size }
    check(matches.single().source.relativePath == row.finalPath) {
        "moved owner is not at finalPath: " + row.memberId
    }
}

private fun originalPackage(originalFqn: String): String {
    val packageName = when {
        originalFqn.startsWith(ORIGINAL_DTO_PACKAGE + ".") -> ORIGINAL_DTO_PACKAGE
        originalFqn.startsWith(ORIGINAL_ROUTE_PACKAGE + ".") -> ORIGINAL_ROUTE_PACKAGE
        else -> ""
    }
    check(packageName.isNotEmpty()) {
        "unsupported original package for " + originalFqn
    }
    return packageName
}

private fun assignedPackage(finalPath: String): String {
    val sourcePrefix = "server/src/main/kotlin/"
    val packagePath = finalPath.removePrefix(sourcePrefix).substringBeforeLast('/')
    return packagePath.replace('/', '.')
}

private fun validateKotlinSourcePath(path: String, label: String) {
    check(path.endsWith(".kt")) { label + " path is not Kotlin: " + path }
    check(!Path.of(path).isAbsolute()) { label + " path must be relative: " + path }
    check(!path.split('/').contains("..")) { label + " path traverses: " + path }
    check(path.startsWith("server/src/main/kotlin/")) { label + " path is outside main Kotlin: " + path }
}

private fun validateSeedImports(ledger: OwnershipLedger, root: Path) {
    val rowsById = ledger.symbols.associateBy { it.memberId }
    val seedKeys = ledger.seedImports.map { ImportRef(it.consumerPath, it.importedFqn, it.alias) }
    check(seedKeys.size == seedKeys.toSet().size) { "duplicate import seed" }
    ledger.seedImports.forEach { seed ->
        check(seed.consumerPath.startsWith("server/src/main/kotlin/")) { "seed consumer outside production Kotlin" }
        check(seed.importedFqn.isNotBlank()) { "seed import is empty" }
        check(seed.memberIds.size == seed.memberIds.toSet().size) { "duplicate mapped seed memberId" }
        seed.memberIds.forEach { memberId ->
            val row = rowsById[memberId]
            check(row != null) { "seed maps an unknown member: " + memberId }
            check(row.originalFqn == seed.importedFqn) {
                "seed FQN mismatch: " + memberId + " maps " + row.originalFqn + ", imported " + seed.importedFqn
            }
        }
        check(seed.memberIds.isNotEmpty()) { "seed import must map at least one ownership member: " + seed.importedFqn }
        check(seed.exceptionId == null) { "seed import exceptions are no longer permitted: " + seed.importedFqn }
    }

    val currentSeedKeys = ledger.seedImports.mapNotNull { seed ->
        val effectiveFqns = seed.memberIds.map { memberId ->
            currentFqn(requireNotNull(rowsById[memberId]))
        }.toSet()
        check(effectiveFqns.size <= 1) { "seed members disagree on current import home" }
        if (seed.memberIds.isNotEmpty() && seed.memberIds.all { memberId ->
                samePackageDisposition(seed, requireNotNull(rowsById[memberId]), root)
            }
        ) {
            null
        } else {
            ImportRef(seed.consumerPath, effectiveFqns.singleOrNull() ?: seed.importedFqn, seed.alias)
        }
    }
    check(currentSeedKeys.size == currentSeedKeys.toSet().size) { "duplicate current import seed" }

    val expectedImports = buildList {
        addAll(actualSeedImports(root, "server/src/main/kotlin/com/plainbase/frameworks/mcp/PlainbaseMcpServer.kt"))
        addAll(
            actualSeedImports(root, "server/src/main/kotlin/com/plainbase/frameworks/ktor/GuardedMutatingFacade.kt")
                .filter { it.importedFqn.endsWith("WriteConflictReason") },
        )
        addAll(
            actualSeedImports(root, "server/src/main/kotlin/com/plainbase/frameworks/ktor/GuardedProposalFacade.kt")
                .filter { it.importedFqn.endsWith("WriteConflictReason") },
        )
    }.toSet()
    currentSeedKeys.toSet() shouldBe expectedImports
}

private fun currentFqn(row: SymbolRow): String =
    if (row.status == "moved") {
        val originalPackage = originalPackage(row.originalFqn)
        assignedPackage(row.finalPath) + "." + row.originalFqn.removePrefix(originalPackage + ".")
    } else {
        row.originalFqn
    }

private fun samePackageDisposition(seed: SeedImport, row: SymbolRow, root: Path): Boolean {
    if (row.status != "moved" || assignedPackage(row.finalPath) != declaredPackage(root.resolve(seed.consumerPath).readText())) return false
    val consumer = maskKotlinSource(root.resolve(seed.consumerPath).readText())
        .lineSequence()
        .filterNot { it.trim().startsWith("import ") }
        .joinToString("\n")
    val finalSource = productionSourceFiles(root).singleOrNull { it.relativePath == row.finalPath } ?: return false
    return containsIdentifier(consumer, row.originalFqn.substringAfterLast('.')) &&
        topLevelDeclarations(finalSource.maskedText, row, listOf(row)).size == 1
}

private fun actualSeedImports(root: Path, relativePath: String): List<ImportRef> {
    val path = root.resolve(relativePath)
    return path.readText().lineSequence()
        .map(String::trim)
        .filter { line ->
            line.startsWith("import com.plainbase.frameworks.ktor.") ||
                line.startsWith("import com.plainbase.frameworks.protocol.")
        }
        .map { line ->
            val match = IMPORT_PATTERN.matchEntire(line) ?: error("unreadable import: " + line)
            ImportRef(relativePath, match.groupValues[1], match.groupValues[2].ifBlank { null })
        }
        .toList()
}

private fun validateProtocolSources(ledger: OwnershipLedger, root: Path) {
    val protocolRoot = root.resolve("server/src/main/kotlin/com/plainbase/frameworks/protocol")
    (B2_PROTOCOL_EXPECTED_FILES + B3_PROTOCOL_EXPECTED_FILES).forEach { name ->
        val file = protocolRoot.resolve(name)
        check(file.isRegularFile() && file.readText().isNotBlank()) { "missing or empty protocol owner: " + name }
    }

    val ownedFqns = ledger.symbols
        .filter { it.status == "moved" && assignedPackage(it.finalPath) == FINAL_PROTOCOL_PACKAGE }
        .map { currentFqn(it) }
        .toSet()
    val files = Files.walk(protocolRoot).use { stream ->
        stream.filter { it.isRegularFile() && it.extension == "kt" }.toList()
    }
    files.forEach { path ->
        validateProtocolSourceText(path.readText(), ownedFqns)
    }
}

private fun validateProtocolSourceText(source: String, ownedFqns: Set<String>) {
    val masked = maskKotlinSource(source)
    val imports = parseImports(masked)
    val forbiddenImport = imports.firstOrNull { it !in B2_PROTOCOL_ALLOWED_IMPORTS }
    check(forbiddenImport == null) { "forbidden protocol import: " + forbiddenImport }

    val forbiddenReference = QUALIFIED_REFERENCE_PATTERN.findAll(masked)
        .map { it.value }
        .firstOrNull { !isAllowedProtocolReference(it, ownedFqns) }
    check(forbiddenReference == null) { "forbidden protocol reference: " + forbiddenReference }

    val forbiddenDefault = B1_PROTOCOL_BANNED_DEFAULT_NAMES.firstOrNull { containsIdentifier(masked, it) }
    check(forbiddenDefault == null) { "forbidden protocol default authority: " + forbiddenDefault }
}

private fun parseImports(maskedSource: String): List<String> =
    maskedSource.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("import ") }
        .map { line -> IMPORT_PATTERN.matchEntire(line)?.groupValues?.get(1) ?: error("unreadable import: " + line) }
        .toList()

private fun isAllowedProtocolReference(reference: String, ownedFqns: Set<String>): Boolean =
    reference == FINAL_PROTOCOL_PACKAGE ||
        reference in B2_PROTOCOL_ALLOWED_IMPORTS ||
        ownedFqns.any { reference == it || reference.startsWith(it + ".") }

private fun forbiddenMovedFqns(source: String, bannedFqns: Set<String>): Set<String> {
    val masked = maskKotlinSource(source)
    return bannedFqns.filter { fqn ->
        Regex("(?<![A-Za-z0-9_])" + Regex.escape(fqn) + "(?![A-Za-z0-9_])").containsMatchIn(masked)
    }.toSet()
}

private fun productionSourceFiles(root: Path): List<SourceFile> {
    val sourceRoot = root.resolve("server/src/main/kotlin")
    return Files.walk(sourceRoot).use { stream ->
        stream
            .filter { it.isRegularFile() && it.extension == "kt" }
            .map { path ->
                val masked = maskKotlinSource(path.readText())
                SourceFile(root.relativize(path).slashPath(), declaredPackage(masked), masked)
            }
            .toList()
    }
}

private fun declaredPackage(maskedSource: String): String =
    PACKAGE_PATTERN.find(maskedSource)?.groupValues?.get(1).orEmpty()

private fun topLevelDeclarations(source: String, row: SymbolRow, rows: List<SymbolRow>): List<Int> = when (row.declarationKind) {
    "class", "interface", "object" ->
        typeDeclarations(source, row)
    "property" -> {
        TOP_LEVEL_PROPERTY_PATTERN.findAll(source)
            .filter { match ->
                    braceDepthAt(source, match.range.first) == 0 &&
                    match.groupValues[1] == row.originalFqn.substringAfterLast('.')
            }
            .map { it.range.first }
            .toList()
    }
    "function" -> {
        val signature = FUNCTION_SIGNATURE_PATTERN.matchEntire(row.signature)
            ?: error("unsupported moved function signature: " + row.memberId)
        val expectedReceiver = resolveRecordedType(signature.groupValues[1], rows)
        val expectedParams = signature.groupValues[2].splitParameterTypes().map { resolveRecordedType(it, rows) }
        TOP_LEVEL_FUNCTION_PATTERN.findAll(source)
            .filter { match ->
                val qualifiedName = match.groupValues[1]
                val receiver = if (qualifiedName.contains('.')) qualifiedName.substringBeforeLast('.') else "-"
                val name = qualifiedName.substringAfterLast('.')
                braceDepthAt(source, match.range.first) == 0 &&
                    name == row.originalFqn.substringAfterLast('.') &&
                    resolveSourceType(source, receiver) == expectedReceiver &&
                    match.groupValues[2].splitParameterTypes().map { resolveSourceType(source, it) } == expectedParams
            }
            .map { it.range.first }
            .toList()
    }
    else -> error("unsupported moved declaration kind: " + row.declarationKind)
}

private fun typeDeclarations(source: String, row: SymbolRow): List<Int> {
    val packageName = originalPackage(row.originalFqn)
    val relativeName = row.originalFqn.removePrefix(packageName + ".")
    val parts = relativeName.split('.')
    if (parts.size == 1) return topLevelDeclarations(source, row.declarationKind, parts.single())
    check(parts.size == 2) { "unsupported nested declaration: " + row.originalFqn }

    val ownerName = parts[0]
    val nestedName = parts[1]
    val ownerPattern = Regex(
        "(?<![A-Za-z0-9_])(?:class|interface|object)\\s+" + Regex.escape(ownerName) +
            "(?![A-Za-z0-9_])",
    )
    val nestedPattern = Regex(
        "(?<![A-Za-z0-9_])(?:class|interface|object)\\s+" + Regex.escape(nestedName) +
            "(?![A-Za-z0-9_])",
    )
    return ownerPattern.findAll(source)
        .filter { braceDepthAt(source, it.range.first) == 0 }
        .flatMap { owner ->
            val bodyStart = source.indexOf('{', owner.range.last + 1)
            if (bodyStart < 0) return@flatMap emptyList()
            val bodyEnd = matchingBrace(source, bodyStart) ?: return@flatMap emptyList()
            val body = source.substring(bodyStart + 1, bodyEnd)
            nestedPattern.findAll(body)
                .filter { braceDepthAt(body, it.range.first) == 0 }
                .map { it.range.first + bodyStart + 1 }
                .toList()
        }
        .toList()
}

private fun topLevelDeclarations(source: String, kind: String, name: String): List<Int> {
    val pattern = Regex(
        "(?<![A-Za-z0-9_])" + Regex.escape(kind) + "\\s+" + Regex.escape(name) +
            "(?![A-Za-z0-9_])",
    )
    return pattern.findAll(source)
        .filter { braceDepthAt(source, it.range.first) == 0 }
        .map { it.range.first }
        .toList()
}

private fun resolveRecordedType(type: String, rows: List<SymbolRow>): String =
    rows.firstOrNull { it.originalFqn == type && it.status == "moved" }?.let(::currentFqn) ?: type

private fun resolveSourceType(source: String, type: String): String {
    val name = type.trim().removeSuffix("?")
    if (name == "-") return "-"
    if (name == "Set<RootName>") return "kotlin.collections.Set<com.plainbase.domain.root.RootName>"
    if (name.startsWith("com.") || name.startsWith("kotlin.") || name.startsWith("kotlinx.")) return name
    val imported = source.lineSequence()
        .map(String::trim)
        .mapNotNull { line -> IMPORT_PATTERN.matchEntire(line)?.groupValues?.get(1) }
        .firstOrNull { it.substringAfterLast('.') == name }
    if (imported != null) return imported
    return when (name) {
        "String" -> "kotlin.String"
        "Int" -> "kotlin.Int"
        "Long" -> "kotlin.Long"
        "Double" -> "kotlin.Double"
        "Boolean" -> "kotlin.Boolean"
        "List" -> "kotlin.collections.List"
        "Set" -> "kotlin.collections.Set"
        else -> declaredPackage(source) + "." + name
    }
}

private fun String.splitParameterTypes(): List<String> =
    trim().takeUnless(String::isEmpty)?.split(',')?.map { it.substringAfterLast(':').trim() }.orEmpty()

private val TOP_LEVEL_PROPERTY_PATTERN = Regex(
    """(?m)^\s*(?:(?:public|private|protected|internal|const|lateinit|override|expect|actual)\s+)*val\s+([A-Za-z_][A-Za-z0-9_]*)(?:\s*:\s*[A-Za-z_][A-Za-z0-9_.<>?]*)?\s*(?:=|$)""",
)
private val TOP_LEVEL_FUNCTION_PATTERN = Regex(
    """(?m)^\s*(?:(?:public|private|protected|internal|inline|infix|operator|suspend|tailrec|expect|actual|override)\s+)*fun\s+([A-Za-z_][A-Za-z0-9_.]*)\s*\(([^)]*)\)\s*:\s*([A-Za-z_][A-Za-z0-9_.]*)""",
)
private val FUNCTION_SIGNATURE_PATTERN =
    Regex("receiver\\(([^)]*)\\);params\\(([^)]*)\\);returns\\(([^)]*)\\)")

private fun braceDepthAt(source: String, position: Int): Int =
    source.substring(0, position).count { it == '{' } - source.substring(0, position).count { it == '}' }

private fun matchingBrace(source: String, start: Int): Int? {
    var depth = 0
    for (index in start until source.length) {
        when (source[index]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return index
            }
        }
    }
    return null
}

internal fun maskKotlinSource(source: String): String {
    val chars = source.toCharArray()
    var index = 0
    while (index < chars.size) {
        index = when {
            source.startsWith("//", index) -> maskLineComment(source, chars, index)
            source.startsWith("/*", index) -> maskBlockComment(source, chars, index)
            source.startsWith("\"\"\"", index) -> maskQuotedLiteral(source, chars, index, "\"\"\"")
            source[index] == '"' -> maskQuotedLiteral(source, chars, index, "\"")
            source[index] == '\'' -> maskCharLiteral(source, chars, index)
            else -> index + 1
        }
    }
    return String(chars)
}

private fun maskLineComment(source: String, chars: CharArray, start: Int): Int {
    var index = start
    while (index < chars.size && source[index] != '\n') {
        blank(chars, index)
        index++
    }
    return index
}

private fun maskBlockComment(source: String, chars: CharArray, start: Int): Int {
    var index = start
    while (index < chars.size) {
        if (source.startsWith("*/", index)) {
            blank(chars, index)
            blank(chars, index + 1)
            return index + 2
        }
        blank(chars, index)
        index++
    }
    return index
}

private fun maskQuotedLiteral(source: String, chars: CharArray, start: Int, delimiter: String): Int {
    var index = start
    blankRange(chars, index, delimiter.length)
    index += delimiter.length
    while (index < chars.size) {
        when {
            source.startsWith(delimiter, index) -> {
                blankRange(chars, index, delimiter.length)
                return index + delimiter.length
            }

            delimiter == "\"" && source[index] == '\\' -> index = maskEscape(chars, index)
            source[index] == '$' && source.getOrNull(index + 1) == '{' -> {
                blank(chars, index)
                index = maskTemplateExpression(source, chars, index + 2)
            }

            source[index] == '$' && isIdentifierStart(source.getOrNull(index + 1)) -> {
                index = preserveSimpleTemplate(source, index)
            }

            else -> {
                blank(chars, index)
                index++
            }
        }
    }
    return index
}

private fun maskTemplateExpression(source: String, chars: CharArray, start: Int): Int {
    var index = start
    var depth = 1
    while (index < chars.size && depth > 0) {
        index = when {
            source.startsWith("//", index) -> maskLineComment(source, chars, index)
            source.startsWith("/*", index) -> maskBlockComment(source, chars, index)
            source.startsWith("\"\"\"", index) -> maskQuotedLiteral(source, chars, index, "\"\"\"")
            source[index] == '"' -> maskQuotedLiteral(source, chars, index, "\"")
            source[index] == '\'' -> maskCharLiteral(source, chars, index)
            source[index] == '{' -> {
                depth++
                index + 1
            }

            source[index] == '}' -> {
                depth--
                index + 1
            }

            else -> index + 1
        }
    }
    return index
}

private fun maskCharLiteral(source: String, chars: CharArray, start: Int): Int {
    var index = start
    blank(chars, index)
    index++
    while (index < chars.size) {
        val escaped = source[index] == '\\' && index + 1 < chars.size
        val closing = source[index] == '\''
        blank(chars, index)
        index++
        if (escaped) {
            blank(chars, index)
            index++
        } else if (closing) {
            break
        }
    }
    return index
}

private fun maskEscape(chars: CharArray, start: Int): Int {
    blank(chars, start)
    if (start + 1 < chars.size) blank(chars, start + 1)
    return start + 2
}

private fun preserveSimpleTemplate(source: String, start: Int): Int {
    var index = start + 1
    while (isIdentifierPart(source.getOrNull(index))) index++
    return index
}

private fun blankRange(chars: CharArray, start: Int, length: Int) {
    for (offset in 0 until length) blank(chars, start + offset)
}

private fun blank(chars: CharArray, index: Int) {
    if (index < chars.size && chars[index] != '\n' && chars[index] != '\r') chars[index] = ' '
}

private fun isIdentifierStart(char: Char?): Boolean = char == '_' || char?.isLetter() == true

private fun isIdentifierPart(source: Char?): Boolean = isIdentifierStart(source) || source?.isDigit() == true

private fun containsIdentifier(source: String, name: String): Boolean =
    Regex("(?<![A-Za-z0-9_])" + Regex.escape(name) + "(?![A-Za-z0-9_])").containsMatchIn(source)

private fun Path.slashPath(): String = toString().replace(java.io.File.separatorChar, '/')

private fun normalize(value: String): String = value.replace(Regex("\\s+"), " ").trim()

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray())
    .joinToString("") { "%02x".format(it) }

private const val EXPECTED_INVENTORY_ID_DIGEST = "4d2bbfa1b2c893138fe13f742f527e54a47550a4b30b0b0e2f004c99cbd5391b"

private fun repositoryRoot(): Path {
    var current: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
    while (current != null) {
        if (current.resolve("server/src/main/kotlin/com/plainbase").isDirectory()) return current
        if (current.resolve("src/main/kotlin/com/plainbase").isDirectory()) return current.parent ?: current
        current = current.parent
    }
    error("Could not locate the Plainbase repository root")
}

private val PACKAGE_PATTERN = Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)""")
private val IMPORT_PATTERN = Regex("^import\\s+([A-Za-z0-9_.]+)(?:\\s+as\\s+([A-Za-z0-9_]+))?$")
private val QUALIFIED_REFERENCE_PATTERN =
    Regex("""(?<![A-Za-z0-9_])(?:com|io|kotlinx|kotlin|java|javax|org)\.[A-Za-z_][A-Za-z0-9_.]*""")
