package com.plainbase.frameworks.koin

import com.plainbase.domain.discussion.DiscussionIndex
import com.plainbase.domain.discussion.DiscussionRows
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.service.ContentWriteMonitor
import com.plainbase.domain.service.DiscussionFacade
import com.plainbase.domain.service.DiscussionFullReads
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.PageReindexListener
import com.plainbase.domain.service.SyncedDiscussionIndex
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.discussion.DiscussionBoot
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.frameworks.ktor.GuardedDiscussionFacade
import com.plainbase.frameworks.lifecycle.ServerResourceOwner
import com.plainbase.frameworks.protocol.DiscussionTransportFacade
import com.plainbase.frameworks.runtime.HistoryProviders
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.mockk
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Path
import kotlin.time.Clock

class DiscussionModuleTest : FunSpec({
    test("transport and domain aliases resolve to the same guarded singleton") {
        val guarded = mockk<GuardedDiscussionFacade>()
        val application = koinApplication {
            modules(createDiscussionModule(ServerResourceOwner()), module { single { guarded } })
        }
        try {
            application.koin.get<DiscussionTransportFacade>() shouldBeSameInstanceAs guarded
            application.koin.get<DiscussionFacade>() shouldBeSameInstanceAs guarded
        } finally {
            application.close()
        }
    }

    test("the discussion listeners are one registered instance") {
        val application = koinApplication { modules(createDiscussionModule(ServerResourceOwner())) }
        try {
            val publication = application.koin.get<IndexBuilder.PublicationListener>(named("discussionPrecompute"))
            val reindex = application.koin.get<PageReindexListener>()

            publication shouldBeSameInstanceAs reindex
        } finally {
            application.close()
        }
    }

    test("the discussion index single is the synced index") {
        val synced = mockk<SyncedDiscussionIndex>()
        val application = koinApplication {
            modules(
                createDiscussionModule(ServerResourceOwner()),
                module { single { synced } },
            )
        }
        try {
            application.koin.get<DiscussionIndex>() shouldBeSameInstanceAs application.koin.get<SyncedDiscussionIndex>()
        } finally {
            application.close()
        }
    }

    test("the boot and index share the full read limiter") {
        val root = Root(
            name = RootName.PRIMARY,
            backend = RootBackend.Local(Path.of("/roots/docs")),
            editable = true,
            history = HistoryMode.OFF,
        )
        val dependencies = module {
            single<PlainbaseConfig> {
                PlainbaseConfig(Path.of("/roots/docs"), Path.of("/tmp/discussion-module"), "127.0.0.1", 8080)
            }
            single<RootRegistry> { RootRegistry.of(listOf(root)) }
            single { RootAvailability(Clock.System) }
            single { HistoryProviders(mapOf(root.name to NoOpHistoryProvider)) }
            single { ContentWriteMonitor() }
            single<DiscussionRows> { mockk(relaxed = true) }
            single<DiscussionStore> { mockk(relaxed = true) }
        }
        val application = koinApplication { modules(createDiscussionModule(ServerResourceOwner()), dependencies) }
        try {
            val bootReads = application.koin.get<DiscussionBoot>().fullReads
            val indexReads = application.koin.get<SyncedDiscussionIndex>().fullReads

            bootReads shouldBeSameInstanceAs indexReads
            indexReads shouldBeSameInstanceAs application.koin.get<DiscussionFullReads>()
        } finally {
            application.close()
        }
    }
})
