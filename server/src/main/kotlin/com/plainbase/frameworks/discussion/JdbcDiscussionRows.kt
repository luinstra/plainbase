package com.plainbase.frameworks.discussion

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.discussion.AnchorMatch
import com.plainbase.domain.discussion.CachedMatch
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionPersistenceFailure
import com.plainbase.domain.discussion.DiscussionRow
import com.plainbase.domain.discussion.DiscussionRowWriter
import com.plainbase.domain.discussion.DiscussionRows
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.EntryRow
import com.plainbase.domain.discussion.EntryRowData
import com.plainbase.domain.discussion.MatchRange
import com.plainbase.domain.discussion.Placement
import com.plainbase.domain.discussion.RowUpdate
import com.plainbase.domain.discussion.Stamp
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.RootName
import com.plainbase.frameworks.search.transaction
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types

class JdbcDiscussionRows(private val db: DiscussionDb) : DiscussionRows {
    override fun <T> writing(block: DiscussionRowWriter.() -> T): T =
        db.writing { block(Writer(it)) }

    override fun <T> tryWriting(block: DiscussionRowWriter.() -> T): T? =
        db.tryWriting { block(Writer(it)) }

    override fun pageCount(root: RootName, pageId: PageId): Int = db.read { connection ->
        connection.prepareStatement(
            "SELECT COUNT(*) FROM discussion WHERE root=? AND page_id=? " +
                "AND state IN ('ok','unreadable','failed')",
        ).use { statement ->
            statement.setString(1, root.value)
            statement.setString(2, pageId.value)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    override fun row(root: RootName, id: DiscussionId): DiscussionRow? = db.read { connection ->
        connection.prepareStatement("SELECT * FROM discussion WHERE root=? AND id=?").use { statement ->
            statement.setString(1, root.value)
            statement.setString(2, id.value)
            statement.executeQuery().use { rows -> if (rows.next()) rows.discussionRow() else null }
        }
    }

    override fun entry(root: RootName, id: DiscussionId, name: EntryName): EntryRow? = db.read { connection ->
        connection.prepareStatement("SELECT * FROM discussion_entry WHERE root=? AND id=? AND name=?").use { statement ->
            statement.setString(1, root.value)
            statement.setString(2, id.value)
            statement.setString(3, name.fileName)
            statement.executeQuery().use { rows -> if (rows.next()) rows.entryRow() else null }
        }
    }

    override fun onPageRaw(
        root: RootName,
        pageId: PageId,
        path: TreePath,
        after: DiscussionId?,
        limit: Int,
    ): List<DiscussionRow> = db.read { connection ->
        connection.prepareStatement(
            "SELECT * FROM discussion WHERE root=? AND (page_id=? OR page_path=?) " +
                "AND state IN ('ok','unreadable','failed') AND (? IS NULL OR id>?) ORDER BY id LIMIT ?",
        ).use { statement ->
            statement.setString(1, root.value)
            statement.setString(2, pageId.value)
            statement.setString(3, path.value)
            statement.setNullableString(4, after?.value)
            statement.setNullableString(5, after?.value)
            statement.setInt(6, limit)
            statement.executeQuery().use { rows -> rows.readDiscussionRows() }
        }
    }

    override fun ids(root: RootName, after: DiscussionId?, limit: Int): List<DiscussionId> = db.read { connection ->
        connection.prepareStatement("SELECT id FROM discussion WHERE root=? AND (? IS NULL OR id>?) ORDER BY id LIMIT ?")
            .use { statement ->
                statement.setString(1, root.value)
                statement.setNullableString(2, after?.value)
                statement.setNullableString(3, after?.value)
                statement.setInt(4, limit)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(DiscussionId.require(rows.getString(1))) } }
            }
    }

    override fun rowsAfter(root: RootName, after: DiscussionId?, limit: Int): List<DiscussionRow> = db.read { connection ->
        connection.prepareStatement("SELECT * FROM discussion WHERE root=? AND (? IS NULL OR id>?) ORDER BY id LIMIT ?")
            .use { statement ->
                statement.setString(1, root.value)
                statement.setNullableString(2, after?.value)
                statement.setNullableString(3, after?.value)
                statement.setInt(4, limit)
                statement.executeQuery().use { rows -> rows.readDiscussionRows() }
            }
    }

