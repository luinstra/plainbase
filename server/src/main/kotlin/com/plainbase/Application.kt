package com.plainbase

import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.repository.SessionRepository
import com.plainbase.domain.repository.SetupTokenRepository
import com.plainbase.domain.repository.UserRepository
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.ProposalService
import com.plainbase.domain.service.RebuildScheduler
import com.plainbase.domain.service.WritePipeline
import com.plainbase.frameworks.cli.AdminCommand
import com.plainbase.frameworks.cli.AdoptCommand
import com.plainbase.frameworks.cli.ReindexCommand
import com.plainbase.frameworks.cli.S3SmokeCommand
import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.git.GitBundleDr
import com.plainbase.frameworks.koin.checkpointModule
import com.plainbase.frameworks.koin.contentModule
import com.plainbase.frameworks.koin.historyModule
import com.plainbase.frameworks.koin.indexModule
import com.plainbase.frameworks.koin.repositoryModule
import com.plainbase.frameworks.koin.restModule
import com.plainbase.frameworks.koin.searchModule
import com.plainbase.frameworks.koin.securityModule
import com.plainbase.frameworks.ktor.KtorServer
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.scheduling.ExecutorAlarm
import com.plainbase.frameworks.spike.NativeSpike
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.context.startKoin
import org.koin.dsl.module
import kotlin.system.exitProcess
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    // kotlin-logging 8.x prints a startup banner from KotlinLogging's class initializer unless
    // KotlinLoggingConfiguration reads this property as false - and it reads it exactly once, at
    // its own class init. Set it before anything touches a logger; both classes initialize at
    // run time under JVM and native image alike, so one programmatic gate covers both binaries.
    System.setProperty("kotlin-logging.logStartupMessage", "false")
    when (args.firstOrNull()) {
        "spike" -> exitProcess(NativeSpike.runAsMain())
        "adopt" -> exitProcess(AdoptCommand.runAsMain(args.drop(1)))
        "reindex" -> exitProcess(ReindexCommand.runAsMain(args.drop(1)))
        "admin" -> exitProcess(AdminCommand.runAsMain(args.drop(1)))
        // Hidden (not in the usage line): the credentialed C0 object-store smoke - operator-run, never CI.
        "s3-smoke" -> exitProcess(S3SmokeCommand.runAsMain(args.drop(1)))
        null, "serve" -> serve()
        else -> {
            System.err.println("Unknown command: ${args.first()} (expected: serve | spike | adopt | reindex | admin)")
            exitProcess(2)
        }
    }
}

