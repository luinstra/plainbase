package com.plainbase.frameworks.ktor

import com.plainbase.domain.root.ReservedSegments
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.ServerTopLevel
import com.plainbase.frameworks.filesystem.Fixtures
import com.plainbase.frameworks.ktor.routes.FrontendBundle
import com.plainbase.frameworks.ktor.routes.SpaTopLevel
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.server.routing.ConstantParameterRouteSelector
import io.ktor.server.routing.HostRouteSelector
import io.ktor.server.routing.HttpAcceptRouteSelector
import io.ktor.server.routing.HttpHeaderRouteSelector
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.HttpMultiAcceptRouteSelector
import io.ktor.server.routing.LocalPortRouteSelector
import io.ktor.server.routing.OptionalParameterRouteSelector
import io.ktor.server.routing.ParameterRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentOptionalParameterRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.PathSegmentRegexRouteSelector
import io.ktor.server.routing.PathSegmentTailcardRouteSelector
import io.ktor.server.routing.PathSegmentWildcardRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.TrailingSlashRouteSelector
import io.ktor.server.routing.routingRoot
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reservation and integrity properties of the embedded frontend bundle and mounted route surface. The inventory
 * is READ from the served tree, never transcribed, so a bundle that grows is answered here rather than by a list
 * somebody has to remember to update. It reads the CLASSPATH copy rather than `frontend/dist`, because that is what
 * is actually served; `processResources` depends on `copyFrontend`, so it is staged before any test runs.
 *
 * The route rows cover the declared server and SPA mounts, plus the actual Ktor tree. The runtime parity row
 * walks builtin, proxy, and off auth-mode topologies so mode-conditional mounts cannot hide an unreserved segment.
 *
 * RESERVATION, at the top level. [ReservedSegments] claims the product owns the top-level URLs it serves,
 * and the bundle's classpath tree is exposed through explicit server routes, so a new directory there is a
 * live route from the moment it is staged. Candidates go through [RootName.of] and then the whole
 * [ReservedSegments.isReserved] predicate rather than [ReservedSegments.words]: an entry no root could be
 * NAMED after (`favicon.svg`, `_app`, a single character) is unshadowable by construction, while `v2` and
 * `pb-icons` ARE already reserved, by the version shape and the prefixes, which are not in the word list.
 * This row is legitimately empty if no entry parses as a name at all, and that is not a gap: an
 * unnameable bundle is one nothing can shadow.
 *
 * FLATNESS, under `assets/`. This is what `AssetRoute`'s bundle-wins comment and [RootUrlGrammarTest]'s
 * KDoc both rest on, and reservation is not a substitute for it: the bundle-wins check runs on the FULL
 * tail before the root split, so a `static/assets/img/x.png` would answer `/assets/img/x.png` from the
 * bundle even for a registered root named `img`, and reserving `img` would not change that. Both comments
 * hold only while Vite's output stays flat, which is exactly the kind of thing a dependency bump changes.
 *
 * Asserting flatness makes the assets level's reservation question moot rather than merely likely: a
 * dotted name cannot parse as a [RootName] at all. That is why there is no third row.
 */
