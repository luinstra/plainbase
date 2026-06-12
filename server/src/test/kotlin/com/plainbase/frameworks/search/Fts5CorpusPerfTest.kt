package com.plainbase.frameworks.search

import com.plainbase.domain.service.IndexHarness
import com.plainbase.domain.service.SectionSplitter
import com.plainbase.domain.service.pageContent
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis

/**
 * Master criterion 2 over the 1,000-page generated corpus (the Phase-1 generator, reused by
 * plan): warm server-side query p95 < 200 ms, with the R14 perf-flake policy — measure AFTER
 * a >= 50-query warmup, assert p95 over >= 200 samples, auto-retry the measurement ONCE before
 * failing (a second failure is a real regression, never silently quarantined).
 *
 * Also records (risk R15) the index build time and the trigram side-index's share of it: the
 * trigram rows are deleted and re-inserted from `section_fts`'s stored columns, timing the write
 * cost, and the file-size delta is measured across a VACUUM. Printed to the test output — these
 * are recorded measurements, not assertions.
 */
class Fts5CorpusPerfTest : FunSpec({

    test("1,000-page corpus: build time + trigram delta recorded; warm query p95 < 200 ms (R14 policy)") {
        val pageCount = 1000
        withTempTree(seed = { root ->
            repeat(pageCount) { n -> writePage(root, "section-%02d/page-%03d.md".format(n % 10, n), pageContent(n)) }
        }) { root ->
            IndexHarness(root).use { harness ->
                val snapshot = harness.builder.rebuild()
                snapshot.pages.size shouldBe pageCount
                val splitter = SectionSplitter()
                val documents = snapshot.pages.map(splitter::split)

                withProvider { provider, dbPath ->
                    val buildMillis = measureTimeMillis { provider.rebuild(documents.asSequence()) }

                    val queries = listOf("page", "body text", "details", "sibling", "page 042", "text", "more", "link", "section", "deplo")
                    fun p95(samples: Int): Double {
                        val times = (0 until samples).map { i ->
                            measureNanoTime { provider.search(query(queries[i % queries.size], limit = 20)) } / 1e6
                        }
                        return times.sorted()[(samples * 95) / 100]
                    }
                    repeat(50) { provider.search(query(queries[it % queries.size])) } // warmup, discarded
                    var p95Millis = p95(200)
                    if (p95Millis >= 200.0) p95Millis = p95(200) // R14: one auto-retry, then it is real

                    // R15 trigram cost: re-populate the side-index from section_fts's stored columns
                    // (same rows, same rowids) to time the write share; size delta across a VACUUM.
                    val trigram = DriverManager.getConnection("jdbc:sqlite:$dbPath").use { raw ->
                        raw.createStatement().use { statement ->
                            // The db runs WAL: checkpoint after each VACUUM or Files.size sees a stale main file.
                            fun compactedSize(): Long {
                                statement.execute("VACUUM")
                                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                                return Files.size(dbPath)
                            }
                            val fullSize = compactedSize()
                            statement.executeUpdate("DELETE FROM section_trigram")
                            val strippedSize = compactedSize()
                            val repopulateMillis = measureTimeMillis {
                                statement.executeUpdate(
                                    "INSERT INTO section_trigram(rowid, title, body) SELECT rowid, title, body FROM section_fts",
                                )
                            }
                            Triple(fullSize, fullSize - strippedSize, repopulateMillis)
                        }
                    }

                    // The recorded measurements (plan S2 acceptance: "recorded in the test output").
                    println("search-perf: 1000-page corpus, ${documents.sumOf { it.sections.size }} section docs")
                    println("search-perf: index build %d ms; trigram repopulate (INSERT..SELECT) %d ms".format(buildMillis, trigram.third))
                    println("search-perf: search.db %d KiB, trigram share %d KiB".format(trigram.first / 1024, trigram.second / 1024))
                    println("search-perf: warm query p95 %.2f ms over 200 samples".format(p95Millis))

                    p95Millis shouldBeLessThan 200.0
                }
            }
        }
    }
})
