package com.plainbase.frameworks.search

import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.search.PageDocuments
import com.plainbase.domain.search.SearchHit
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.sqlite.SQLiteConnection
import org.sqlite.SQLiteLimits
import java.nio.file.Files
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Regression coverage for bounded, rooted exclusions during a generation rebuild. */
class Fts5RebuildExclusionTest : FunSpec({

    test("600 rooted exclusions publish the expected carried and fresh corpus under a 999-variable writer limit") {
        withOwnedProvider { provider, db ->
            val extraRoot = RootName.require("extra")
            val oldCorpus = listOf(
                pageDocuments(1, title = "Retired", preamble = "retiredtoken"),
                pageDocuments(2, title = "Carried", preamble = "carrytoken xcarryneedleymarker"),
                pageDocuments(1, root = extraRoot, title = "Other root", preamble = "crossroottoken"),
                pageDocuments(3, title = "Old superseded", preamble = "supersededtoken"),
                pageDocuments(256, title = "Boundary 256", preamble = "boundarytoken256"),
                pageDocuments(257, title = "Boundary 257", preamble = "boundarytoken257"),
                pageDocuments(512, title = "Boundary 512", preamble = "boundarytoken512"),
                pageDocuments(513, title = "Boundary 513", preamble = "boundarytoken513"),
                pageDocuments(600, title = "Boundary 600", preamble = "boundarytoken600"),
            )
            provider.rebuild(oldCorpus.asSequence())
            db.write { connection ->
                (connection as SQLiteConnection).setLimit(SQLiteLimits.SQLITE_LIMIT_VARIABLE_NUMBER, 999)
            }

            val retired = LinkedHashSet<RootedPageId>(600)
            (1..600).forEach { ordinal ->
                retired += when (ordinal) {
                    1 -> rooted(1)
                    2 -> rooted(3)
                    256 -> rooted(256)
                    257 -> rooted(257)
                    512 -> rooted(512)
                    513 -> rooted(513)
                    600 -> rooted(600)
                    else -> rooted(10_000 + ordinal)
                }
            }
            val replacement = pageDocuments(3, title = "Replacement", contentHash = "sha256:new-3", preamble = "replacementtoken")
            val fresh = pageDocuments(99, title = "Fresh", preamble = "freshnewtoken")

            provider.rebuild(sequenceOf(replacement, fresh), retired = retired)

            provider.indexedState().mapValues { (_, state) -> state.contentHash } shouldBe mapOf(
                rooted(2) to "sha256:2",
                rooted(1, extraRoot) to "sha256:1",
                rooted(3) to "sha256:new-3",
                rooted(99) to "sha256:99",
            )
            provider.search(query("retiredtoken")).total shouldBe 0L
            provider.search(query("supersededtoken")).total shouldBe 0L
            provider.search(query("boundarytoken256")).total shouldBe 0L
            provider.search(query("boundarytoken600")).total shouldBe 0L
            provider.search(query("carrytoken")).hits.map(SearchHit::rooted) shouldBe listOf(rooted(2))
            provider.search(query("crossroottoken")).hits.map(SearchHit::rooted) shouldBe listOf(rooted(1, extraRoot))
            provider.search(query("replacementtoken")).hits.map(SearchHit::rooted) shouldBe listOf(rooted(3))
            provider.search(query("freshnewtoken")).hits.map(SearchHit::rooted) shouldBe listOf(rooted(99))
            provider.search(query("ryneed")).hits.map(SearchHit::rooted) shouldBe listOf(rooted(2))
        }
    }

    test("all selected exclusion sizes preserve rooted carry, supersession, and primary/trigram search state") {
        listOf(0, 1, 255, 256, 257, 600).forEach { size ->
            withOwnedProvider { provider, db ->
                val oldCorpus = matrixCorpus()
                val incoming = listOf(
                    pageDocuments(3, title = "Matrix replacement", contentHash = "sha256:new-3", preamble = "matrixnewreplacementtoken"),
                    pageDocuments(99, title = "Matrix fresh", preamble = "matrixfreshnewtoken"),
                )
                val retired = retirementSet(size)
                db.setVariableLimitForTest()
                provider.rebuild(oldCorpus.asSequence())
                provider.rebuild(incoming.asSequence(), retired = retired)

                provider.indexedState().mapValues { (_, state) -> state.contentHash } shouldBe
                    expectedState(oldCorpus, incoming, retired)
                provider.search(query("matrixcarrytoken")).hits.map(SearchHit::rooted) shouldBe listOf(rooted(2))
                provider.search(query("matrixcrossroottoken")).hits.map(SearchHit::rooted) shouldBe
                    listOf(rooted(1, RootName.require("extra")))
                provider.search(query("matrixnewreplacementtoken")).hits.map(SearchHit::rooted) shouldBe listOf(rooted(3))
                provider.search(query("matrixfreshnewtoken")).hits.map(SearchHit::rooted) shouldBe listOf(rooted(99))
                provider.search(query("trixneedle")).hits.map(SearchHit::rooted) shouldBe listOf(rooted(2))
                provider.search(query("trixret")).total shouldBe if (rooted(1) in retired) 0L else 1L
                provider.search(query("matrixoldreplacementtoken")).total shouldBe 0L
                if (rooted(1) in retired) {
                    provider.search(query("matrixdeletedtoken")).total shouldBe 0L
                } else {
                    provider.search(query("matrixdeletedtoken")).hits.map(SearchHit::rooted) shouldBe listOf(rooted(1))
                }
                listOf(255, 256, 257, 512, 513, 600).forEach { boundary ->
                    val expected = if (rooted(boundary) in retired) 0L else 1L
                    provider.search(query("matrixboundarytoken$boundary")).total shouldBe expected
                }
            }
        }
    }

    test("helper stages exact fixed-shape batches and clears every requested size") {
        val expectedBatchSizes = mapOf(
            0 to emptyList(),
            1 to listOf(1),
            255 to listOf(255),
            256 to listOf(256),
            257 to listOf(256, 1),
            600 to listOf(256, 256, 88),
        )
        expectedBatchSizes.forEach { (size, expected) ->
            withOwnedProvider { _, db ->
                val retired = retirementSet(size)
                val preparedSql = mutableListOf<String>()
                val batchSizes = mutableListOf<Int>()
                var staged = emptySet<RootedPageId>()
                var afterClear = emptySet<RootedPageId>()
                db.write { realConnection ->
                    realConnection.transaction {
                        val recording = RecordingConnection(realConnection, preparedSql, batchSizes)
                        Fts5RebuildExclusions.stage(recording, retired)
                        staged = recording.readTempRows()
                        Fts5RebuildExclusions.clear(recording)
                        afterClear = recording.readTempRows()
                    }
                }
                staged shouldBe retired
                afterClear.shouldBeEmpty()
                batchSizes shouldBe expected
                preparedSql shouldBe if (size == 0) emptyList() else listOf(EXCLUSION_INSERT_SQL)
            }
        }
    }

    test("null is unrestricted, empty carries, and a later explicit empty set does not reuse exclusions") {
        withOwnedProvider { provider, _ ->
            val old = listOf(
                pageDocuments(1, preamble = "transition-one"),
                pageDocuments(2, preamble = "transition-two"),
            )
            provider.rebuild(old.asSequence())
            provider.rebuild(emptySequence(), retired = null)
            provider.indexedState() shouldBe emptyMap()

            provider.rebuild(old.asSequence())
            provider.rebuild(emptySequence(), retired = emptySet())
            provider.indexedState().keys shouldBe setOf(rooted(1), rooted(2))

            provider.rebuild(emptySequence(), retired = setOf(rooted(1)))
            provider.index(
                listOf(pageDocuments(1, contentHash = "sha256:reindexed", preamble = "transition-reindexed")),
            )
            provider.rebuild(emptySequence(), retired = emptySet())
            provider.indexedState().keys shouldBe setOf(rooted(1), rooted(2))
            provider.search(query("transition-reindexed")).hits.map(SearchHit::rooted) shouldBe listOf(rooted(1))
            provider.search(query("transition-two")).hits.map(SearchHit::rooted) shouldBe listOf(rooted(2))

            provider.rebuild(emptySequence(), retired = null)
            provider.indexedState() shouldBe emptyMap()
        }
    }

    test("a failed second population batch restores the sentinel and the same writer retries cleanly") {
        withOwnedProvider { provider, db ->
            val old = listOf(
                pageDocuments(1, preamble = "populationoldprimary"),
                pageDocuments(2, preamble = "populationoldsecondary xpopulationoldtrigrammarker"),
            )
            provider.rebuild(old.asSequence())
            provider.rebuild(emptySequence(), retired = emptySet())
            val before = provider.indexedState()
            val beforePrimary = provider.search(query("populationoldprimary"))
            val beforeTrigram = provider.search(query("oldtrig"))
            val sentinel = rooted(2)
            db.write { connection ->
                connection.prepareStatement(
                    "INSERT INTO temp.rebuild_exclusion(root, page_id) VALUES (?, ?)",
                ).use { statement ->
                    statement.setString(1, sentinel.root.value)
                    statement.setBytes(2, sentinel.id.toByteArray())
                    statement.executeUpdate()
                }
            }
            db.executeWriterSql(
                "CREATE TEMP TRIGGER fail_population BEFORE INSERT ON rebuild_exclusion " +
                    "WHEN (SELECT count(*) FROM rebuild_exclusion) >= 256 BEGIN " +
                    "SELECT RAISE(ABORT, 'injected population failure'); END",
            )

            try {
                val failure = shouldThrow<Exception> {
                    provider.rebuild(
                        sequenceOf(pageDocuments(1, preamble = "populationattemptednew xpopulationattemptedtrigrammarker")),
                        retired = retirementSet(257),
                    )
                }
                failure.message.orEmpty() shouldContain "injected population failure"
                provider.indexedState() shouldBe before
                provider.search(query("populationoldprimary")) shouldBe beforePrimary
                provider.search(query("oldtrig")) shouldBe beforeTrigram
                provider.search(query("populationattemptednew")).total shouldBe 0L
                provider.search(query("attemptedtrig")).total shouldBe 0L
                db.readWriterTempRows() shouldBe setOf(sentinel)
            } finally {
                db.executeWriterSqlIfPresent("DROP TRIGGER fail_population")
            }

            provider.rebuild(emptySequence(), retired = emptySet())
            provider.indexedState() shouldBe before
            provider.search(query("populationoldprimary")) shouldBe beforePrimary
            provider.search(query("oldtrig")) shouldBe beforeTrigram
            provider.search(query("populationoldsecondary")).hits.map(SearchHit::rooted) shouldBe listOf(rooted(2))
            db.readWriterTempRows().shouldBeEmpty()

            provider.rebuild(
                sequenceOf(pageDocuments(3, preamble = "populationretryfresh")),
                retired = setOf(rooted(2)),
            )
            provider.indexedState().keys shouldBe setOf(rooted(1), rooted(3))
            provider.search(query("populationoldprimary")).hits.map(SearchHit::rooted) shouldBe listOf(rooted(1))
            provider.search(query("populationoldsecondary")).total shouldBe 0L
            provider.search(query("populationretryfresh")).hits.map(SearchHit::rooted) shouldBe listOf(rooted(3))
            db.readWriterTempRows().shouldBeEmpty()
        }
    }

    test("a publication failure after carry rolls back the old corpus and a different-set retry commits") {
        withOwnedProvider { provider, db ->
            val old = listOf(
                pageDocuments(1, preamble = "publicationoldprimary"),
                pageDocuments(2, preamble = "publicationoldsecondary xpublicationoldtrigrammarker"),
                pageDocuments(3, preamble = "publication-old-three"),
            )
            provider.rebuild(old.asSequence())
            val before = provider.indexedState()
            val beforePrimary = provider.search(query("publicationoldprimary"))
            val beforeTrigram = provider.search(query("oldtrig"))
            db.executeWriterSql(
                "CREATE TEMP TRIGGER fail_publication BEFORE UPDATE ON main.search_meta " +
                    "WHEN NEW.key = 'active_generation' BEGIN " +
                    "SELECT RAISE(ABORT, 'injected publication failure'); END",
            )
            val replacement = pageDocuments(
                1,
                contentHash = "sha256:publication-new",
                preamble = "publicationnewprimary xpublicationnewtrigrammarker",
            )

            try {
                val failure = shouldThrow<Exception> {
                    provider.rebuild(sequenceOf(replacement), retired = setOf(rooted(2)))
                }
                failure.message.orEmpty() shouldContain "injected publication failure"
                provider.indexedState() shouldBe before
                provider.search(query("publicationoldprimary")) shouldBe beforePrimary
                provider.search(query("oldtrig")) shouldBe beforeTrigram
                provider.search(query("publicationnewprimary")).total shouldBe 0L
                provider.search(query("newtrig")).total shouldBe 0L
                provider.search(query("publicationoldsecondary")).total shouldBe 1L
                db.write { connection ->
                    connection.distinctGenerations("section_doc") shouldBe listOf(connection.activeGeneration())
                    connection.distinctGenerations("search_page") shouldBe listOf(connection.activeGeneration())
                }
            } finally {
                db.executeWriterSqlIfPresent("DROP TRIGGER fail_publication")
            }

            provider.rebuild(sequenceOf(replacement), retired = emptySet())
            provider.indexedState().keys shouldBe setOf(rooted(1), rooted(2), rooted(3))
            provider.search(query("publicationnewprimary")).hits.map(SearchHit::rooted) shouldBe listOf(rooted(1))
            provider.search(query("newtrig")).hits.map(SearchHit::rooted) shouldBe listOf(rooted(1))
            provider.search(query("publicationoldsecondary")).hits.map(SearchHit::rooted) shouldBe listOf(rooted(2))
            provider.search(query("oldtrig")).hits.map(SearchHit::rooted) shouldBe listOf(rooted(2))
            db.readWriterTempRows().shouldBeEmpty()
        }
    }

    test("the first failed explicit rebuild rolls back TEMP creation and the next explicit rebuild recreates it") {
        withOwnedProvider { provider, db ->
            provider.index(listOf(pageDocuments(1, preamble = "creation-old")))
            shouldThrow<IllegalStateException> {
                provider.rebuild(
                    sequence {
                        yield(pageDocuments(2, preamble = "creation-partial"))
                        error("sequence failure after staging")
                    },
                    retired = setOf(rooted(1), rooted(2001)),
                )
            }
            provider.search(query("creation-old")).total shouldBe 1L
            db.writerTempTableExists() shouldBe false

            provider.rebuild(emptySequence(), retired = emptySet())
            provider.search(query("creation-old")).total shouldBe 1L
            db.writerTempTableExists() shouldBe true
            db.readWriterTempRows().shouldBeEmpty()
        }
    }

    test("a reader during an explicit rebuild observes the complete old result until the commit") {
        withOwnedProvider { provider, _ ->
            provider.rebuild(
                sequenceOf(
                    pageDocuments(1, preamble = "reader-old-one"),
                    pageDocuments(2, preamble = "reader-old-two"),
                    pageDocuments(4, preamble = "reader-survivor"),
                ),
            )
            val baseline = provider.search(query("reader-old", limit = 20))
            val inserted = CountDownLatch(1)
            val release = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val worker = thread(start = true, name = "fts5-explicit-rebuild") {
                runCatching {
                    provider.rebuild(
                        sequence {
                            yield(pageDocuments(1, preamble = "reader-new-one"))
                            inserted.countDown()
                            check(release.await(10, TimeUnit.SECONDS)) { "reader rebuild release timed out" }
                            yield(pageDocuments(3, preamble = "reader-new-three"))
                        },
                        retired = setOf(rooted(2)),
                    )
                }.onFailure(failure::set)
            }

            var operationFailure: Throwable? = null
            try {
                check(inserted.await(10, TimeUnit.SECONDS)) { "rebuild did not reach its paused insert" }
                provider.search(query("reader-old", limit = 20)) shouldBe baseline
            } catch (error: Throwable) {
                operationFailure = error
            } finally {
                release.countDown()
                worker.join(10_000)
                worker.isAlive shouldBe false
            }
            operationFailure?.let { throw it }
            failure.get()?.let { throw AssertionError("explicit rebuild failed", it) }
            provider.search(query("reader-old")).total shouldBe 0L
            provider.search(query("reader-new-one")).hits.map(SearchHit::rooted) shouldBe listOf(rooted(1))
            provider.search(query("reader-new-three")).hits.map(SearchHit::rooted) shouldBe listOf(rooted(3))
            provider.search(query("reader-survivor")).hits.map(SearchHit::rooted) shouldBe listOf(rooted(4))
        }
    }

    test("a reader transaction keeps its old WAL snapshot across the writer commit") {
        withOwnedProvider { provider, db ->
            provider.rebuild(sequenceOf(pageDocuments(1, preamble = "snapshot-old")))
            val writerDone = CountDownLatch(1)
            val writerFailure = AtomicReference<Throwable?>()
            var snapshotWorker: Thread? = null
            var oldGeneration = 0L
            var duringGeneration = 0L
            var newGeneration = 0L
            var oldRows = emptySet<RootedPageId>()
            var duringRows = emptySet<RootedPageId>()
            var newRows = emptySet<RootedPageId>()

            db.read { reader ->
                val originalAutoCommit = reader.autoCommit
                reader.autoCommit = false
                try {
                    oldGeneration = reader.activeGeneration()
                    oldRows = reader.pageRows(oldGeneration)
                    snapshotWorker = thread(start = true, name = "fts5-snapshot-rebuild") {
                        runCatching {
                            provider.rebuild(sequenceOf(pageDocuments(2, preamble = "snapshot-new")), retired = emptySet())
                        }.onFailure(writerFailure::set)
                        writerDone.countDown()
                    }
                    check(writerDone.await(10, TimeUnit.SECONDS)) { "writer did not commit" }
                    duringGeneration = reader.activeGeneration()
                    duringRows = reader.pageRows(duringGeneration)
                    reader.commit()
                    reader.autoCommit = true
                    newGeneration = reader.activeGeneration()
                    newRows = reader.pageRows(newGeneration)
                } catch (failure: Throwable) {
                    runCatching { reader.rollback() }
                    throw failure
                } finally {
                    try {
                        snapshotWorker?.let { worker ->
                            worker.join(10_000)
                            worker.isAlive shouldBe false
                        }
                    } finally {
                        try {
                            if (!reader.autoCommit) runCatching { reader.rollback() }
                        } finally {
                            if (reader.autoCommit != originalAutoCommit) reader.autoCommit = originalAutoCommit
                        }
                    }
                }
            }

            writerFailure.get()?.let { throw AssertionError("snapshot rebuild failed", it) }
            oldGeneration shouldBe duringGeneration
            oldRows shouldBe duringRows
            newGeneration shouldBe oldGeneration + 1
            newRows shouldBe setOf(rooted(1), rooted(2))
        }
    }

    test("TEMP exclusion state is writer-local, transient across reopen, and empty after publication") {
        val dir = Files.createTempDirectory("plainbase-rebuild-locality-test")
        val path = dir.resolve("search.db")
        try {
            SearchDb(path).use { db ->
                val provider = Fts5SearchProvider(db)
                provider.rebuild(sequenceOf(pageDocuments(1, preamble = "locality-token")), retired = emptySet())
                db.write { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery(
                            "SELECT count(*) FROM sqlite_temp_master WHERE type='table' AND name='rebuild_exclusion'",
                        ).use { rows ->
                            rows.next()
                            rows.getLong(1) shouldBe 1L
                        }
                        statement.executeQuery("SELECT count(*) FROM temp.rebuild_exclusion").use { rows ->
                            rows.next()
                            rows.getLong(1) shouldBe 0L
                        }
                        statement.executeQuery("SELECT count(*) FROM main.sqlite_master WHERE name='rebuild_exclusion'").use { rows ->
                            rows.next()
                            rows.getLong(1) shouldBe 0L
                        }
                    }
                }
                db.read { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery(
                            "SELECT count(*) FROM sqlite_temp_master WHERE type='table' AND name='rebuild_exclusion'",
                        ).use { rows ->
                            rows.next()
                            rows.getLong(1) shouldBe 0L
                        }
                    }
                }
            }

            SearchDb(path).use { db ->
                val provider = Fts5SearchProvider(db)
                provider.search(query("locality-token")).total shouldBe 1L
                db.writerTempTableExists() shouldBe false
                provider.rebuild(emptySequence(), retired = emptySet())
                db.writerTempTableExists() shouldBe true
                db.readWriterTempRows().shouldBeEmpty()
                provider.search(query("locality-token")).total shouldBe 1L
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
})

private fun SearchHit.rooted(): RootedPageId = RootedPageId(root, pageId)

private const val EXCLUSION_INSERT_SQL = "INSERT INTO temp.rebuild_exclusion(root, page_id) VALUES (?, ?)"

private inline fun <T> withOwnedProvider(block: (Fts5SearchProvider, SearchDb) -> T): T {
    val dir = Files.createTempDirectory("plainbase-rebuild-exclusion-test")
    val path = dir.resolve("search.db")
    return try {
        SearchDb(path).use { db -> block(Fts5SearchProvider(db), db) }
    } finally {
        dir.toFile().deleteRecursively()
    }
}

private fun SearchDb.setVariableLimitForTest() {
    write { connection ->
        (connection as SQLiteConnection).setLimit(SQLiteLimits.SQLITE_LIMIT_VARIABLE_NUMBER, 999)
    }
}

private fun matrixCorpus(): List<PageDocuments> = listOf(
    pageDocuments(1, contentHash = "sha256:deleted-1", preamble = "matrixdeletedtoken xmatrixretiredneedleymarker"),
    pageDocuments(2, contentHash = "sha256:carry-2", preamble = "matrixcarrytoken xmatrixneedleymarker"),
    pageDocuments(1, root = RootName.require("extra"), contentHash = "sha256:cross-root-1", preamble = "matrixcrossroottoken"),
    pageDocuments(3, contentHash = "sha256:old-3", preamble = "matrixoldreplacementtoken"),
    pageDocuments(255, contentHash = "sha256:boundary-255", preamble = "matrixboundarytoken255"),
    pageDocuments(256, contentHash = "sha256:boundary-256", preamble = "matrixboundarytoken256"),
    pageDocuments(257, contentHash = "sha256:boundary-257", preamble = "matrixboundarytoken257"),
    pageDocuments(512, contentHash = "sha256:boundary-512", preamble = "matrixboundarytoken512"),
    pageDocuments(513, contentHash = "sha256:boundary-513", preamble = "matrixboundarytoken513"),
    pageDocuments(600, contentHash = "sha256:boundary-600", preamble = "matrixboundarytoken600"),
)

private fun retirementSet(size: Int): LinkedHashSet<RootedPageId> {
    val special = mapOf(
        1 to rooted(1),
        2 to rooted(3),
        255 to rooted(255),
        256 to rooted(256),
        257 to rooted(257),
        512 to rooted(512),
        513 to rooted(513),
        600 to rooted(600),
    )
    return LinkedHashSet<RootedPageId>(size).apply {
        (1..size).forEach { ordinal -> add(special[ordinal] ?: rooted(10_000 + ordinal)) }
    }
}

private fun expectedState(
    old: List<PageDocuments>,
    incoming: List<PageDocuments>,
    retired: Set<RootedPageId>,
): Map<RootedPageId, String> {
    val incomingKeys = incoming.map { RootedPageId(it.root, it.pageId) }.toSet()
    val carried = old.filter {
        val key = RootedPageId(it.root, it.pageId)
        key !in retired && key !in incomingKeys
    }
    return (carried + incoming).associate { RootedPageId(it.root, it.pageId) to it.contentHash }
}

private fun SearchDb.executeWriterSql(sql: String) {
    write { connection -> connection.createStatement().use { it.executeUpdate(sql) } }
}

private fun SearchDb.executeWriterSqlIfPresent(sql: String) {
    runCatching { executeWriterSql(sql) }
}

private fun SearchDb.readWriterTempRows(): Set<RootedPageId> = write { connection -> connection.readTempRows() }

private fun SearchDb.writerTempTableExists(): Boolean = write { connection ->
    connection.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT count(*) FROM sqlite_temp_master WHERE type='table' AND name='rebuild_exclusion'",
        ).use { rows ->
            rows.next()
            rows.getLong(1) == 1L
        }
    }
}

