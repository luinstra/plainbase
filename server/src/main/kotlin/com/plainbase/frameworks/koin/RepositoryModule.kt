package com.plainbase.frameworks.koin

import app.cash.sqldelight.db.SqlDriver
import com.plainbase.domain.repository.DirtyPageRepository
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.PageCheckpointRepository
import com.plainbase.domain.repository.UrlAliasRepository
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightDirtyPageRepository
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
import com.plainbase.frameworks.sqldelight.SqlDelightUrlAliasRepository
import org.koin.dsl.module

val repositoryModule = module {
    single<SqlDriver> { DatabaseFactory.createDriver(get<PlainbaseConfig>().appDatabasePath) }
    single { DatabaseFactory.createDatabase(get()) }
    single<IdMapRepository> { SqlDelightIdMapRepository(get()) }
    single<UrlAliasRepository> { SqlDelightUrlAliasRepository(get()) }
    single<PageCheckpointRepository> { SqlDelightPageCheckpointRepository(get()) }
    single<DirtyPageRepository> { SqlDelightDirtyPageRepository(get()) }
}
