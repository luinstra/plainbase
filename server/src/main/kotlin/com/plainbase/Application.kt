package com.plainbase

import app.cash.sqldelight.db.SqlDriver
import com.plainbase.domain.content.ContentStore
import com.plainbase.domain.content.WatchCoverage
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.SessionRepository
import com.plainbase.domain.repository.SetupTokenRepository
import com.plainbase.domain.repository.UserRepository
import com.plainbase.domain.root.BindingLatch
import com.plainbase.domain.root.BindingStatus
import com.plainbase.domain.root.BootRefusal
import com.plainbase.domain.root.DetachedRoots
import com.plainbase.domain.root.ObservationEpoch
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootConvergence
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.ProposalService
import com.plainbase.domain.service.WritePipeline
import com.plainbase.frameworks.cli.AdminCommand
import com.plainbase.frameworks.cli.AdoptCommand
import com.plainbase.frameworks.cli.CommandOutput
import com.plainbase.frameworks.cli.ReindexCommand
import com.plainbase.frameworks.cli.RootCommand
import com.plainbase.frameworks.cli.S3SmokeCommand
import com.plainbase.frameworks.cli.systemCommandOutput
import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.config.ConfigLoader
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.config.TransportSecurityPolicy
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.git.GitBundleDr
import com.plainbase.frameworks.koin.checkpointModule
import com.plainbase.frameworks.koin.createContentModule
import com.plainbase.frameworks.koin.createHistoryModule
import com.plainbase.frameworks.koin.createRepositoryModule
import com.plainbase.frameworks.koin.createRestModule
import com.plainbase.frameworks.koin.createSearchModule
import com.plainbase.frameworks.koin.indexModule
import com.plainbase.frameworks.koin.securityModule
import com.plainbase.frameworks.ktor.KtorServer
import com.plainbase.frameworks.lifecycle.GitMaintenanceTasks
import com.plainbase.frameworks.lifecycle.GracefulShutdown
import com.plainbase.frameworks.lifecycle.ServerResourceOwner
import com.plainbase.frameworks.lifecycle.ServerResourcePhase
import com.plainbase.frameworks.lifecycle.ServerRunControl
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.runtime.DeferredObjectHistory
import com.plainbase.frameworks.runtime.HistoryProviders
import com.plainbase.frameworks.runtime.ObjectHistoryCallbacks
import com.plainbase.frameworks.runtime.RootBootInputs
import com.plainbase.frameworks.runtime.RootBootProbe
import com.plainbase.frameworks.runtime.RootHistorySelection
import com.plainbase.frameworks.runtime.RootStores
import com.plainbase.frameworks.runtime.ServerOpeners
import com.plainbase.frameworks.runtime.prepareRootBootInputs
import com.plainbase.frameworks.scheduling.ExecutorAlarm
import com.plainbase.frameworks.spike.NativeSpike
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.Koin
import org.koin.core.error.InstanceCreationException
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlin.time.Clock

private val logger by lazy { KotlinLogging.logger {} }

fun main(args: Array<String>) {
    // kotlin-logging 8.x prints a startup banner from KotlinLogging's class initializer unless
    // KotlinLoggingConfiguration reads this property as false - and it reads it exactly once, at
    // its own class init. Set it before anything touches a logger; both classes initialize at
    // run time under JVM and native image alike, so one programmatic gate covers both binaries.
    System.setProperty("kotlin-logging.logStartupMessage", "false")
    val output = systemCommandOutput()
    when (args.firstOrNull()) {
        "spike" -> exitProcess(NativeSpike.runAsMain(output))
        "adopt" -> exitProcess(AdoptCommand.runAsMain(args.drop(1), output))
        "reindex" -> exitProcess(ReindexCommand.runAsMain(args.drop(1), output))
        "admin" -> exitProcess(AdminCommand.runAsMain(args.drop(1), output))
        "root" -> exitProcess(RootCommand.runAsMain(args.drop(1), output))
        // Hidden (not in the usage line): the credentialed C0 object-store smoke - operator-run, never CI.
        "s3-smoke" -> exitProcess(S3SmokeCommand.runAsMain(args.drop(1), output))
        null, "serve" -> serve(output)
        else -> {
            output.error("Unknown command: ${args.first()} (expected: serve | spike | adopt | reindex | admin | root)")
            exitProcess(2)
        }
    }
}

private fun serve(output: CommandOutput) {
    // Resolve config BEFORE building the Koin graph so loader failures remain the CLI's expected status-1 path.
    val status = ConfigLoader.loadForCommand("serve", output::error)?.let { config -> runServer(config, output) } ?: 1
    if (status != 0) exitProcess(1)
}

@Suppress("TooGenericExceptionCaught")
internal fun runServer(
    config: PlainbaseConfig,
    output: CommandOutput,
    openers: ServerOpeners = ServerOpeners(),
    control: ServerRunControl = ServerRunControl(),
): Int =
    try {
        runOwnedServer(config, output, openers, control)
        0
    } catch (_: ServeRefusal) {
        1
    } catch (failure: Throwable) {
        throw unwrapOpenerFailure(failure) ?: failure
    }