class FrontendBundleTest : FunSpec({

    test("every top-level bundle entry that could name a root is a reserved segment") {
        withClue("these bundle entries are live top-level routes; ReservedSegments must already own their names") {
            bundleTopLevel().mapNotNull { RootName.of(it) }.filterNot(ReservedSegments::isReserved).map { it.value }.shouldBeEmpty()
        }
    }

    test("every live top-level route segment is reserved") {
        val routeSegments = (
            SpaTopLevel.segments +
                SpaTopLevel.parameterized.map { it.removePrefix("/").substringBefore("/") } +
                ServerTopLevel.segments
            ).toSet()
        val rootNames = routeSegments.mapNotNull { RootName.of(it) }
        val expectedNonSurvivors = setOf("p")
        withClue(
            "live route segments (${routeSegments.size}) produced ${rootNames.size} RootName survivors; " +
                "expected ${routeSegments.size - expectedNonSurvivors.size}, non-survivors=$expectedNonSurvivors",
        ) {
            routeSegments.filterNot { it in rootNames.map(RootName::value) }.toSet() shouldBe expectedNonSurvivors
            rootNames shouldHaveSize routeSegments.size - expectedNonSurvivors.size
        }
        val unreserved = rootNames.filterNot(ReservedSegments::isReserved).map { it.value }
        withClue("live route segments (${routeSegments.size}) must be reserved: $unreserved") {
            unreserved.shouldBeEmpty()
        }
    }

    test("every mounted constant top-level route segment matches the declared inventory in every auth mode") {
        val expected = (
            ServerTopLevel.segments +
                FrontendBundle.files +
                FrontendBundle.directories +
                FrontendBundle.ownedElsewhere.keys +
                SpaTopLevel.segments
            ).toSet()
        listOf(
            "builtin" to (true to false),
            "proxy" to (false to true),
            "off" to (false to false),
        ).forEach { (mode, auth) ->
            val segments = mountedTopLevelSegmentsForAuthMode(
                builtinAuthEnabled = auth.first,
                proxyAuthEnabled = auth.second,
            )
            val rootNames = segments.mapNotNull(RootName::of)
            val unreserved = rootNames.filterNot(ReservedSegments::isReserved).map { it.value }
            withClue(
                "runtime route-tree walk for $mode found ${segments.size} constant top-level segments; " +
                    "expected=${expected.size} values=$expected, RootName survivors=${rootNames.size}, " +
                    "unreserved=$unreserved, segments=$segments",
            ) {
                segments shouldBe expected
                unreserved.shouldBeEmpty()
            }
        }
    }

    test("the bundle's assets/ is FLAT: every entry is a dotted regular file, so none can name a root") {
        // Every entry, not a filtered subset - the filter is what made the reservation form of this row
        // vacuous, since `RootName.of` dropped all of today's hashed files before the check was reached.
        val offenders = bundleAssetsLevel().filterNot { (name, path) -> "." in name && Files.isRegularFile(path) }
        withClue("a nested or dot-free assets/ entry defeats the bundle-wins check on the full tail") {
            offenders.map { (name, _) -> name }.shouldBeEmpty()
        }
    }

    test("H2: the FrontendBundle ledger and the served tree name exactly the same top-level entries") {
        // DISTINCT from the reservation row above, and the difference IS the trap that makes one list get
        // written from the other: drop `favicon.svg` from `FrontendBundle.files` and the reservation row
        // stays GREEN, because `favicon.svg` is dotted, cannot parse as a RootName, and is correctly ABSENT
        // from the reserved set - while the favicon 404s in production. Only this equality observes that.
        //
        // `.toSet()` is LOAD-BEARING. `files + directories` is a `List<String>` and `bundleTopLevel()` is a
        // `Set<String>`; `AbstractList.equals` is false against any non-List, so written without it this
        // COMPILES (both are `Collection<String>`), is ALWAYS false, and every back-out below stops proving
        // anything. Confirm it passes on the untouched tree before trusting a single one of them.
        val ledgered = (FrontendBundle.files + FrontendBundle.directories + FrontendBundle.ownedElsewhere.keys).toSet()
        withClue("the ledger and the served bundle disagree; a route is missing or a ledger entry is stale") {
            ledgered shouldBe bundleTopLevel()
        }
    }

    test("every FrontendBundle.directories entry is FLAT: its route matches exactly ONE path segment") {
        // `get("/{dir}/{file}")` matches one segment, so a nested `/fonts/sub/x.woff2` falls past it to the
        // root wildcard and 404s in production while H2 above stays green, because the NAME `fonts` is still
        // ledgered and still on the tree. This half has NO source back-out: its RED is environmental,
        // arriving the day somebody adds `frontend/public/fonts/sub/`. Section 8 registers it as ungated
        // rather than dressing it as a proven falsifier.
        FrontendBundle.directories.forEach { dir ->
            val nested = Files.list(bundleDir().resolve(dir)).use { stream ->
                stream.filter { !Files.isRegularFile(it) }.map { it.fileName.toString() }.toList()
            }
            withClue("$dir/ holds a subdirectory, which the one-segment route cannot reach") {
                nested.shouldBeEmpty()
            }
        }
    }
})

/**
 * The served bundle's directory on the test classpath, guarded so both rows cannot inventory the WRONG
 * tree. `getResource` returns the first `static/` on the classpath and would silently hand back a
 * dependency's, or a stray one left in a build directory - a real accident, hit while developing this
 * file: a `mkdir` under `build/resources/test/static` shadowed the real bundle and both rows then ran
 * against an empty decoy. Enumerating ALL of them and demanding exactly one closes that; the `index.html`
 * check then catches an unstaged or half-copied bundle.
 */
