package com.plainbase.search

import com.plainbase.domain.search.SearchProvider
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * One engine under the contract: a fresh, empty [provider] per harness instance, plus the reset
 * hook the durability tests need — [reopen] closes and reopens the engine's backing store in
 * place (a file-backed engine cycles its database; a server-backed engine reconnects).
 */
interface SearchEngineHarness : AutoCloseable {
    val provider: SearchProvider

    /** Closes and reopens the same backing store; [provider] afterwards speaks to the reopened engine. */
    fun reopen()
}

/**
 * The shared engine contract (chunk S3; master criterion 1): every behavior the `SearchProvider`
 * port PROMISES, encoded once, engine-blind, and inherited by each enabled engine through a
 * concrete subclass supplying its [factory] (+ its criterion-4 [equivalence] comparator — the
 * embedded default is exact-ordered-sequence; a future engine joins pinned-but-reviewable,
 * Appendix G, and must pass this suite UNMODIFIED before it ships).
 *
 * Coverage: indexing (field reach, plain-text AND semantics), deletion, rebuild semantics,
 * citation payloads, port-level `statusFilter` filtering, adversarial-query no-error corpus
 * (fixed + property fuzz), concurrent-search-during-rebuild with the torn hits/total assertion
 * (master criterion 6 / Iteration-2 BLOCKING-1 — encoded HERE, once, never re-written per
 * engine), zero-hit behavior, `indexedState` truthfulness, per-page replace semantics, ordering
 * determinism, paging, reopen durability, and reindex equivalence (criterion 4).
 *
 * Engine-blindness is structural: this package imports no engine, no `frameworks.`, no SQL —
 * [SearchContractPurityTest] fails the build if that ever changes. Pinned queries are CJK-free
 * per the §A6 golden guidance; nothing here is tokenizer-specific, so no per-tokenizer tag
 * appears in this suite (tokenizer pins live with their engine, e.g. the tagged trigram tests).
 */
