package com.plainbase.frameworks.search

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.search.Highlight
import com.plainbase.domain.search.PageDocuments
import com.plainbase.domain.search.PageSearchState
import com.plainbase.domain.search.SearchHit
import com.plainbase.domain.search.SearchProvider
import com.plainbase.domain.search.SearchQuery
import com.plainbase.domain.search.SearchResults
import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement

/**
 * The embedded engine: SQLite FTS5 over [SearchDb] (§B5 in full). What each port operation maps to:
 *
 *  - [index]/[delete]: one transaction PER PAGE against the active generation — a concurrent query
 *    sees a page's document set entirely old or entirely new, never half (§B4 per-page atomicity).
 *  - [rebuild]: generation swap as ONE write transaction — insert every generation-N+1 row, flip
 *    `active_generation`, GC, commit. Under WAL, readers never observe uncommitted writer rows,
 *    which buys three §B4 guarantees at once: an in-progress rebuild is invisible even to FTS5's
 *    TABLE-WIDE bm25 statistics (concurrent scores cannot drift while a rebuild runs); a rebuild
 *    that dies anywhere rolls back to nothing, so the next rebuild repairs for free (never a
 *    wedge on the (generation, page_id) primary key); and partial/duplicate corpora are
 *    impossible. An in-flight read transaction holding the pre-swap WAL snapshot still sees its
 *    complete generation.
 *  - [search]: hits and total run inside ONE deferred read transaction — the first SELECT
 *    establishes the WAL snapshot, the count reads the SAME snapshot and the same
 *    `active_generation` subselect, so a hits/total pair can never mix generations across a
 *    concurrent swap (the Iteration-2 BLOCKING-1 resolution). When the primary MATCH yields zero
 *    hits, the trigram side-index answers instead (substring semantics — the §B5 CJK rescue),
 *    inside the same transaction; the response shape is identical and the fallback never fires
 *    when the primary has hits.
 *  - [indexedState]: the engine-truth diff base, read from `search_page` on the writer connection
 *    (it belongs to the serialized sync path, like every write).
 *
 * Scores are negated bm25 (FTS5 reports better-is-more-negative; the wire promises higher=better)
 * with the [WEIGHT_TITLE]…[WEIGHT_OWNER] column weights — tier-2 pinned-but-reviewable values,
 * tuned against the BM25 golden query set, explicitly NOT frozen (§A6). Ordering is fully
 * deterministic via the explicit tiebreak (score DESC, page_id, heading_id — §A4).
 */
class Fts5SearchProvider(private val db: SearchDb) : SearchProvider {

    override fun index(pages: List<PageDocuments>) = db.write { connection ->
        pages.forEach { page ->
            connection.transaction {
                val generation = connection.activeGeneration()
                connection.deletePage(generation, page.pageId)
                connection.insertPage(generation, page)
            }
        }
    }

    override fun delete(ids: Collection<PageId>) = db.write { connection ->
        ids.forEach { id ->
            connection.transaction { connection.deletePage(connection.activeGeneration(), id) }
        }
    }

    override fun rebuild(pages: Sequence<PageDocuments>) = db.write { connection ->
        val published = connection.transaction {
            val active = connection.activeGeneration()
            // Belt-and-braces: rows outside the active generation can only be debris (a crash
            // mid-commit, or a database written by the historical multi-transaction rebuild).
            // Clearing them first means the (generation, page_id) key below can never collide.
            connection.deleteGenerations("!= ?", active)
            val next = active + 1
            pages.forEach { page -> connection.insertPage(next, page) }
            connection.prepareStatement("UPDATE search_meta SET value = ? WHERE key = 'active_generation'").use { statement ->
                statement.setString(1, next.toString())
                statement.executeUpdate()
            }
            // §B5's letter says "GC deletes generations < N+1 afterwards"; folding the GC into the
            // swap transaction satisfies its intent (only the active generation survives a rebuild)
            // while also closing the crash-after-flip-before-GC window the two-step letter leaves.
            connection.deleteGenerations("< ?", next)
            next
        }
        logger.debug { "search rebuild published generation $published" }
    }

