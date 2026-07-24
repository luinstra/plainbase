package com.plainbase.frameworks.cli

import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.repository.ApiTokenMeta
import com.plainbase.domain.repository.Role
import com.plainbase.domain.root.AbsenceProof
import com.plainbase.domain.root.BindingRef
import com.plainbase.domain.root.ProofSource
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.service.ApiTokenService
import com.plainbase.domain.service.SessionService
import com.plainbase.domain.service.SetupService
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.security.ApiTokenMinter
import com.plainbase.frameworks.security.Argon2PasswordHasher
import com.plainbase.frameworks.security.SetupTokenMinter
import com.plainbase.frameworks.security.TokenHasher
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.PlainbaseDb
import com.plainbase.frameworks.sqldelight.SqlDelightApiTokenRepository
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRetirementRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRoleRepository
import com.plainbase.frameworks.sqldelight.SqlDelightSessionRepository
import com.plainbase.frameworks.sqldelight.SqlDelightSetupTokenRepository
import com.plainbase.frameworks.sqldelight.SqlDelightTransactionRunner
import com.plainbase.frameworks.sqldelight.SqlDelightUserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock

/**
 * `plainbase admin <mint-token|revoke-token|list-tokens|grant-role|setup-token>` — the agent-token admin surface
 * (A2) plus A4a's human-auth seams: `grant-role` (the proxy/recovery first-admin seam, §4) and `setup-token` (the
 * builtin first-admin bootstrap, §5). Two-level dispatch mirrors [AdoptCommand]'s structure.
 *
 * stdout is a CLI result contract: a minted plaintext token (agent or setup) is emitted ONCE here and NOWHERE
 * else — never via the kotlin-logging facade. Expected refusals use stderr; unexpected failures preserve their
 * throwable through the logging facade.
 */
object AdminCommand {
    private val logger = KotlinLogging.logger {}

    /**
     * Entry point for the `main` dispatch: env + `DATA_DIR/plainbase.conf` config, exit-code result. Resolves
     * via [PlainbaseConfig.loadForCommand] (not `fromEnv`) so a FILE-configured `auth.mode=builtin` is visible
     * to the setup-token bootstrap gate (A4a minor) - it only READS the conf file (no DB driver), so it runs
     * before the DataDirLock with no migration race. A bad config (IAE or HOCON) surfaces as `admin:` + exit 1.
     */
    fun runAsMain(args: List<String>, output: CommandOutput = systemCommandOutput()): Int {
        val config = PlainbaseConfig.loadForCommand("admin", output::error) ?: return 1
        // force-retire re-reads the registry FRESH under `roots.lock` (see [forceRetire]); from `main` that is a
        // real reload of DATA_DIR's config, so a `root remove` that committed since startup is visible.
        return run(args, config, output, reloadConfig = { PlainbaseConfig.loadForCommand("admin", output::error) })
    }

    /**
     * [reloadConfig] re-reads DATA_DIR's config on demand; only `force-retire` uses it, to re-check the registry under
     * `roots.lock`. It defaults to the passed [config] (a direct caller that runs no concurrent `root remove` needs no
     * reload), and `runAsMain` overrides it with a real disk reload.
     */
    fun run(
        args: List<String>,
        config: PlainbaseConfig,
        output: CommandOutput = systemCommandOutput(),
        reloadConfig: () -> PlainbaseConfig? = { config },
    ): Int =
        try {
            runChecked(args, config, output, reloadConfig)
        } catch (e: Exception) {
            logger.error(e) { "admin command failed" }
            output.error("admin: unexpected failure")
            1
        }

