package com.plainbase.frameworks.ktor

import com.plainbase.domain.root.ReservedSegments
import com.plainbase.domain.root.RootName
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.nio.file.Files
import java.nio.file.Path

/**
 * The falsifier under [ReservedSegments]'s claim about the embedded frontend bundle. Today that claim is a
 * sentence in a comment, and a sentence cannot notice `frontend/public/img/` being added: the bundle is served
 * wholesale from the classpath (`staticResources("/", "static")`), so a new directory is a live route the product
 * owns from the moment it is staged. The inventory is READ from the served tree, never transcribed, so a grown
 * bundle is answered by this test rather than by a list somebody has to remember to update. It reads the CLASSPATH
 * copy rather than `frontend/dist`, because the classpath is what is actually served; `processResources` depends
 * on `copyFrontend`, so it is staged before any test runs.
 *
 * Candidates go through [RootName.of] and are then checked against the whole [ReservedSegments.isReserved]
 * predicate rather than [ReservedSegments.words]. Both halves matter: an entry no root could be NAMED after
 * (`favicon.svg`, `_app`, a single character) is unshadowable by construction and is not a hazard, while `v2`
 * and `pb-icons` ARE already reserved - by the version shape and the prefixes, which are not in the word list.
 * A `words`-only membership test would false-fail on every one of them.
 *
 * The two rows guard different things. The ASSETS-level one is the hazard that is live today: `staticResourceBytes`
 * resolves `static/assets/$tail` and `AssetRoute` runs the `325d195` bundle-wins check on the FULL tail BEFORE the
 * root split, so a `static/assets/img/x.png` would serve bundle bytes at `/assets/img/x.png` even for a registered
 * root named `img`. Vite's output is flat today, so that is latent rather than live - which is exactly when a
 * falsifier is worth having. The TOP-LEVEL one is not a shadow at HEAD: a root name never occupies a bare segment,
 * it appears only after `/docs`, `/assets`, `/p` and `/browse`. It enforces [ReservedSegments]'s own policy that
 * Plainbase owns the top-level URLs it serves or expects to, and it is what would keep that policy honest if the
 * URL shape ever flattened to `/{root}/{path}`.
 */
class FrontendBundleTest : FunSpec({

    test("every top-level bundle entry that could name a root is a reserved segment") {
        withClue("these bundle entries are live top-level routes; ReservedSegments must already own their names") {
            unreservedNames(bundleTopLevel()).shouldBeEmpty()
        }
    }

    test("every entry under the bundle's assets/ that could name a root is a reserved segment") {
        withClue("these would shadow a same-named root's /assets/{root}/... space via the bundle-wins check") {
            unreservedNames(bundleAssetsLevel()).shouldBeEmpty()
        }
    }
})

/** The hazard set: bundle entries that a root COULD be named after and that nothing reserves. Empty by contract. */
private fun unreservedNames(entries: Set<String>): List<String> =
    entries.mapNotNull { RootName.of(it) }.filterNot { ReservedSegments.isReserved(it) }.map { it.value }

/**
 * The served bundle's directory on the test classpath. The `index.html` check is the anti-vacuity guard for BOTH
 * rows, and it is also what pins this to OUR bundle: `getResource` hands back the FIRST `static/` on the
 * classpath, so a dependency shipping one of its own would otherwise be inventoried in place of the real thing.
 */
private fun bundleDir(): Path {
    val url = requireNotNull(FrontendBundleTest::class.java.classLoader.getResource("static")) {
        "static/ is not on the test classpath - processResources must depend on copyFrontend"
    }
    val dir = Path.of(url.toURI()) // a directory classpath under test; a JAR URI would throw here, loudly
    check(Files.isRegularFile(dir.resolve("index.html"))) {
        "$dir holds no index.html - the bundle is unstaged or stale (run :frontend:npmBuild), or it is not ours"
    }
    return dir
}

/** The bundle's top-level entries - what `staticResources("/", "static")` serves directly under `/`. */
private fun bundleTopLevel(): Set<String> =
    Files.list(bundleDir()).use { stream -> stream.map { it.fileName.toString() }.toList() }.toSet()

/**
 * The entries under `static/assets/`, where Vite emits its hashed js/css. This row's own anti-vacuity guard is the
 * non-empty check: a staged-but-empty `assets/` would otherwise let it pass by asserting over nothing.
 */
private fun bundleAssetsLevel(): Set<String> {
    val assets = bundleDir().resolve("assets")
    check(Files.isDirectory(assets)) { "static/assets/ is missing - run :frontend:npmBuild" }
    val entries = Files.list(assets).use { stream -> stream.map { it.fileName.toString() }.toList() }.toSet()
    check(entries.isNotEmpty()) { "static/assets/ is empty - the Vite bundle did not stage" }
    return entries
}
