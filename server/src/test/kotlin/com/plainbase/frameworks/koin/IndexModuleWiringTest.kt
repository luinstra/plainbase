package com.plainbase.frameworks.koin

import app.cash.sqldelight.db.SqlDriver
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * DI wiring smoke test for the chunk-5 index pass — the same "declared but not installed" guard as
 * [ContentModuleWiringTest], for [indexModule]: the PRODUCTION module set must resolve
 * [IndexBuilder] with every constructor dependency satisfied.
 *
 * The SQL driver is overridden with an in-memory one (Koin: later module wins) because resolving
 * [IndexBuilder] instantiates the alias registry, which loads its view from the database — the
 * production driver would create `./data/plainbase.db` as a test side effect. No rebuild is run;
 * construction alone proves the wiring.
 */
class IndexModuleWiringTest : FunSpec({

    test("the production module set resolves IndexBuilder (indexModule is installed)") {
        val app = koinApplication {
            modules(
                configModule,
                contentModule,
                repositoryModule,
                securityModule,
                historyModule,
                indexModule,
                module { single<SqlDriver> { DatabaseFactory.createInMemoryDriver() } },
            )
        }
        try {
            app.koin.get<IndexBuilder>().shouldBeInstanceOf<IndexBuilder>()
        } finally {
            app.close()
        }
    }
})