    private fun runChecked(
        args: List<String>,
        config: PlainbaseConfig,
        output: CommandOutput,
        reloadConfig: () -> PlainbaseConfig?,
    ): Int {
        // setup-token mutates DB state on DATA_DIR shared with a live server, so it MUST hold the DataDirLock BEFORE
        // any driver opens + migrates the DB (fix D: a second process opening/migrating before losing the lock race
        // is the exact hazard). It owns its driver lifecycle inside the lock.
        if (args.firstOrNull() == "setup-token") return setupToken(config, args.drop(1), output)
        // Validate the subcommand is known BEFORE acquiring the lock, so an unknown subcommand returns exit 2 (usage)
        // even when a server holds the lock — not exit 1 (a silent exit-code shift). (Fix B2c.)
        val sub = args.firstOrNull()
        if (sub !in LOCKED_SUBCOMMANDS) {
            output.error(USAGE)
            return 2
        }
        // mint/revoke/grant WRITE and ALL four trigger DatabaseFactory.createDriver's implicit, non-idempotent migrate
        // (DDL + a user_version bump) — racing a starting server's migration corrupts/throws. Hold the DataDirLock
        // FIRST, exactly like setup-token + reindex (fix B2c).
        val lock = DataDirLock.tryAcquire(config.dataDir)
        if (lock == null) {
            output.error("admin $sub: a Plainbase server is holding ${config.dataDir} — stop it before running this command")
            return 1
        }
        return lock.use {
            val driver = DatabaseFactory.createDriver(config.appDatabasePath)
            try {
                val database = DatabaseFactory.createDatabase(driver)
                val tokenService = ApiTokenService(
                    minter = ApiTokenMinter(),
                    hasher = TokenHasher(),
                    tokens = SqlDelightApiTokenRepository(database),
                    clock = Clock.System,
                )
                val roleRepo = SqlDelightRoleRepository(database)
                when (sub) {
                    "mint-token" -> mintToken(tokenService, args.drop(1), output)
                    "revoke-token" -> revokeToken(tokenService, args.drop(1), output)
                    "list-tokens" -> listTokens(tokenService, args.drop(1), output)
                    "grant-role" -> grantRole(roleRepo, args.drop(1), output)
                    "force-retire" -> forceRetire(database, config, reloadConfig, args.drop(1), output)
                    // Unreachable: sub is in LOCKED_SUBCOMMANDS (validated above before the lock).
                    else -> error("unreachable: $sub")
                }
            } finally {
                driver.close()
            }
        }
    }

    /** `mint-token <label> [mode]` — mode defaults to read-only; prints the plaintext ONCE. */
    private fun mintToken(service: ApiTokenService, args: List<String>, output: CommandOutput): Int {
        val label = args.getOrNull(0)
        if (label == null || args.size > 2) {
            // Reject surplus positionals (A2-amber): a typo'd 3rd arg must be a usage error, not silently ignored.
            output.error("usage: plainbase admin mint-token <label> [${modeUsage()}]")
            return 2
        }
        val mode = parseMode(args.getOrNull(1)) ?: run {
            output.error("unknown mode '${args[1]}' — legal values: ${modeUsage()}")
            return 2
        }
        val minted = service.mint(label = label, mode = mode)
        output.result("token id: ${minted.id} (label: $label, mode: ${mode.name.lowercase()})")
        output.result(minted.plaintext)
        output.result("store this now — it is not recoverable; the server keeps only its hash")
        return 0
    }

    /** `revoke-token <id>` — sets revoked_at; idempotent for an unknown/already-revoked id. */
    private fun revokeToken(service: ApiTokenService, args: List<String>, output: CommandOutput): Int {
        val id = args.getOrNull(0)
        if (id == null || args.size > 1) {
            // Reject surplus positionals (A2-amber): a typo'd 2nd arg must be a usage error, not silently ignored.
            output.error("usage: plainbase admin revoke-token <id>")
            return 2
        }
        service.revoke(id)
        output.result("revoked token id: $id")
        return 0
    }

    /** `list-tokens` — metadata only (no plaintext exists to print). */
    private fun listTokens(service: ApiTokenService, args: List<String>, output: CommandOutput): Int {
        if (args.isNotEmpty()) {
            output.error("usage: plainbase admin list-tokens")
            return 2
        }
        val rows = service.list()
        output.result("tokens: ${rows.size}")
        rows.forEach { output.result("  ${describe(it)}") }
        return 0
    }

    /**
     * `grant-role <issuer> <external_id> <viewer|editor|admin>` — the §4 proxy/recovery first-admin seam: an
     * idempotent [SqlDelightRoleRepository.upsert] of a role for ANY identity (a proxy user keys `proxy/<external
     * _id>`, which a builtin-shaped setup token can't seed). Seeds any mode's first admin and recovers a locked-out
     * builtin admin.
     */
    private fun grantRole(roleRepo: SqlDelightRoleRepository, args: List<String>, output: CommandOutput): Int {
        if (args.size != 3) {
            output.error("usage: plainbase admin grant-role <issuer> <external_id> <${roleUsage()}>")
            return 2
        }
        val (issuer, externalId, rawRole) = args
        val role = parseRole(rawRole) ?: run {
            output.error("unknown role '$rawRole' — legal values: ${roleUsage()}")
            return 2
        }
        roleRepo.upsert(issuer, externalId, role, Clock.System.now())
        output.result("granted role ${role.name.lowercase()} to $issuer/$externalId")
        return 0
    }

