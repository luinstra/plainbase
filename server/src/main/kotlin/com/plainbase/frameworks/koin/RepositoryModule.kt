package com.plainbase.frameworks.koin

import app.cash.sqldelight.db.SqlDriver
import com.plainbase.domain.repository.ApiTokenRepository
import com.plainbase.domain.repository.AuditRepository
import com.plainbase.domain.repository.DirtyPageRepository
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.PageCheckpointRepository
import com.plainbase.domain.repository.ProposalRepository
import com.plainbase.domain.repository.RetirementRepository
import com.plainbase.domain.repository.RoleRepository
import com.plainbase.domain.repository.RootTopologyRepository
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
import com.plainbase.frameworks.sqldelight.SqlDelightProposalRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRetirementRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRoleRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRootTopologyRepository
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
    // The ONE deleter (C0): the proof-apply transaction plus the durable freshness token it checks against.
    single<RetirementRepository> { SqlDelightRetirementRepository(get()) }
    // The DURABLE binding latch (C3): where each root points, and whether we believe it. Durable because the
    // wrong-bucket wipe survives a restart, so the thing that stops it has to as well.
    single<RootTopologyRepository> { SqlDelightRootTopologyRepository(get()) }
    single<UrlAliasRepository> { SqlDelightUrlAliasRepository(get()) }
    single<PageCheckpointRepository> { SqlDelightPageCheckpointRepository(get()) }
    single<DirtyPageRepository> { SqlDelightDirtyPageRepository(get()) }
    single<ApiTokenRepository> { SqlDelightApiTokenRepository(get()) }
    single<ProposalRepository> { SqlDelightProposalRepository(get()) }
    single<RoleRepository> { SqlDelightRoleRepository(get()) }
    single<AuditRepository> { SqlDelightAuditRepository(get()) }
    single<UserRepository> { SqlDelightUserRepository(get()) }
    single<SessionRepository> { SqlDelightSessionRepository(get()) }
    single<SetupTokenRepository> { SqlDelightSetupTokenRepository(get()) }
    single<TransactionRunner> { SqlDelightTransactionRunner(get()) }
}
