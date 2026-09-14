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
import com.plainbase.frameworks.lifecycle.ServerResourceOwner
import com.plainbase.frameworks.lifecycle.ServerResourcePhase
import com.plainbase.frameworks.runtime.ContentRepositories
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightApiTokenRepository
import com.plainbase.frameworks.sqldelight.SqlDelightAuditRepository
import com.plainbase.frameworks.sqldelight.SqlDelightProposalRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRoleRepository
import com.plainbase.frameworks.sqldelight.SqlDelightSessionRepository
import com.plainbase.frameworks.sqldelight.SqlDelightSetupTokenRepository
import com.plainbase.frameworks.sqldelight.SqlDelightTransactionRunner
import com.plainbase.frameworks.sqldelight.SqlDelightUserRepository
import org.koin.dsl.module
import org.koin.dsl.onClose
import java.nio.file.Path

internal fun createRepositoryModule(
    openDriver: (Path) -> SqlDriver,
    closeDriver: (SqlDriver) -> Unit,
    resourceOwner: ServerResourceOwner,
) = module {
    val driverDefinition = single<SqlDriver> {
        val open = { openDriver(get<PlainbaseConfig>().appDatabasePath) }
        resourceOwner.construct("app database") {
            open().also { driver ->
                resourceOwner.own(ServerResourcePhase.APP_DATABASE, driver, closeDriver)
            }
        }
    }
    driverDefinition onClose {
        resourceOwner.drainServices()
    }
    single { DatabaseFactory.createDatabase(get()) }
    // Shared content state keeps proof, alias, checkpoint, dirty-page, and root bindings on one database.
    single { ContentRepositories(get()) }
    single<IdMapRepository> { get<ContentRepositories>().idMap }
    single<RetirementRepository> { get<ContentRepositories>().retirements }
    single<RootTopologyRepository> { get<ContentRepositories>().topology }
    single<UrlAliasRepository> { get<ContentRepositories>().aliases }
    single<PageCheckpointRepository> { get<ContentRepositories>().checkpoints }
    single<DirtyPageRepository> { get<ContentRepositories>().dirtyPages }
    single<ApiTokenRepository> { SqlDelightApiTokenRepository(get()) }
    single<ProposalRepository> { SqlDelightProposalRepository(get()) }
    single<RoleRepository> { SqlDelightRoleRepository(get()) }
    single<AuditRepository> { SqlDelightAuditRepository(get()) }
    single<UserRepository> { SqlDelightUserRepository(get()) }
    single<SessionRepository> { SqlDelightSessionRepository(get()) }
    single<SetupTokenRepository> { SqlDelightSetupTokenRepository(get()) }
    single<TransactionRunner> { SqlDelightTransactionRunner(get()) }
}

internal fun repositoryModule(resourceOwner: ServerResourceOwner) =
    createRepositoryModule(DatabaseFactory::createDriver, { it.close() }, resourceOwner)
