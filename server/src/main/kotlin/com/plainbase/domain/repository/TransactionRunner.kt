package com.plainbase.domain.repository

/**
 * Port for running several repository writes in ONE atomic database transaction (A4a). The bootstrap (§5) must
 * mark-token-used + insert-user + grant ADMIN as a single unit — a partial (user inserted, token not marked) is
 * impossible — which a framework-free service expresses only through a port. The SQLDelight impl wraps
 * `PlainbaseDb.transactionWithResult`; a thrown [block] rolls the whole transaction back.
 *
 * Framework-free (hexagonal): the impl is `SqlDelightTransactionRunner`.
 */
interface TransactionRunner {

    /** Runs [block] inside one transaction, returning its result; any throw rolls the whole transaction back. */
    fun <T> inTransaction(block: () -> T): T
}
