package com.plainbase.acceptance

import com.plainbase.domain.service.IndexBuilderReindexConcurrencyTest
import com.plainbase.domain.service.RebuildSchedulerTest
import com.plainbase.frameworks.cli.ReindexCommandTest
import com.plainbase.frameworks.filesystem.Fixtures
import com.plainbase.frameworks.ktor.AdminRouteTest
import com.plainbase.frameworks.ktor.WatchingRestHarness
import com.plainbase.frameworks.ktor.plainbaseModule
import com.plainbase.frameworks.search.Fts5CorpusPerfTest
import com.plainbase.frameworks.search.Fts5SearchProviderContractTest
import com.plainbase.frameworks.search.SearchEquivalenceTest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files

/**
 * Chunk S8 — the master plan's Phase-2 gate, encoded (every §Verification row maps to a named,
 * passing test OR a named owner-ratified deferral). Like [Phase1AcceptanceTest] it encodes each row
 * by SELECTION-with-floor where the work lives in another class, and INLINE where it is S8-new; it
 * never duplicates an assertion. Runs in `./gradlew test`/`build`/CI and via `:server:acceptanceTest`.
 *
 * §Verification rows → coverage:
 *   1. shared contract suite (indexing/deletion/rebuild/citations/filtering) — S3
 *      `SearchProviderContract` via FTS5, selected with a floor. The "both engines" half is
 *      OWNER-RATIFIED-DEFERRED with the second engine (Appendix G); the parameterization is the
 *      standing readiness proof.
 *   2. < 200 ms p95 (fixture + 1k corpus) — S2 `Fts5CorpusPerfTest`, selected (not re-measured).
 *   3. typo top-3 on Meilisearch; embedded exempt — OWNER-RATIFIED-DEFERRED (Iteration 3): the
 *      criterion is Meilisearch-bound by its own text and travels with Appendix G. Recorded below
 *      as a documented deferral row, never a fake pass.
 *   4. delete search.db + reindex equivalence (+ self-healing sync) — S8 `SearchEquivalenceTest`.
 *   5. switching search.engine = just a rebuild — OWNER-RATIFIED-DEFERRED (Iteration 3): one engine
 *      to switch to; the standing proof is the S3 engine-parameterization + the port design.
 *      Recorded below as a documented deferral row.
 *   6. concurrent-search-during-rebuild — S3 contract (torn hits/total assertion), within the
 *      contract selection above.
 *   7. edit-on-disk searchable within 5 s — S8, INLINE below, Linux-CI-only (macOS polling cannot
 *      meet 5 s — §B1; reported skipped on macOS, never failed).
 *
 * Plus the S8 debate-driven correctness guards: criterion 13 (reindex atomicity), criterion 14
 * (cross-process lock refusal, inside `ReindexCommandTest`), criterion 15 (deterministic scheduler).
 */
