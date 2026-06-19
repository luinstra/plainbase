package com.plainbase.acceptance

import com.plainbase.domain.render.HeadingIdsGoldenTest
import com.plainbase.domain.service.FrontmatterPatcherGoldenTest
import com.plainbase.domain.service.LinkResolutionGoldenTest
import com.plainbase.frameworks.ktor.RestGoldenTest
import com.plainbase.frameworks.ktor.SearchGoldenTest
import com.plainbase.frameworks.ktor.WriteGoldenTest
import io.kotest.core.spec.style.FunSpec

/**
 * The five forever-API golden corpora, run as ONE named suite (chunk 8, acceptance criterion 3;
 * PB-SEARCH-1 joined with Phase-2 chunk S4). Selection, not duplication: each corpus test class
 * below remains the single source of truth and still runs standalone; this suite re-executes them
 * by class selection with an executed-test floor, so the named gate can never be vacuously green.
 *
 * ============================== NEVER-CHANGE POLICY ==============================
 * These corpora pin Plainbase's FROZEN forever-APIs (master plan §2; phase-1 plan §A,
 * `.crew/plans/phase-1-implementation-plan-task-breakdown-forever.md`; phase-2 plan §A,
 * `.crew/plans/phase-2-search-implementation-plan-pb-search-1-con.md`):
 *
 *   - PB-SLUG-1   (heading-id algorithm)      -> `golden/heading-ids.tsv`
 *   - PB-LINK-1   (link resolution + scheme allowlist) -> `golden/link-resolution.tsv`
 *   - PB-PATCH-1  (surgical frontmatter patcher)       -> `golden/patcher/` byte-pairs
 *   - PB-REST-1   (REST shapes + error envelope)       -> `golden/rest/` snapshots
 *   - PB-SEARCH-1 (search shape + grammar + error envelopes) -> `golden/rest/search-*.json`,
 *                 `golden/rest/error-invalid-query-*.json`
 *   - PB-WRITE-1  (PUT save shapes + status taxonomy + round-trip) -> `golden/rest/write-*.json`
 *
 * A change that breaks ANY row of these corpora is a forever-API break, not a fix. Corrections to
 * golden rows were FREE only during the chunk-2 authoring window, which closed when chunk 2 landed
 * (commit 4c16a79, 2026-06-10); they are FROZEN ever since. The PB-SEARCH-1 snapshots froze when
 * chunk S4 landed (2026-06-12).
 *
 * One deliberate asymmetry — PB-PATCH-1 (§A3): ACCEPTANCE behavior is permanently frozen (every
 * `.in`/`.out` byte-pair and `.alreadypresent` case can never change), but a REFUSED case may later
 * be RELAXED into an acceptance by a documented revision — adding a `.out` for a today-refused
 * input is legal; flipping an existing `.out` is not. PB-LINK-1's scheme allowlist and broken-link
 * error classes are append-only; PB-REST-1 fields are never removed or retyped.
 * Additive amendments on record: tree folder-node `url` added 2026-06-12 (additive, ADR-0003);
 * create-response `id` + `url` added 2026-06 (additive, W6 — `POST /api/v1/pages` 201 now identifies
 * the created resource so the client navigates to the server-authoritative url; owner+debate-approved).
 *
 * PB-SEARCH-1 freeze-tier notes (phase-2 §A6 — what these goldens do and do NOT freeze):
 *   - `score` VALUES are deliberately NOT frozen (§A4: engine-scaled, never comparable across
 *     engines or releases) — the comparison normalizes each hit's `score` to the `{{score}}`
 *     placeholder AFTER type-checking it as a finite JSON number. Everything else in the parsed
 *     tree IS frozen, `content_hash` recomputed from fixture bytes like every Phase-1 golden.
 *   - These snapshots are tier-1 FOREVER; the broader pinned query set (exact snippet text and
 *     hit ordering across many queries, the BM25 weight-tuning pins) is tier-2
 *     pinned-but-reviewable — regenerable with documented review, e.g. on a deliberate SQLite
 *     bump — and lives OUTSIDE this suite. Do not promote tier-2 pins in here.
 *   - Pinned golden queries must avoid CJK-sensitive terms (CJK tokenization is engine- and
 *     tokenizer-specific); deliberate CJK assertions are tagged per-engine/per-tokenizer in the
 *     engine suites, never placed in a forever golden. The sentinel term is proven unique in the
 *     fixture corpus by a self-validating precondition inside the test itself.
 *   - `search_unavailable` is registered-but-unused vocabulary (§A5): no Phase-2 code path can
 *     emit it (the embedded engine is in-process), so no golden exists for it yet — its envelope
 *     freezes when the first out-of-process engine ships.
 *
 * Phase 2 freeze ledger (finalized at S8): tier-1 forever goldens are exactly the five corpora
 * above — PB-SEARCH-1 added no tier-1 golden beyond SearchGoldenTest's 5 (sentinel single-hit,
 * zero-hit, three error envelopes). The BM25 weight pins, the per-query snippet/ordering set, the
 * reindex-equivalence sequences, and any CJK/trigram assertion are tier-2 pinned-but-reviewable or
 * per-tokenizer-tagged — they live OUTSIDE this suite and may be regenerated under documented
 * review. The S8 reindex endpoint/CLI carry a non-frozen ReindexResponse body (like RescanResponse,
 * §A5) that no golden pins. No S8 work adds or moves a tier-1 golden.
 *
 * PB-WRITE-1 freeze ledger (froze when W3a landed, 2026-06): transport is a RAW `text/markdown`
 * body + the `If-Match` base_hash header (an RFC 7232 strong tag `"sha256:<64-hex>"`); `GET` returns
 * that value as `ETag` so the round-trip is byte-native. Responses are VARIANT DTOs — field ABSENCE
 * is part of the shape (`WrittenResponse` has no `warning` key; `WrittenButUnindexedResponse` does;
 * `commit` is present-`null` until W4). The status taxonomy is frozen: 200 saved (warning-or-not),
 * 409 drift, 422 unsupported-edit, 400 invalid_base_hash, 404, 413 body_too_large (`max_bytes` in
 * the body), 415, 503. The drift-only `reason` enum is `{content_changed, page_moved, page_deleted}`
 * (`page_moved` producer-reserved; **`id_changed` is deliberately NOT a reason** — id/slug/
 * redirect_from rejections are 422 + code + field). Write error codes are append-only;
 * `commit`/`warning`/`current_*`/`max_bytes` are never removed or retyped; the 1 MiB body cap is
 * configurable UPWARD (additive). Tier-1 goldens are the four `golden/rest/write-*.json` snapshots
 * plus the adversarial byte-identical round-trip (WriteGoldenTest's 7 tests).
 *
 * W2 (`POST /api/v1/pages` new-page creation, 2026-06) conforms to PB-WRITE-1 at **201 Created** (the
 * create-vs-edit status distinction), with the `ETag` read-half. New append-only codes are
 * `page_exists` (409, body carries the real `path`) and `invalid_create_request` (400); the new
 * `WriteOutcome.AlreadyExists` case adds only a forced `error(...)` branch to the frozen `toWire`
 * (no new envelope). The clean create returns a create-specific success shape: `CreatedResponse`
 * (`{id, url, content_hash, commit}`) — the frozen `WrittenResponse` keys PLUS the minted `id` + the
 * server-authoritative canonical `url` (W6 additive amendment, above): a 201 identifies the created
 * resource so the client navigates to the server's url, never a client-derived slug. `url` is the
 * published `IndexedPage.url` (slugified/collision-de-duped), never re-composed from the on-disk path
 * — pinned by a divergence golden using a non-ASCII slug. Tier-1 holds two create snapshots,
 * `golden/rest/write-post-ok.json` (the 201 create shape) and `golden/rest/write-post-ok-unicode.json`
 * (the url-divergence guard), so WriteGoldenTest is now 11 tests (incl. the path-space-loser
 * permalink fallback: a null-canonical-url create addresses the `/p/{id}` permalink, never a
 * fabricated `/docs/<raw path>`).
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

    test("PB-SEARCH-1: the search snapshot corpus (5 frozen-shape tests, score-normalized)") {
        SelectedSuite.run(SearchGoldenTest::class).shouldHavePassed("SearchGoldenTest", atLeastTests = 5)
    }

    test(
        "PB-WRITE-1: the write snapshot corpus (11 tests; raw round-trip, 409/422 split, present-null commit, 201 create id+url + url-divergence, unindexed-create url:null, loser permalink fallback)",
    ) {
        SelectedSuite.run(WriteGoldenTest::class).shouldHavePassed("WriteGoldenTest", atLeastTests = 11)
    }
})
