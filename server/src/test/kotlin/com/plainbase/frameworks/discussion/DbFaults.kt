package com.plainbase.frameworks.discussion

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

internal class DbFaults(path: Path) : AutoCloseable {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$path")
    private var discussionRenamed = false
    private var anchorMatchRenamed = false

    fun failWrites() {
        connection.createStatement().use { statement ->
            WRITE_TABLES.forEach { table ->
                WRITE_OPERATIONS.forEach { operation ->
                    fail(statement, table, operation)
                }
            }
        }
    }

    fun failDiscussionEntryInserts() {
        connection.createStatement().use { statement -> fail(statement, "discussion_entry", "INSERT") }
    }

    fun failReads() {
        connection.createStatement().use { statement ->
            statement.execute("ALTER TABLE discussion RENAME TO discussion_faulted")
        }
        discussionRenamed = true
    }

    fun failAnchorMatchReads() {
        connection.createStatement().use { statement ->
            statement.execute("ALTER TABLE anchor_match RENAME TO anchor_match_faulted")
        }
        anchorMatchRenamed = true
    }

    fun heal() {
        connection.createStatement().use { statement ->
            WRITE_TABLES.forEach { table ->
                WRITE_OPERATIONS.forEach { operation ->
                    val trigger = "db_fault_${table}_${operation.lowercase()}"
                    statement.execute("DROP TRIGGER IF EXISTS $trigger")
                }
            }
            if (discussionRenamed) statement.execute("ALTER TABLE discussion_faulted RENAME TO discussion")
            if (anchorMatchRenamed) statement.execute("ALTER TABLE anchor_match_faulted RENAME TO anchor_match")
        }
        discussionRenamed = false
        anchorMatchRenamed = false
    }

    override fun close() {
        try {
            heal()
        } finally {
            connection.close()
        }
    }

    private companion object {
        val WRITE_TABLES = listOf("discussion", "discussion_entry", "anchor_match")
        val WRITE_OPERATIONS = listOf("INSERT", "UPDATE", "DELETE")

        fun fail(statement: java.sql.Statement, table: String, operation: String) {
            val trigger = "db_fault_${table}_${operation.lowercase()}"
            statement.execute(
                "CREATE TRIGGER $trigger BEFORE $operation ON $table " +
                    "BEGIN SELECT RAISE(ABORT, 'fixture write failure'); END",
            )
        }
    }
}