    /**
     * `force-retire <root> <id>` — the operator un-wedge hatch (§0.3.1): mints an OPERATOR [AbsenceProof] for a named
     * `(root, id)`, moving its live binding into `retired_binding` so a page stuck at 503 limbo answers 410 and gets
     * an exit. POST-lock validation (arity + [RootName]/[PageId] parse) exits 2 like `mintToken`; the root MUST be
     * REGISTERED (a detached root's permalink is contractually 404, so retiring into one would write an unobservable
     * row). Idempotent: an already-retired id is exit 0. On success the id is reclaimable at its own (root, path) by
     * the next pass's un-retire arm.
     *
     * The registered-root check AND the retirement run under `roots.lock`, over a registry re-read FRESH ([reloadConfig])
     * under it. The DataDirLock the dispatch holds does NOT exclude `plainbase root remove`, which mutates `roots.conf`
     * under `roots.lock` alone - so the config snapshot loaded at command start can go stale, and retiring against it
     * would write into a now-detached root and falsely promise a 410 (a detached root's permalink is 404). Holding
     * `roots.lock` across the fresh check + the retirement closes that window. Lock ordering is safe: force-retire is
     * the only holder of both, always DataDirLock-then-roots.lock; `root` takes only `roots.lock`, `serve` only the
     * DataDirLock - so no cycle exists.
     */
    private fun forceRetire(
        database: PlainbaseDb,
        config: PlainbaseConfig,
        reloadConfig: () -> PlainbaseConfig?,
        args: List<String>,
        output: CommandOutput,
    ): Int {
        val root = args.getOrNull(0)?.let(RootName::of)
        val id = args.getOrNull(1)?.let(PageId::of)
        if (args.size != 2 || root == null || id == null) {
            output.error("usage: plainbase admin force-retire <root> <id>")
            return 2
        }
        val rootsLock = DataDirLock.tryAcquire(config.dataDir, DataDirLock.ROOTS_LOCK_FILE_NAME)
        if (rootsLock == null) {
            output.error(
                "admin force-retire: a `plainbase root` command is holding " +
                    "${config.dataDir.resolve(DataDirLock.ROOTS_LOCK_FILE_NAME)} — retry once it finishes",
            )
            return 1
        }
        return rootsLock.use {
            val roots = reloadConfig()?.roots?.list ?: return@use 1 // a bad config reload already emitted its own line
            if (RootRegistry.of(roots).byName(root) == null) {
                output.error("admin force-retire: root '${root.value}' is not registered; re-add it before retiring its pages")
                return@use 1
            }
            retire(database, root, id, output)
        }
    }

    /** The retirement itself: mint the OPERATOR proof for a live `(root, id)` binding, or the idempotent/no-binding answers. */
    private fun retire(database: PlainbaseDb, root: RootName, id: PageId, output: CommandOutput): Int {
        val idMap = SqlDelightIdMapRepository(database)
        val retirements = SqlDelightRetirementRepository(database)
        // STAMP BEFORE reading the target binding (revoke-before-stamp, C5). The binding read below is the negative
        // evidence this OPERATOR proof rests on; capturing both freshness stamps first means a concurrent restore's
        // re-bind landing between the read and the apply advances binding_epoch past this value and `applyProofs`
        // discards the retire (fail-closed), rather than reaping the binding + `dirty_page` row the restore re-created.
        val observationId = retirements.observation(root)
        val bindingEpoch = retirements.bindingEpoch(root)
        val binding = idMap.bindingInRoot(root, id)
        if (binding == null) {
            val tombstone = idMap.retiredAt(root, id)
            return if (tombstone != null) {
                output.result("already retired at ${tombstone.path.path.value}")
                0
            } else {
                output.error("admin force-retire: no live binding for ${id.value} in root '${root.value}'")
                1
            }
        }
        val proof = AbsenceProof(
            root = root,
            source = ProofSource.OPERATOR,
            observationId = observationId,
            bindingEpoch = bindingEpoch,
            covers = setOf(BindingRef(binding.path.path, id)),
        )
        if (RootedPageId(root, id) !in retirements.applyProofs(listOf(proof), witnessed = emptySet())) {
            output.error("admin force-retire: refused to retire ${id.value} in root '${root.value}' (freshness or binding re-read)")
            return 1
        }
        output.result(
            "force-retired ${id.value} in root '${root.value}' (last at ${binding.path.path.value}); " +
                "/p/${root.value}/${id.value} now answers 410 for a snapshot-absent page. If the file is still present, " +
                "the next pass reclaims the id at its own (root, path).",
        )
        return 0
    }

