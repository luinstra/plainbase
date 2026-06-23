package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.repository.TransactionRunner

/**
 * SQLDelight adapter for [TransactionRunner] over `PlainbaseDb.transactionWithResult` (the same monitor every repo
 * write shares). Used by `SetupService` so the bootstrap (mark-token-used + insert-user + grant ADMIN) commits
 * atomically — a throw inside the block rolls the whole transaction back (the §5 TOCTOU-free bootstrap).
 */
class SqlDelightTransactionRunner(private val db: PlainbaseDb) : TransactionRunner {

    override fun <T> inTransaction(block: () -> T): T = db.transactionWithResult { block() }
}
