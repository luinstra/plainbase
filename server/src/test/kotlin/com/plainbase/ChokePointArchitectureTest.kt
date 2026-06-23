package com.plainbase

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The A3 choke-point structural floor (the synthesis's hand-rolled source-scan, NOT ArchUnit — no new dep),
 * extending [DomainPurityTest]'s idiom. Two guarantees a route-walk cannot make and the compiler cannot fully
 * make (the test-only mint factories are PUBLIC in src/main):
 *
 *  1. **Routes touch ONLY the guarded facades.** No file under `frameworks/ktor/routes/` (or a future
 *     `frameworks/mcp/`) references a raw mutator TYPE (`WritePipeline`/`ContentStore`/`IndexBuilder`) NOR a
 *     facade IMPL (`GuardedReadFacade`/`GuardedMutatingFacade`) NOR `RestServices`'s old bundle — only the
 *     `ReadFacade`/`MutatingFacade` interfaces via the `RouteContext`. This makes "check() precedes the call"
 *     MOOT: there is no raw mutator call site in a route to mis-order.
 *  2. **No route forges a grant.** No route constructs a grant (`EditGrant(`/`CreateGrant(`/`ManageGrant(`) NOR
 *     calls the `grantForTests*` factories. (The broader "no PRODUCTION mint outside PolicyService" scan is
 *     [GrantUnforgeabilityTest].)
 */
class ChokePointArchitectureTest : FunSpec({

    val routes = routesSourceRoot()
    val files = Files.walk(routes).use { stream ->
        stream.filter { it.isRegularFile() && it.extension == "kt" }.toList()
    }

    // Raw mutator TYPES + facade IMPLs a route must never name; plus the grant-forgery patterns.
    val forbiddenReferences = listOf(
        "WritePipeline",
        "ContentStore",
        "IndexBuilder",
        "GuardedReadFacade",
        "GuardedMutatingFacade",
        "RestServices",
        "EditGrant(",
        "CreateGrant(",
        "ManageGrant(",
        "grantForTests",
        "createGrantForTests",
        "manageGrantForTests",
    )

    test("the scan sees every registered route file (anti-vacuous floor)") {
        // The route source files registered in KtorServer.plainbaseModule (the route fns + RouteSupport glue);
        // A4a added AuthRoutes/SessionRoutes/SetupRoutes/AdminUserRoutes; A4b adds AdminTokenRoutes, so the floor rises.
        files.size shouldBeGreaterThanOrEqual 19
        val names = files.map { it.name }.toSet()
        names.containsAll(
            setOf(
                "PageRoutes.kt", "PageWriteRoutes.kt", "PageCreateRoutes.kt", "AdminRoute.kt",
                "AssetRoute.kt", "PermalinkRoute.kt", "BrowseRedirectRoute.kt", "AliasRoute.kt",
                "HistoryRoutes.kt", "SearchRoute.kt", "TreeRoute.kt", "PreviewRoute.kt",
                "AuthRoutes.kt", "SessionRoutes.kt", "SetupRoutes.kt", "AdminUserRoutes.kt",
                "AdminTokenRoutes.kt",
            ),
        ).shouldBeTrue()
    }

    test("no route references a raw mutator type, a facade impl, or a grant mint") {
        val violations = files.flatMap { file ->
            val text = file.readText()
            forbiddenReferences.filter { token -> referencesToken(text, token) }
                .map { "${routes.relativize(file)}: forbidden reference to '$it'" }
        }
        violations.shouldBeEmpty()
    }
})

/**
 * Whether [text] REFERENCES [token] as code (not merely in a comment/KDoc). Comments are stripped first — line
 * (`//`) and block (`/* … */`, incl. single-line `/** … */`) — so a KDoc `[WritePipeline.write]` or a `mirrors
 * `LocalContentStore.…`` note is not a false positive. The token is matched at an identifier BOUNDARY so
 * `LocalContentStore` does NOT match `ContentStore` while `ContentStore.read` does (a `(` suffix token like
 * `EditGrant(` matches literally — it already ends at a non-identifier char).
 */
internal fun referencesToken(text: String, token: String): Boolean {
    val code = stripComments(text)
    return if (token.endsWith("(")) {
        code.contains(token)
    } else {
        // Identifier boundary: the char before must not be an identifier char (so `Local`+`ContentStore` fails).
        Regex("(?<![A-Za-z0-9_])" + Regex.escape(token) + "(?![A-Za-z0-9_])").containsMatchIn(code)
    }
}

/** Removes line (`//`) and block (`/* … */`) comments so only CODE remains (a tiny, sufficient stripper). */
private fun stripComments(text: String): String {
    val noBlock = text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
    return noBlock.lineSequence().joinToString("\n") { it.substringBefore("//") }
}

/** Locates `server/src/main/kotlin/com/plainbase/frameworks/ktor/routes` by walking up from the test CWD. */
internal fun routesSourceRoot(): Path = mainSourceRoot().resolve("frameworks/ktor/routes")

/** Locates `server/src/main/kotlin/com/plainbase` by walking up from the test CWD (the Fixtures pattern). */
internal fun mainSourceRoot(): Path {
    var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    while (dir != null) {
        for (candidate in listOf("src/main/kotlin/com/plainbase", "server/src/main/kotlin/com/plainbase")) {
            val resolved = dir.resolve(candidate)
            if (Files.isDirectory(resolved)) return resolved
        }
        dir = dir.parent
    }
    error("Could not locate the main source tree from ${System.getProperty("user.dir")}")
}
