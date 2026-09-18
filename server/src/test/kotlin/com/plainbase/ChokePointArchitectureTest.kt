package com.plainbase

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

private val EXPECTED_FILES_BY_ROOT = mapOf(
    "frameworks/ktor/routes" to setOf(
        "AdminRoute.kt",
        "AdminTokenRoutes.kt",
        "AdminUserRoutes.kt",
        "ApiFallbackRoute.kt",
        "AssetRoute.kt",
        "AuthRoutes.kt",
        "BrowseRedirectRoute.kt",
        "CreateDocumentComposer.kt",
        "FrontendStaticRoute.kt",
        "HealthRoute.kt",
        "HistoryRoutes.kt",
        "PageCreateRoutes.kt",
        "PageRoutes.kt",
        "PageWriteRoutes.kt",
        "PermalinkRoute.kt",
        "PreviewRoute.kt",
        "ProposalRoutes.kt",
        "RootContentRoute.kt",
        "RouteBoundarySupport.kt",
        "RouteSupport.kt",
        "SearchRoute.kt",
        "SessionRoutes.kt",
        "SetupRoutes.kt",
        "SpaShellRoute.kt",
        "TreeRoute.kt",
    ),
    "frameworks/mcp" to setOf(
        "McpAmbiguousDtos.kt",
        "McpTools.kt",
        "McpMount.kt",
        "PlainbaseMcpServer.kt",
    ),
    "frameworks/protocol" to setOf(
        "ErrorDtos.kt",
        "ProposalDtos.kt",
        "ReadDtos.kt",
        "RestDtos.kt",
        "RestJson.kt",
        "SearchDtos.kt",
        "WriteConflictReason.kt",
        "CanonicalIds.kt",
        "ProposeCommandParse.kt",
        "ProposeCommandParser.kt",
    ),
)

/**
 * The A3 choke-point structural floor, extended to the registered route, MCP, and shared-protocol roots.
 *
 * Routes and MCP must not name raw mutators, facade implementations, or grant mints. The protocol root shares the
 * raw-authority ban so a neutral file cannot become a new choke-point escape hatch.
 */
class ChokePointArchitectureTest : FunSpec({

    val mainRoot = mainSourceRoot()
    val walkedRoots = routeMcpAndProtocolSourceRoots()
    val walked = walkedRoots.associateBy { mainRoot.relativize(it).slashPath() }

    val filesByRoot = walked.mapValues { (_, root) ->
        Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() && it.extension == "kt" }.toList()
        }
    }
    val files = filesByRoot.values.flatten()

    val forbiddenReferences = listOf(
        "WritePipeline",
        "ContentStore",
        "IndexBuilder",
        "ProposalRepository",
        "GuardedReadFacade",
        "GuardedMutatingFacade",
        "GuardedProposalFacade",
        "RestServices",
        "EditGrant(",
        "CreateGrant(",
        "ManageGrant(",
        "ApproveGrant(",
        "grantForTests",
        "createGrantForTests",
        "manageGrantForTests",
        "approveGrantForTests",
    )

    test("the scan sees every expected file in every registered root") {
        walkedRoots.size shouldBe walked.size
        walked.keys shouldBe EXPECTED_FILES_BY_ROOT.keys
        val missing = EXPECTED_FILES_BY_ROOT.flatMap { (rootKey, expectedNames) ->
            val actualNames = filesByRoot.getValue(rootKey).map { it.name }.toSet()
            (expectedNames - actualNames).map { rootKey + "/" + it }
        }
        missing.shouldBeEmpty()
    }

    test("no route-facing source references a raw mutator type, facade impl, or grant mint") {
        val violations = files.flatMap { file ->
            val text = file.readText()
            forbiddenReferences.filter { token -> referencesToken(text, token) }
                .map { mainRoot.relativize(file).slashPath() + ": forbidden reference to '" + it + "'" }
        }
        violations.shouldBeEmpty()
    }
})

/**
 * Whether text references [token] as code, excluding line and block comments. Identifier tokens use boundaries so
 * LocalContentStore does not match ContentStore; a suffix call marker remains a literal call-site check.
 */
internal fun referencesToken(text: String, token: String): Boolean {
    val code = stripComments(text)
    return if (token.endsWith("(")) {
        code.contains(token)
    } else {
        Regex("(?<![A-Za-z0-9_])" + Regex.escape(token) + "(?![A-Za-z0-9_])").containsMatchIn(code)
    }
}

/** Removes line and block comments so only code remains for this bounded architecture scan. */
internal fun stripComments(text: String): String {
    val noBlock = text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
    return noBlock.lineSequence().joinToString("\n") { it.substringBefore("//") }
}

/** Every root covered by the choke-point inventory: routes, MCP, and shared protocol. */
internal fun routeMcpAndProtocolSourceRoots(): List<Path> {
    val main = mainSourceRoot()
    return listOf(
        main.resolve("frameworks/ktor/routes"),
        main.resolve("frameworks/mcp"),
        main.resolve("frameworks/protocol"),
    )
}

/** Locates server/src/main/kotlin/com/plainbase by walking up from the test CWD. */
internal fun mainSourceRoot(): Path {
    var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    while (dir != null) {
        for (candidate in listOf("src/main/kotlin/com/plainbase", "server/src/main/kotlin/com/plainbase")) {
            val resolved = dir.resolve(candidate)
            if (Files.isDirectory(resolved)) return resolved
        }
        dir = dir.parent
    }
    error("Could not locate the Plainbase main source tree from " + System.getProperty("user.dir"))
}

private fun Path.slashPath(): String = toString().replace(java.io.File.separatorChar, '/')