class Phase2AcceptanceTest : FunSpec({
    tags(Acceptance)

    // --- §Verification rows by selection (floors only ever rise; an empty selection fails loud) ---

    test(
        "criteria 1 & 6: the S3 engine contract passes against the embedded engine " +
            "(indexing, deletion, rebuild, citations, filtering, concurrent rebuild)",
    ) {
        // 17 contract tests today; the second-engine half is owner-ratified-deferred (Appendix G).
        SelectedSuite.run(Fts5SearchProviderContractTest::class).shouldHavePassed("Fts5SearchProviderContractTest", atLeastTests = 17)
    }

    test("criterion 2: search returns first results < 200 ms p95 over the fixture + 1k corpus (S2 perf test)") {
        SelectedSuite.run(Fts5CorpusPerfTest::class).shouldHavePassed("Fts5CorpusPerfTest", atLeastTests = 1)
    }

    test("criterion 4: delete search.db + reindex (and self-healing sync alone) yield equivalent fixed-query-set sequences") {
        SelectedSuite.run(SearchEquivalenceTest::class).shouldHavePassed("SearchEquivalenceTest", atLeastTests = 2)
    }

    test("the reindex endpoint produces a freshly generation-swapped index, 409s a concurrent call, and releases the flag on failure") {
        SelectedSuite.run(AdminRouteTest::class).shouldHavePassed("AdminRouteTest", atLeastTests = 3)
    }

    test(
        "the plainbase reindex CLI exits 0/2, prints the exact summary, and refuses to run under a server's DATA_DIR lock (criterion 14)",
    ) {
        SelectedSuite.run(ReindexCommandTest::class).shouldHavePassed("ReindexCommandTest", atLeastTests = 3)
    }

    test("criterion 13: a reindex cannot regress the engine behind a concurrent watcher rebuild (atomicity guard)") {
        SelectedSuite.run(
            IndexBuilderReindexConcurrencyTest::class,
        ).shouldHavePassed("IndexBuilderReindexConcurrencyTest", atLeastTests = 1)
    }

    test("criterion 15: the RebuildScheduler debounce/cap/single-flight is proven deterministically under a fake clock") {
        SelectedSuite.run(RebuildSchedulerTest::class).shouldHavePassed("RebuildSchedulerTest", atLeastTests = 6)
    }

    // --- §Verification criteria 3 & 5: OWNER-RATIFIED-DEFERRED (Iteration 3) — recorded, not faked ---

    test("criterion 3 (typo top-3 on Meilisearch; embedded exempt) is OWNER-RATIFIED-DEFERRED with the second engine (Appendix G)") {
        // DEFERRED: the criterion is Meilisearch-bound by its own text; the embedded exemption it
        // grants IS the shipped Phase-2 state. Not implemented here, by owner ratification — never a
        // fake pass. The acceptance criterion is "documented deferral, never silently absent".
    }

    test("criterion 5 (switching search.engine = just a rebuild) is OWNER-RATIFIED-DEFERRED with the second engine (Appendix G)") {
        // DEFERRED: there is one engine to switch to. The standing structural proof is the S3
        // engine-parameterization + the port design (nothing engine-specific persists outside the
        // disposable index). The test itself defers with Appendix G — recorded, never faked.
    }

    // --- §Verification criterion 7: end-to-end watcher latency (Linux-CI-only) ---

    test("criterion 7: a file edited on disk outside Plainbase is searchable within 5 s (watcher, Linux only)")
        .config(enabled = System.getProperty("os.name").orEmpty().startsWith("Linux", ignoreCase = true)) {
            // §B1: macOS uses the JDK PollingWatchService (multi-second), so the 5 s promise binds
            // Linux (the deployment platform); on macOS this reports skipped, never failed. The
            // watcher machinery (FileWatcher + RebuildScheduler + the sync listener) is the real
            // production graph, assembled exactly as Application.serve() does. Edits land on a TEMP
            // COPY — never the frozen fixtures/demo-docs (ForeverApiGoldenSuite never-change policy).
            WatchingRestHarness(Fixtures.demoDocs).use { harness ->
                testApplication {
                    application { plainbaseModule(harness.services) }

                    val sentinel = "xyzzy-watcher-sentinel" // absent from the whole corpus: a hit proves the edit propagated
                    val target = harness.root.resolve("guides/getting-started.md")
                    val original = if (Files.exists(target)) Files.readString(target) else "# Getting Started\n"
                    Files.writeString(target, original + "\n\n## Probe\n\n$sentinel\n")

                    // Await, never sleep: poll until the term is searchable or the 5 s budget elapses.
                    val deadline = System.currentTimeMillis() + 5_000
                    var hits = emptyList<Any>()
                    while (System.currentTimeMillis() < deadline) {
                        val response = client.get("/api/v1/search?q=$sentinel")
                        if (response.status == HttpStatusCode.OK) {
                            hits = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("hits").jsonArray
                            if (hits.isNotEmpty()) break
                        }
                        delay(100)
                    }
                    hits.shouldNotBeEmpty()
                }
            }
        }
})