    /**
     * `setup-token [--force]` — mints a BUILTIN first-admin bootstrap token, printing the plaintext ONCE. WITHOUT
     * `--force` it mints only on an empty / no-enabled-admin DB; WITH `--force` it re-mints regardless (lost-token
     * / sole-admin-disabled recovery). BUILTIN mode only (a proxy first-admin uses `grant-role`). Gated on the
     * DATA_DIR lock like `reindex` — refuse if a server holds it.
     */
    private fun setupToken(config: PlainbaseConfig, args: List<String>, output: CommandOutput): Int {
        // Accept ONLY [] or [--force]; trailing junk after --force is a usage error, not silently ignored (fix F).
        val force = when (args) {
            emptyList<String>() -> false
            listOf("--force") -> true
            else -> {
                output.error("usage: plainbase admin setup-token [--force]")
                return 2
            }
        }
        if (config.auth.mode != AuthMode.BUILTIN) {
            output.error(
                "setup-token requires auth.mode=builtin (current: ${config.auth.mode.name.lowercase()}); " +
                    "a proxy/off first admin is seeded with `plainbase admin grant-role`",
            )
            return 2
        }
        // Lock FIRST — BEFORE the driver opens + migrates the DB (fix D). A live server holding the lock means we must
        // not open/migrate underneath it; everything DB-touching happens only once the lock is held.
        val lock = DataDirLock.tryAcquire(config.dataDir)
        if (lock == null) {
            output.error("setup-token: a Plainbase server is holding ${config.dataDir} — stop it before minting")
            return 1
        }
        return lock.use {
            val driver = DatabaseFactory.createDriver(config.appDatabasePath)
            try {
                val database = DatabaseFactory.createDatabase(driver)
                if (!force && SqlDelightUserRepository(database).countEnabledAdmins() > 0) {
                    output.error(
                        "an enabled admin already exists; refusing to mint a bootstrap token (use --force to re-mint for recovery)",
                    )
                    return@use 2
                }
                val minted = setupService(database).mintBootstrapToken()
                output.result(minted.plaintext)
                output.result(
                    "store this now — it is not recoverable. Consume it via POST /api/v1/setup/consume to create the first admin.",
                )
                0
            } finally {
                driver.close()
            }
        }
    }

    /** The full [SetupService] wiring over [database] — used only by `setup-token` (the bootstrap mint). */
    private fun setupService(database: PlainbaseDb): SetupService {
        val hasher = TokenHasher()
        val sessions = SessionService(
            minter = com.plainbase.frameworks.security.SessionTokenMinter(hasher),
            hasher = hasher,
            sessions = SqlDelightSessionRepository(database),
            clock = Clock.System,
        )
        return SetupService(
            minter = SetupTokenMinter(hasher),
            hasher = hasher,
            setupTokens = SqlDelightSetupTokenRepository(database),
            users = SqlDelightUserRepository(database),
            roles = SqlDelightRoleRepository(database),
            sessions = sessions,
            passwordHasher = Argon2PasswordHasher(),
            idProvider = UuidV7IdProvider(),
            transactions = SqlDelightTransactionRunner(database),
            clock = Clock.System,
        )
    }

    private fun describe(row: ApiTokenMeta): String = buildString {
        append(row.id)
        append(" label=").append(row.agentLabel)
        append(" mode=").append(row.mode.name.lowercase())
        append(" created=").append(row.createdAt)
        row.lastUsedAt?.let { append(" lastUsed=").append(it) }
        row.expiresAt?.let { append(" expires=").append(it) }
        if (row.revokedAt != null) append(" REVOKED@").append(row.revokedAt)
    }

    /** Accepts the [AgentMode] name (any case, `-`/`_` interchangeable); null defaults to read-only. */
    private fun parseMode(raw: String?): AgentMode? {
        if (raw == null) return AgentMode.READ_ONLY
        val token = raw.trim().uppercase().replace('-', '_')
        return AgentMode.entries.firstOrNull { it.name == token }
    }

    /** Accepts a [Role] name (any case). */
    private fun parseRole(raw: String): Role? {
        val token = raw.trim().uppercase()
        return Role.entries.firstOrNull { it.name == token }
    }

    private fun modeUsage(): String = AgentMode.entries.joinToString("|") { it.name.lowercase().replace('_', '-') }

    private fun roleUsage(): String = Role.entries.joinToString("|") { it.name.lowercase() }

    /** The lock-guarded subcommand group (NOT setup-token, which has its own lock path) — the dispatch `when` mirror. */
    private val LOCKED_SUBCOMMANDS = setOf("mint-token", "revoke-token", "list-tokens", "grant-role", "force-retire")

    private val USAGE = "usage: plainbase admin <mint-token <label> [${modeUsage()}] | revoke-token <id> | " +
        "list-tokens | grant-role <issuer> <external_id> <${roleUsage()}> | force-retire <root> <id> | setup-token [--force]>"
}