@Suppress("ktlint:standard:no-unit-return")
private fun runOwnedServer(
    config: PlainbaseConfig,
    output: CommandOutput,
    openers: ServerOpeners,
    control: ServerRunControl,
): Unit {
    val resources = ServerResourceOwner()

    val runOpeners = ServerOpeners(
        openDriver = { path -> openTyped { openers.openDriver(path) } },
        openLocal = { inputs -> openTyped { openers.openLocal(inputs) } },
        openObject = { objectConfig, ignoreRules, dirtyPaths, isDirty, rowsAtStart ->
            openTyped { openers.openObject(objectConfig, ignoreRules, dirtyPaths, isDirty, rowsAtStart) }
        },
        openSearch = { path -> openTyped { openers.openSearch(path) } },
    )

    val app = resources.construct("Koin context") {
        koinApplication().also { resources.own(ServerResourcePhase.KOIN_CONTEXT, it, control.closeContext) }
    }
    var hook: Thread? = null

    try {
        control.onContextAcquired(app)
        val maintenanceTasks = resources.construct("git maintenance tasks") {
            GitMaintenanceTasks().also(resources::ownMaintenance)
        }
        app.modules(
            module { single { config } },
            createRepositoryModule(runOpeners.openDriver, control.closeDriver, resources),
            securityModule,
            indexModule,
            checkpointModule,
            createSearchModule(runOpeners.openSearch, control.closeSearch, resources),
            createRestModule(resources, control.afterRouteContextBuilt, control.buildRouteContext),
        )
        val koin = app.koin
        // THE BOOT GATE, consumed in STAGES (C5 S1.7). The gate itself - `evaluateBootGate` - is the ONE function
        // `plainbase root` also runs, over the candidate roots.conf it is about to write, so a refusal added to
        // boot lands in the CLI for free and neither can drift from the other.
        //
        // CONSUME IT IN THE ORDER `serve()` HAS ALWAYS EMITTED, not at one `firstOrNull()`. A refusal that jumps
        // the queue SWALLOWS every warning behind it: a git-gate failure exits AFTER the storage/roots warnings
        // and after every earlier root's unavailable-WARN have printed, and an operator losing a boot warning is
        // information loss on the exact surface multi-root exists to make visible.
        //
        // The CONFIG+FILESYSTEM refusals (sites 2 + 3) are consumed before any lazy service is resolved. The isolated
        // context itself is already owned, so refusal cleanup remains explicit and cannot touch the global Koin context.
        consumeConfigBootGate(config, output)
        // Site 4 - the per-root verdicts (ADR-0006 git gate + the D5-over-D4 availability probe), walked in REGISTRY
        // (rank) order, which is the order the loop this replaces walked. An Unavailable WARNs and CONTINUES; a
        // Refused exits 1. This loop is also where boot availability is SEEDED - the gate DECIDES, `serve()` ACTS
        // (the `detachedRootsRefusal` idiom): `markUnavailable` mutates a runtime singleton, so it must never live
        // inside a function the CLI also calls.
        //
        // Still BEFORE the lock/rebuild/reconcile block, because rebuild() and reconcileDirtyPages() trigger commits
        // and a "git missing" failure must fire FIRST with an actionable message, never as a doomed commit's stack trace.
        val bootInputs = prepareRootBootInputs(config, runOpeners.openLocal, maintenanceTasks)
        consumeRootBootGate(config, bootInputs, output)
        val availability = bootInputs.availability
        control.onBootAvailability(availability)
        val historySelection = bootInputs.history
        // Rev-3.4 DR nudge: an object boot that survives the gate check with git DISABLED (`git.enabled`
        // unset/false) has no commit-grained history, so name the exposure ONCE (backups are operator-owned).
        // Since C5, `git.enabled=true` in object mode wires a real GitCliHistoryProvider + bundle DR, so this
        // WARN no longer fires unconditionally on every object boot - only the git-disabled ones.
        objectModeGitDisabledWarning(config, historySelection.byRoot.getValue(bootInputs.registry.primary.name))?.let {
            logger.warn { it }
        }
        // Hold the DATA_DIR advisory lock for the server's whole lifetime, acquired
        // BEFORE any rebuild/watcher registration. A second server on the same DATA_DIR - or an offline
        // `plainbase reindex` while this one runs - is refused, never silently racing search.db writes.
        val ownedLock = resources.construct("DATA_DIR lock") {
            DataDirLock.tryAcquire(config.dataDir)?.also { resources.own(ServerResourcePhase.DATA_DIR_LOCK, it) { lock -> lock.close() } }
        }
        if (ownedLock == null) {
            refuseServe(output, "another Plainbase process is holding ${config.dataDir} - stop it before starting a second instance")
        }
        // Everything past the lock runs INSIDE the try/finally so the lock ALWAYS releases - including a
        // prepare() failure (a forced-on Git hitting a read-only/disk-full content dir, or a `git init` fault),
        // which must surface as the same actionable `serve:` message as gateCheck(), never a raw stack trace
        // that also leaks the lock. Startup ORDER is unchanged: gateCheck (pre-lock) → lock → prepare() →
        // watcher → rebuild.
        run {
            // First app-database open: the driver is resolved only after the DATA_DIR lock and before repository/epoch
            // consumers are resolved.
            koin.get<SqlDriver>()
            koin.loadModules(
                listOf(
                    createContentModule(config, bootInputs, runOpeners.openObject, control.closeObject, resources),
                    createHistoryModule(config, bootInputs.history, resources, control.onDrAcquired, control.closeDr),
                ),
            )
            val bootEpoch = koin.get<ObservationEpoch>()
            bootInputs.signals.arm(bootEpoch::broke)
            if (config.storage.backend == StorageBackend.OBJECT) koin.get<ObjectContentStore>()
            val stores = koin.get<RootStores>()
            val historyProviders = koin.get<HistoryProviders>()
            // Multi-root C2 boot guard (ADR-0011 D1/D15): bindings under roots absent from the config
            // WARN; a nonempty id_map ENTIRELY disjoint from the config refuses to serve. The driver
            // and post-lock content/history modules are resolved before this guard so their shared
            // instances are ready for the remainder of startup; all resource-bearing resolution stays here,
            // after DataDirLock.tryAcquire.
            //
            // The guard runs BEFORE the object-mode hydrate/git-DR branch, and that ordering is CORRECT rather than
            // merely tolerated: this reads id_map (the app DB), while the restore/hydrate branch touches the bucket and
            // the DATA_DIR mirror and never id_map - so the guard sees the same rows on either side of it. Multi-root
            // does not change that: object mode stays single-root by decision (an explicit `roots {}` plus object storage
            // is a boot error), so there is no multi-root object wiring for it to race.
            detachedRootsRefusal(
                koin.get<IdMapRepository>().roots(),
                // The REGISTRY, not config.roots.list: one runtime topology snapshot for the guard to
                // agree with (identical names by construction).
                koin.get<RootRegistry>().roots.map { it.name }.toSet(),
            )?.let { refusal -> refuseServe(output, refusal) }
            // Object mode: hydrate the DATA_DIR mirror from the bucket FIRST in the lock region, strictly
            // BEFORE the first rebuild() and reconcileDirtyPages() below - both read the post-hydrate
            // mirror through the port, which is what makes a retained-mark recovery commit-or-drift-skip
            // correctly. The first LIST is also the R16 fail-closed TLS/signature self-check; its refusal
            // surfaces via the same deterministic error channel and status-1 refusal path as the other gates.
            //
            // C5: when git is enabled, a bundle-DR restore runs strictly BEFORE hydrate, and the boot
            // reconcile strictly AFTER - both in this same lock region, hydrate/hydrate's mirror walk. Nested
            // behind `config.git.enabled == true` so a git-DISABLED object boot never constructs `GitBundleDr`
            // (the R9 lazy-wiring discipline: git-disabled object mode must stay byte-identical to the
            // hydrate-only C4 boot).
            hydrateObjectMode(config, koin, bootInputs.history.objectHistory, output, control)
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
            // Reads `countEnabledAdmins` only AFTER the lock is held + validated.
            if (config.auth.mode == AuthMode.BUILTIN && koin.get<UserRepository>().countEnabledAdmins() == 0L) {
                logger.warn { "Setup required: run `plainbase admin setup-token` to mint the first-admin bootstrap token" }
            }
            // Ready the history backing store now - AFTER the lock validates/owns DATA_DIR (never
            // touch it before the lock; this is why repo init was lazy) and BEFORE the watcher and the first
            // rebuild. The startup rebuild reads (lastCommits) before any save commits, and `git -C workTree log`
            // walks UP to an ancestor `.git` when CONTENT_DIR has none - so a forced-on content root with no own
            // repo would otherwise abort serve (plain dir) or read the wrong ancestor repo. NoOp is a no-op.
            prepareHistories(config, bootInputs.registry, historyProviders, bootInputs.history, availability, output)
            val builder = koin.get<IndexBuilder>()
            // §B2 startup ordering, no unwatched window: the watchers register BEFORE the first rebuild.
            // Events arriving while the initial build is in flight coalesce into at most one follow-up
            // rebuild via the scheduler's single-flight dirty flag.
            val scheduler = resources.construct("rebuild scheduler") {
                control.createScheduler(builder).also {
                    resources.own(ServerResourcePhase.SCHEDULER, it) { scheduler -> scheduler.close() }
                }
            }
            // ONE watcher per AVAILABLE root, all feeding the ONE debounced scheduler. The scheduler stays root-BLIND
            // and needs no change: a rebuild is a whole-corpus pass, so a vanished root's queued events are harmless
            // (the next pass's probe skips it), and the root on each closure is carried for LOGGING only. A root that
            // was unavailable at boot gets no watcher at all - there is nothing to watch, and the status is sticky
            // until restart anyway. Which means every AVAILABLE root has a watcher, and that is what makes the
            // watcher's root-liveness probe (ContentStore.watch) a corpus-wide bound rather than a per-root nicety:
            // an idle root's loss is detected without any traffic to trip it.
            //
            // The two callbacks below are DIFFERENT KINDS OF FACT, and keeping them apart is the whole point:
            //  - onFailure is the worker's DEATH - the tree stops converging for good, which is an outage a restart
            //    genuinely fixes, so it marks the root unavailable (503, sticky, `watcher_failed`);
            //  - onCoverage is how much of the tree the watcher can SEE. A subtree it cannot register (the inotify
            //    watch limit, a `chmod 000` directory) leaves a root that is THERE and serves every byte - it just
            //    converges on a periodic pass instead of on events. Marking THAT unavailable would 503 a healthy root
            //    over a host-wide kernel limit, stickily, until a restart that only re-registers, re-fails and
            //    re-marks. So it lands in the non-sticky convergence holder, flips back on its own, and reaches the
            //    operator through `/healthz` rather than through an outage.
            //  - onBreak is the C2 seam, and it is a THIRD kind of fact again: a GAP in the observation. A dropped-event
            //    storm, a subtree that stopped being watched, a key that died under a directory still standing, a tree
            //    swapped out by a deploy - each one means this watcher cannot honestly say it has been watching without
            //    interruption, and an observation epoch is the only thing in the system that may turn "the page is not
            //    there any more" into a DELETE. So a break revokes that authority wholesale and the root's unproven rows
            //    fall back to limbo. It is deliberately NOT an availability mark and NOT a coverage report: the root is
            //    usually perfectly healthy, and what it lost is its standing to delete, not its ability to serve.
            val convergence = koin.get<RootConvergence>()
            val epochs = koin.get<ObservationEpoch>()
            val watchers = koin.get<RootRegistry>().roots
                .filter { availability.current().isAvailable(it.name) }
                .map { root ->
                    control.onWatcherRegistration(root.name)
                    // Installing the watcher is what makes an epoch EARNABLE here, so it is what declares it (C2), and
                    // an object-backed main declares it too - the rebuild is what withholds EPOCH from a backend whose
                    // watch is a poller. A root with no watcher earns nothing, which is the honest floor: two scans with
                    // an `rm` between them and two scans with an unmounted submount between them are the same pair of
                    // scans, and only a watcher can tell them apart.
                    epochs.observing(root.name)
                    resources.construct("watcher for ${root.name}") {
                        stores[root.name].watch(
                            onChange = { scheduler.schedule() },
                            onFailure = { failure ->
                                logger.error(failure) { "the watcher for root '${root.name}' died; marking it unavailable" }
                                availability.markUnavailable(root.name, UnavailableCause.WATCHER_FAILED)
                            },
                            onCoverage = { coverage -> convergence.record(root.name, whole = coverage == WatchCoverage.WHOLE) },
                            onBreak = { cause -> epochs.broke(root.name, cause) },
                        ).also { watcher -> resources.own(ServerResourcePhase.WATCHERS, watcher, control.closeWatcher) }
                            .also { watcher -> control.onWatcherAcquired(root.name, watcher) }
                    }
                }
            val routeContext = koin.get<com.plainbase.frameworks.ktor.RouteContext>()
            control.onRuntimeContext(routeContext)
            val server = resources.construct("HTTP server") {
                control.createHttpServer(config, routeContext).also { server ->
                    resources.own(ServerResourcePhase.HTTP, server, control.closeHttp)
                    resources.registerServiceAdmissionClose(server::closeAdmission)
                    control.onHttpAcquired(server)
                }
            }
            // Plainbase's hook and the normal-return `finally` both invoke this teardown. Ktor registers a separate
            // engine hook that stops its engine; the shared Plainbase resource closers converge if those paths race.
            //
            // ORDER IS LOAD-BEARING: the HTTP server drains first, so no in-flight save is severed mid-write;
            // watchers stop the object-mode poll thread BEFORE the transport it uses; the scheduler drains before
            // the DR flush, so an in-flight rebuild's commits still make the final bundle; the transport closes
            // after the ship that needs it; the DATA_DIR lock releases last, once nothing is writing under it.
            //
            // Collaborator forecasts feed shutdown diagnostics; they do not cap completion waits.
            // The supervisor owns forced termination, while cleanup retains dependencies until preceding work completes.
            val activeShutdown = GracefulShutdown(
                resources.steps(
                    mapOf(
                        ServerResourcePhase.HTTP to KtorServer.STOP_BOUND_MILLIS,
                        ServerResourcePhase.WATCHERS to watchers.size * ContentStore.WATCH_CLOSE_BOUND_MILLIS,
                        ServerResourcePhase.SCHEDULER to ExecutorAlarm.CLOSE_BOUND_MILLIS,
                        ServerResourcePhase.DISASTER_RECOVERY to GitBundleDr.CLOSE_BOUND_MILLIS,
                    ),
                ),
                warningState = resources.warningState,
                warningStateInitializer = resources::initializeWarningRun,
                managesWarningPhases = false,
            )
            try {
                // Full scan at startup builds the snapshot (§C4); the rescan route rebuilds on demand. The
                // rebuild also self-heals the index for any page left dirty by a prior interrupted save.
                control.initialRebuild(builder)
                // PB-WRITE-1 fix H: write-ahead recovery of a prior interrupted save, after the index is whole
                // and before serving - drift-skips a page whose on-disk bytes changed since the crash.
                koin.get<WritePipeline>().reconcileDirtyPages()
                // P1b: inspect-then-decide crash-recovery of a prior interrupted APPLY, after the index is whole + after
                // reconcileDirtyPages may have re-committed a crashed dirty page - it resolves each APPLYING row's
                // CURRENT pageId path and stamps APPLIED (write landed) or PENDING (it didn't). Cannot race a live apply.
                koin.get<ProposalService>().reconcileApplying()
                // Armed as late as possible - directly around the only call that parks the main thread. Arming it
                // earlier would let a SIGTERM during the boot rebuild tear the tree down UNDER a main thread that
                // then goes on to bind the port; boot is already crash-safe (the reconciles above recover an
                // interrupted one), so the narrow window costs nothing and the interleaving would.
                hook = activeShutdown.installHook()
                control.onHookInstalled(requireNotNull(hook))
                control.startServer(server)
            } finally {
                activeShutdown.run() // the clean-exit path; a no-op wait if the hook already ran it
                hook?.let(::removeShutdownHook)
            }
        }
    } finally {
        resources.close()
    }
}

