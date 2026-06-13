package com.plainbase.frameworks.search

import com.plainbase.domain.search.SearchProvider
import com.plainbase.search.SearchEngineHarness
import com.plainbase.search.SearchProviderContract
import java.nio.file.Files

/**
 * The embedded engine joining the S3 contract (with the default exact-ordered-sequence
 * criterion-4 comparator — the §A4 deterministic tiebreak makes anything less a regression).
 * One harness, two legs:
 *
 *  - [Fts5SearchProviderContractTest]: a fresh throwaway `search.db` per test — the in-memory-style
 *    leg (SearchDb is file-based by design, so "in-memory" means an ephemeral temp file).
 *  - [Fts5ReopenedDbContractTest]: the file-backed-with-reopen leg — the database is closed and
 *    reopened before any test code runs, so the ENTIRE contract additionally proves the
 *    existing-file open path (schema re-validation against a populated-then-cycled store).
 *
 * Both legs run the contract's own reopen-durability test on top, via [SearchEngineHarness.reopen].
 */
private class Fts5ContractHarness(reopenOnOpen: Boolean) : SearchEngineHarness {

    private val dir = Files.createTempDirectory("plainbase-contract-test")
    private val dbPath = dir.resolve("search.db")
    private var db = SearchDb(dbPath)

    override var provider: SearchProvider = Fts5SearchProvider(db)
        private set

    init {
        if (reopenOnOpen) reopen()
    }

    override fun reopen() {
        db.close()
        db = SearchDb(dbPath)
        provider = Fts5SearchProvider(db)
    }

    override fun close() {
        db.close()
        dir.toFile().deleteRecursively()
    }
}

class Fts5SearchProviderContractTest : SearchProviderContract({ Fts5ContractHarness(reopenOnOpen = false) })

class Fts5ReopenedDbContractTest : SearchProviderContract({ Fts5ContractHarness(reopenOnOpen = true) })
