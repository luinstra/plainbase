package com.plainbase.frameworks.ktor

import com.plainbase.domain.root.ReservedSegments
import com.plainbase.domain.root.RootName
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.nio.file.Files
import java.nio.file.Path

/**
 * Two properties of the embedded frontend bundle. The inventory is READ from the served tree, never
 * transcribed, so a bundle that grows is answered here rather than by a list somebody has to remember to
 * update. It reads the CLASSPATH copy rather than `frontend/dist`, because the classpath is what is
 * actually served; `processResources` depends on `copyFrontend`, so it is staged before any test runs.
 *
 * RESERVATION, at the top level. [ReservedSegments] claims the product owns the top-level URLs it serves,
 * and the bundle is served wholesale from `staticResources("/", "static")`, so a new directory there is a
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

    test("the bundle's assets/ is FLAT: every entry is a dotted regular file, so none can name a root") {
        // Every entry, not a filtered subset - the filter is what made the reservation form of this row
        // vacuous, since `RootName.of` dropped all of today's hashed files before the check was reached.
        val offenders = bundleAssetsLevel().filterNot { (name, path) -> "." in name && Files.isRegularFile(path) }
        withClue("a nested or dot-free assets/ entry defeats the bundle-wins check on the full tail") {
            offenders.map { (name, _) -> name }.shouldBeEmpty()
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
private fun bundleDir(): Path {
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

/** The bundle's top-level entry names - what `staticResources("/", "static")` serves directly under `/`. */
private fun bundleTopLevel(): Set<String> =
    Files.list(bundleDir()).use { stream -> stream.map { it.fileName.toString() }.toList() }.toSet()

/** The entries under `static/assets/`, where Vite emits its hashed js/css, as name-to-path pairs. */
private fun bundleAssetsLevel(): List<Pair<String, Path>> {
    val assets = bundleDir().resolve("assets")
    check(Files.isDirectory(assets)) { "static/assets/ is missing - run :frontend:npmBuild" }
    val entries = Files.list(assets).use { stream -> stream.map { it.fileName.toString() to it }.toList() }
    check(entries.isNotEmpty()) { "static/assets/ is empty - the Vite bundle did not stage" }
    return entries
}