    override fun cached(root: RootName, id: DiscussionId): CachedMatch? = db.read { connection ->
        connection.prepareStatement("SELECT anchor_hash, page_hash, match, placement FROM anchor_match WHERE root=? AND id=?")
            .use { statement ->
                statement.setString(1, root.value)
                statement.setString(2, id.value)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) return@use null
                    val match = decodeMatch(rows.getString("match"), rows.getString("placement")) ?: return@use null
                    CachedMatch(rows.getString("anchor_hash"), rows.getString("page_hash"), match)
                }
            }
    }

    private class Writer(private val connection: Connection) : DiscussionRowWriter {
        override fun truncate() = jdbcCall {
            connection.transaction {
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM anchor_match")
                    statement.executeUpdate("DELETE FROM discussion_entry")
                    statement.executeUpdate("DELETE FROM discussion")
                }
            }
            Unit
        }

        override fun apply(root: RootName, id: DiscussionId, update: RowUpdate, stamp: Stamp?, dropMatch: Boolean) = jdbcCall {
            connection.transaction {
                when (update) {
                    RowUpdate.Delete -> delete(root, id)
                    is RowUpdate.Unknown -> throw IllegalStateException("unknown discussion observation cannot be applied: ${update.cause}")
                    is RowUpdate.Failed -> failed(root, id, update.reason)
                    is RowUpdate.Upsert -> upsert(root, id, update, stamp, dropMatch)
                }
            }
        }

        override fun storeMatch(
            root: RootName,
            id: DiscussionId,
            anchorHash: String,
            pageHash: String,
            match: AnchorMatch,
        ) = jdbcCall {
            val (encoded, placement) = encodeMatch(match)
            connection.prepareStatement(
                "INSERT INTO anchor_match(root,id,anchor_hash,page_hash,match,placement) " +
                    "SELECT ?,?,?,?,?,? WHERE EXISTS (SELECT 1 FROM discussion WHERE root=? AND id=?) " +
                    "ON CONFLICT(root,id) DO UPDATE SET anchor_hash=excluded.anchor_hash,page_hash=excluded.page_hash," +
                    "match=excluded.match,placement=excluded.placement",
            ).use { statement ->
                statement.setString(1, root.value)
                statement.setString(2, id.value)
                statement.setString(3, anchorHash)
                statement.setString(4, pageHash)
                statement.setString(5, encoded)
                statement.setNullableString(6, placement)
                statement.setString(7, root.value)
                statement.setString(8, id.value)
                statement.executeUpdate()
            }
            Unit
        }

        private fun delete(root: RootName, id: DiscussionId) {
            listOf("anchor_match", "discussion_entry", "discussion").forEach { table ->
                connection.prepareStatement("DELETE FROM $table WHERE root=? AND id=?").use { statement ->
                    statement.setString(1, root.value)
                    statement.setString(2, id.value)
                    statement.executeUpdate()
                }
            }
        }

        private fun failed(root: RootName, id: DiscussionId, reason: String) = jdbcCall {
            val changed = connection.prepareStatement(
                "UPDATE discussion SET state='failed',reason=?,stamp=NULL WHERE root=? AND id=?",
            ).use { statement ->
                statement.setString(1, reason)
                statement.setString(2, root.value)
                statement.setString(3, id.value)
                statement.executeUpdate()
            }
            if (changed == 0) {
                connection.prepareStatement(
                    "INSERT INTO discussion(root,id,state,reason,stamp,comment_count) VALUES(?,?,'failed',?,NULL,0)",
                ).use { statement ->
                    statement.setString(1, root.value)
                    statement.setString(2, id.value)
                    statement.setString(3, reason)
                    statement.executeUpdate()
                }
            }
        }

        private inline fun <T> jdbcCall(block: () -> T): T = try {
            block()
        } catch (failure: SQLException) {
            throw DiscussionPersistenceFailure(failure)
        }

        private fun upsert(root: RootName, id: DiscussionId, update: RowUpdate.Upsert, stamp: Stamp?, dropMatch: Boolean) {
            val row = update.row
            val priorAnchor = connection.prepareStatement("SELECT anchor_hash FROM discussion WHERE root=? AND id=?").use { statement ->
                statement.setString(1, root.value)
                statement.setString(2, id.value)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
            if (dropMatch || priorAnchor != row.anchorHash) {
                connection.prepareStatement("DELETE FROM anchor_match WHERE root=? AND id=?").use { statement ->
                    statement.setString(1, root.value)
                    statement.setString(2, id.value)
                    statement.executeUpdate()
                }
            }
            connection.prepareStatement(
                "INSERT INTO discussion(root,id,state,reason,stamp,page_id,page_path,status,anchor_kind,anchor_hash," +
                    "starter_key,created,updated,comment_count,starter_kind,starter_label,quote_preview) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                    "ON CONFLICT(root,id) DO UPDATE SET state=excluded.state,reason=excluded.reason,stamp=excluded.stamp," +
                    "page_id=excluded.page_id,page_path=excluded.page_path,status=excluded.status," +
                    "anchor_kind=excluded.anchor_kind,anchor_hash=excluded.anchor_hash,starter_key=excluded.starter_key," +
                    "created=excluded.created,updated=excluded.updated,comment_count=excluded.comment_count," +
                    "starter_kind=excluded.starter_kind,starter_label=excluded.starter_label,quote_preview=excluded.quote_preview",
            ).use { statement ->
                statement.setString(1, root.value)
                statement.setString(2, id.value)
                statement.setString(3, row.state)
                statement.setNullableString(4, row.reason)
                statement.setNullableString(5, stamp?.takeUnless { it.racy }?.value)
                statement.setNullableString(6, row.pageId?.value)
                statement.setNullableString(7, row.pagePath?.value)
                statement.setNullableString(8, row.status)
                statement.setNullableString(9, row.anchorKind)
                statement.setNullableString(10, row.anchorHash)
                statement.setNullableString(11, row.starterKey)
                statement.setNullableLong(12, row.created)
                statement.setNullableLong(13, row.updated)
                statement.setInt(14, row.commentCount)
                statement.setNullableString(15, row.starterKind)
                statement.setNullableString(16, row.starterLabel)
                statement.setNullableString(17, row.quotePreview)
                statement.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM discussion_entry WHERE root=? AND id=?").use { statement ->
                statement.setString(1, root.value)
                statement.setString(2, id.value)
                statement.executeUpdate()
            }
            update.entries.forEach { entry -> insertEntry(root, id, entry) }
        }

        private fun insertEntry(root: RootName, id: DiscussionId, row: EntryRowData) {
            connection.prepareStatement(
                "INSERT INTO discussion_entry(root,id,name,sha256,version,author_key) VALUES(?,?,?,?,?,?)",
            ).use { statement ->
                statement.setString(1, root.value)
                statement.setString(2, id.value)
                statement.setString(3, row.name.fileName)
                statement.setString(4, row.sha256)
                statement.setString(5, row.version)
                statement.setString(6, row.authorKey)
                statement.executeUpdate()
            }
        }
    }
}