    override fun indexedState(): Map<PageId, PageSearchState> = db.write { connection ->
        connection.prepareStatement("SELECT page_id, content_hash, path FROM search_page WHERE generation = ?").use { statement ->
            statement.setLong(1, connection.activeGeneration())
            statement.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) {
                        val state = PageSearchState(contentHash = rows.getString(2), path = TreePath.require(rows.getString(3)))
                        put(PageId.fromByteArray(rows.getBytes(1)), state)
                    }
                }
            }
        }
    }

    override fun search(query: SearchQuery): SearchResults {
        val primaryMatch = MatchExpression.primary(query.text) ?: return SearchResults(0, emptyList())
        if (query.statusFilter?.isEmpty() == true) return SearchResults(0, emptyList())
        return db.read { connection ->
            connection.transaction {
                val primary = connection.runQuery(PRIMARY_INDEX, primaryMatch, query)
                when {
                    primary.total > 0L -> primary
                    else -> MatchExpression.trigram(query.text)?.let { connection.runQuery(TRIGRAM_INDEX, it, query) } ?: primary
                }
            }
        }
    }

    /** One fts table + its bm25 weight vector (positionally matching the table's columns). */
    private class FtsIndex(val table: String, val weights: String)

    private fun Connection.runQuery(index: FtsIndex, match: String, query: SearchQuery): SearchResults {
        val statusPredicate = query.statusFilter?.let { "AND d.status IN (${it.joinToString(", ") { "?" }})" }.orEmpty()
        val from = "FROM ${index.table} JOIN section_doc d ON d.doc_id = ${index.table}.rowid"
        val where = """
            WHERE ${index.table} MATCH ?
              AND d.generation = (SELECT CAST(value AS INTEGER) FROM search_meta WHERE key = 'active_generation')
              $statusPredicate
        """.trimIndent()

        val hits = prepareStatement(
            """
            SELECT d.page_id, d.heading_id,
                   -bm25(${index.table}, ${index.weights}) AS score,
                   snippet(${index.table}, -1, char(1), char(2), '…', $SNIPPET_TOKENS) AS snip
            $from
            $where
            ORDER BY score DESC, d.page_id, d.heading_id
            LIMIT ? OFFSET ?
            """.trimIndent(),
        ).use { statement ->
            var p = bindMatchAndStatus(statement, match, query)
            statement.setInt(p++, query.limit)
            statement.setInt(p, query.offset)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val (snippet, highlights) = SnippetMarkers.toHighlights(rows.getString(4))
                        add(
                            SearchHit(
                                pageId = PageId.fromByteArray(rows.getBytes(1)),
                                headingId = rows.getString(2),
                                snippet = snippet,
                                highlights = highlights,
                                score = rows.getDouble(3),
                            ),
                        )
                    }
                }
            }
        }

        // Same transaction, same WAL snapshot, same generation subselect: total can never come
        // from a different generation than the hits above (§B5 / Iteration-2 BLOCKING-1).
        val total = prepareStatement("SELECT count(*) $from\n$where").use { statement ->
            bindMatchAndStatus(statement, match, query)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }
        return SearchResults(total = total, hits = hits)
    }

    /** Binds the shared predicate parameters; returns the next free parameter index. */
    private fun bindMatchAndStatus(statement: PreparedStatement, match: String, query: SearchQuery): Int {
        var p = 1
        statement.setString(p++, match)
        query.statusFilter?.forEach { statement.setString(p++, it) }
        return p
    }

    private fun Connection.activeGeneration(): Long =
        createStatement().use { statement ->
            statement.executeQuery("SELECT CAST(value AS INTEGER) FROM search_meta WHERE key = 'active_generation'").use { rows ->
                if (rows.next()) rows.getLong(1) else 0L
            }
        }

    private fun Connection.insertPage(generation: Long, page: PageDocuments) {
        prepareStatement("INSERT INTO search_page(generation, page_id, content_hash, path) VALUES (?, ?, ?, ?)").use { statement ->
            statement.setLong(1, generation)
            statement.setBytes(2, page.pageId.toByteArray())
            statement.setString(3, page.contentHash)
            statement.setString(4, page.path.value)
            statement.executeUpdate()
        }
        val insertDoc = prepareStatement(
            "INSERT INTO section_doc(generation, page_id, heading_id, status) VALUES (?, ?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS,
        )
        val insertFts =
            prepareStatement("INSERT INTO section_fts(rowid, title, heading, body, tags, aliases, owner) VALUES (?, ?, ?, ?, ?, ?, ?)")
        val insertTrigram = prepareStatement("INSERT INTO section_trigram(rowid, title, body) VALUES (?, ?, ?)")
        insertDoc.use {
            insertFts.use {
                insertTrigram.use {
                    page.sections.forEach { section ->
                        insertDoc.setLong(1, generation)
                        insertDoc.setBytes(2, page.pageId.toByteArray())
                        insertDoc.setString(3, section.headingId)
                        insertDoc.setString(4, section.status)
                        insertDoc.executeUpdate()
                        val docId = insertDoc.generatedKeys.use { keys ->
                            keys.next()
                            keys.getLong(1)
                        }
                        insertFts.setLong(1, docId)
                        insertFts.setString(2, section.title)
                        insertFts.setString(3, section.heading)
                        insertFts.setString(4, section.body)
                        // Lists collapse to newline-joined text: `\n` is a tokenizer separator and
                        // the only control character (with \t) the §B4 invariant lets through.
                        insertFts.setString(5, section.tags.joinToString("\n"))
                        insertFts.setString(6, section.aliases.joinToString("\n"))
                        insertFts.setString(7, section.owner)
                        insertFts.executeUpdate()
                        insertTrigram.setLong(1, docId)
                        insertTrigram.setString(2, section.title)
                        insertTrigram.setString(3, section.body)
                        insertTrigram.executeUpdate()
                    }
                }
            }
        }
    }

    private fun Connection.deletePage(generation: Long, id: PageId) {
        val docFilter = "(SELECT doc_id FROM section_doc WHERE generation = ? AND page_id = ?)"
        val bytes = id.toByteArray()
        listOf(
            "DELETE FROM section_fts WHERE rowid IN $docFilter",
            "DELETE FROM section_trigram WHERE rowid IN $docFilter",
            "DELETE FROM section_doc WHERE generation = ? AND page_id = ?",
            "DELETE FROM search_page WHERE generation = ? AND page_id = ?",
        ).forEach { sql ->
            prepareStatement(sql).use { statement ->
                statement.setLong(1, generation)
                statement.setBytes(2, bytes)
                statement.executeUpdate()
            }
        }
    }

    /**
     * Deletes every generation matching `generation [comparison]` from all four tables — the GC
     * (`"< ?"`, only the active generation survives a swap, §B5) and the rebuild's defensive
     * debris sweep (`"!= ?"`) share this shape.
     */
    private fun Connection.deleteGenerations(comparison: String, bound: Long) {
        val docFilter = "(SELECT doc_id FROM section_doc WHERE generation $comparison)"
        listOf(
            "DELETE FROM section_fts WHERE rowid IN $docFilter",
            "DELETE FROM section_trigram WHERE rowid IN $docFilter",
            "DELETE FROM section_doc WHERE generation $comparison",
            "DELETE FROM search_page WHERE generation $comparison",
        ).forEach { sql ->
            prepareStatement(sql).use { statement ->
                statement.setLong(1, bound)
                statement.executeUpdate()
            }
        }
    }

    companion object {
        // §B5 bm25 column weights (title, heading, body, tags, aliases, owner) — tier-2
        // pinned-but-reviewable, tuned against golden/search/bm25-queries.tsv, NOT frozen.
        const val WEIGHT_TITLE: Double = 10.0
        const val WEIGHT_HEADING: Double = 5.0
        const val WEIGHT_BODY: Double = 1.0
        const val WEIGHT_TAGS: Double = 2.0
        const val WEIGHT_ALIASES: Double = 2.0
        const val WEIGHT_OWNER: Double = 2.0

        // The trigram side-index ranks its (rare) fallback answers with the same title-over-body bias.
        const val WEIGHT_TRIGRAM_TITLE: Double = 5.0
        const val WEIGHT_TRIGRAM_BODY: Double = 1.0

        /** §B5's snippet window: `snippet(…, -1, …)` auto-picks the best column, 12 tokens. */
        const val SNIPPET_TOKENS: Int = 12

        private val PRIMARY_INDEX =
            FtsIndex("section_fts", "$WEIGHT_TITLE, $WEIGHT_HEADING, $WEIGHT_BODY, $WEIGHT_TAGS, $WEIGHT_ALIASES, $WEIGHT_OWNER")
        private val TRIGRAM_INDEX = FtsIndex("section_trigram", "$WEIGHT_TRIGRAM_TITLE, $WEIGHT_TRIGRAM_BODY")

        private val logger = KotlinLogging.logger {}
    }
}

