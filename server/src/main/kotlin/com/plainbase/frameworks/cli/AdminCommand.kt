package com.plainbase.frameworks.cli

import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.repository.ApiTokenMeta
import com.plainbase.domain.repository.Role
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
import com.plainbase.frameworks.sqldelight.SqlDelightRoleRepository
import com.plainbase.frameworks.sqldelight.SqlDelightSessionRepository
import com.plainbase.frameworks.sqldelight.SqlDelightSetupTokenRepository
import com.plainbase.frameworks.sqldelight.SqlDelightTransactionRunner
import com.plainbase.frameworks.sqldelight.SqlDelightUserRepository
import kotlin.time.Clock

/**
 * `plainbase admin <mint-token|revoke-token|list-tokens|grant-role|setup-token>` — the agent-token admin surface
 * (A2) plus A4a's human-auth seams: `grant-role` (the proxy/recovery first-admin seam, §4) and `setup-token` (the
 * builtin first-admin bootstrap, §5). Two-level dispatch mirrors [AdoptCommand]'s structure.
 *
 * stdout is a CLI output contract (`println` by design, like `adopt`/`spike`): a minted plaintext token (agent or
 * setup) is printed ONCE here and NOWHERE else — never via the kotlin-logging facade. Diagnostics still go through
 * the logging facade.
 */
object AdminCommand {

    /** Entry point for the `main` dispatch: real env config, exit-code result. */
    fun runAsMain(args: List<String>): Int = run(args, PlainbaseConfig.fromEnv())

    fun run(args: List<String>, config: PlainbaseConfig): Int {
        // setup-token mutates DB state on DATA_DIR shared with a live server, so it MUST hold the DataDirLock BEFORE
        // any driver opens + migrates the DB (fix D: a second process opening/migrating before losing the lock race
        // is the exact hazard). It owns its driver lifecycle inside the lock; the read-mostly token/role subcommands
        // keep the shared open-driver path below.
        if (args.firstOrNull() == "setup-token") return setupToken(config, args.drop(1))
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
            return when (args.firstOrNull()) {
                "mint-token" -> mintToken(tokenService, args.drop(1))
                "revoke-token" -> revokeToken(tokenService, args.drop(1))
                "list-tokens" -> listTokens(tokenService, args.drop(1))
                "grant-role" -> grantRole(roleRepo, args.drop(1))
                else -> {
                    System.err.println(USAGE)
                    2
                }
            }
        } finally {
            driver.close()
        }
    }

    /** `mint-token <label> [mode]` — mode defaults to read-only; prints the plaintext ONCE. */
    private fun mintToken(service: ApiTokenService, args: List<String>): Int {
        val label = args.getOrNull(0)
        if (label == null) {
            System.err.println("usage: plainbase admin mint-token <label> [${modeUsage()}]")
            return 2
        }
        val mode = parseMode(args.getOrNull(1)) ?: run {
            System.err.println("unknown mode '${args[1]}' — legal values: ${modeUsage()}")
            return 2
        }
        val minted = service.mint(label = label, mode = mode)
        println("token id: ${minted.id} (label: $label, mode: ${mode.name.lowercase()})")
        println(minted.plaintext)
        println("store this now — it is not recoverable; the server keeps only its hash")
        return 0
    }

    /** `revoke-token <id>` — sets revoked_at; idempotent for an unknown/already-revoked id. */
    private fun revokeToken(service: ApiTokenService, args: List<String>): Int {
        val id = args.getOrNull(0)
        if (id == null) {
            System.err.println("usage: plainbase admin revoke-token <id>")
            return 2
        }
        service.revoke(id)
        println("revoked token id: $id")
        return 0
    }

    /** `list-tokens` — metadata only (no plaintext exists to print). */
    private fun listTokens(service: ApiTokenService, args: List<String>): Int {
        if (args.isNotEmpty()) {
            System.err.println("usage: plainbase admin list-tokens")
            return 2
        }
        val rows = service.list()
        println("tokens: ${rows.size}")
        rows.forEach { println("  ${describe(it)}") }
        return 0
    }

    /**
     * `grant-role <issuer> <external_id> <viewer|editor|admin>` — the §4 proxy/recovery first-admin seam: an
     * idempotent [SqlDelightRoleRepository.upsert] of a role for ANY identity (a proxy user keys `proxy/<external
     * _id>`, which a builtin-shaped setup token can't seed). Seeds any mode's first admin and recovers a locked-out
     * builtin admin.
     */
    private fun grantRole(roleRepo: SqlDelightRoleRepository, args: List<String>): Int {
        if (args.size != 3) {
            System.err.println("usage: plainbase admin grant-role <issuer> <external_id> <${roleUsage()}>")
            return 2
        }
        val (issuer, externalId, rawRole) = args
        val role = parseRole(rawRole) ?: run {
            System.err.println("unknown role '$rawRole' — legal values: ${roleUsage()}")
            return 2
        }
        roleRepo.upsert(issuer, externalId, role, Clock.System.now())
        println("granted role ${role.name.lowercase()} to $issuer/$externalId")
        return 0
    }

    /**
     * `setup-token [--force]` — mints a BUILTIN first-admin bootstrap token, printing the plaintext ONCE. WITHOUT
     * `--force` it mints only on an empty / no-enabled-admin DB; WITH `--force` it re-mints regardless (lost-token
     * / sole-admin-disabled recovery). BUILTIN mode only (a proxy first-admin uses `grant-role`). Gated on the
     * DATA_DIR lock like `reindex` — refuse if a server holds it.
     */
    private fun setupToken(config: PlainbaseConfig, args: List<String>): Int {
        // Accept ONLY [] or [--force]; trailing junk after --force is a usage error, not silently ignored (fix F).
        val force = when (args) {
            emptyList<String>() -> false
            listOf("--force") -> true
            else -> {
                System.err.println("usage: plainbase admin setup-token [--force]")
                return 2
            }
        }
        if (config.auth.mode != AuthMode.BUILTIN) {
            System.err.println(
                "setup-token requires auth.mode=builtin (current: ${config.auth.mode.name.lowercase()}); " +
                    "a proxy/off first admin is seeded with `plainbase admin grant-role`",
            )
            return 2
        }
        // Lock FIRST — BEFORE the driver opens + migrates the DB (fix D). A live server holding the lock means we must
        // not open/migrate underneath it; everything DB-touching happens only once the lock is held.
        val lock = DataDirLock.tryAcquire(config.dataDir)
        if (lock == null) {
            System.err.println("setup-token: a Plainbase server is holding ${config.dataDir} — stop it before minting")
            return 1
        }
        return lock.use {
            val driver = DatabaseFactory.createDriver(config.appDatabasePath)
            try {
                val database = DatabaseFactory.createDatabase(driver)
                if (!force && SqlDelightUserRepository(database).countEnabledAdmins() > 0) {
                    System.err.println(
                        "an enabled admin already exists; refusing to mint a bootstrap token (use --force to re-mint for recovery)",
                    )
                    return@use 2
                }
                val minted = setupService(database).mintBootstrapToken()
                println(minted.plaintext)
                println("store this now — it is not recoverable. Consume it via POST /api/v1/setup/consume to create the first admin.")
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

    private val USAGE = "usage: plainbase admin <mint-token <label> [${modeUsage()}] | revoke-token <id> | " +
        "list-tokens | grant-role <issuer> <external_id> <${roleUsage()}> | setup-token [--force]>"
}