private fun ResultSet.readDiscussionRows(): List<DiscussionRow> = buildList { while (next()) add(discussionRow()) }

private fun ResultSet.discussionRow(): DiscussionRow = DiscussionRow(
    root = RootName.require(getString("root")),
    id = DiscussionId.require(getString("id")),
    state = getString("state"),
    reason = getString("reason"),
    stamp = getString("stamp")?.let(::Stamp),
    pageId = getString("page_id")?.let { PageId.of(it) ?: throw SQLException("invalid page id in discussions.db") },
    pagePath = getString("page_path")?.let { TreePath.of(it) ?: throw SQLException("invalid page path in discussions.db") },
    status = getString("status"),
    anchorKind = getString("anchor_kind"),
    anchorHash = getString("anchor_hash"),
    starterKey = getString("starter_key"),
    created = nullableLong("created"),
    updated = nullableLong("updated"),
    commentCount = getInt("comment_count"),
    starterKind = getString("starter_kind"),
    starterLabel = getString("starter_label"),
    quotePreview = getString("quote_preview"),
)

private fun ResultSet.entryRow(): EntryRow = EntryRow(
    root = RootName.require(getString("root")),
    id = DiscussionId.require(getString("id")),
    name = EntryName.parse(getString("name")) ?: throw SQLException("invalid entry name in discussions.db"),
    sha256 = getString("sha256"),
    version = getString("version"),
    authorKey = getString("author_key"),
)