/**
 * The §B5 MATCH builder — the engine half of the frozen A1 promise that bare `q` is PLAIN TEXT and
 * no input ever surfaces an engine syntax error. Every whitespace-separated token is wrapped in
 * FTS5 double-quotes (internal `"` doubled), neutralizing the entire operator grammar (quotes,
 * parens, AND/OR/NOT/NEAR, `*^:`); tokens join with implicit AND and the FINAL token is starred
 * for prefix matching (`"deplo"*`). A token that tokenizes to nothing inside its quotes (pure
 * punctuation) is a no-op term to FTS5, not an error — verified engine behavior, and re-proven
 * continuously by the engine-as-oracle property test.
 *
 * C0 control characters are stripped first: they carry no query meaning, and an embedded NUL in a
 * bound string can truncate the FTS5 query parser's view mid-phrase ("unterminated string" —
 * observed), which would be exactly the engine error A1 bans.
 */
internal object MatchExpression {

    /** Unicode whitespace — `(?U)` because plain `\s` is ASCII-only and U+00A0 etc. must split too. */
    private val whitespace = Regex("""(?U)\s+""")

    /** The primary (unicode61) MATCH expression, or null when the text holds no tokens. */
    fun primary(text: String): String? = tokens(text)?.let { tokens ->
        tokens.mapIndexed { i, token -> if (i == tokens.lastIndex) "${quote(token)}*" else quote(token) }.joinToString(" ")
    }

