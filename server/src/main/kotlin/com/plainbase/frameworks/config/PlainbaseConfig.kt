package com.plainbase.frameworks.config

import com.plainbase.frameworks.ktor.RemoteAddress
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import java.nio.file.Files
import java.nio.file.Path

/**
 * Application configuration.
 *
 * - `CONTENT_DIR` — canonical, user-owned content tree (Markdown + assets). §4 hard rule.
 * - `DATA_DIR`    — app-owned state (SQLite DB, plainbase.conf, caches, search.db).
 *
 * Environment variables override defaults; `DATA_DIR/plainbase.conf` (HOCON, ADR-0009) is layered in by
 * [fromEnvAndFile] — **env always wins**, the file only supplies values env omits. Secrets stay in env,
 * never the file. [fromEnv] is the env-only fast path the spike + content-only CLIs (reindex, adopt) use; the
 * server and the `admin` CLI use [fromEnvAndFile] (admin needs the file-configured `auth.mode`).
 */
data class PlainbaseConfig(
    val contentDir: Path,
    val dataDir: Path,
    val host: String,
    val port: Int,
    /**
     * PB-WRITE-1 body cap: the maximum `PUT /api/v1/pages/{id}` request-body size in bytes; a body
     * exceeding it is rejected `413 body_too_large` (the response carries this authoritative number,
     * so clients never hardcode it). Default 1 MiB; raisable per deploy (raising is additive — the
     * frozen contract is the cap BEHAVIOR + the code + the `max_bytes` field, never the number).
     */
    val maxWriteBodyBytes: Long = DEFAULT_MAX_WRITE_BODY_BYTES,
    /**
     * W3b asset upload cap: the maximum `POST /api/v1/pages/{id}/assets` request-body size in bytes; a body
     * exceeding it is rejected `413 body_too_large` (the response carries this authoritative number). A
     * separate, LARGER cap than [maxWriteBodyBytes] — assets are binaries (screenshots, pdfs, fonts), so a
     * 1 MiB document cap is wrong for them. Default 10 MiB; raisable per deploy (raising is additive).
     */
    val maxAssetBytes: Long = DEFAULT_MAX_ASSET_BYTES,
    /** W4 Git-history layer config (ADR-0006): enablement tri-state + the commit identity. */
    val git: GitConfig = GitConfig(),
    /** Phase-4 auth substrate (ADR-0008): bind-guard + secure-context inputs; restart-only (§0.9). */
    val auth: AuthConfig = AuthConfig(),
) {
    /** Path of the app-state SQLite database (workflow + security state, never content). */
    val appDatabasePath: Path get() = dataDir.resolve("plainbase.db")

    /**
     * Path of the derived-state search database (§B5/ADR-0004): rebuildable from the published
     * snapshot at any time, deletable with zero data loss — always a separate file from
     * [appDatabasePath] (§4 hard rule).
     */
    val searchDatabasePath: Path get() = dataDir.resolve("search.db")

    /**
     * Startup guard: fails fast with an operator-actionable message when the configured
     * CONTENT_DIR is missing or not a directory. Without it the first scan dies on a bare
     * `NoSuchFileException` that names nothing the operator can act on; silently serving an
     * empty tree would be worse (§4 — the content tree is the product).
     *
     * Also rejects DATA_DIR == CONTENT_DIR: that config violates §4's user-owned/app-owned
     * separation, and concretely puts plainbase.db/search.db (plus their -wal/-journal siblings —
     * none of them dotfiles) INSIDE the watched content root, where every checkpoint write would
     * re-trigger the watcher: a silent, self-sustaining rebuild loop. Strict nesting either way
     * stays legal — the watcher excludes a strictly-nested DATA_DIR, and under a strict ancestor
     * the app's writes land outside the watched tree.
     */
    fun requireContentDir(): Path {
        require(Files.isDirectory(contentDir)) { "CONTENT_DIR does not exist or is not a directory: $contentDir" }
        require(dataDir.toAbsolutePath().normalize() != contentDir.toAbsolutePath().normalize()) {
            "DATA_DIR and CONTENT_DIR must be different directories (both are $contentDir): app-owned state " +
                "(plainbase.db, search.db) inside the user-owned content root would re-trigger the watcher " +
                "after every rebuild — a self-sustaining rebuild loop (§4 separation)"
        }
        return contentDir
    }

    /**
     * ADR-0008 fail-closed bind guard. Returns an operator-actionable refusal MESSAGE when the bind is
     * non-loopback AND there is no trusted-proxy config AND no explicit insecure override — else null (start
     * permitted). Pure (no socket, no exit) so it unit-tests like [requireContentDir]; `serve()` prints the
     * message + `exitProcess(1)`.
     *
     * Loopback HTTP is always allowed (dev). The guard runs for EVERY mode, `off` included: `off` is the MOST
     * dangerous mode (fully unauthenticated), so a non-loopback `off` bind without an override is the open
     * internet serving an open surface — exactly what must be refused, never exempted.
     */
    fun bindGuardRefusal(): String? {
        // A4b: a PROXY-mode misconfig is refused even on a LOOPBACK bind — a loopback PROXY with no CIDR/secret still
        // trusts any loopback sibling. So this completeness check runs BEFORE the loopback early-return below. The
        // secret is the real trust anchor (CIDR alone trusts a whole subnet), so BOTH are required; the message
        // names both remedies.
        if (auth.mode == AuthMode.PROXY && (auth.trustedProxyCidrs.isEmpty() || auth.proxySecret.isNullOrBlank())) {
            return "auth.mode=proxy requires both a trusted-proxy allowlist and a shared secret. " +
                "Remedies: set PLAINBASE_TRUSTED_PROXY to the proxy's /32; set PLAINBASE_PROXY_SECRET to a shared value the proxy stamps."
        }
        if (!isNonLoopbackBind()) return null // loopback HTTP always allowed (dev)
        if (auth.trustedProxyCidrs.isNotEmpty()) return null // proxy mode declared (A4b terminates TLS)
        if (auth.insecureHttp) return null // explicit, knowing override (logs loudly)
        return "binds $host with auth.mode=${auth.mode.name.lowercase()} but no TLS/trusted-proxy and no insecure override. " +
            "Remedies: (1) front with a TLS proxy and set PLAINBASE_TRUSTED_PROXY CIDRs; " +
            "(2) bind loopback (PLAINBASE_HOST=127.0.0.1) behind the proxy; " +
            "(3) set PLAINBASE_INSECURE_HTTP=1 to knowingly serve plaintext."
    }

    /** True when [host] is a non-loopback / wildcard bind interface (the bind guard's exposure test, WI 3). */
    fun isNonLoopbackBind(): Boolean = RemoteAddress.isNonLoopbackBind(host)

    /**
     * The `Secure` attribute for the `pb_session` cookie (ADR-0008, WI-8). True whenever the transport is TLS-fronted
     * — MIRRORING the bind guard's "proxy declared ⇒ TLS upstream" logic: a non-loopback bind is fronted by TLS, AND
     * the canonical production deployment (LOOPBACK bind behind a TLS-terminating proxy, [bindGuardRefusal]) declares
     * [AuthConfig.trustedProxyCidrs] — that too is TLS-fronted, so the cookie must carry `Secure`. ONLY pure
     * loopback-dev with NO trusted proxy stays false (a `Secure` cookie would never be sent back over plain
     * http://localhost, breaking dev login).
     *
     * Deliberately NOT relaxed by [AuthConfig.insecureHttp] (`PLAINBASE_INSECURE_HTTP`, review I): that flag is only
     * the bind-guard escape for loopback-dev / agent-bearer scenarios — it lets the server bind plaintext, it does NOT
     * make credentialed builtin HUMAN auth work over a plaintext network. A non-loopback insecure-http bind still
     * marks the cookie `Secure` (so a browser won't send it over the plaintext), AND [isSecureContext] refuses the
     * credential per-request regardless — so credentialed human login over insecure-http simply does not function by
     * design. Serve human auth over loopback or behind a TLS-terminating reverse proxy; we do NOT make plaintext human
     * auth easy.
     */
    fun secureCookie(): Boolean = isNonLoopbackBind() || auth.trustedProxyCidrs.isNotEmpty()

    companion object {
        const val VERSION: String = "0.1.0"

        const val DEFAULT_PORT: Int = 8080

        /**
         * Default bind host: loopback (§ADR-0008). Out-of-the-box `serve` is dev/off-safe on `127.0.0.1`;
         * exposing the server requires an EXPLICIT non-loopback `PLAINBASE_HOST`, which trips the bind guard
         * unless TLS/trusted-proxy or `PLAINBASE_INSECURE_HTTP` is configured. (Docker/compose host handling
         * is A4b's job.)
         */
        const val DEFAULT_HOST: String = "127.0.0.1"

        /** PB-WRITE-1 default body cap: 1 MiB. Raisable via `PLAINBASE_MAX_WRITE_BODY_BYTES` (raising is additive). */
        const val DEFAULT_MAX_WRITE_BODY_BYTES: Long = 1_048_576

        /** W3b default asset cap: 10 MiB. Raisable via `PLAINBASE_MAX_ASSET_BYTES` (raising is additive). */
        const val DEFAULT_MAX_ASSET_BYTES: Long = 10_485_760

        /** W4 default Git author/committer identity (D1) — Phase 3 has no principal. */
        const val DEFAULT_GIT_AUTHOR_NAME: String = "Plainbase"
        const val DEFAULT_GIT_AUTHOR_EMAIL: String = "plainbase@localhost"

        /** A4b default proxy identity header (the IdP subject the trusted proxy stamps); operator-configurable. */
        const val DEFAULT_PROXY_IDENTITY_HEADER: String = "X-Forwarded-User"

        /**
         * Env-only construction (the CLIs/spike fast path). No file is read; this is exactly the
         * env-and-defaults behavior [fromEnvAndFile] falls back to when no `plainbase.conf` is present.
         */
        fun fromEnv(env: Map<String, String> = System.getenv()): PlainbaseConfig =
            build(env, ConfigFactory.empty())

        /**
         * Layered construction (ADR-0009): read `DATA_DIR/plainbase.conf` (HOCON) THEN overlay env —
         * **env always wins**, the file only supplies values env omits. A missing `plainbase.conf` is a clean
         * no-op (identical to [fromEnv]). [dataDir] locates the file and so is the one field that can never
         * come from it: it is resolved from env/default exactly as [fromEnv] does, never file-derived.
         */
        fun fromEnvAndFile(env: Map<String, String> = System.getenv()): PlainbaseConfig {
            val dataDir = Path.of(env["DATA_DIR"] ?: "./data").toAbsolutePath().normalize()
            val confPath = dataDir.resolve("plainbase.conf")
            // `.resolve()` so the ADR-0009 `${?…}` substitution the docs advertise actually resolves instead of
            // throwing ConfigException.NotResolved at the first typed getter (B3). ConfigResolveOptions.defaults()
            // resolves within-file refs then falls back to the JVM system ENV (not system properties); the optional
            // `${?…}` form drops silently when its var is unset (a bare `${…}` still throws by design).
            val file = if (Files.isRegularFile(confPath)) {
                ConfigFactory.parseFile(confPath.toFile()).resolve(ConfigResolveOptions.defaults())
            } else {
                ConfigFactory.empty()
            }
            return build(env, file)
        }

        /**
         * The single env-wins fallback chain shared by [fromEnv] and [fromEnvAndFile]: each field reads
         * `env[KEY] ?: file."path" ?: default`, so the env-always-wins invariant lives in ONE place. Typed
         * getters only (no `unwrapped()` reflection, no serialized data class) — that is what keeps it
         * native-safe.
         */
        private fun build(env: Map<String, String>, file: Config): PlainbaseConfig = PlainbaseConfig(
            contentDir = Path.of(env["CONTENT_DIR"] ?: file.stringOrNull("contentDir") ?: "./content").toAbsolutePath().normalize(),
            dataDir = Path.of(env["DATA_DIR"] ?: "./data").toAbsolutePath().normalize(),
            host = env["PLAINBASE_HOST"] ?: file.stringOrNull("host") ?: DEFAULT_HOST,
            port = env.longStrict("PLAINBASE_PORT")?.toIntInRange("PLAINBASE_PORT") ?: file.intOrNull("port") ?: DEFAULT_PORT,
            maxWriteBodyBytes = env.positiveLongStrict("PLAINBASE_MAX_WRITE_BODY_BYTES")
                ?: file.longOrNull("maxWriteBodyBytes")?.takeIf { it > 0 } ?: DEFAULT_MAX_WRITE_BODY_BYTES,
            maxAssetBytes = env.positiveLongStrict("PLAINBASE_MAX_ASSET_BYTES")
                ?: file.longOrNull("maxAssetBytes")?.takeIf { it > 0 } ?: DEFAULT_MAX_ASSET_BYTES,
            git = GitConfig(
                enabled = env.boolStrict("PLAINBASE_GIT_ENABLED") ?: file.boolStrict("git.enabled"),
                authorName = env["PLAINBASE_GIT_AUTHOR_NAME"] ?: file.stringOrNull("git.authorName") ?: DEFAULT_GIT_AUTHOR_NAME,
                authorEmail = env["PLAINBASE_GIT_AUTHOR_EMAIL"] ?: file.stringOrNull("git.authorEmail") ?: DEFAULT_GIT_AUTHOR_EMAIL,
            ),
            auth = AuthConfig(
                mode = AuthMode.parse(env["PLAINBASE_AUTH_MODE"] ?: file.stringOrNull("auth.mode")),
                trustedProxyCidrs = requireParseableCidrs(
                    env["PLAINBASE_TRUSTED_PROXY"]?.toCommaList() ?: file.stringListOrNull("auth.trustedProxy") ?: emptyList(),
                ),
                insecureHttp = env.boolStrict("PLAINBASE_INSECURE_HTTP") ?: file.boolStrict("auth.insecureHttp") ?: false,
                agentDirectCommitGlobs = env["PLAINBASE_AGENT_DIRECT_COMMIT_GLOBS"]?.toCommaList()
                    ?: file.stringListOrNull("auth.agentDirectCommit.globs") ?: emptyList(),
                // A secret SHOULD come from env (the "secrets stay in env" rule), but the file path is allowed for
                // completeness; the deploy docs steer operators to env.
                proxySecret = env["PLAINBASE_PROXY_SECRET"] ?: file.stringOrNull("auth.proxySecret"),
                proxyIdentityHeader = (env["PLAINBASE_PROXY_IDENTITY_HEADER"] ?: file.stringOrNull("auth.proxyIdentityHeader"))
                    ?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_PROXY_IDENTITY_HEADER,
            ),
        )

        /**
         * Fail-fast on a malformed `trustedProxyCidrs` entry (A1-amber): a present-but-unparseable CIDR (a bare
         * address with no `/prefix`, or an out-of-range prefix) is rejected at LOAD — not silently dropped (which
         * would shrink/empty the allowlist and flip the fail-closed bind guard, exposing a plaintext bind). After
         * this, "non-empty `trustedProxyCidrs`" provably means "≥1 PARSEABLE CIDR". CIDR parsing stays in ONE place
         * ([RemoteAddress.isParseableCidr]); the config layer never re-implements it.
         */
        private fun requireParseableCidrs(cidrs: List<String>): List<String> {
            cidrs.firstOrNull { !RemoteAddress.isParseableCidr(it) }?.let {
                throw IllegalArgumentException(
                    "PLAINBASE_TRUSTED_PROXY contains an unparseable CIDR: '$it' (expected a.b.c.d/n or IPv6/n)",
                )
            }
            return cidrs
        }

        /**
         * Strict env-wins numeric read: if [key] is ABSENT returns null (fall through to file/default); if it is
         * PRESENT it MUST parse, else fail-fast (env-wins means a present env value is authoritative — silently
         * dropping a typo'd `PLAINBASE_PORT=80x0` back to the file/default is the opposite of env-wins).
         */
        private fun Map<String, String>.longStrict(key: String): Long? {
            val raw = this[key] ?: return null
            return raw.trim().toLongOrNull()
                ?: throw IllegalArgumentException("$key must be an integer, got '$raw'")
        }

        private fun Map<String, String>.positiveLongStrict(key: String): Long? {
            val value = longStrict(key) ?: return null
            require(value > 0) { "$key must be a positive integer, got '$value'" }
            return value
        }

        private fun Long.toIntInRange(key: String): Int {
            require(this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$key out of range, got '$this'" }
            return toInt()
        }

        /**
         * Strict env-wins boolean read. Absent → null; present must be one of the documented canonical forms
         * (`1`/`0`, `true`/`false`, case-insensitive) — the bind-guard remedy tells operators
         * `PLAINBASE_INSECURE_HTTP=1`, so `1`/`0` must actually work, not silently coerce to false.
         */
        private fun Map<String, String>.boolStrict(key: String): Boolean? {
            val raw = this[key] ?: return null
            return when (raw.trim().lowercase()) {
                "1", "true" -> true
                "0", "false" -> false
                else -> throw IllegalArgumentException("$key must be one of 1/0/true/false, got '$raw'")
            }
        }
    }
}

/** Splits a comma-separated env value into trimmed, non-blank entries (the env form of a HOCON list). */
private fun String.toCommaList(): List<String> = split(',').map { it.trim() }.filter { it.isNotEmpty() }

private fun Config.stringOrNull(path: String): String? = if (hasPath(path)) getString(path) else null

private fun Config.intOrNull(path: String): Int? = if (hasPath(path)) getInt(path) else null

private fun Config.longOrNull(path: String): Long? = if (hasPath(path)) getLong(path) else null

private fun Config.stringListOrNull(path: String): List<String>? = if (hasPath(path)) getStringList(path) else null

/**
 * Strict file-side bool read mirroring the env `boolStrict`: ABSENT → null (fall through to default); PRESENT →
 * MUST parse one of 1/0/true/false, else fail-fast. Closes the env-vs-file inconsistency where the file path
 * `toBooleanStrictOrNull()` SWALLOWED a typo'd bool to null while the env path threw (a typo silently disabling a
 * security flag is the opposite of fail-fast). Read as a HOCON string so `auth.insecureHttp = "1"` is accepted.
 */
private fun Config.boolStrict(path: String): Boolean? {
    val raw = stringOrNull(path) ?: return null
    return when (raw.trim().lowercase()) {
        "1", "true" -> true
        "0", "false" -> false
        else -> throw IllegalArgumentException("$path must be one of 1/0/true/false, got '$raw'")
    }
}

/**
 * W4 Git-history config (ADR-0006). [enabled] is a tri-state: `null` auto-detects a repo in CONTENT_DIR
 * (the detection lives in `historyModule`, not here); `true`/`false` override either direction.
 * [authorName]/[authorEmail] are the commit identity (Phase 3 default `Plainbase <plainbase@localhost>`;
 * the author/committer split is plumbed for Phase 4). There is no amend/squash knob — one commit per save, always (fix D).
 */
data class GitConfig(
    val enabled: Boolean? = null,
    val authorName: String = PlainbaseConfig.DEFAULT_GIT_AUTHOR_NAME,
    val authorEmail: String = PlainbaseConfig.DEFAULT_GIT_AUTHOR_EMAIL,
)

/**
 * How requests authenticate (ADR-0008). Restart-only (§0.9). A1 ships the enum + the bind guard's use of it;
 * A3/A4 add the live extraction/enforcement.
 * - [OFF] — no human auth (loopback dev); the MOST dangerous mode, so a non-loopback bind is still
 *   subject to the fail-closed bind guard (refused without proxy/TLS config or `PLAINBASE_INSECURE_HTTP`).
 * - [BUILTIN] — built-in password login (A4a).
 * - [PROXY] — a trusted reverse-proxy asserts identity via a header (A4b).
 */
enum class AuthMode {
    OFF,
    BUILTIN,
    PROXY,
    ;

    companion object {
        /**
         * Parses [raw] (env or HOCON) case-insensitively. A blank/absent value defaults to [OFF]; a NON-blank
         * unknown value fails fast naming the legal values — a typo'd `auth.mode` must never silently disable
         * auth (risk #9).
         */
        fun parse(raw: String?): AuthMode {
            val token = raw?.trim()
            if (token.isNullOrEmpty()) return OFF
            return entries.firstOrNull { it.name.equals(token, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Unknown auth.mode '$token' — legal values: ${entries.joinToString(", ") { it.name.lowercase() }}",
                )
        }
    }
}

/**
 * Phase-4 auth substrate config (ADR-0008), all restart-only (§0.9).
 * - [mode] — the [AuthMode] above; default [AuthMode.OFF].
 * - [trustedProxyCidrs] — proxy source CIDRs whose `X-Forwarded-Proto: https` is trusted (secure-context,
 *   WI 5; A4b spoof check). Empty = no trusted proxy.
 * - [insecureHttp] — the explicit, knowing override that lets the bind guard serve credentials over plaintext.
 * - [agentDirectCommitGlobs] — PROVISIONAL: lands now (config-only); ENFORCEMENT is Phase 5 (§0.7), unused in
 *   Phase 4. Default `[]`.
 */
data class AuthConfig(
    val mode: AuthMode = AuthMode.OFF,
    val trustedProxyCidrs: List<String> = emptyList(),
    val insecureHttp: Boolean = false,
    val agentDirectCommitGlobs: List<String> = emptyList(),
    /**
     * A4b PROXY mode: the shared secret the trusted proxy stamps as `X-Plainbase-Proxy-Secret`. REQUIRED in proxy
     * mode (the [bindGuardRefusal] enforces it) — it is the real trust anchor: a CIDR alone trusts a whole subnet,
     * so a sibling on a shared net could stamp the identity header. Stays in env, never logged.
     */
    val proxySecret: String? = null,
    /** A4b PROXY mode: the operator-configurable identity header name (default `X-Forwarded-User`). */
    val proxyIdentityHeader: String = PlainbaseConfig.DEFAULT_PROXY_IDENTITY_HEADER,
)
