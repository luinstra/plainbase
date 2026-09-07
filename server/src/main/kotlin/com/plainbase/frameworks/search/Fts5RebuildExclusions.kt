package com.plainbase.frameworks.search

import com.plainbase.domain.root.RootedPageId
import java.sql.Connection

/**
 * Writer-local scratch state for a bounded generation rebuild exclusion set.
 *
 * The table is TEMP, so it is scoped to the SQLite writer connection and is never durable search
 * authority. The fixed two-parameter prepared INSERT bounds SQLite variables per statement.
 * `BATCH_SIZE` bounds queued JDBC batch entries and each flush size; it is not a cumulative
 * variable limit. Staging iterates the caller-provided set as supplied and does not sort it.
 * Callers must invoke [stage] and [clear] on the writer connection inside the rebuild transaction:
 * [stage] resets and populates the table, and [clear] must run after carry/publication work and
 * before a successful commit. A rollback restores the TEMP table to its pre-rebuild state.
 */
internal object Fts5RebuildExclusions {

    private const val BATCH_SIZE = 256
    private const val CLEAR_SQL = "DELETE FROM temp.rebuild_exclusion"

    fun stage(connection: Connection, retired: Set<RootedPageId>) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "CREATE TEMP TABLE IF NOT EXISTS rebuild_exclusion(" +
                    "root TEXT NOT NULL, page_id BLOB NOT NULL, PRIMARY KEY(root, page_id))",
            )
        }
        clear(connection)
        if (retired.isEmpty()) return

        connection.prepareStatement(
            "INSERT INTO temp.rebuild_exclusion(root, page_id) VALUES (?, ?)",
        ).use { statement ->
            var pending = 0
            retired.forEach { rooted ->
                statement.setString(1, rooted.root.value)
                statement.setBytes(2, rooted.id.toByteArray())
                statement.addBatch()
                pending++
                if (pending == BATCH_SIZE) {
                    statement.executeBatch()
                    // Explicitly clear queued JDBC entries after each full-size flush so every batch is bounded
                    // independently; do not rely on driver cleanup between executeBatch calls.
                    statement.clearBatch()
                    pending = 0
                }
            }
            if (pending > 0) statement.executeBatch()
        }
    }

    fun clear(connection: Connection) {
        connection.prepareStatement(CLEAR_SQL).use { statement ->
            statement.executeUpdate()
        }
    }
}
