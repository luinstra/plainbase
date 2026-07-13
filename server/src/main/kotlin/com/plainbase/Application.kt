package com.plainbase

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.repository.SessionRepository
import com.plainbase.domain.repository.SetupTokenRepository
import com.plainbase.domain.repository.UserRepository
import com.plainbase.domain.root.BootRefusal
import com.plainbase.domain.root.DetachedRoots
import com.plainbase.domain.root.RootAvailability
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootShadow
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.service.CanonicalUrlBuilder
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.ProposalService
import com.plainbase.domain.service.RebuildScheduler
import com.plainbase.domain.service.UrlAliasRegistry
import com.plainbase.domain.service.WritePipeline
import com.plainbase.frameworks.cli.AdminCommand
import com.plainbase.frameworks.cli.AdoptCommand
import com.plainbase.frameworks.cli.ReindexCommand
import com.plainbase.frameworks.cli.RootCommand
import com.plainbase.frameworks.cli.S3SmokeCommand
import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.git.GitBundleDr
import com.plainbase.frameworks.koin.HistoryProviders
import com.plainbase.frameworks.koin.RootStores
import com.plainbase.frameworks.koin.checkpointModule
import com.plainbase.frameworks.koin.contentModule
import com.plainbase.frameworks.koin.historyModule
import com.plainbase.frameworks.koin.indexModule
import com.plainbase.frameworks.koin.repositoryModule
import com.plainbase.frameworks.koin.restModule
import com.plainbase.frameworks.koin.searchModule
import com.plainbase.frameworks.koin.securityModule
import com.plainbase.frameworks.ktor.KtorServer
import com.plainbase.frameworks.lifecycle.GracefulShutdown
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.scheduling.ExecutorAlarm
import com.plainbase.frameworks.spike.NativeSpike
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.context.startKoin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Path
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
        "root" -> exitProcess(RootCommand.runAsMain(args.drop(1)))
        // Hidden (not in the usage line): the credentialed C0 object-store smoke - operator-run, never CI.
        "s3-smoke" -> exitProcess(S3SmokeCommand.runAsMain(args.drop(1)))
        null, "serve" -> serve()
        else -> {
            System.err.println("Unknown command: ${args.first()} (expected: serve | spike | adopt | reindex | admin | root)")
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

    // THE BOOT GATE, consumed in STAGES (C5 S1.7). The gate itself - `evaluateBootGate` - is the ONE function
    // `plainbase root` also runs, over the candidate roots.conf it is about to write, so a refusal added to
    // boot lands in the CLI for free and neither can drift from the other.
    //
    // CONSUME IT IN THE ORDER `serve()` HAS ALWAYS EMITTED, not at one `firstOrNull()`. A refusal that jumps
    // the queue SWALLOWS every warning behind it: a git-gate failure exits AFTER the storage/roots warnings
    // and after every earlier root's unavailable-WARN have printed, and an operator losing a boot warning is
    // information loss on the exact surface multi-root exists to make visible.
    //
    // The CONFIG+FILESYSTEM refusals (sites 2 + 3) are taken here, BEFORE the graph is touched. That ordering
    // is load-bearing, not stylistic: in object mode resolving RootStores resolves DirtyPageRepository, which
    // OPENS AND MIGRATES the app DB - and the DB may not be opened before the DATA_DIR lock (fix D, the rule
    // restated at the lock region below). A doomed boot must not migrate a database on its way out.
    val refusals = config.bootRefusals()
    fun refuse(kinds: Set<BootRefusal.Kind>) {
        refusals.firstOrNull { it.kind in kinds }?.let {
            System.err.println("serve: ${it.message}")
            exitProcess(1)
        }
    }
    // Site 2: the topology matrix (a missing CONTENT_DIR, a nested pair, a DATA_DIR collision, an incomplete
    // object-mode key matrix). It names itself as a `serve:` refusal, never a raw stack trace, and never
    // silently serves an empty tree. Within a stage the FIRST refusal in matrix order is the one printed -
    // the same one `requireContentDir()` throws.
    refuse(TOPOLOGY_REFUSAL_KINDS)
    // Q9/Q10 ignored-key warnings (never fatal): local mode names any configured-but-ignored
    // storage.object.* keys; object mode warns when CONTENT_DIR was explicitly set.
    config.storageWarnings().forEach { logger.warn { it } }
    // Multi-root warnings (never fatal): an ignored explicit CONTENT_DIR, an extra root whose path is not there
    // (it serves 503 until restored + restarted), and direct-commit globs on a read-only root (ADR-0011 D11-D13).
    config.rootsWarnings().forEach { logger.warn { it } }
    // Site 3 - ADR-0008 fail-closed bind guard: config-only, so it fails BEFORE the heavier git-gate/lock/rebuild
    // work, and AFTER the warnings above, exactly as it always has.
    refuse(BIND_REFUSAL_KINDS)
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
    // Site 4 - the per-root verdicts (ADR-0006 git gate + the D5-over-D4 availability probe), walked in REGISTRY
    // (rank) order, which is the order the loop this replaces walked. An Unavailable WARNs and CONTINUES; a
    // Refused exits 1. This loop is also where boot availability is SEEDED - the gate DECIDES, `serve()` ACTS
    // (the `detachedRootsRefusal` idiom): `markUnavailable` mutates a runtime singleton, so it must never live
    // inside a function the CLI also calls.
    //
    // Still BEFORE the lock/rebuild/reconcile block, because rebuild() and reconcileDirtyPages() trigger commits
    // and a "git missing" failure must fire FIRST with an actionable message, never as a doomed commit's stack trace.
    val availability = koin.get<RootAvailability>()
    val stores = koin.get<RootStores>()
    val histories = koin.get<HistoryProviders>()
    for (verdict in evaluateBootGate(config, koin.get<RootRegistry>(), stores, histories).verdicts) {
        when (verdict) {
            is RootGateVerdict.Unavailable -> {
                availability.markUnavailable(verdict.root, UnavailableCause.MISSING_AT_BOOT)
                logger.warn {
                    "root '${verdict.root}' is not available at ${verdict.path}: it will serve 503 until the path is " +
                        "restored and the server restarted (its pages, aliases and checkpoints are left untouched)"
                }
            }
            is RootGateVerdict.Refused -> {
                System.err.println("serve: ${verdict.message}")
                exitProcess(1)
            }
            is RootGateVerdict.Ready -> Unit
        }
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
        // Multi-root C2 boot guard (ADR-0011 D1/D15): bindings under roots absent from the config
        // WARN; a nonempty id_map ENTIRELY disjoint from the config refuses to serve. ORDERING
        // CONSTRAINT: this repository get is the process's FIRST app-DB open, which runs the
        // migration - it must stay AFTER DataDirLock.tryAcquire (a concurrent second instance
        // racing that first-open migration is exactly what the lock prevents) and satisfies the
        // fix-D never-open-before-the-lock rule below the OBJECT branch.
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
        )?.let { refusal ->
            lock.close() // exitProcess skips the outer finally (the hydrate-failure arm's shape)
            System.err.println("serve: $refusal")
            exitProcess(1)
        }
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
            // Same per-root loop as the gate, skipping the roots the gate loop marked unavailable: main-AUTO keeps
            // its ensureRepo, a CLAIMED root readies only the git-home (its repo is the operator's), NoOp no-ops.
            val serving = availability.current()
            koin.get<RootRegistry>().roots
                .filter { serving.isAvailable(it.name) }
                .forEach { histories[it.name].prepare() }
        } catch (e: Exception) {
            // exitProcess terminates the JVM without running the outer finally, so release the lock
            // explicitly here - otherwise a forced-on Git failure would leak it in embedded/test use.
            lock.close()
            System.err.println("serve: ${e.message}")
            exitProcess(1)
        }
        val builder = koin.get<IndexBuilder>()
        // §B2 startup ordering, no unwatched window: the watchers register BEFORE the first rebuild.
        // Events arriving while the initial build is in flight coalesce into at most one follow-up
        // rebuild via the scheduler's single-flight dirty flag.
        val scheduler = RebuildScheduler(rebuild = { builder.rebuild() }, alarm = ExecutorAlarm())
        // ONE watcher per AVAILABLE root, all feeding the ONE debounced scheduler. The scheduler stays root-BLIND
        // and needs no change: a rebuild is a whole-corpus pass, so a vanished root's queued events are harmless
        // (the next pass's probe skips it), and the root on each closure is carried for LOGGING only. A root that
        // was unavailable at boot gets no watcher at all - there is nothing to watch, and the status is sticky
        // until restart anyway. Which means every AVAILABLE root has a watcher, and that is what makes the
        // watcher's root-liveness probe (ContentStore.watch) a corpus-wide bound rather than a per-root nicety:
        // an idle root's loss is detected without any traffic to trip it. The failure callback below is the
        // narrower detector - a worker that dies while the root is FINE would otherwise leave a healthy-looking
        // server that has silently stopped converging.
        val watchers = koin.get<RootRegistry>().roots
            .filter { availability.current().isAvailable(it.name) }
            .map { root ->
                stores[root.name].watch(
                    onChange = { scheduler.schedule() },
                    onFailure = { failure ->
                        logger.error(failure) { "the watcher for root '${root.name}' died; marking it unavailable" }
                        availability.markUnavailable(root.name, UnavailableCause.WATCHER_FAILED)
                    },
                )
            }
        val server = KtorServer(config, koin.get())
        // The ONE teardown, run by BOTH the SIGTERM hook and the clean-exit `finally` below (idempotent, so
        // both firing is safe). SIGTERM is how docker/systemd/k8s all stop us, and `embeddedServer` installs
        // no hook of its own - so without this NONE of these closes ran on a normal production restart, and
        // object mode silently skipped its final DR bundle ship every time (see [GracefulShutdown]).
        //
        // ORDER IS LOAD-BEARING: the HTTP server drains first, so no in-flight save is severed mid-write;
        // watchers stop the object-mode poll thread BEFORE the transport it uses; the scheduler drains before
        // the DR flush, so an in-flight rebuild's commits still make the final bundle; the transport closes
        // after the ship that needs it; the DATA_DIR lock releases last, once nothing is writing under it.
        val shutdown = GracefulShutdown(
            buildList {
                add(GracefulShutdown.Step("http server") { server.stop() })
                add(GracefulShutdown.Step("watchers") { watchers.forEach { it.close() } })
                add(GracefulShutdown.Step("rebuild scheduler") { scheduler.close() })
                if (config.storage.backend == StorageBackend.OBJECT) {
                    // Same `git.enabled` guard as the boot-side wiring, so a git-disabled object boot never
                    // constructs GitBundleDr here either (the R9 lazy-wiring discipline).
                    if (config.git.enabled == true) add(GracefulShutdown.Step("git bundle DR") { koin.get<GitBundleDr>().close() })
                    add(GracefulShutdown.Step("object store transport") { koin.get<ObjectContentStore>().close() })
                }
                add(GracefulShutdown.Step("DATA_DIR lock") { lock.close() })
            },
        )
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
            // Multi-root C3 boot guard (ADR-0011 D3(a)): a top-level 'main' URL segment in the main
            // root makes legacy /docs/main/... links indistinguishable from root-qualified URLs.
            // Deliberately AFTER both reconciles - each can WRITE pages (a crash-recovered save/apply
            // of a main/... create) and republish, so the guard must judge the LAST pre-serve snapshot.
            mainRootUrlCollisionRefusal(builder.current)?.let { refusal ->
                lock.close() // exitProcess skips the outer finally (the hydrate-failure arm's shape)
                System.err.println("serve: $refusal")
                exitProcess(1)
            }
            val aliases = koin.get<UrlAliasRegistry>().all()
            deadLegacyAliasWarning(aliases)?.let { logger.warn { it } }
            // The C5 shadow WARN, on the same last-pre-serve snapshot and for the same reason: a root name that
            // shadows a top-level segment of main silently re-points every circulating link through it.
            shadowedRootWarning(builder.current, aliases, koin.get<RootRegistry>())?.let { logger.warn { it } }
            // Armed as late as possible - directly around the only call that parks the main thread. Arming it
            // earlier would let a SIGTERM during the boot rebuild tear the tree down UNDER a main thread that
            // then goes on to bind the port; boot is already crash-safe (the reconciles above recover an
            // interrupted one), so the narrow window costs nothing and the interleaving would.
            shutdown.installHook()
            server.start(wait = true)
        } finally {
            shutdown.run() // the clean-exit / embedded path; a no-op wait if the SIGTERM hook already ran it
        }
    } finally {
        lock.close() // idempotent: the teardown above already released it, unless we failed before it existed
    }
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
    BootRefusal.Kind.MAIN_UNUSABLE,
    BootRefusal.Kind.ROOT_PAIR,
    BootRefusal.Kind.ROOT_VS_DATA_DIR,
    BootRefusal.Kind.OBJECT_KEYS,
)