    /**
     * The trigram MATCH expression: same quoted tokens, NO prefix star — the trigram tokenizer
     * already gives substring semantics, and sub-3-char phrases are no-op terms there.
     */
    fun trigram(text: String): String? = tokens(text)?.joinToString(" ") { quote(it) }

    // Strip ALL C0 except \t/\n (the SectionSplitter policy), NOT just the non-whitespace ones:
    // U+001C–U+001F are Java-whitespace but not Unicode White_Space, so the splitter would leave
    // them INSIDE a quoted token where unicode61 reads them as phrase adjacency ("a b", not AND).
    private fun tokens(text: String): List<String>? = text
        .filterNot { it < ' ' && it != '\t' && it != '\n' }
        .split(whitespace)
        .filter { it.isNotEmpty() }
        .ifEmpty { null }

    private fun quote(token: String): String = "\"${token.replace("\"", "\"\"")}\""
}

/**
 * Converts FTS5's sentinel-marked `snippet()` output (`char(1)`/`char(2)` delimiters — impossible
 * in indexed text, C0 is stripped at §B4) into the frozen A3 contract: plain text plus UTF-16
 * code-unit offsets, half-open `[start, end)`, ascending, non-overlapping, never splitting a
 * surrogate pair (ends snap OUTWARD to code-point boundaries; overlaps created by snapping merge).
 * Unbalanced sentinels are defensive territory (R7): the markers are stripped, the unpaired range
 * is dropped, and the page still serves — never a 500.
 */
internal object SnippetMarkers {

    private val logger = KotlinLogging.logger {}

    fun toHighlights(marked: String): Pair<String, List<Highlight>> {
        val text = StringBuilder()
        val ranges = mutableListOf<Highlight>()
        var open = -1
        var unbalanced = false
        for (ch in marked) {
            when (ch) {
                '\u0001' -> {
                    if (open >= 0) unbalanced = true
                    open = text.length
                }
                '\u0002' -> {
                    when {
                        open < 0 -> unbalanced = true
                        open < text.length -> ranges += Highlight(open, text.length)
                    }
                    open = -1
                }
                else -> text.append(ch)
            }
        }
        if (open >= 0) unbalanced = true
        if (unbalanced) {
            // The only signal that a sentinel survived into indexed text — i.e. the upstream §B4
            // C0-strip invariant was breached. Defensive R7 behavior: log, serve, never a 500.
            logger.warn { "unbalanced snippet sentinels (upstream C0-strip invariant breached?); unpaired ranges dropped" }
        }
        val snippet = text.toString()
        return snippet to ranges.map { it.snapToCodePoints(snippet) }.merge()
    }

    /** A3: a range never splits a surrogate pair — expand outward to the code-point boundary. */
    private fun Highlight.snapToCodePoints(text: String): Highlight {
        val safeStart = if (start > 0 && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start - 1 else start
        val safeEnd = if (end < text.length && text[end].isLowSurrogate() && text[end - 1].isHighSurrogate()) end + 1 else end
        return if (safeStart == start && safeEnd == end) this else Highlight(safeStart, safeEnd)
    }

    /** Keeps the offset list ascending and non-overlapping after snapping (touching ranges coalesce). */
    private fun List<Highlight>.merge(): List<Highlight> = fold(mutableListOf()) { merged, range ->
        val last = merged.lastOrNull()
        if (last != null && range.start <= last.end) {
            merged[merged.lastIndex] = Highlight(last.start, maxOf(last.end, range.end))
        } else {
            merged += range
        }
        merged
    }
}
