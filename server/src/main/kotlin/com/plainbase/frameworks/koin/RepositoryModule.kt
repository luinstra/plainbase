package com.plainbase.frameworks.koin

import app.cash.sqldelight.db.SqlDriver
import com.plainbase.domain.repository.ApiTokenRepository
import com.plainbase.domain.repository.AuditRepository
import com.plainbase.domain.repository.DirtyPageRepository
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.PageCheckpointRepository
import com.plainbase.domain.repository.RoleRepository
import com.plainbase.domain.repository.SessionRepository
import com.plainbase.domain.repository.SetupTokenRepository
import com.plainbase.domain.repository.TransactionRunner
import com.plainbase.domain.repository.UrlAliasRepository
import com.plainbase.domain.repository.UserRepository
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightApiTokenRepository
import com.plainbase.frameworks.sqldelight.SqlDelightAuditRepository
import com.plainbase.frameworks.sqldelight.SqlDelightDirtyPageRepository
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightPageCheckpointRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRoleRepository
import com.plainbase.frameworks.sqldelight.SqlDelightSessionRepository
import com.plainbase.frameworks.sqldelight.SqlDelightSetupTokenRepository
import com.plainbase.frameworks.sqldelight.SqlDelightTransactionRunner
import com.plainbase.frameworks.sqldelight.SqlDelightUrlAliasRepository
import com.plainbase.frameworks.sqldelight.SqlDelightUserRepository
import org.koin.dsl.module

val repositoryModule = module {
    single<SqlDriver> { DatabaseFactory.createDriver(get<PlainbaseConfig>().appDatabasePath) }
    single { DatabaseFactory.createDatabase(get()) }
    single<IdMapRepository> { SqlDelightIdMapRepository(get()) }
    single<UrlAliasRepository> { SqlDelightUrlAliasRepository(get()) }
    single<PageCheckpointRepository> { SqlDelightPageCheckpointRepository(get()) }
    single<DirtyPageRepository> { SqlDelightDirtyPageRepository(get()) }
    single<ApiTokenRepository> { SqlDelightApiTokenRepository(get()) }
    single<RoleRepository> { SqlDelightRoleRepository(get()) }
    single<AuditRepository> { SqlDelightAuditRepository(get()) }
    single<UserRepository> { SqlDelightUserRepository(get()) }
    single<SessionRepository> { SqlDelightSessionRepository(get()) }
    single<SetupTokenRepository> { SqlDelightSetupTokenRepository(get()) }
    single<TransactionRunner> { SqlDelightTransactionRunner(get()) }
}