val BIND_REFUSAL_KINDS: Set<BootRefusal.Kind> = setOf(BootRefusal.Kind.BIND_GUARD)

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
fun evaluateBootGate(
    config: PlainbaseConfig,
    registry: RootRegistry,
    stores: RootStores,
    histories: HistoryProviders,
): BootGate {
    val refusals = config.bootRefusals().toMutableList()
    val verdicts = rootGateVerdicts(registry, stores, histories)
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
fun rootGateVerdicts(
    registry: RootRegistry,
    stores: RootStores,
    histories: HistoryProviders,
): List<RootGateVerdict> = registry.roots.map { root ->
    if (root.name != RootName.MAIN && !stores[root.name].available()) {
        RootGateVerdict.Unavailable(root.name, root.localPath)
    } else {
        try {
            histories[root.name].gateCheck()
            RootGateVerdict.Ready(root.name)
        } catch (e: Exception) {
            RootGateVerdict.Refused(root.name, e.message ?: e.toString())
        }
    }
}

/**
 * THE gate, over an arbitrary candidate config, with no global Koin and no database - the entry point
 * `plainbase root` uses, and the ONLY caller that needs it (`serve()` already has a graph; building a second
 * one would construct a second [RootAvailability] and a second set of stores, and the one the gate marked
 * would not be the one the server serves).
 *
 * The graph is the PRODUCTION wiring - [contentModule] + [historyModule], the same objects `serve()` resolves -
 * so a future change to how a root's store or history provider is built lands here for free. **A CLI that
 * hand-wired them would have reproduced `serve`'s graph by hand, which is the same drift the shared gate exists
 * to prevent.** It is ISOLATED (`koinApplication`, never `startKoin`), so the baseline and candidate graphs
 * coexist and neither touches the global context.
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
    val app = koinApplication { modules(module { single { config } }, contentModule, historyModule) }
    return try {
        evaluateBootGate(config, app.koin.get(), app.koin.get(), app.koin.get())
    } finally {
        app.close()
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
                "rows, per root name, from the six root-bearing tables - e.g. " +
                "sqlite3 DATA_DIR/plainbase.db \"DELETE FROM id_map WHERE root='<name>'\" " +
                "(repeat for url_alias, identity_issue, page_checkpoint, dirty_page, and proposals). Do NOT delete " +
                "plainbase.db itself: it also holds users, sessions, API tokens, roles, proposals, and the audit log. " +
                "NOTE: a detached root's PENDING/APPLYING proposals stay exactly as they are - they are never applied, " +
                "never terminally failed, and never deleted - and they revive if the root's name returns to roots{}."
    }

private fun Set<RootName>.sortedNames(): String = map { it.value }.sorted().joinToString(", ")

/**
 * Main's top-level segment space, from the BUILT snapshot (C5 S3.1): the ONE index the reserved-`main`
 * refusal and the shadowed-root warning both read, so they cannot drift - they are the same map.
 *
 * Both grammars, because `splitRootTail`'s four callers do not all speak one path language (see [RootShadow]):
 * the SLUGIFIED url space (`/docs`, `by-path`, and main's `url_alias` rows) and the RAW content-path space
 * (`/browse`, `/assets`). Feeding [aliases] is the BOOT side's alone - an alias outlives the `redirect_from`
 * frontmatter that minted it, so no filesystem scan can find one, which is exactly why `plainbase root`'s
 * scan-derived twin cannot see them and why this warning exists rather than being "the CLI's check, later".
 */
private fun mainSegmentIndex(snapshot: PageIndex, aliases: Map<RootedPath, PageId>): Map<String, List<TreePath>> {
    val section = snapshot.section(RootName.MAIN)
    return RootShadow.topLevelIndex(
        urlPaths = section.pages.mapNotNull { it.urlPath } +
            CanonicalUrlBuilder.folderUrlPaths(section.folders).values.filterNotNull() +
            aliases.keys.filter { it.root == RootName.MAIN }.map { it.path },
        contentPaths = section.pages.map { it.path } + section.folders.map { it.path } + section.assets,
    )
}

/**
 * The C3 boot guard (ADR-0011 D3(a)), pure like [detachedRootsRefusal]: the fatal refusal text when
 * the MAIN root's built snapshot contains a top-level segment literally `main` - a page URL
 * path, a folder URL (the [CanonicalUrlBuilder.folderUrlPaths] truth `TreeBuilder` consumes, never
 * re-derived slugification), an asset path (assets mirror the redirect grammar, so `/assets/main/...` is
 * equally ambiguous), or a RAW content path - or null to serve. Segment-level detection catches the
 * whole equivalence class the URL grammar cares about (a dir `Main` slugifies to the same colliding
 * segment, a frontmatter `slug: main` page, an asset dir) and works in object mode where there is
 * no local FS to list. Main only: an extra root's top-level `main` dir is harmless (its URLs are
 * `/docs/{extra}/main/...`, root segment first - no legacy form ever pointed there). Boot-only is
 * sufficient: a `main/` dir created at RUNTIME mints self-consistent `/docs/main/main/...` URLs
 * with no pre-existing legacy links to break; the guard exists to stop the UPGRADE of a corpus
 * whose circulating `/docs/main-dir/...` links would otherwise silently re-resolve.
 *
 * **C5 WIDENED it to the raw content-path space**, which it had been missing: a `main/` folder carrying a
 * `_folder.yaml slug:` override moves OUT of the URL space (so the old check saw nothing) while
 * `/browse/main/…` stays ambiguous - `BrowseRedirectRoute` splits it as root `main` plus a tail naming no
 * file, and a link that used to 302 to the page now 404s. Nobody is upgrading into this (the whole multi-root
 * feature is unreleased), so widening the refusal now is free, and it will never be this cheap again.
 */
internal fun mainRootUrlCollisionRefusal(snapshot: PageIndex): String? {
    val main = RootName.MAIN.value
    val colliding = mainSegmentIndex(snapshot, emptyMap())[main].orEmpty().map { it.value }.distinct()
    if (colliding.isEmpty()) return null
    return "REFUSING TO SERVE: the main root contains top-level URL segment '$main' - since multi-root " +
        "(ADR-0011 D1/D3) '$main' is the RESERVED root segment, so an old /docs/$main/... deep link is " +
        "indistinguishable from a root-qualified URL and would silently re-resolve to the wrong page. " +
        "Colliding entries: ${boundedPathList(colliding)}. Rename the directory (or the frontmatter `slug:` / " +
        "_folder.yaml `slug:` source producing the segment) so no top-level URL segment is '$main'. This applies " +
        "even to a fresh corpus - the reservation is deterministic, not upgrade-conditional. NOTE: renaming " +
        "permanently forfeits any circulating /docs/$main/... deep links and their recorded aliases; after the " +
        "rename those old links answer not-found instead of redirecting."
}

/**
 * The C5 shadow WARN (D-C5-6), from the SAME segment index the reserved-`main` refusal reads: a REGISTERED
 * extra root whose NAME is a top-level segment of main. `splitRootTail` treats a tail's first segment as a
 * root iff it names a registered root, so the moment a root named `guides` joins a main that already has a
 * top-level `guides/` - or a page whose `slug:` is `guides`, or an alias row at `guides/…` - every circulating
 * link through that segment stops resolving inside main and starts resolving inside the new root.
 *
 * **BOOT WARNS AND NEVER REFUSES, and this is the decision most worth arguing with.** A refusal would mean an
 * author creating a top-level folder called `handbook` in main - a pure docs edit, through the product's own
 * UI - BRICKS THE NEXT RESTART of a server that has a root named `handbook`. That converts a link ambiguity
 * into a production outage, and it is precisely the "residual shadow edge ... accepted tradeoff" ADR-0011 D3
 * ruled on. The reserved-`main` case stays a REFUSAL because it is deterministic and unavoidable, not because
 * refusal is the general policy. `plainbase root add` DOES refuse (with `--force`), and that is a CLI-owned
 * policy decision - deliberately stricter than boot, not a proxy for a boot check.
 */
internal fun shadowedRootWarning(
    snapshot: PageIndex,
    aliases: Map<RootedPath, PageId>,
    registry: RootRegistry,
): String? {
    val index = mainSegmentIndex(snapshot, aliases)
    // registry.extras, never `registry.roots.filter { it.name != RootName.MAIN }`: the partition exists for
    // exactly this, and the hand-rolled filter would cost this file a second ledgered main-by-name comparison.
    val shadowed = registry.extras.mapNotNull { root -> index[root.name.value]?.let { root.name to it } }
    if (shadowed.isEmpty()) return null
    val detail = shadowed.joinToString("; ") { (name, paths) ->
        "root '${name.value}' shadows ${boundedPathList(paths.map { it.value })}"
    }
    return "root name(s) collide with a top-level segment of the main root: $detail. Links through that segment " +
        "(/docs/<name>/..., /browse/<name>/..., /assets/<name>/...) now resolve inside the ROOT, not inside main - " +
        "main's own entries under it are reachable only through their permalinks (/p/{id}). Rename the main-root " +
        "entry, or rename the root (remove + add, which appends its rank)."
}

/**
 * The companion boot WARN (never a refusal - the corpus itself is fine): main-root `url_alias`
 * rows whose stored URL path starts with the reserved `main` segment. If such a row predates the
 * multi-root upgrade, its legacy link is PERMANENTLY dead: `/docs/main/x` now resolves as
 * root `main` + path `x`, so the recorded `(main, main/x)` row is only reachable at
 * `/docs/main/main/x` - a URL that never circulated pre-upgrade. The wording is deliberately
 * hedged: a row minted AFTER the upgrade (a runtime-created `main/` dir whose page later moved) is
 * legitimately reachable at that doubled URL, and the row alone cannot tell the two apart.
 */
internal fun deadLegacyAliasWarning(aliases: Map<RootedPath, PageId>): String? {
    val main = RootName.MAIN.value
    val dead = aliases.keys
        .filter { it.root == RootName.MAIN && it.path.segments.first() == main }
        .map { it.path.value }
    if (dead.isEmpty()) return null
    return "url_alias holds ${dead.size} main-root row(s) whose URL path starts with the reserved '$main' " +
        "segment: ${boundedPathList(dead)}. If these rows predate the multi-root upgrade, their old " +
        "/docs/<path> links are dead: the reserved root segment routes /docs/$main/x to ($main, x), so such a " +
        "row is only reachable at /docs/$main/<path> - a URL that never circulated before the upgrade. Rows " +
        "recorded after the upgrade are unaffected."
}

/** The deterministic, testable offender bound the two texts above share: first 10, lexicographic, `(+N more)`. */
private fun boundedPathList(paths: List<String>): String {
    val sorted = paths.sorted()
    val overflow = sorted.size - 10
    return sorted.take(10).joinToString(", ") + if (overflow > 0) " (+$overflow more)" else ""
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