private class ServeRefusal : RuntimeException()

private class OpenerFailure(val original: Exception) : RuntimeException(original)

private fun refuseServe(output: CommandOutput, message: String): Nothing {
    output.error("serve: $message")
    throw ServeRefusal()
}

@Suppress("TooGenericExceptionCaught")
private fun <T> openTyped(open: () -> T): T =
    try {
        open()
    } catch (failure: Exception) {
        throw OpenerFailure(failure)
    }

private fun unwrapOpenerFailure(failure: Throwable): Throwable? {
    var current = failure
    while (current is InstanceCreationException) {
        current = current.cause ?: return null
    }
    return (current as? OpenerFailure)?.original
}

private fun removeShutdownHook(hook: Thread) {
    try {
        Runtime.getRuntime().removeShutdownHook(hook)
    } catch (failure: IllegalStateException) {
        logger.debug(failure) { "shutdown began before the Plainbase hook could be removed" }
    }
}

private fun consumeConfigBootGate(config: PlainbaseConfig, output: CommandOutput) {
    val refusals = config.bootRefusals()
    refuseFirst(refusals, TOPOLOGY_REFUSAL_KINDS, output)
    config.storageWarnings().forEach { logger.warn { it } }
    config.rootsWarnings().forEach { logger.warn { it } }
    refuseFirst(refusals, BIND_REFUSAL_KINDS, output)
    when {
        config.auth.insecureHttp && config.isNonLoopbackBind() ->
            logger.warn {
                "PLAINBASE_INSECURE_HTTP set: serving credentials over PLAINTEXT on ${config.host} - anyone on the " +
                    "network can capture them (ADR-0008)"
            }

        config.auth.insecureHttp ->
            logger.info {
                "PLAINBASE_INSECURE_HTTP set but the bind is loopback (${config.host}) - the override is redundant here " +
                    "(loopback HTTP is always allowed)"
            }
    }
}

