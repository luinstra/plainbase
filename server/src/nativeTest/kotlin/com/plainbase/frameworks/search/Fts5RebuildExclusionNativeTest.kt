package com.plainbase.frameworks.search

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.search.PageDocuments
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.search.SectionDocument
import org.junit.jupiter.api.Tag
import org.sqlite.SQLiteConnection
import org.sqlite.SQLiteLimits
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Native-image coverage for the bounded TEMP JDBC path and its same-writer rollback/retry contract. */
@Tag("native")
class Fts5RebuildExclusionNativeTest {

    @Test
    fun `600 rooted exclusions and null-empty distinction work in the native image`() {
        val dir = Files.createTempDirectory("pb-native-rebuild-exclusion")
        try {
            SearchDb(dir.resolve("search.db")).use { db ->
                val provider = Fts5SearchProvider(db)
                val extra = RootName.require("extra")
                provider.rebuild(
                    sequenceOf(
                        page(1, "native deleted", "native-deleted-token"),
                        page(2, "native carried", "native-carried-token xnative-substring", root = RootName.PRIMARY),
                        page(1, "native cross root", "native-cross-root-token", root = extra),
                        page(3, "native old replacement", "native-old-replacement-token"),
                        page(256, "native boundary", "native-boundary-256"),
                        page(257, "native boundary", "native-boundary-257"),
                        page(600, "native boundary", "native-boundary-600"),
                    ),
                )
                db.write { connection ->
                    (connection as SQLiteConnection).setLimit(SQLiteLimits.SQLITE_LIMIT_VARIABLE_NUMBER, 999)
                }
                assertVariableLimitIs999(db)

                val retired = LinkedHashSet<RootedPageId>(600)
                (1..600).forEach { ordinal ->
                    retired += when (ordinal) {
                        1 -> rooted(1)
                        2 -> rooted(3)
                        256 -> rooted(256)
                        257 -> rooted(257)
                        600 -> rooted(600)
                        else -> rooted(20_000 + ordinal)
                    }
                }
                provider.rebuild(
                    sequenceOf(
                        page(3, "native replacement", "native-new-replacement-token"),
                        page(99, "native fresh", "native-fresh-token"),
                    ),
                    retired = retired,
                )

                assertEquals(
                    setOf(rooted(2), rooted(1, extra), rooted(3), rooted(99)),
                    provider.indexedState().keys,
                )
                assertEquals(0L, provider.search(SearchQuery("native-deleted-token", 20, 0)).total)
                assertEquals(0L, provider.search(SearchQuery("native-boundary-257", 20, 0)).total)
                assertEquals(1L, provider.search(SearchQuery("native-carried-token", 20, 0)).total)
                assertEquals(1L, provider.search(SearchQuery("native-substring", 20, 0)).total)
                assertEquals(1L, provider.search(SearchQuery("native-new-replacement-token", 20, 0)).total)

                provider.rebuild(emptySequence(), retired = emptySet())
                assertEquals(
                    setOf(rooted(2), rooted(1, extra), rooted(3), rooted(99)),
                    provider.indexedState().keys,
                )
                assertEquals(1L, provider.search(SearchQuery("native-carried-token", 20, 0)).total)

                provider.rebuild(emptySequence(), retired = null)
                assertEquals(emptyMap(), provider.indexedState())
                provider.rebuild(
                    sequenceOf(page(2, "native carried again", "native-carried-again")),
                    retired = emptySet(),
                )
                assertEquals(setOf(rooted(2)), provider.indexedState().keys)
            }
        } finally {
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `metadata publication failure rolls back TEMP rows and different-set retries succeed natively`() {
        val dir = Files.createTempDirectory("pb-native-rebuild-retry")
        try {
            SearchDb(dir.resolve("search.db")).use { db ->
                val provider = Fts5SearchProvider(db)
                provider.rebuild(
                    sequenceOf(
                        page(1, "native stable", "native-stable-primary xnativeoldtrigrammarker"),
                        page(2, "native secondary", "native-secondary-primary"),
                    ),
                    retired = emptySet(),
                )
                val before = provider.indexedState()
                val beforePrimary = provider.search(SearchQuery("native-stable-primary", 20, 0))
                val beforeTrigram = provider.search(SearchQuery("oldtrig", 20, 0))
                val beforeSecondary = provider.search(SearchQuery("native-secondary-primary", 20, 0))
                db.write { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeUpdate(
                            "CREATE TEMP TRIGGER fail_native_publication BEFORE UPDATE ON main.search_meta " +
                                "WHEN NEW.key = 'active_generation' BEGIN " +
                                "SELECT RAISE(ABORT, 'native publication failure'); END",
                        )
                    }
                }

                try {
                    val failure = assertFailsWith<Exception> {
                        provider.rebuild(
                            sequenceOf(page(1, "native attempted", "native-attempted-primary xnativeattemptedtrigrammarker")),
                            retired = setOf(rooted(1)),
                        )
                    }
                    assertTrue(failure.message.orEmpty().contains("native publication failure"))
                    assertEquals(before, provider.indexedState())
                    assertEquals(beforePrimary, provider.search(SearchQuery("native-stable-primary", 20, 0)))
                    assertEquals(beforeTrigram, provider.search(SearchQuery("oldtrig", 20, 0)))
                    assertEquals(beforeSecondary, provider.search(SearchQuery("native-secondary-primary", 20, 0)))
                    assertEquals(0L, provider.search(SearchQuery("native-attempted-primary", 20, 0)).total)
                    assertEquals(0L, provider.search(SearchQuery("attemptedtrig", 20, 0)).total)
                    assertEquals(0L, tempRowCount(db))
                } finally {
                    db.write { connection ->
                        connection.createStatement().use { statement -> statement.executeUpdate("DROP TRIGGER fail_native_publication") }
                    }
                }

                provider.rebuild(emptySequence(), retired = emptySet())
                assertEquals(before, provider.indexedState())
                assertEquals(beforePrimary, provider.search(SearchQuery("native-stable-primary", 20, 0)))
                assertEquals(beforeTrigram, provider.search(SearchQuery("oldtrig", 20, 0)))
                assertEquals(beforeSecondary, provider.search(SearchQuery("native-secondary-primary", 20, 0)))
                assertEquals(0L, provider.search(SearchQuery("native-attempted-primary", 20, 0)).total)
                assertEquals(0L, provider.search(SearchQuery("attemptedtrig", 20, 0)).total)
                assertEquals(0L, tempRowCount(db))

                provider.rebuild(
                    sequenceOf(page(3, "native retry fresh", "native-retry-fresh")),
                    retired = setOf(rooted(2)),
                )
                assertEquals(setOf(rooted(1), rooted(3)), provider.indexedState().keys)
                assertEquals(1L, provider.search(SearchQuery("native-stable-primary", 20, 0)).total)
                assertEquals(0L, provider.search(SearchQuery("native-secondary-primary", 20, 0)).total)
                assertEquals(1L, provider.search(SearchQuery("native-retry-fresh", 20, 0)).total)
                assertEquals(0L, tempRowCount(db))
            }
        } finally {
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun assertVariableLimitIs999(db: SearchDb) {
        val failure = assertFailsWith<Exception> {
            db.write { connection ->
                val sql = "SELECT " + List(1000) { "?" }.joinToString(",")
                connection.prepareStatement(sql).use { }
            }
        }
        assertTrue(failure.message.orEmpty().contains("too many SQL variables"))
    }

    private fun tempRowCount(db: SearchDb): Long = db.write { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM temp.rebuild_exclusion").use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }
    }

    private fun page(idNumber: Int, title: String, body: String, root: RootName = RootName.PRIMARY): PageDocuments {
        val id = pageId(idNumber)
        val path = TreePath.require("native/$idNumber.md")
        val section = SectionDocument(
            pageId = id,
            headingId = null,
            title = title,
            heading = null,
            headingPath = emptyList(),
            body = body,
            tags = emptyList(),
            owner = null,
            aliases = emptyList(),
            path = path,
            status = "active",
        )
        return PageDocuments(
            pageId = id,
            contentHash = "sha256:$title",
            root = root,
            path = path,
            sections = listOf(section),
        )
    }

    private fun pageId(number: Int): PageId = PageId.require("0197aaaa-0000-7000-8000-%012x".format(number))

    private fun rooted(number: Int, root: RootName = RootName.PRIMARY) = RootedPageId(root, pageId(number))
}
