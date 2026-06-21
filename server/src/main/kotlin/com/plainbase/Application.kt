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
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.context.startKoin
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

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
    // ADR-0008 fail-closed bind guard: config-only, so it fails BEFORE the heavier git-gate/lock/rebuild work.
    // Same idiom as the gates that follow (System.err + exitProcess(1), never a thrown stack trace) — a bind
    // misconfiguration is an operator-actionable startup refusal, not an argument-precondition bug.
    config.bindGuardRefusal()?.let {
        System.err.println("serve: $it")
        exitProcess(1)
    }
    if (config.auth.insecureHttp && config.isNonLoopbackBind()) {
        logger.warn {
            "PLAINBASE_INSECURE_HTTP set: serving credentials over PLAINTEXT on ${config.host} — anyone on the " +
                "network can capture them (ADR-0008)"
        }
    }
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
    // Everything past the lock runs INSIDE the try/finally so the lock ALWAYS releases — including a
    // prepare() failure (a forced-on Git hitting a read-only/disk-full content dir, or a `git init` fault),
    // which must surface as the same actionable `serve:` message as gateCheck(), never a raw stack trace
    // that also leaks the lock. Startup ORDER is unchanged: gateCheck (pre-lock) → lock → prepare() →
    // watcher → rebuild.
    try {
        // W5 P1: ready the history backing store now — AFTER the lock validates/owns DATA_DIR (P1-3: never
        // touch it before the lock; this is why repo init was lazy) and BEFORE the watcher and the first
        // rebuild. The startup rebuild reads (lastCommits) before any save commits, and `git -C workTree log`
        // walks UP to an ancestor `.git` when CONTENT_DIR has none — so a forced-on content root with no own
        // repo would otherwise abort serve (plain dir) or read the wrong ancestor repo. NoOp is a no-op.
        try {
            koin.get<HistoryProvider>().prepare()
        } catch (e: Exception) {
            // exitProcess terminates the JVM without running the outer finally, so release the lock
            // explicitly here — otherwise a forced-on Git failure would leak it in embedded/test use.
            lock.close()
            System.err.println("serve: ${e.message}")
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
        }
    } finally {
        lock.close()
    }
}