private fun refuseFirst(
    refusals: List<BootRefusal>,
    kinds: Set<BootRefusal.Kind>,
    output: CommandOutput,
) {
    refusals.firstOrNull { it.kind in kinds }?.let {
        refuseServe(output, it.message)
    }
}

private fun consumeRootBootGate(config: PlainbaseConfig, inputs: RootBootInputs, output: CommandOutput) {
    evaluateBootGate(config, inputs.registry, inputs.probes).verdicts.forEach { verdict ->
        when (verdict) {
            is RootGateVerdict.Unavailable -> {
                inputs.availability.markUnavailable(verdict.root, UnavailableCause.MISSING_AT_BOOT)
                logger.warn {
                    "root '${verdict.root}' is not available at ${verdict.path}: it will serve 503 until the path is " +
                        "restored and the server restarted (its pages, aliases and checkpoints are left untouched)"
                }
            }

            is RootGateVerdict.Refused -> {
                refuseServe(output, verdict.message)
            }

            is RootGateVerdict.Ready -> Unit
        }
    }
}

private fun hydrateObjectMode(
    config: PlainbaseConfig,
    koin: Koin,
    objectHistory: DeferredObjectHistory,
    output: CommandOutput,
    control: ServerRunControl,
) {
    if (config.storage.backend != StorageBackend.OBJECT) return

    // Record the binding before the first LIST; an untrusted binding gets a mirror derived from this bucket.
    val objectStore = koin.get<ObjectContentStore>()
    if (koin.get<BindingLatch>().observe(koin.get<RootRegistry>().primary.name, objectStore.binding) != BindingStatus.TRUSTED) {
        objectStore.rebind()
    }
    runCatching {
        when (config.git.enabled) {
            true -> {
                val bundleDr = koin.get<GitBundleDr>()
                control.armObjectHistory(
                    objectHistory,
                    ObjectHistoryCallbacks(
                        repoPath = objectStore.mirror::resolveRepoRelativePath,
                        onCommit = bundleDr::onCommitAsync,
                    ),
                )
                control.afterDrArm(bundleDr)
                objectHistory.requireReady()
                val restored = control.restoreBundle(bundleDr)
                control.afterDrRestore(bundleDr)
                control.hydrateObject(objectStore, restored.isRestored)
                control.afterObjectHydrate(objectStore)
                control.reconcileBundle(bundleDr, restored)
                control.afterDrReconcile(bundleDr)
            }

            false, null -> objectStore.hydrate()
        }
    }.onFailure { failure ->
        rethrowError(failure)
        logger.error(failure) { "serve object hydrate or bundle restore failed" }
        refuseServe(output, failure.message ?: "unexpected failure")
    }
}

