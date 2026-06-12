package com.plainbase.frameworks.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * Runs a raw single-value SQL query against [this] driver — the chunk 4b "binary at rest" criterion
 * demands direct-SQL `length(id) = 16` assertions, deliberately below the typed query layer (a
 * column adapter bug must not be able to vouch for itself).
 */
fun SqlDriver.queryLong(sql: String): Long =
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value(requireNotNull(cursor.getLong(0)))
        },
        parameters = 0,
    ).value