internal fun bundleDir(): Path {
    val found = FrontendBundleTest::class.java.classLoader.getResources("static").toList()
    check(found.size == 1) {
        "expected exactly one static/ on the test classpath, found ${found.size}: $found - " +
            "a second one shadows the real bundle and would silently be inventoried in its place"
    }
    val dir = Path.of(found.single().toURI()) // a directory classpath under test; a JAR URI would throw here, loudly
    check(Files.isRegularFile(dir.resolve("index.html"))) {
        "$dir holds no index.html - the bundle is unstaged or stale (run :frontend:npmBuild)"
    }
    return dir
}

/** The bundle's top-level entry names, exposed directly under the server's root URL surface. */
internal fun bundleTopLevel(): Set<String> =
    Files.list(bundleDir()).use { stream -> stream.map { it.fileName.toString() }.toList() }.toSet()

/** The entries under `static/assets/`, where Vite emits its hashed js/css, as name-to-path pairs. */
private fun bundleAssetsLevel(): List<Pair<String, Path>> {
    val assets = bundleDir().resolve("assets")
    check(Files.isDirectory(assets)) { "static/assets/ is missing - run :frontend:npmBuild" }
    val entries = Files.list(assets).use { stream -> stream.map { it.fileName.toString() to it }.toList() }
    check(entries.isNotEmpty()) { "static/assets/ is empty - the Vite bundle did not stage" }
    return entries
}

private fun mountedTopLevelSegments(root: RoutingNode): Set<String> {
    val segments = linkedSetOf<String>()

    fun visit(node: RoutingNode) {
        when (val selector = node.selector) {
            is PathSegmentConstantRouteSelector -> segments += selector.value
            is PathSegmentParameterRouteSelector ->
                if (selector.prefix.isNullOrEmpty() && selector.suffix.isNullOrEmpty()) {
                    return
                } else {
                    error("top-level path parameter selector encodes a constant: $selector")
                }

            is PathSegmentOptionalParameterRouteSelector ->
                if (selector.prefix.isNullOrEmpty() && selector.suffix.isNullOrEmpty()) {
                    return
                } else {
                    error("top-level optional path parameter selector encodes a constant: $selector")
                }

            is PathSegmentRegexRouteSelector ->
                error("top-level path regex selector encodes a constant: $selector")

            is PathSegmentTailcardRouteSelector ->
                if (selector.prefix.isEmpty()) {
                    return
                } else {
                    error("top-level path tailcard selector encodes a constant: $selector")
                }

            is PathSegmentWildcardRouteSelector -> return

            is ConstantParameterRouteSelector,
            is ParameterRouteSelector,
            is OptionalParameterRouteSelector,
            is HttpMethodRouteSelector,
            is HttpHeaderRouteSelector,
            is HttpAcceptRouteSelector,
            is HttpMultiAcceptRouteSelector,
            is HostRouteSelector,
            is LocalPortRouteSelector,
            TrailingSlashRouteSelector,
            -> node.children.forEach(::visit)

            else -> {
                if (selector.javaClass.name == CONTENT_TYPE_SELECTOR_CLASS) {
                    node.children.forEach(::visit)
                } else {
                    error("unrecognized Ktor route selector ${selector.javaClass.name}: $selector")
                }
            }
        }
    }

    root.children.forEach(::visit)
    return segments
}

private const val CONTENT_TYPE_SELECTOR_CLASS = "io.ktor.server.routing.ContentTypeHeaderRouteSelector"

private fun mountedTopLevelSegmentsForAuthMode(
    builtinAuthEnabled: Boolean,
    proxyAuthEnabled: Boolean,
): Set<String> {
    var segments = emptySet<String>()
    RestHarness(
        Fixtures.demoDocs,
        builtinAuthEnabled = builtinAuthEnabled,
        proxyAuthEnabled = proxyAuthEnabled,
        proxySecret = if (proxyAuthEnabled) "test-secret" else null,
    ).use { harness ->
        testApplication {
            application { plainbaseModule(harness.services) }
            startApplication()
            segments = mountedTopLevelSegments(application.routingRoot)
        }
    }
    return segments
}
