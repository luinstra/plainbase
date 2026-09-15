package com.plainbase.frameworks.koin

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.ApiTokenRepository
import com.plainbase.domain.repository.AuditRepository
import com.plainbase.domain.repository.DirtyPage
import com.plainbase.domain.repository.DirtyPageRepository
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.PageCheckpointRepository
import com.plainbase.domain.repository.ProposalRepository
import com.plainbase.domain.repository.RetirementRepository
import com.plainbase.domain.repository.RoleRepository
import com.plainbase.domain.repository.RootTopologyRepository
import com.plainbase.domain.repository.SessionRepository
import com.plainbase.domain.repository.SetupTokenRepository
import com.plainbase.domain.repository.Stage
import com.plainbase.domain.repository.TransactionRunner
import com.plainbase.domain.repository.UrlAliasRepository
import com.plainbase.domain.repository.UserRepository
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.frameworks.config.ConfigLoader
import com.plainbase.frameworks.lifecycle.ServerResourceOwner
import com.plainbase.frameworks.runtime.ContentRepositories
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.koin.core.KoinApplication
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.instance.SingleInstanceFactory
import org.koin.dsl.module
import java.util.concurrent.atomic.AtomicInteger

@OptIn(KoinInternalApi::class)
class RepositoryModuleWiringTest : FunSpec({

    test("content repository aliases share one lazy group and real database") {
        val config = ConfigLoader.fromEnv(emptyMap())
        val owner = ServerResourceOwner()
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val app = createOwnedTestKoinApplication(
            owner,
            listOf(
                module { single { config } },
                createRepositoryModule(
                    openDriver = {
                        opened.incrementAndGet()
                        DatabaseFactory.createInMemoryDriver()
                    },
                    closeDriver = { driver ->
                        closed.incrementAndGet()
                        driver.close()
                    },
                    resourceOwner = owner,
                ),
            ),
        )
        try {
            val repositories = app.koin.get<ContentRepositories>()
            app.koin.get<IdMapRepository>() shouldBeSameInstanceAs repositories.idMap
            app.koin.get<UrlAliasRepository>() shouldBeSameInstanceAs repositories.aliases
            app.koin.get<PageCheckpointRepository>() shouldBeSameInstanceAs repositories.checkpoints
            app.koin.get<DirtyPageRepository>() shouldBeSameInstanceAs repositories.dirtyPages
            app.koin.get<RetirementRepository>() shouldBeSameInstanceAs repositories.retirements
            app.koin.get<RootTopologyRepository>() shouldBeSameInstanceAs repositories.topology
            repeat(2) {
                app.koin.get<IdMapRepository>() shouldBeSameInstanceAs repositories.idMap
                app.koin.get<UrlAliasRepository>() shouldBeSameInstanceAs repositories.aliases
                app.koin.get<PageCheckpointRepository>() shouldBeSameInstanceAs repositories.checkpoints
                app.koin.get<DirtyPageRepository>() shouldBeSameInstanceAs repositories.dirtyPages
                app.koin.get<RetirementRepository>() shouldBeSameInstanceAs repositories.retirements
                app.koin.get<RootTopologyRepository>() shouldBeSameInstanceAs repositories.topology
            }
            opened.get() shouldBe 1
            closed.get() shouldBe 0

            val pageId = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")
            val path = RootedPath(RootName.PRIMARY, TreePath.require("guides/a.md"))
            val rootedId = RootedPageId(RootName.PRIMARY, pageId)
            val expected = DirtyPage(pageId, path, "sha256:abc", Stage.WRITING)
            repositories.dirtyPages.mark(pageId, path, expected.expectedHash, expected.stage)
            repositories.dirtyPages.get(rootedId) shouldBe expected
            app.koin.get<DirtyPageRepository>().get(rootedId) shouldBe expected
            opened.get() shouldBe 1
            closed.get() shouldBe 0
        } finally {
            owner.close()
        }
        opened.get() shouldBe 1
        closed.get() shouldBe 1
    }

    test("non-content repositories do not create the content group") {
        val config = ConfigLoader.fromEnv(emptyMap())
        val owner = ServerResourceOwner()
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val app = createOwnedTestKoinApplication(
            owner,
            listOf(
                module { single { config } },
                createRepositoryModule(
                    openDriver = {
                        opened.incrementAndGet()
                        DatabaseFactory.createInMemoryDriver()
                    },
                    closeDriver = { driver ->
                        closed.incrementAndGet()
                        driver.close()
                    },
                    resourceOwner = owner,
                ),
            ),
        )
        val groupFactory = contentRepositoriesFactory(app)
        try {
            groupFactory.isCreated(null) shouldBe false
            listOf<() -> Any>(
                { app.koin.get<ApiTokenRepository>() },
                { app.koin.get<ProposalRepository>() },
                { app.koin.get<RoleRepository>() },
                { app.koin.get<AuditRepository>() },
                { app.koin.get<UserRepository>() },
                { app.koin.get<SessionRepository>() },
                { app.koin.get<SetupTokenRepository>() },
                { app.koin.get<TransactionRunner>() },
            ).forEach { resolve ->
                resolve()
                groupFactory.isCreated(null) shouldBe false
                opened.get() shouldBe 1
            }
            closed.get() shouldBe 0

            val dirty = app.koin.get<DirtyPageRepository>()
            groupFactory.isCreated(null) shouldBe true
            dirty shouldBeSameInstanceAs app.koin.get<ContentRepositories>().dirtyPages
            opened.get() shouldBe 1
            closed.get() shouldBe 0
        } finally {
            owner.close()
        }
        opened.get() shouldBe 1
        closed.get() shouldBe 1
    }
})

@OptIn(KoinInternalApi::class)
private fun contentRepositoriesFactory(app: KoinApplication): SingleInstanceFactory<*> {
    val factories = app.koin.instanceRegistry.instances.values.filter { factory ->
        factory.beanDefinition.primaryType == ContentRepositories::class
    }
    require(factories.size == 1) { "expected one ContentRepositories definition, found ${factories.size}" }
    val factory = factories.single()
    require(factory is SingleInstanceFactory<*>) {
        "ContentRepositories definition was ${factory::class}, not a singleton"
    }
    return factory
}