private fun serve() {
    // Resolve config BEFORE building the Koin graph (R2-2): a bad config (an IllegalArgumentException from the
    // Q9/auth validation, OR a HOCON ConfigException - malformed plainbase.conf, unresolved ${...}, wrong-typed
    // value) must unwrap cleanly into a `serve:` stderr line + exit(1), never a raw stack trace. Resolving here
    // (not via a Koin `single {}`) is what keeps the error unwrapped - Koin would wrap it in an
    // InstanceCreationException that dodges the funnel. The resolved instance is the graph's single source.
    val config = PlainbaseConfig.loadForCommand("serve") ?: exitProcess(1)
    val koin = startKoin {
        modules(
            module { single { config } }, contentModule, repositoryModule, securityModule, indexModule,
            checkpointModule, searchModule, historyModule, restModule,
        )
    }.koin

    // Fail fast, actionably: a missing CONTENT_DIR (or an incomplete object-mode key matrix) must name
    // itself as a `serve:` refusal, not raw-stack-trace out of the `require(...)` - the same funnel as the
    // config load and the gates below, never silently serving an empty tree.
    try {
        config.requireContentDir()
    } catch (e: IllegalArgumentException) {
        System.err.println("serve: ${e.message}")
        exitProcess(1)
    }
    // Q9/Q10 ignored-key warnings (never fatal): local mode names any configured-but-ignored
    // storage.object.* keys; object mode warns when CONTENT_DIR was explicitly set.
    config.storageWarnings().forEach { logger.warn { it } }
    // ADR-0008 fail-closed bind guard: config-only, so it fails BEFORE the heavier git-gate/lock/rebuild work.
    // Same idiom as the gates that follow (System.err + exitProcess(1), never a thrown stack trace) - a bind
    // misconfiguration is an operator-actionable startup refusal, not an argument-precondition bug.
    config.bindGuardRefusal()?.let {
        System.err.println("serve: $it")
        exitProcess(1)
    }
    if (config.auth.insecureHttp && config.isNonLoopbackBind()) {
        logger.warn {
            "PLAINBASE_INSECURE_HTTP set: serving credentials over PLAINTEXT on ${config.host} - anyone on the " +
                "network can capture them (ADR-0008)"
        }
    } else if (config.auth.insecureHttp) {
        logger.info {
            "PLAINBASE_INSECURE_HTTP set but the bind is loopback (${config.host}) - the override is redundant here " +
                "(loopback HTTP is always allowed)"
        }
    }
    // Git gate-check (ADR-0006): AFTER requireContentDir() and BEFORE the lock/rebuild/
    // reconcile block - rebuild() and reconcileDirtyPages() trigger commits, so a "git missing" failure
    // must fire FIRST with an actionable message, never as a doomed commit's stack trace. NoOp is a clean
    // no-op. Mirror the DataDirLock failure idiom: System.err + exitProcess(1), never a thrown trace.
    try {
        koin.get<HistoryProvider>().gateCheck()
    } catch (e: Exception) {
        System.err.println("serve: ${e.message}")
        exitProcess(1)
    }
    // Rev-3.4 DR nudge: an object boot that survives the gate check with git DISABLED (`git.enabled`
    // unset/false) has no commit-grained history, so name the exposure ONCE (backups are operator-owned).
    // Since C5, `git.enabled=true` in object mode wires a real GitCliHistoryProvider + bundle DR, so this
    // WARN no longer fires unconditionally on every object boot - only the git-disabled ones.
    objectModeGitDisabledWarning(config, koin.get<HistoryProvider>())?.let { logger.warn { it } }
    // Hold the DATA_DIR advisory lock for the server's whole lifetime, acquired
    // BEFORE any rebuild/watcher registration. A second server on the same DATA_DIR - or an offline
    // `plainbase reindex` while this one runs - is refused, never silently racing search.db writes.
    val lock = DataDirLock.tryAcquire(config.dataDir)
    if (lock == null) {
        System.err.println("serve: another Plainbase process is holding ${config.dataDir} - stop it before starting a second instance")
        exitProcess(1)
    }
    // Everything past the lock runs INSIDE the try/finally so the lock ALWAYS releases - including a
    // prepare() failure (a forced-on Git hitting a read-only/disk-full content dir, or a `git init` fault),
    // which must surface as the same actionable `serve:` message as gateCheck(), never a raw stack trace
    // that also leaks the lock. Startup ORDER is unchanged: gateCheck (pre-lock) → lock → prepare() →
    // watcher → rebuild.
    try {
        // Object mode: hydrate the DATA_DIR mirror from the bucket FIRST in the lock region, strictly
        // BEFORE the first rebuild() and reconcileDirtyPages() below - both read the post-hydrate
        // mirror through the port, which is what makes a retained-mark recovery commit-or-drift-skip
        // correctly. The first LIST is also the R16 fail-closed TLS/signature self-check; its refusal
        // surfaces via the same System.err + exit(1) idiom as the other gates.
        //
        // C5: when git is enabled, a bundle-DR restore runs strictly BEFORE hydrate, and the boot
        // reconcile strictly AFTER - both in this same lock region, hydrate/hydrate's mirror walk. Nested
        // behind `config.git.enabled == true` so a git-DISABLED object boot never constructs `GitBundleDr`
        // (the R9 lazy-wiring discipline: git-disabled object mode must stay byte-identical to the
        // hydrate-only C4 boot).
        if (config.storage.backend == StorageBackend.OBJECT) {
            try {
                if (config.git.enabled == true) {
                    val bundleDr = koin.get<GitBundleDr>()
                    val restored = bundleDr.restore() // gate + FORK-2 sentinel truth table (2b); a non-404 GET failure aborts boot
                    koin.get<ObjectContentStore>().hydrate(strict = restored.isRestored) // restore path is STRICT (FORK 1)
                    bundleDr.reconcileBootCommit(restored) // ONE plumbing commit or no-op; clears the sentinel
                } else {
                    koin.get<ObjectContentStore>().hydrate()
                }
            } catch (e: Exception) {
                // exitProcess skips the outer finally - release the lock explicitly (the prepare() idiom).
                lock.close()
                System.err.println("serve: ${e.message}")
                exitProcess(1)
            }
        }
        val now = Clock.System.now()
        // Startup-time prune, INSIDE the lock so no other process races the DB: drop dead session/setup-token
        // rows that accumulate in the insert/update-only tables. Once at boot, never per-write (write amplification).
        koin.get<SessionRepository>().prune(now)
        koin.get<SetupTokenRepository>().prune(now)
        // A4b: load-or-generate the proxy-CSRF HMAC server key NOW - inside the lock - so a concurrent boot can never
        // race a double-generate into app_meta. Resolving the ProxyCsrf single forces the key load here rather
        // than relying on the lazy RouteContext resolution timing.
        koin.get<com.plainbase.frameworks.security.ProxyCsrf>()
        // A4a: on an empty / no-enabled-admin builtin DB, emit ONLY a NON-SECRET hint - NEVER a token on
        // the boot path (stdout/stderr are the scraped log under docker/systemd). The secret comes ONLY from the CLI.
        // Reads `countEnabledAdmins` only AFTER the lock is held + validated (fix D: never open/migrate the DB before
        // the lock, so a second process can't open+migrate before losing the lock race).
        if (config.auth.mode == AuthMode.BUILTIN && koin.get<UserRepository>().countEnabledAdmins() == 0L) {
            logger.warn { "Setup required: run `plainbase admin setup-token` to mint the first-admin bootstrap token" }
        }
        // Ready the history backing store now - AFTER the lock validates/owns DATA_DIR (never
        // touch it before the lock; this is why repo init was lazy) and BEFORE the watcher and the first
        // rebuild. The startup rebuild reads (lastCommits) before any save commits, and `git -C workTree log`
        // walks UP to an ancestor `.git` when CONTENT_DIR has none - so a forced-on content root with no own
        // repo would otherwise abort serve (plain dir) or read the wrong ancestor repo. NoOp is a no-op.
        try {
            koin.get<HistoryProvider>().prepare()
        } catch (e: Exception) {
            // exitProcess terminates the JVM without running the outer finally, so release the lock
            // explicitly here - otherwise a forced-on Git failure would leak it in embedded/test use.
            lock.close()
            System.err.println("serve: ${e.message}")
            exitProcess(1)
        }
        val builder = koin.get<IndexBuilder>()
        // §B2 startup ordering, no unwatched window: the watcher registers BEFORE the first rebuild.
        // Events arriving while the initial build is in flight coalesce into at most one follow-up
        // rebuild via the scheduler's single-flight dirty flag.
        val scheduler = RebuildScheduler(rebuild = { builder.rebuild() }, alarm = ExecutorAlarm())
        val watch = koin.get<ContentStore>().watch { scheduler.schedule() }
        try {
            // Full scan at startup builds the snapshot (§C4); the rescan route rebuilds on demand. The
            // rebuild also self-heals the index for any page left dirty by a prior interrupted save.
            builder.rebuild()
            // PB-WRITE-1 fix H: write-ahead recovery of a prior interrupted save, after the index is whole
            // and before serving - drift-skips a page whose on-disk bytes changed since the crash.
            koin.get<WritePipeline>().reconcileDirtyPages()
            // P1b: inspect-then-decide crash-recovery of a prior interrupted APPLY, after the index is whole + after
            // reconcileDirtyPages may have re-committed a crashed dirty page - it resolves each APPLYING row's
            // CURRENT pageId path and stamps APPLIED (write landed) or PENDING (it didn't). Cannot race a live apply.
            koin.get<ProposalService>().reconcileApplying()
            KtorServer(config, koin.get()).start(wait = true)
        } finally {
            watch.close() // stops the object-mode poll thread BEFORE the transport it uses is closed
            scheduler.close()
            // Release the object-store transport (the ktor HttpClient) on shutdown; the poll is already
            // stopped by watch.close() above. LOCAL mode has no transport to close.
            if (config.storage.backend == StorageBackend.OBJECT) {
                // C5: the final graceful-shutdown bundle ship BEFORE the transport it needs closes; same
                // `git.enabled` guard as the boot-side wiring so a git-disabled object boot never touches
                // GitBundleDr here either. Order load-bearing.
                if (config.git.enabled == true) koin.get<GitBundleDr>().close()
                koin.get<ObjectContentStore>().close()
            }
        }
    } finally {
        lock.close()
    }
}

/**
 * The single rev-3.4 backup-guidance WARN (pure accessor, the [PlainbaseConfig.bindGuardRefusal]
 * idiom): non-null exactly when an object-mode boot runs without git history, i.e. point-in-time
 * content recovery is entirely the operator's backup schedule. `serve()` logs it ONCE; there is no
 * snapshot or manifest writer (backups are operator-owned by decision).
 */
internal fun objectModeGitDisabledWarning(config: PlainbaseConfig, history: HistoryProvider): String? =
    if (config.storage.backend == StorageBackend.OBJECT && !history.enabled) {
        "object mode with git disabled: no commit-grained history; content point-in-time recovery is " +
            "your backup schedule (see the backup guidance)"
    } else {
        null
    }