private fun prepareHistories(
    config: PlainbaseConfig,
    registry: RootRegistry,
    histories: HistoryProviders,
    selection: RootHistorySelection,
    availability: RootAvailability,
    output: CommandOutput,
) {
    runCatching {
        if (config.storage.backend == StorageBackend.OBJECT && config.git.enabled == true) {
            selection.objectHistory.requireReady()
        }
        val serving = availability.current()
        registry.roots
            .filter { serving.isAvailable(it.name) }
            .forEach { histories[it.name].prepare() }
    }.onFailure { failure ->
        rethrowError(failure)
        logger.error(failure) { "serve history preparation failed" }
        refuseServe(output, failure.message ?: "unexpected failure")
    }
}

private fun rethrowError(failure: Throwable) {
    if (failure is Error) throw failure
}

/**
 * How `serve()` CONSUMES the gate, in the order it has always emitted (C5 S1.7). Every [BootRefusal.Kind]
 * belongs to exactly one stage, and `BootGateOrderingTest` fails the build if a NEW kind belongs to none -
 * a refusal the gate produces and boot silently ignores would be the worst of both worlds.
 *
 * The topology matrix refuses BEFORE the config warnings; the bind guard AFTER them; the per-root git gate is
 * reached through [BootGate.verdicts], never through [BootGate.refusals] - same message, right place in the
 * boot output. (`serve()` printing GIT_GATE from `refusals` too would print the git failure twice, from the
 * wrong stage. The entries exist in `refusals` for the CLI's key diff.)
 */
