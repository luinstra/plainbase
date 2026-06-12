package com.plainbase.acceptance

import com.plainbase.domain.render.HeadingIdsGoldenTest
import com.plainbase.domain.service.FrontmatterPatcherGoldenTest
import com.plainbase.domain.service.LinkResolutionGoldenTest
import com.plainbase.frameworks.ktor.RestGoldenTest
import io.kotest.core.spec.style.FunSpec

/**
 * The four forever-API golden corpora, run as ONE named suite (chunk 8, acceptance criterion 3).
 * Selection, not duplication: each corpus test class below remains the single source of truth and
 * still runs standalone; this suite re-executes them by class selection with an executed-test
 * floor, so the named gate can never be vacuously green.
 *
 * ============================== NEVER-CHANGE POLICY ==============================
 * These corpora pin Plainbase's FROZEN forever-APIs (master plan §2; phase-1 plan §A,
 * `.crew/plans/phase-1-implementation-plan-task-breakdown-forever.md`):
 *
 *   - PB-SLUG-1  (heading-id algorithm)      -> `golden/heading-ids.tsv`
 *   - PB-LINK-1  (link resolution + scheme allowlist) -> `golden/link-resolution.tsv`
 *   - PB-PATCH-1 (surgical frontmatter patcher)       -> `golden/patcher/` byte-pairs
 *   - PB-REST-1  (REST shapes + error envelope)       -> `golden/rest/` snapshots
 *
 * A change that breaks ANY row of these corpora is a forever-API break, not a fix. Corrections to
 * golden rows were FREE only during the chunk-2 authoring window, which closed when chunk 2 landed
 * (commit 4c16a79, 2026-06-10); they are FROZEN ever since.
 *
 * One deliberate asymmetry — PB-PATCH-1 (§A3): ACCEPTANCE behavior is permanently frozen (every
 * `.in`/`.out` byte-pair and `.alreadypresent` case can never change), but a REFUSED case may later
 * be RELAXED into an acceptance by a documented revision — adding a `.out` for a today-refused
 * input is legal; flipping an existing `.out` is not. PB-LINK-1's scheme allowlist and broken-link
 * error classes are append-only; PB-REST-1 fields are never removed or retyped.
 * Additive amendments on record: tree folder-node `url` added 2026-06-12 (additive, ADR-0003).
 * =================================================================================
 */
class ForeverApiGoldenSuite : FunSpec({
    tags(Acceptance)

    // Floors = today's corpus sizes. Corpora are append-only, so floors only ever rise; a selection
    // that discovers fewer tests than the corpus already holds is a broken suite, not a passing one.

    test("PB-SLUG-1: the heading-ids golden corpus (27 data rows + corpus-size pin)") {
        SelectedSuite.run(HeadingIdsGoldenTest::class).shouldHavePassed("HeadingIdsGoldenTest", atLeastTests = 28)
    }

    test("PB-LINK-1: the link-resolution golden corpus (34 rows + corpus-size pin)") {
        SelectedSuite.run(LinkResolutionGoldenTest::class).shouldHavePassed("LinkResolutionGoldenTest", atLeastTests = 35)
    }

    test("PB-PATCH-1: the patcher byte-pair corpus (12 accepted + 1 already-present + 25 refused)") {
        SelectedSuite.run(FrontmatterPatcherGoldenTest::class).shouldHavePassed("FrontmatterPatcherGoldenTest", atLeastTests = 38)
    }

    test("PB-REST-1: the REST snapshot corpus (8 frozen-shape tests)") {
        SelectedSuite.run(RestGoldenTest::class).shouldHavePassed("RestGoldenTest", atLeastTests = 8)
    }
})