abstract class SearchProviderContract(
    factory: () -> SearchEngineHarness,
    equivalence: ReindexEquivalence.Comparator = ReindexEquivalence.exactOrderedSequence,
) : FunSpec({

    suspend fun <T> withEngine(block: suspend (SearchEngineHarness) -> T): T = factory().use { block(it) }

    test("indexing: every document field is searchable — title, heading, body, tags, aliases, owner") {
        withEngine { engine ->
            engine.provider.rebuild(
                sequenceOf(
                    page(
                        1,
                        title = "Quarterly Report",
                        tags = listOf("fiscal"),
                        aliases = listOf("q-report"),
                        owner = "treasury",
                        preamble = "Numbers for the quarter.",
                        sections = listOf("details" to "Spreadsheets and forecasts."),
                    ),
                ),
            )
            listOf("quarterly", "fiscal", "q-report", "treasury", "details", "forecasts").forEach { term ->
                withClue(term) {
                    val results = engine.provider.search(query(term))
                    results.total shouldBeGreaterThan 0L
                    results.hits.map { it.pageId }.toSet() shouldBe setOf(pageId(1))
                }
            }
        }
    }

    test("indexing: a multi-token query demands every token (§A1 plain-text AND semantics)") {
        withEngine { engine ->
            engine.provider.rebuild(
                sequenceOf(
                    page(1, preamble = "rolling deploy of the release"),
                    page(2, preamble = "rolling hills"),
                    page(3, preamble = "deploy quickly"),
                ),
            )
            engine.provider.search(query("rolling deploy")).hits.map { it.pageId } shouldBe listOf(pageId(1))
        }
    }

    test("citation payloads: a hit names the matched page and section; a page-level hit carries a null headingId") {
        withEngine { engine ->
            engine.provider.rebuild(
                sequenceOf(
                    page(1, preamble = "the preamble mentions oxidation", sections = listOf("safety" to "Wear gloves around reagents.")),
                    page(2, preamble = "unrelated filler"),
                ),
            )
            val sectionHit = engine.provider.search(query("reagents")).hits.single()
            sectionHit.pageId shouldBe pageId(1)
            sectionHit.headingId shouldBe "safety"
            val pageHit = engine.provider.search(query("oxidation")).hits.single()
            pageHit.pageId shouldBe pageId(1)
            pageHit.headingId.shouldBeNull()
        }
    }

    test("citation payloads: finite descending scores, plain-text snippet, well-formed offsets (§A6 tier 3)") {
        withEngine { engine ->
            engine.provider.rebuild((1..4).asSequence().map { page(it, preamble = "the beacon flashes twice, then the beacon rests") })
            val hits = engine.provider.search(query("beacon")).hits
            hits.shouldNotBeEmpty()
            hits.zipWithNext().forEach { (a, b) -> (a.score >= b.score).shouldBeTrue() }
            hits.forEach { hit ->
                hit.score.isFinite().shouldBeTrue()
                // Deliberately unasserted: that the snippet SHOWS the matched term — §A3 leaves
                // snippet selection to engine quality (a snippet without the match is legal).
                hit.shouldHaveWellFormedHighlights()
            }
        }
    }

    test("zero-hit behavior: an unmatched term and a token-free query both answer (0, []) — never an error") {
        withEngine { engine ->
            engine.provider.rebuild(sequenceOf(page(1, preamble = "ordinary content")))
            listOf("xyzzy-no-such-term", "", "   ", "\t\n").forEach { text ->
                withClue("q=${text.toList()}") {
                    val results = engine.provider.search(query(text))
                    results.total shouldBe 0L
                    results.hits.shouldBeEmpty()
                }
            }
        }
    }

    test("deletion: every document of a deleted page disappears and the engine forgets the page entirely") {
        withEngine { engine ->
            engine.provider.index(
                listOf(
                    page(1, preamble = "vanishing preamble", sections = listOf("h" to "vanishing section body")),
                    page(2, preamble = "survivor"),
                ),
            )
            engine.provider.delete(listOf(pageId(1)))
            engine.provider.search(query("vanishing")).total shouldBe 0L
            engine.provider.search(query("survivor")).total shouldBe 1L
            engine.provider.indexedState().keys shouldBe setOf(pageId(2))
        }
    }

    test("per-page replace semantics: a removed heading's document disappears with the replace") {
        withEngine { engine ->
            val twoSections = listOf("alpha" to "first body", "beta" to "second body")
            engine.provider.index(listOf(page(1, contentHash = "sha256:a", preamble = "intro", sections = twoSections)))
            engine.provider.search(query("second")).hits.map { it.headingId } shouldBe listOf("beta")

            engine.provider.index(listOf(page(1, contentHash = "sha256:b", preamble = "intro", sections = twoSections.take(1))))
            engine.provider.search(query("second")).total shouldBe 0L
            engine.provider.search(query("first")).hits.map { it.headingId } shouldBe listOf("alpha")
        }
    }

    test("rebuild semantics: full-corpus replacement — the old corpus is unfindable, indexedState mirrors the new corpus exactly") {
        withEngine { engine ->
            val old = (1..3).map { page(it, preamble = "ancient relic") }
            val new = (11..12).map { page(it, preamble = "fresh artifact") }
            engine.provider.rebuild(old.asSequence())
            engine.provider.rebuild(new.asSequence())
            engine.provider.search(query("relic")).total shouldBe 0L
            engine.provider.search(query("artifact")).total shouldBe 2L
            engine.provider.indexedState().keys shouldBe new.map { it.pageId }.toSet()

            engine.provider.rebuild(emptySequence())
            engine.provider.search(query("artifact")).total shouldBe 0L
            engine.provider.indexedState() shouldBe emptyMap()
        }
    }

    test("filtering: port-level statusFilter — filtered out, included when listed, empty set matches nothing, null matches all") {
        withEngine { engine ->
            engine.provider.rebuild(
                sequenceOf(
                    page(1, status = "active", preamble = "shared term"),
                    page(2, status = "archived", preamble = "shared term"),
                ),
            )
            engine.provider.search(query("shared")).total shouldBe 2L
            engine.provider.search(query("shared", statusFilter = setOf("active"))).hits.map { it.pageId } shouldBe listOf(pageId(1))
            engine.provider.search(query("shared", statusFilter = setOf("active", "archived"))).total shouldBe 2L
            engine.provider.search(query("shared", statusFilter = emptySet())).total shouldBe 0L
        }
    }

    test("indexedState truthfulness: empty engine, then index / per-page replace (hash + path) / delete are tracked exactly") {
        withEngine { engine ->
            engine.provider.indexedState() shouldBe emptyMap()
            engine.provider.index(listOf(page(1, contentHash = "sha256:v1"), page(2, contentHash = "sha256:v2")))
            engine.provider.indexedState().mapValues { it.value.contentHash } shouldBe
                mapOf(pageId(1) to "sha256:v1", pageId(2) to "sha256:v2")

            engine.provider.index(listOf(page(1, contentHash = "sha256:v1b", path = "moved/page-1.md")))
            val state = engine.provider.indexedState().getValue(pageId(1))
            state.contentHash shouldBe "sha256:v1b"
            state.path.value shouldBe "moved/page-1.md"

            engine.provider.delete(listOf(pageId(2)))
            engine.provider.indexedState().keys shouldBe setOf(pageId(1))
        }
    }

    test("ordering is deterministic: the same query answers the same ordered hit sequence every time, ties included") {
        withEngine { engine ->
            engine.provider.rebuild((5 downTo 1).asSequence().map { page(it, title = "Clone", preamble = "twin payload") })
            val first = engine.provider.search(query("twin", limit = 50)).hits
            first.map { it.pageId }.toSet() shouldBe (1..5).map { pageId(it) }.toSet()
            repeat(3) { engine.provider.search(query("twin", limit = 50)).hits shouldBe first }
        }
    }

    test("paging: limit/offset windows tile the full ordered result set; an empty window past the end keeps the true total") {
        withEngine { engine ->
            engine.provider.rebuild((1..7).asSequence().map { page(it, preamble = "needle haystack") })
            val full = engine.provider.search(query("needle", limit = 50))
            full.total shouldBe 7L
            val tiled = (0 until 7 step 2).flatMap { offset -> engine.provider.search(query("needle", limit = 2, offset = offset)).hits }
            tiled.map { it.pageId } shouldBe full.hits.map { it.pageId }

            val past = engine.provider.search(query("needle", limit = 2, offset = 100))
            past.hits.shouldBeEmpty()
            past.total shouldBe 7L
        }
    }

    test("adversarial-query corpus: hostile inputs never error — every answer is shape-valid") {
        withEngine { engine ->
            engine.provider.rebuild(sequenceOf(page(1, title = "Deploy Guide", preamble = "rolling deploy with kubernetes")))
            adversarialCorpus.forEach { text ->
                withClue("q=${text.toList()}") {
                    val results = engine.provider.search(query(text))
                    (results.total >= 0L).shouldBeTrue()
                    (results.hits.size <= 20).shouldBeTrue()
                    results.hits.forEach { it.shouldHaveWellFormedHighlights() }
                }
            }
        }
    }

    test("property: arbitrary adversarial strings never produce an engine error") {
        withEngine { engine ->
            engine.provider.rebuild(
                sequenceOf(
                    page(
                        1,
                        title = "Deploy Guide",
                        preamble = "rolling deploy with kubernetes",
                        sections = listOf("notes" to "café résumé"),
                    ),
                    page(2, preamble = "plain filler text"),
                ),
            )
            checkAll(200, adversarialQueries) { text ->
                val results = engine.provider.search(query(text)) // any engine exception here breaks the frozen A1 promise
                check(results.total >= 0 && results.hits.size <= 20) { "malformed result for ${text.toList()}" }
            }
        }
    }

    test("criterion 6: concurrent searches during rebuilds never error and never see a torn (hit set, total) pair") {
        withEngine { engine ->
            // The two corpora MUST have different match counts for the probe (3 vs 5): only then can
            // this catch a total from one generation paired with hits from the other (BLOCKING-1).
            val corpusA = (1..3).map { page(it, preamble = "tornprobe alpha payload") }
            val corpusB = (11..15).map { page(it, preamble = "tornprobe beta payload") }
            val idsA = corpusA.map { it.pageId }.toSet()
            val idsB = corpusB.map { it.pageId }.toSet()
            val provider = engine.provider
            provider.rebuild(corpusA.asSequence())

            val stop = AtomicBoolean(false)
            val started = CountDownLatch(3)
            val failures = ConcurrentLinkedQueue<String>()
            val readers = List(3) {
                thread {
                    started.countDown()
                    while (!stop.get()) {
                        val results = runCatching { provider.search(query("tornprobe", limit = 50)) }
                            .getOrElse {
                                failures += "query errored: $it"
                                return@thread
                            }
                        val pages = results.hits.map { it.pageId }.toSet()
                        val consistent = (results.total == 3L && pages == idsA) || (results.total == 5L && pages == idsB)
                        if (!consistent) failures += "torn pair: total=${results.total} pages=$pages"
                    }
                }
            }
            started.await()
            repeat(8) { round -> provider.rebuild((if (round % 2 == 0) corpusB else corpusA).asSequence()) }
            stop.set(true)
            readers.forEach { it.join(10_000) }
            readers.forEach { check(!it.isAlive) { "reader thread wedged past the join timeout" } }
            failures.toList().shouldBeEmpty()
        }
    }

    test("reopen durability: the indexed corpus survives an engine reopen — answers and indexedState intact") {
        withEngine { engine ->
            val corpus = contractCorpus()
            engine.provider.rebuild(corpus.asSequence())
            val before = ReindexEquivalence.capture(engine.provider)
            engine.reopen()
            equivalence.compare(before, ReindexEquivalence.capture(engine.provider))
            engine.provider.indexedState().keys shouldBe corpus.map { it.pageId }.toSet()
        }
    }

    test("criterion 4: the fixed query set answers equivalently after a reindex and on a freshly built engine") {
        val corpus = contractCorpus()
        val before = withEngine { engine ->
            engine.provider.rebuild(corpus.asSequence())
            val captured = ReindexEquivalence.capture(engine.provider)
            engine.provider.rebuild(corpus.asSequence()) // reindex in place
            equivalence.compare(captured, ReindexEquivalence.capture(engine.provider))
            captured
        }
        withEngine { engine ->
            // Derived state is fully rebuildable: a brand-new engine fed the same corpus is equivalent.
            engine.provider.rebuild(corpus.asSequence())
            equivalence.compare(before, ReindexEquivalence.capture(engine.provider))
        }
    }
})