val TOPOLOGY_REFUSAL_KINDS: Set<BootRefusal.Kind> = setOf(
    BootRefusal.Kind.ROOT_PAIR,
    BootRefusal.Kind.ROOT_VS_DATA_DIR,
    BootRefusal.Kind.OBJECT_KEYS,
)

val BIND_REFUSAL_KINDS: Set<BootRefusal.Kind> = setOf(BootRefusal.Kind.BIND_GUARD)

/**
 * The kinds `serve()` RECORDS and DEGRADES on rather than refusing - the fourth disposition, and the one that
 * makes the partition honest instead of forcing an outage into a stage that exits.
 *
 * `PRIMARY_UNUSABLE` is an OUTAGE, not a config fault: the config is perfectly well-formed and a directory is late.
 * So `serve()` treats the primary exactly as it treats an extra in the same state - Unavailable, a WARN, 503 for its
 * pages - through [rootGateVerdicts], and this set is what says so out loud.
 *
 * It stays a [BootRefusal] for the two consumers that still need it, and both genuinely do: the OFFLINE commands
 * refuse on it through `requireContentDir()` (a `plainbase reindex` over a content tree that is not there is a
 * no-op pretending to be a rebuild), and `plainbase root`'s baseline diff is keyed on it - where it can only ever
 * appear on BOTH sides, since no value `root` writes can move the primary.
 */
val DEGRADED_REFUSAL_KINDS: Set<BootRefusal.Kind> = setOf(BootRefusal.Kind.PRIMARY_UNUSABLE)

/** Reached through [BootGate.verdicts] in rank order, so an earlier root's WARN still prints before it. */
val VERDICT_REFUSAL_KINDS: Set<BootRefusal.Kind> = setOf(BootRefusal.Kind.GIT_GATE)

/** One root's boot-gate verdict - `serve()`'s own per-root loop, made callable. */
sealed interface RootGateVerdict {
    val root: RootName

