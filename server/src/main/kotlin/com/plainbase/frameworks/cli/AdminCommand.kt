package com.plainbase.frameworks.cli

import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.repository.ApiTokenMeta
import com.plainbase.domain.service.ApiTokenService
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.security.ApiTokenMinter
import com.plainbase.frameworks.security.TokenHasher
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightApiTokenRepository
import kotlin.time.Clock

/**
 * `plainbase admin <mint-token|revoke-token|list-tokens>` — the A2 agent-token admin surface. CLI-only for A2
 * (the admin HTTP route + UI is A4b); two-level dispatch mirrors [AdoptCommand]'s structure, the top-level
 * `admin` branch in `Application.kt` delegating to this inner `when`.
 *
 * stdout is a CLI output contract (`println` by design, like `adopt`/`spike`): the minted plaintext token is
 * printed ONCE here and NOWHERE else — never via the kotlin-logging facade (the id is public and may be logged;
 * the secret/plaintext never is). Diagnostics still go through the logging facade.
 */
object AdminCommand {

    /** Entry point for the `main` dispatch: real env config, exit-code result. */
    fun runAsMain(args: List<String>): Int = run(args, PlainbaseConfig.fromEnv())

    fun run(args: List<String>, config: PlainbaseConfig): Int {
        val driver = DatabaseFactory.createDriver(config.appDatabasePath)
        try {
            val service = ApiTokenService(
                minter = ApiTokenMinter(),
                hasher = TokenHasher(),
                tokens = SqlDelightApiTokenRepository(DatabaseFactory.createDatabase(driver)),
                clock = Clock.System,
            )
            return when (args.firstOrNull()) {
                "mint-token" -> mintToken(service, args.drop(1))
                "revoke-token" -> revokeToken(service, args.drop(1))
                "list-tokens" -> listTokens(service, args.drop(1))
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

    private fun modeUsage(): String = AgentMode.entries.joinToString("|") { it.name.lowercase().replace('_', '-') }

    private val USAGE = "usage: plainbase admin <mint-token <label> [${modeUsage()}] | revoke-token <id> | list-tokens>"
}
