package com.plainbase.frameworks.markdown

import com.plainbase.domain.content.Nfc
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.service.FixtureIndexStub
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Acceptance criterion 2 (the MASTER criterion) — determinism. Every fixture page must render
 * byte-identical HTML. The renderer holds no mutable cross-render state (the parser/options are
 * immutable; per-page allocator and maps are allocated inside [FlexmarkRenderer.render]), so
 * identical input must yield identical output — the property the whole forever-API rests on.
 *
 * Critically this is a WARM-vs-FRESH comparison: a renderer reused across the whole suite
 * (`warmRenderer`) is compared against a renderer constructed FRESH inside each iteration. A shared
 * pair of long-lived renderers would each accumulate identical history, so any per-render leak (e.g.
 * an id allocator or link map not reset between calls) would corrupt BOTH identically and the test
 * would pass while isolation was broken. Comparing warm output to a never-before-used instance makes
 * the test actually prove per-render isolation.
 */
class RenderDeterminismTest : FunSpec({

    val warmRenderer = FlexmarkRenderer(FixtureIndexStub(Fixtures.demoDocs))

    val pages = Files.walk(Fixtures.demoDocs).use { stream ->
        stream.filter { it.isRegularFile() && it.name.endsWith(".md") }.toList()
    }

    fun relOf(file: java.nio.file.Path) = Nfc.normalize(Fixtures.demoDocs.relativize(file).toString().replace('\\', '/'))

    // Warm the reused renderer by rendering every page once before the comparison loop, so its
    // accumulated state (if any leaked) differs from a fresh instance's empty state.
    pages.forEach { warmRenderer.render(TreePath.require(relOf(it)), Files.readAllBytes(it)) }

    pages.forEach { file ->
        val rel = relOf(file)
        test("determinism: $rel renders byte-identical (warm renderer vs a fresh one)") {
            val source = Files.readAllBytes(file)
            val path = TreePath.require(rel)
            // A fresh renderer with no render history; warmRenderer has rendered the whole tree already.
            val fresh = FlexmarkRenderer(FixtureIndexStub(Fixtures.demoDocs))
            warmRenderer.render(path, source).html shouldBe fresh.render(path, source).html
        }
    }
})