/**
 * The fixed §A6 tier-3 hostile inputs: engine-operator shapes (quotes, parens, boolean keywords,
 * column filters, `^*:`), SQL shapes, lone `%`/`_`, control characters, BOM/zero-width/RTL
 * controls, non-BMP, lone surrogates, and a long input. Asserted for NO-ERROR only — never for
 * specific hits, so the corpus stays engine-blind (CJK appears only under this no-error reading).
 */
private val adversarialCorpus: List<String> = listOf(
    "\"", "\"\"", "\"unterminated", "'", "(", ")", "((", "))", "()",
    "NOT (a OR b)", "a AND b", "x NEAR(a b, 1)", "title:secret^2", "body:*", "wild*", "-deploy", "+deploy",
    "%", "_", "\\", "{", "}", "`;--", "'); DROP TABLE pages; --",
    "\u0000", "\u0001", "\u0002", "a\u0000b", "\u001F", "\u007F",
    "\uFEFF", "\u200B\u200D", "\u202Ereversed", "\u00A0", "\u3000",
    "caf\u00E9", "\uFB01nd", "\uD83E\uDD91", "\uD834\uDD1E mark", "\uD800", "\uDC00", "\uFFFD",
    "\u65E5\u672C\u8A9E \u30AC\u30A4\u30C9",
    "deploy ".repeat(60).trim(),
)

/** The property generator behind the fuzz leg: hostile fragments + raw code points (incl. lone surrogates), concatenated. */
private val adversarialQueries: Arb<String> = run {
    val fragments = Arb.element(adversarialCorpus + listOf(" ", "\t", "\n", "deploy", "deplo*", "\"deploy\""))
    val rawCodepoint = Arb.int(0x20..0x10FFFF)
        .map { if (it in 0xD800..0xDFFF) "\uFFFD" else String(Character.toChars(it)) }
    val loneSurrogate = Arb.int(0xD800..0xDFFF).map { it.toChar().toString() }
    val piece = arbitrary { rs ->
        when (rs.random.nextInt(4)) {
            0 -> rawCodepoint.bind()
            1 -> loneSurrogate.bind()
            else -> fragments.bind()
        }
    }
    Arb.list(piece, 0..16).map { it.joinToString("") }
}
