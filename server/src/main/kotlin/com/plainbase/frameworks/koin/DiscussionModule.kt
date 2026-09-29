package com.plainbase.frameworks.koin

import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.discussion.DiscussionIndex
import com.plainbase.domain.discussion.DiscussionPageSource
import com.plainbase.domain.discussion.DiscussionRows
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.service.AbsenceClassifier
import com.plainbase.domain.service.AnchorMatches
import com.plainbase.domain.service.AnchorPrecompute
import com.plainbase.domain.service.DiscussionFacade
import com.plainbase.domain.service.DiscussionFullReads
import com.plainbase.domain.service.DiscussionIdProvider
import com.plainbase.domain.service.DiscussionPageResolver
import com.plainbase.domain.service.DiscussionPublicationSignal
import com.plainbase.domain.service.DiscussionReads
import com.plainbase.domain.service.DiscussionReparseExecutor
import com.plainbase.domain.service.DiscussionReparser
import com.plainbase.domain.service.DiscussionSyncState
import com.plainbase.domain.service.DiscussionWriter
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.PageReindexListener
import com.plainbase.domain.service.SyncedDiscussionIndex
import com.plainbase.domain.service.UuidV7DiscussionIdProvider
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.discussion.DiscussionBoot
import com.plainbase.frameworks.discussion.DiscussionDb
import com.plainbase.frameworks.discussion.JdbcDiscussionRows
import com.plainbase.frameworks.filesystem.LocalDiscussionStore
import com.plainbase.frameworks.filesystem.rootLivenessProbe
import com.plainbase.frameworks.ktor.DiscussionReadProjection
import com.plainbase.frameworks.ktor.GuardedDiscussionFacade
import com.plainbase.frameworks.lifecycle.ServerResourceOwner
import com.plainbase.frameworks.lifecycle.ServerResourcePhase
import com.plainbase.frameworks.runtime.HistoryProviders
import com.plainbase.frameworks.runtime.ObservedIndexRuntime
import com.plainbase.frameworks.runtime.RootStores
import com.plainbase.frameworks.scheduling.ExecutorAlarm
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose
import kotlin.time.Clock

internal fun createDiscussionModule(resourceOwner: ServerResourceOwner) = module {
    single {
        val databasePath = get<PlainbaseConfig>().discussionDatabasePath
        resourceOwner.construct("discussion database") {
            DiscussionDb(databasePath).also { database ->
                resourceOwner.own(ServerResourcePhase.DISCUSSION_DATABASE, database) { it.close() }
            }
        }
    } onClose {
        resourceOwner.drainServices()
    }
    single<DiscussionRows> { JdbcDiscussionRows(get<DiscussionDb>()) }
    single<LocalDiscussionStore> {
        val registry = get<RootRegistry>()
        val scopedRoots = registry.roots.filter { it.editable && it.backend is RootBackend.Local }
        val roots = scopedRoots.associate { root -> root.name to (root.backend as RootBackend.Local).path }
        val probes = roots.values.associate { path ->
            val normalized = path.toAbsolutePath().normalize()
            normalized to rootLivenessProbe(normalized)
        }
        val availability = get<RootAvailability>()
        LocalDiscussionStore(
            roots = roots,
            probeRoot = { base -> probes[base]?.invoke(base) ?: false },
            onRootUnavailable = { root -> availability.markUnavailable(root, UnavailableCause.VANISHED) },
        )
    }
    single<DiscussionStore> { get<LocalDiscussionStore>() }
    single {
        val roots = get<RootRegistry>().roots
            .filter { it.editable && it.backend is RootBackend.Local }
            .map { it.name }
        DiscussionSyncState(roots)
    }
    single { DiscussionFullReads(get<DiscussionStore>()) }
    single<DiscussionIdProvider> { UuidV7DiscussionIdProvider() }
    single<DiscussionPageSource> {
        val builder = get<ObservedIndexRuntime>().builder
        val absence = get<AbsenceClassifier>()
        val stores = get<RootStores>()
        DiscussionPageSource { root, ref ->
            val snapshot = builder.current
            absence.requireVerifiedAbsence(root, ref.pageId, snapshot)
            val page = snapshot.pageAt(RootedPageId(root, ref.pageId))
            if (page == null) ContentRead.ConfirmedAbsent else absence.read(stores[root], RootedPath(root, page.path))
        }
    }
    single {
        val histories = get<HistoryProviders>()
        DiscussionWriter(
            monitor = get(),
            store = get(),
            pages = get(),
            histories = histories::get,
            index = get(),
            ids = get(),
            clock = Clock.System,
        )
    }
    single {
        val index = get<ObservedIndexRuntime>()
        val stores = get<RootStores>()
        GuardedDiscussionFacade(
            policy = get(), writer = get(), reads = get(), registry = get(), availability = get(),
            resolver = get(), absence = get(), indexBuilder = index.builder, stores = stores::get, labeler = get(),
            projection = get(),
        )
    }
    single<DiscussionFacade> { get<GuardedDiscussionFacade>() }
    single {
        val stores = get<RootStores>()
        DiscussionReadProjection(get(), get(), get(), get(), stores::get)
    }
    single<SyncedDiscussionIndex> {
        SyncedDiscussionIndex(get(), get(), get(), get())
    }
    single<DiscussionIndex> { get<SyncedDiscussionIndex>() }
    single {
        val scopeRoots = get<RootRegistry>().roots
            .filter { it.editable && it.backend is RootBackend.Local }
            .mapTo(linkedSetOf()) { it.name }
        DiscussionReparser(scopeRoots, get(), get(), get())
    }
    single {
        DiscussionReparseExecutor(
            reparser = get(),
            sync = get(),
            availability = get(),
            alarm = ExecutorAlarm("plainbase-discussion-reparse"),
            afterClear = { root -> get<DiscussionPublicationSignal>().rootSynced(root) },
        )
    }
    single {
        DiscussionReads(get(), get(), get(), get(), get())
    }
    single { DiscussionPageResolver(sync = get(), availability = get(), absence = get()) }
    single { AnchorMatches(get(), get(), get(), get()) }
    single {
        val stores: Map<RootName, ContentStore> = get<RootStores>().let { rootStores ->
            get<RootRegistry>().roots.associate { root -> root.name to rootStores[root.name] }
        }
        val builder = get<IndexBuilder>()
        AnchorPrecompute(
            rows = get(),
            discussions = get(),
            contents = { root -> requireNotNull(stores[root]) { "no content store for root '${root.value}'" } },
            fullReads = get(),
            absence = get<AbsenceClassifier>(),
            sync = get(),
            availability = get<RootAvailability>(),
            current = builder::current,
            alarm = ExecutorAlarm("plainbase-anchor-precompute"),
        )
    }
    single { DiscussionPublicationSignal() }
    single<IndexBuilder.PublicationListener>(named("discussionPrecompute")) { get<DiscussionPublicationSignal>() }
    single<PageReindexListener> { get<DiscussionPublicationSignal>() }
    single {
        val config = get<PlainbaseConfig>()
        DiscussionBoot(
            rows = get(),
            store = get(),
            fullReads = get(),
            reparser = get(),
            sync = get(),
            histories = get<HistoryProviders>(),
            monitor = get(),
            availability = get(),
            registry = get(),
            clock = Clock.System,
            identity = CommitIdentity(config.git.authorName, config.git.authorEmail),
        )
    }
}
