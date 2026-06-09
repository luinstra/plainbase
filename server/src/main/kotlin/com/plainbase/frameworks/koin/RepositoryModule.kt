package com.plainbase.frameworks.koin

import app.cash.sqldelight.db.SqlDriver
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.PlainbaseDb
import org.koin.dsl.module

val repositoryModule = module {
    single<SqlDriver> { DatabaseFactory.createDriver(get<PlainbaseConfig>().appDatabasePath) }
    single { DatabaseFactory.createDatabase(get()) }
}
