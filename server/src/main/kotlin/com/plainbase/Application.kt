package com.plainbase

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.RebuildScheduler
import com.plainbase.domain.service.WritePipeline
import com.plainbase.frameworks.cli.AdoptCommand
import com.plainbase.frameworks.cli.ReindexCommand
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.koin.checkpointModule
import com.plainbase.frameworks.koin.configModule
import com.plainbase.frameworks.koin.contentModule
import com.plainbase.frameworks.koin.historyModule
import com.plainbase.frameworks.koin.indexModule
import com.plainbase.frameworks.koin.repositoryModule
import com.plainbase.frameworks.koin.restModule
import com.plainbase.frameworks.koin.searchModule
import com.plainbase.frameworks.koin.securityModule
import com.plainbase.frameworks.ktor.KtorServer
import com.plainbase.frameworks.spike.NativeSpike
import org.koin.core.context.startKoin
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // kotlin-logging 8.x prints a startup banner from KotlinLogging's class initializer unless
    // KotlinLoggingConfiguration reads this property as false — and it reads it exactly once, at
    // its own class init. Set it before anything touches a logger; both classes initialize at
    // run time under JVM and native image alike, so one programmatic gate covers both binaries.
    System.setProperty("kotlin-logging.logStartupMessage", "false")
    when (args.firstOrNull()) {
        "spike" -> exitProcess(NativeSpike.runAsMain())
        "adopt" -> exitProcess(AdoptCommand.runAsMain(args.drop(1)))
        "reindex" -> exitProcess(ReindexCommand.runAsMain(args.drop(1)))
        null, "serve" -> serve()
        else -> {
            System.err.println("Unknown command: ${args.first()} (expected: serve | spike | adopt | reindex)")
            exitProcess(2)
        }
    }
}

private fun serve() {
    val koin = startKoin {
        modules(
            configModule, contentModule, repositoryModule, securityModule, indexModule, checkpointModule, searchModule,
            historyModule, restModule,
        )
    }.koin

    val config = koin.get<PlainbaseConfig>()
    // Fail fast, actionably: a missing CONTENT_DIR must name itself, not surface as the scan's
    // bare NoSuchFileException — and never silently serve an empty tree.
    config.requireContentDir()
    // W4 gate-check (ADR-0006, M2 ordering): AFTER requireContentDir() and BEFORE the lock/rebuild/
    // reconcile block — rebuild() and reconcileDirtyPages() trigger commits, so a "git missing" failure
    // must fire FIRST with an actionable message, never as a doomed commit's stack trace. NoOp is a clean
    // no-op. Mirror the DataDirLock failure idiom: System.err + exitProcess(1), never a thrown trace.
    try {
        koin.get<HistoryProvider>().gateCheck()
    } catch (e: Exception) {
        System.err.println("serve: ${e.message}")
        exitProcess(1)
    }
    // Resolution 1b: hold the DATA_DIR advisory lock for the server's whole lifetime, acquired
    // BEFORE any rebuild/watcher registration. A second server on the same DATA_DIR — or an offline
    // `plainbase reindex` while this one runs — is refused, never silently racing search.db writes.
    val lock = DataDirLock.tryAcquire(config.dataDir)
    if (lock == null) {
        System.err.println("serve: another Plainbase process is holding ${config.dataDir} — stop it before starting a second instance")
        exitProcess(1)
    }
    val builder = koin.get<IndexBuilder>()
    // §B2 startup ordering, no unwatched window: the watcher registers BEFORE the first rebuild.
    // Events arriving while the initial build is in flight coalesce into at most one follow-up
    // rebuild via the scheduler's single-flight dirty flag.
    val scheduler = RebuildScheduler(rebuild = { builder.rebuild() })
    val watch = koin.get<ContentStore>().watch { scheduler.schedule() }
    try {
        // Full scan at startup builds the snapshot (§C4); the rescan route rebuilds on demand. The
        // rebuild also self-heals the index for any page left dirty by a prior interrupted save.
        builder.rebuild()
        // PB-WRITE-1 fix H: write-ahead recovery of a prior interrupted save, after the index is whole
        // and before serving — drift-skips a page whose on-disk bytes changed since the crash.
        koin.get<WritePipeline>().reconcileDirtyPages()
        KtorServer(config, koin.get()).start(wait = true)
    } finally {
        watch.close()
        scheduler.close()
        lock.close()
    }
}