private fun ResultSet.nullableLong(column: String): Long? = getLong(column).let { if (wasNull()) null else it }

private fun PreparedStatement.setNullableString(index: Int, value: String?) {
    if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
}

private fun PreparedStatement.setNullableLong(index: Int, value: Long?) {
    if (value == null) setNull(index, Types.INTEGER) else setLong(index, value)
}

private fun encodeMatch(match: AnchorMatch): Pair<String, String?> = when (match) {
    AnchorMatch.PageLevel -> "page_level" to null
    is AnchorMatch.Exact -> "exact:${match.range.byteStart}-${match.range.byteEnd}" to null
    is AnchorMatch.Moved -> "moved:${match.range.byteStart}-${match.range.byteEnd}" to null
    is AnchorMatch.Ambiguous -> {
        val ranges = match.candidates.take(20).joinToString(",") { "${it.byteStart}-${it.byteEnd}" }
        "ambiguous:${match.count}:${if (match.truncated) 't' else 'f'}:$ranges" to null
    }
    is AnchorMatch.Changed -> "changed" to when (val placement = match.placement) {
        is Placement.Heading -> "heading:${placement.id}"
        is Placement.Line -> "line:${placement.line}"
    }
}

private fun decodeMatch(raw: String, rawPlacement: String?): AnchorMatch? {
    if (raw == "page_level") return AnchorMatch.PageLevel
    if (raw == "changed") {
        val placement = rawPlacement ?: return null
        if (placement.startsWith("heading:")) return AnchorMatch.Changed(Placement.Heading(placement.removePrefix("heading:")))
        if (placement.startsWith("line:")) {
            val line = placement.removePrefix("line:").toLongOrNull()?.takeIf { it > 0 } ?: return null
            return AnchorMatch.Changed(Placement.Line(line))
        }
        return null
    }
    val exact = RANGE_PATTERN.matchEntire(raw)
    if (exact != null) {
        val range = exact.groupsRange() ?: return null
        return when (exact.groupValues[1]) {
            "exact" -> AnchorMatch.Exact(range)
            "moved" -> AnchorMatch.Moved(range)
            else -> null
        }
    }
    val ambiguous = AMBIGUOUS_PATTERN.matchEntire(raw) ?: return null
    val count = ambiguous.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
    val truncated = when (ambiguous.groupValues[2]) {
        "t" -> true
        "f" -> false
        else -> return null
    }
    val ranges = ambiguous.groupValues[3].split(',').mapNotNull(::parseRange)
    if (ranges.isEmpty() || ranges.size > 20 || ranges.size != ambiguous.groupValues[3].split(',').size) return null
    return AnchorMatch.Ambiguous(count, ranges, truncated)
}

private fun MatchResult.groupsRange(): MatchRange? {
    val start = groupValues[2].toIntOrNull() ?: return null
    val end = groupValues[3].toIntOrNull() ?: return null
    if (start > end) return null
    return MatchRange(start, end)
}

private fun parseRange(value: String): MatchRange? {
    val match = RANGE_PAIR_PATTERN.matchEntire(value) ?: return null
    val start = match.groupValues[1].toIntOrNull() ?: return null
    val end = match.groupValues[2].toIntOrNull() ?: return null
    if (start > end) return null
    return MatchRange(start, end)
}

private val RANGE_PATTERN = Regex("^(exact|moved):([0-9]+)-([0-9]+)$")
private val RANGE_PAIR_PATTERN = Regex("^([0-9]+)-([0-9]+)$")
private val AMBIGUOUS_PATTERN = Regex("^ambiguous:([0-9]+):([tf]):(.+)$")
