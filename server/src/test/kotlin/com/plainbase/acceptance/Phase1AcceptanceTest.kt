package com.plainbase.acceptance

import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.LinkChecker
import com.plainbase.frameworks.filesystem.Fixtures
import com.plainbase.frameworks.ktor.MoveFileIntegrationTest
import com.plainbase.frameworks.markdown.RenderDeterminismTest
import io.kotest.core.Tag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import java.nio.file.Files
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * The Kotest tag marking the Phase-1 acceptance gate. THE invocation is
 * `./gradlew :server:acceptanceTest` (package-filtered, immune to tag filters) — do NOT select via
 * `-Dkotest.tags=Acceptance`: the in-process [SelectedSuite] launcher inherits that system property,
 * so the nested engine excludes the untagged selected specs and the floors trip (fail-loud).
 * The native half of the gate carries the JUnit `@Tag("acceptance")` equivalent — see
 * `Phase1AcceptanceNativeTest` in `src/nativeTest`, which runs the link gate inside the image.
 */
object Acceptance : Tag()

/**
 * Chunk 8 — the master plan's Phase-1 gate, encoded (plan: "Phase-1 acceptance suite", acceptance
 * criteria 1–2):
 *
 *  1. **Zero broken links** across `fixtures/demo-docs` EXCEPT the exact pinned set in
 *     `golden/known-broken-links.json` (the 2 broken links + 1 broken anchor of
 *     `notes/broken-links.md` — deliberate Phase-5 test material). The comparison is whole-report
 *     and exact, so it doubles as the broken-link golden test.
 *  2. The chunk-3 renderer-determinism test and the chunk-6 move-file/permalink/alias master
 *     criterion are part of this gate by SELECTION (see [SelectedSuite]) — included, not duplicated.
 *
 * Runs in `./gradlew test` (hence `build` and CI) like every suite here; `ForeverApiGoldenSuite`
 * carries the third criterion (the four frozen corpora as one named suite).
 */
class Phase1AcceptanceTest : FunSpec({
    tags(Acceptance)

    test("link gate: zero broken links across the fixture tree EXCEPT the pinned manifest set") {
        IndexHarness(Fixtures.demoDocs).use { harness ->
            val report = LinkChecker().check(harness.builder.rebuild())

            // Exact, ordered comparison against the manifest (page order, then document order):
            // an extra broken link ANYWHERE, a missing expected one, or any drift in
            // page/text/target/class fails the gate.
            val actual = report.broken.map {
                KnownBrokenLink(page = it.page.value, text = it.text, target = it.target, errorClass = it.reason.wireValue)
            }
            actual shouldContainExactly KnownBrokenLink.manifest()
        }
    }

    test("chunk-3 renderer determinism is part of the gate (selected, not duplicated)") {
        // RenderDeterminismTest emits one test per fixture page — the floor is the live page count,
        // so a selection that quietly discovers nothing (or a partial tree) cannot pass.
        val fixturePages = Files.walk(Fixtures.demoDocs).use { stream ->
            stream.filter { it.isRegularFile() && it.name.endsWith(".md") }.count()
        }
        SelectedSuite.run(RenderDeterminismTest::class).shouldHavePassed("RenderDeterminismTest", atLeastTests = fixturePages)
    }

    test("chunk-6 move-file/permalink/alias master criterion is part of the gate") {
        SelectedSuite.run(MoveFileIntegrationTest::class).shouldHavePassed("MoveFileIntegrationTest", atLeastTests = 1)
    }
})