private fun Connection.readTempRows(): Set<RootedPageId> =
    createStatement().use { statement ->
        statement.executeQuery("SELECT root, page_id FROM temp.rebuild_exclusion").use { rows ->
            buildSet {
                while (rows.next()) add(RootedPageId(RootName.require(rows.getString(1)), PageId.fromByteArray(rows.getBytes(2))))
            }
        }
    }

private fun Connection.activeGeneration(): Long =
    createStatement().use { statement ->
        statement.executeQuery("SELECT CAST(value AS INTEGER) FROM search_meta WHERE key='active_generation'").use { rows ->
            rows.next()
            rows.getLong(1)
        }
    }

private fun Connection.pageRows(generation: Long): Set<RootedPageId> =
    prepareStatement("SELECT root, page_id FROM search_page WHERE generation = ?").use { statement ->
        statement.setLong(1, generation)
        statement.executeQuery().use { rows ->
            buildSet {
                while (rows.next()) add(RootedPageId(RootName.require(rows.getString(1)), PageId.fromByteArray(rows.getBytes(2))))
            }
        }
    }

private fun Connection.distinctGenerations(table: String): List<Long> =
    createStatement().use { statement ->
        statement.executeQuery("SELECT DISTINCT generation FROM $table ORDER BY generation").use { rows ->
            buildList { while (rows.next()) add(rows.getLong(1)) }
        }
    }

private class RecordingConnection(
    private val delegate: Connection,
    private val preparedSql: MutableList<String>,
    private val batchSizes: MutableList<Int>,
) : Connection by delegate {

    override fun prepareStatement(sql: String): PreparedStatement {
        val statement = delegate.prepareStatement(sql)
        return if (sql == EXCLUSION_INSERT_SQL) {
            preparedSql += sql
            RecordingPreparedStatement(statement, batchSizes)
        } else {
            statement
        }
    }
}

private class RecordingPreparedStatement(
    private val delegate: PreparedStatement,
    private val batchSizes: MutableList<Int>,
) : PreparedStatement by delegate {

    private var queued = 0

    override fun addBatch() {
        delegate.addBatch()
        queued++
    }

    override fun executeBatch(): IntArray {
        batchSizes += queued
        queued = 0
        return delegate.executeBatch()
    }

    override fun clearBatch() {
        delegate.clearBatch()
        queued = 0
    }
}
