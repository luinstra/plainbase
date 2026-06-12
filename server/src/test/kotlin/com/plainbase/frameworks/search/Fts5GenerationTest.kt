package com.plainbase.frameworks.search

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.sql.DriverManager
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * §B5 generation machinery under real concurrency.
 *
 * **Swap + torn-pair (Iteration-2 BLOCKING-1):** the two corpora have DIFFERENT match counts for
 * the probe query (gen A matches 3 docs, gen B matches 5), so every concurrent response must be
 * `(hit set, total) ∈ {(A set, 3), (B set, 5)}` — internally one generation, with a total from
 * the SAME generation as the hits. A mixed pair (new hits with an old total or vice versa) is
 * exactly what the one-deferred-read-transaction design exists to prevent, and exactly what this
 * asserts. Queries must never error while rebuilds swap underneath them.
 *
 * **GC:** after the dust settles, only the active generation survives in any table.
 *
 * **One-transaction rebuild (gauntlet BLOCKING fix):** a rebuild that dies anywhere rolls back to
 * NOTHING (the next rebuild repairs for free — §B4's promise, never a primary-key wedge); debris
 * rows outside the active generation are swept defensively; and because readers never observe
 * uncommitted writer rows under WAL, an in-progress rebuild cannot shift live bm25 scores even
 * though FTS5 statistics are table-wide.
 *
 * **Per-page atomicity (§B4):** a reader during a page upsert sees the page's document set
 * entirely old or entirely new — never a mix, never a partial count.
 */
class Fts5GenerationTest : FunSpec({

    test("concurrent queries across generation swaps: never an error, never a torn (hits, total) pair; GC leaves one generation") {
        withProvider { provider, dbPath ->
            val corpusA = (1..3).map { pageDocuments(it, preamble = "genprobe alpha payload") }
            val corpusB = (11..15).map { pageDocuments(it, preamble = "genprobe beta payload") }
            val idsA = corpusA.map { it.pageId }.toSet()
            val idsB = corpusB.map { it.pageId }.toSet()
            provider.rebuild(corpusA.asSequence())

            val stop = AtomicBoolean(false)
            val started = CountDownLatch(3)
            val failures = ConcurrentLinkedQueue<String>()
            val readers = List(3) {
                thread {
                    started.countDown()
                    while (!stop.get()) {
                        val results = runCatching { provider.search(query("genprobe", limit = 50)) }
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
            failures.toList().shouldBeEmpty()

            // GC proof, on the raw file: a single generation remains anywhere, and it is the active one.
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { probe ->
                probe.createStatement().use { statement ->
                    fun longs(sql: String) = statement.executeQuery(sql).use { rows ->
                        buildList { while (rows.next()) add(rows.getLong(1)) }
                    }
                    val active = longs("SELECT CAST(value AS INTEGER) FROM search_meta WHERE key='active_generation'").single()
                    longs("SELECT DISTINCT generation FROM section_doc") shouldBe listOf(active)
                    longs("SELECT DISTINCT generation FROM search_page") shouldBe listOf(active)
                }
            }
        }
    }

    test("per-page atomicity: a concurrent reader never sees a mixed old/new document set for one page") {
        withProvider { provider, _ ->
            val oldSections = (1..6).map { "old-$it" to "atomprobe old body $it" }
            val newSections = (1..6).map { "new-$it" to "atomprobe new body $it" }
            fun version(hash: String, sections: List<Pair<String, String>>) =
                pageDocuments(1, contentHash = hash, preamble = "atomprobe preamble", sections = sections)
            provider.index(listOf(version("sha256:old", oldSections)))

            val oldIds = setOf(null) + oldSections.map { it.first }
            val newIds = setOf(null) + newSections.map { it.first }
            val stop = AtomicBoolean(false)
            val failures = ConcurrentLinkedQueue<String>()
            val reader = thread {
                while (!stop.get()) {
                    val results = runCatching { provider.search(query("atomprobe", limit = 50)) }
                        .getOrElse {
                            failures += "query errored: $it"
                            return@thread
                        }
                    val headings = results.hits.map { it.headingId }.toSet()
                    if (headings != oldIds && headings != newIds) failures += "mixed doc set: $headings"
                    if (results.total != 7L) failures += "partial page visible: total=${results.total}"
                }
            }
            repeat(25) { round ->
                val flip = round % 2 == 0
                provider.index(listOf(version(if (flip) "sha256:new" else "sha256:old", if (flip) newSections else oldSections)))
            }
            stop.set(true)
            reader.join(10_000)
            failures.toList().shouldBeEmpty()
        }
    }

    test("an aborted rebuild rolls back to nothing: queries keep the old corpus, the NEXT rebuild succeeds (no wedge)") {
        withProvider { provider, dbPath ->
            val corpusA = (1..3).map { pageDocuments(it, preamble = "genprobe alpha payload") }
            val corpusB = (11..15).map { pageDocuments(it, preamble = "genprobe beta payload") }
            provider.rebuild(corpusA.asSequence())

            // The lazy pages sequence dies after yielding two pages — mid-insert, the worst spot.
            shouldThrow<IllegalStateException> {
                provider.rebuild(
                    sequence {
                        yield(corpusB[0])
                        yield(corpusB[1])
                        error("disk full / process kill stand-in")
                    },
                )
            }

            // Rolled back to NOTHING: the old corpus answers, and no partial rows linger anywhere.
            provider.search(query("genprobe", limit = 50)).hits.map { it.pageId }.toSet() shouldBe corpusA.map { it.pageId }.toSet()
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { probe ->
                probe.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(DISTINCT generation) FROM section_doc").use { rows ->
                        rows.next()
                        rows.getLong(1) shouldBe 1L
                    }
                }
            }

            // §B4's promise: the next rebuild repairs for free — no (generation, page_id) wedge.
            provider.rebuild(corpusB.asSequence())
            val healed = provider.search(query("genprobe", limit = 50))
            healed.total shouldBe 5L
            healed.hits.map { it.pageId }.toSet() shouldBe corpusB.map { it.pageId }.toSet()
        }
    }

    test("debris rows outside the active generation are swept at rebuild start (crash-shaped databases self-repair)") {
        withProvider { provider, dbPath ->
            val corpusA = (1..3).map { pageDocuments(it, preamble = "genprobe alpha payload") }
            provider.rebuild(corpusA.asSequence())

            // Hand-commit orphans ABOVE the active generation — the shape a crash mid-commit or a
            // historical multi-transaction rebuild would leave, including a page id the next
            // rebuild re-inserts (the exact primary-key collision that used to wedge forever).
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { raw ->
                raw.createStatement().use { statement ->
                    val active = statement.executeQuery("SELECT CAST(value AS INTEGER) FROM search_meta WHERE key='active_generation'")
                        .use { rows ->
                            rows.next()
                            rows.getLong(1)
                        }
                    val orphan = active + 1
                    statement.executeUpdate(
                        "INSERT INTO search_page(generation, page_id, content_hash, path) " +
                            "SELECT $orphan, page_id, content_hash, path FROM search_page WHERE generation = $active",
                    )
                    statement.executeUpdate(
                        "INSERT INTO section_doc(generation, page_id, heading_id, status) " +
                            "SELECT $orphan, page_id, heading_id, status FROM section_doc WHERE generation = $active",
                    )
                }
            }

            provider.rebuild(corpusA.asSequence()) // would hit the (generation, page_id) PK without the sweep
            val results = provider.search(query("genprobe", limit = 50))
            results.total shouldBe 3L
            results.hits.map { it.pageId }.toSet() shouldBe corpusA.map { it.pageId }.toSet()
        }
    }

    test("an IN-PROGRESS rebuild never shifts live scores: uncommitted rows are invisible to FTS5's table-wide bm25 statistics") {
        withProvider { provider, _ ->
            val corpusA = (1..3).map { pageDocuments(it, preamble = "genprobe alpha payload") }
            // A much larger replacement corpus: if its uncommitted rows leaked into the bm25
            // statistics (document count, average length), the probe's scores would move.
            val corpusB = (11..40).map { pageDocuments(it, preamble = "genprobe beta payload word salad of considerable length") }
            provider.rebuild(corpusA.asSequence())
            val baseline = provider.search(query("genprobe", limit = 50))

            val midRebuild = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val rebuilder = thread {
                provider.rebuild(
                    sequence {
                        yieldAll(corpusB.take(20)) // 20 pages inserted, transaction still open
                        midRebuild.countDown()
                        check(resume.await(10, TimeUnit.SECONDS)) { "test deadlock" }
                        yieldAll(corpusB.drop(20))
                    },
                )
            }
            check(midRebuild.await(10, TimeUnit.SECONDS)) { "rebuild never reached the midpoint" }
            try {
                val during = provider.search(query("genprobe", limit = 50))
                during shouldBe baseline // identical hits AND identical scores, not merely the same set
            } finally {
                resume.countDown()
                rebuilder.join(10_000)
            }
            provider.search(query("genprobe", limit = 50)).total shouldBe 30L
        }
    }
})