    /** An EXTRA whose store is not there: MISSING_AT_BOOT, gate check SKIPPED, serves 503 (D5-over-D4). */
    data class Unavailable(override val root: RootName, val path: Path?) : RootGateVerdict

    /** `gateCheck()` threw: `serve` exits 1 with this message. */
    data class Refused(override val root: RootName, val message: String) : RootGateVerdict

    data class Ready(override val root: RootName) : RootGateVerdict
}

/**
 * Every refusal `serve()` raises from CONFIG + FILESYSTEM, plus the per-root verdicts. [refusals] is COMPLETE
 * (no short-circuit) and STRUCTURED: `serve` prints the first of a stage's, and the CLI diffs the KEYS.
 */
data class BootGate(val refusals: List<BootRefusal>, val verdicts: List<RootGateVerdict>)

/**
 * THE boot gate. NOT a list of the checks `serve()` runs - it IS the code `serve()` runs, and `plainbase root`
 * runs it too, over the candidate config it is about to write (C5 D-C5-17). That is the whole mechanism: the
 * CLI cannot forget an item on a list it does not keep, and a check added here lands in the CLI for free.
 *
 * Every stage is evaluated (NO short-circuit) so a caller can DIFF two configs' refusal sets - a baseline that
 * stops at its first refusal cannot tell you whether the candidate introduced a SECOND one behind it. `serve()`
 * still refuses with the FIRST of the stage it is consuming, so its operator-facing message is byte-identical
 * to what it has always been.
 *
 * **PURE INSPECTION. It probes and it decides; it never creates, initializes or writes anything** - not the
 * git-home, not a repo, not DATA_DIR (`BootGatePurityTest` diffs the whole filesystem across a run). `prepare()`
 * is the MUTATING half of the same boot ordering and is deliberately NOT called here: `serve()` calls it
 * separately, AFTER the DATA_DIR lock, and `plainbase root` never calls it at all. A validation command that
 * quietly `git init`s a directory would be a far worse bug than the one this mechanism exists to fix.
 *
 * The boot refusals NOT here are named, with reasons, in `BootRefusalLedgerTest`.
 */
internal fun evaluateBootGate(
    config: PlainbaseConfig,
    registry: RootRegistry,
    probes: Map<RootName, RootBootProbe>,
): BootGate {
    val refusals = config.bootRefusals().toMutableList()
    val verdicts = rootGateVerdicts(registry, probes)
    verdicts.filterIsInstance<RootGateVerdict.Refused>().forEach {
        refusals += BootRefusal(BootRefusal.Kind.GIT_GATE, setOf(it.root), it.message)
    }
    return BootGate(refusals, verdicts)
}

/**
 * The per-root half of the gate, in REGISTRY (rank) order (ADR-0006 + ADR-0011 D5-over-D4).
 *
 * **PROBE FIRST.** An extra that is not there is marked missing and its gate check is SKIPPED - a `history = native`
 * extra sitting on an unmounted disk must degrade to 503 like any other unavailable root, not take the whole server
 * down, and the guard re-arms on the next restart when the disk is back and it can actually judge the repo. So a CLI
 * that ran the git guard unconditionally would be STRICTER than the server: it would refuse
 * `root add x /not-yet-mounted --history native`, which `serve()` accepts by design. **Enumeration is not merely
 * leaky, it is leaky in BOTH directions** - and running the server's own loop gets the ordering right for free.
 *
 * Separated from [evaluateBootGate] so callers that have the WIRING but no config - the multi-root REST harness,
 * which seeds boot availability - call the real loop instead of re-implementing its probe half. A test-only copy
 * cannot brick production, but it can make a multi-root test pass while `serve` diverges, which is the same disease
 * one blast radius over.
 */
internal fun rootGateVerdicts(
    registry: RootRegistry,
    probes: Map<RootName, RootBootProbe>,
): List<RootGateVerdict> = registry.roots.map { root ->
    // EVERY root, main included. main used to be exempt from the probe and refused the boot outright from the
    // topology matrix instead - two behaviors for one condition, decided by which root it happened to be. A server
    // that will not start because ONE volume is late is a server that cannot survive a reboot ordering race, and it
    // takes every OTHER root down with it over a directory that is usually seconds away. So main degrades exactly as
    // an extra does: 503 for its pages (never 404 - a 404 reads as deleted), its own WARN, and the same restart to
    // recover once the mount is back (`RootAvailability` is sticky by design; a vanished root's identity state is
    // not trustworthy afterwards).
    val probe = probes.getValue(root.name)
    if (!probe.available()) {
        RootGateVerdict.Unavailable(root.name, root.localPath)
    } else {
        runCatching {
            probe.gateCheck()
            RootGateVerdict.Ready(root.name)
        }.getOrElse { failure ->
            if (failure is Error) throw failure
            RootGateVerdict.Refused(root.name, failure.message ?: failure.toString())
        }
    }
}

/**
 * THE gate, over an arbitrary candidate config, with no global Koin and no database - the entry point
 * `plainbase root` uses, and the ONLY caller that needs it (`serve()` already has a graph; building a second
 * one would construct a second [RootAvailability] and a second set of stores, and the one the gate marked
 * would not be the one the server serves).
 *
 * The graph uses the shared explicit preparation plus module recipes. `serve()` supplies run-owned opener callbacks
 * to those recipes, while this inspection graph uses standalone defaults. It is ISOLATED
 * (`koinApplication`, never `startKoin`), so the baseline and candidate graphs coexist and neither touches the
 * global context.
 *
 * [repositoryModule] is DELIBERATELY ABSENT, and that is a SEAL, not an omission: the app DB may not be opened
 * without the DATA_DIR lock, and `plainbase root` does not take it (it takes `roots.lock`, precisely so staging
 * a topology change while the server runs works). With no `repositoryModule` in the graph an accidental
 * object-mode resolution does not quietly open and MIGRATE the database - it fails LOUD with a missing
 * definition. The [check] below is the second seal, and the two are independent.
 */
fun bootGateFor(config: PlainbaseConfig): BootGate {
    check(config.storage.backend == StorageBackend.LOCAL) {
        "the boot gate runs LOCAL-only: an object-mode candidate carries a roots {} block and is refused at LOAD, " +
            "so it can never reach here (C5 D-C5-17.2)"
    }
    val openers = ServerOpeners()
    val inputs = prepareRootBootInputs(config, openers.openLocal, GitMaintenanceTasks.inert())
    val resources = ServerResourceOwner()
    val app = resources.construct("Koin context") {
        koinApplication().also { resources.own(ServerResourcePhase.KOIN_CONTEXT, it) { application -> application.close() } }
    }
    return try {
        app.modules(
            module { single { config } },
            createContentModule(config, inputs, openers.openObject, { it.close() }, resources),
            createHistoryModule(config, inputs.history, resources),
        )
        val epoch = app.koin.get<ObservationEpoch>()
        inputs.signals.arm(epoch::broke)
        evaluateBootGate(config, inputs.registry, inputs.probes)
    } finally {
        resources.close()
    }
}

/**
 * The C2 boot guard's serve() shape (ADR-0011 D15): evaluates the pure [DetachedRoots] verdict,
 * logs the partial-detachment WARN itself, and returns the fatal refusal text (or null to serve).
 * A synthesized legacy config runs it with configured = {main}, and every pre-C2 DB migrates to
 * all-'main' rows, so it is trivially Clean there. The remediation is config-first, then TARGETED
 * and backup-first - it must never advise deleting plainbase.db, which also holds the security and
 * review truth (users, sessions, API tokens, roles, proposals, the audit log).
 */
internal fun detachedRootsRefusal(bound: Set<RootName>, configured: Set<RootName>): String? =
    when (val verdict = DetachedRoots.evaluate(bound, configured)) {
        DetachedRoots.Verdict.Clean -> null
        is DetachedRoots.Verdict.Detached -> {
            logger.warn {
                "id_map holds page bindings under root(s) absent from roots{}: ${verdict.roots.sortedNames()}. " +
                    "Their pages are not served and their permalinks stay dormant until a root with the same name is " +
                    "restored (root names are permanent; re-adding restores bindings subject to the ADR-0011 D2 supersede rules)."
            }
            null
        }
        is DetachedRoots.Verdict.AllDetached ->
            "REFUSING TO SERVE: every page binding in this DATA_DIR belongs to root(s) absent from the " +
                "configuration (bound: ${bound.sortedNames()}; configured: ${configured.sortedNames()}). This DATA_DIR likely " +
                "belongs to a different deployment, or the roots{} block was rewritten wholesale. Remedies, in order: " +
                "(1) fix roots{} so the bound name(s) above are declared again (root names are permanent identifiers), " +
                "or point DATA_DIR at the right directory; (2) if the removal is intentional and losing those roots' " +
                "permalinks and old-URL redirects is accepted: back up DATA_DIR first, then delete only the detached " +
                "rows, per root name, from the root-bearing tables - e.g. " +
                "sqlite3 DATA_DIR/plainbase.db \"DELETE FROM id_map WHERE root='<name>'\" " +
                "(repeat for retired_binding, url_alias, identity_issue, page_checkpoint, dirty_page, proposals, " +
                "git_checkpoint, root_observation, and root_topology). Do NOT delete " +
                "plainbase.db itself: it also holds users, sessions, API tokens, roles, proposals, and the audit log. " +
                "NOTE: a detached root's PENDING/APPLYING proposals stay exactly as they are - they are never applied, " +
                "never terminally failed, and never deleted - and they revive if the root's name returns to roots{}."
    }

private fun Set<RootName>.sortedNames(): String = map { it.value }.sorted().joinToString(", ")

/**
 * The single rev-3.4 backup-guidance WARN (pure accessor, the [TransportSecurityPolicy]
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
