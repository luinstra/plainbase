package com.plainbase.frameworks.config

import com.plainbase.BuildInfo
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.BootRefusal
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.CommitGlob
import com.plainbase.frameworks.ktor.RemoteAddress
import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigResolveOptions
import com.typesafe.config.ConfigValue
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Application configuration.
 *
 * - `CONTENT_DIR` - canonical, user-owned content tree (Markdown + assets). §4 hard rule.
 * - `DATA_DIR`    - app-owned state (SQLite DB, plainbase.conf, caches, search.db).
 *
 * Environment variables override defaults; `DATA_DIR/plainbase.conf` (HOCON, ADR-0009) is layered in by
 * [fromEnvAndFile] - **env always wins**, the file only supplies values env omits. Secrets stay in env,
 * never the file. [fromEnv] is the env-only fast path only the credential-free `spike` uses; the server and
 * every DATA_DIR-sharing CLI (`admin`, `adopt`, `reindex`) use [fromEnvAndFile], so their file-configured
 * decisions (auth.mode, storage.backend) all match serve for the same DATA_DIR.
 */
data class PlainbaseConfig(
    val contentDir: Path,
    val dataDir: Path,
    val host: String,
    val port: Int,
    /**
     * PB-WRITE-1 body cap: the maximum `PUT /api/v1/pages/{id}` request-body size in bytes; a body
     * exceeding it is rejected `413 body_too_large` (the response carries this authoritative number,
     * so clients never hardcode it). Default 1 MiB; raisable per deploy (raising is additive - the
     * frozen contract is the cap BEHAVIOR + the code + the `max_bytes` field, never the number).
     */
    val maxWriteBodyBytes: Long = DEFAULT_MAX_WRITE_BODY_BYTES,
    /**
     * W3b asset upload cap: the maximum `POST /api/v1/pages/{id}/assets` request-body size in bytes; a body
     * exceeding it is rejected `413 body_too_large` (the response carries this authoritative number). A
     * separate, LARGER cap than [maxWriteBodyBytes] - assets are binaries (screenshots, pdfs, fonts), so a
     * 1 MiB document cap is wrong for them. Default 10 MiB; raisable per deploy (raising is additive).
     */
    val maxAssetBytes: Long = DEFAULT_MAX_ASSET_BYTES,
    /** Git-history layer config (ADR-0006): enablement tri-state + the commit identity. */
    val git: GitConfig = GitConfig(),
    /** Phase-4 auth substrate (ADR-0008): bind-guard + secure-context inputs; restart-only (§0.9). */
    val auth: AuthConfig = AuthConfig(),
    /** Storage-backend selection (Q9): the local filesystem authority (default) or an S3-compatible bucket. */
    val storage: StorageConfig = StorageConfig(),
    /**
     * Where [contentDir] came from (Q10 source tracking): the env/file/default arms of the build chain,
     * captured because object mode IGNORES CONTENT_DIR and must warn only when it was EXPLICITLY set.
     */
    val contentDirSource: ConfigSource = ConfigSource.DEFAULT,
    /**
     * The root topology (multi-root C1): the parsed `roots {}` block, or the synthesized back-compat
     * `main` every legacy config gets (byte-identical to today's [contentDir]/[storage] behavior).
     * The default runs at CONSTRUCTION only - `.copy(contentDir = ...)` or `.copy(storage = ...)`
     * keeps the pre-copy value, so do NOT rely on copy() to re-derive roots; reconstruct via
     * [fromEnvAndFile]. (Copied test configs stay correct anyway because the stale synthesized main
     * resolves to the same [contentDir] value - the equal-value invariant [mainContentRoot] pins.)
     */
    val roots: RootsConfig = RootsConfig.synthesized(contentDir, storage),
) {
    /** Path of the app-state SQLite database (workflow + security state, never content). */
    val appDatabasePath: Path get() = dataDir.resolve("plainbase.db")

    /**
     * Path of the MACHINE-MANAGED roots file (C5 D-C5-1): `plainbase root add|remove` rewrites it wholesale,
     * and the loader merges it with the operator's own `roots {}` block. Declared here so the CLI never
     * re-derives it. The operator's `plainbase.conf` is NEVER opened for writing - that guarantee is an
     * absence of code, not a best-effort round trip.
     */
    val managedRootsPath: Path get() = dataDir.resolve(MANAGED_ROOTS_FILE)

    /**
     * Path of the derived-state search database (§B5/ADR-0004): rebuildable from the published
     * snapshot at any time, deletable with zero data loss - always a separate file from
     * [appDatabasePath] (§4 hard rule).
     */
    val searchDatabasePath: Path get() = dataDir.resolve("search.db")

    /**
     * Startup guard: fails fast with an operator-actionable message when the configured
     * CONTENT_DIR is missing or not a directory. Without it the first scan dies on a bare
     * `NoSuchFileException` that names nothing the operator can act on; silently serving an
     * empty tree would be worse (§4 - the content tree is the product).
     *
     * Also rejects DATA_DIR == CONTENT_DIR: that config violates §4's user-owned/app-owned
     * separation, and concretely puts plainbase.db/search.db (plus their -wal/-journal siblings -
     * none of them dotfiles) INSIDE the watched content root, where every checkpoint write would
     * re-trigger the watcher: a silent, self-sustaining rebuild loop. Strict nesting either way
     * stays legal - the watcher excludes a strictly-nested DATA_DIR, and under a strict ancestor
     * the app's writes land outside the watched tree.
     *
     * Split by [RootsConfig.origin] (ADR-0011 D9): a synthesized (legacy) config runs exactly the
     * guard above - zero drift, the byte-identical mandate lives here. An explicit `roots {}` block
     * carries no back-compat obligation, so it gets the full strict matrix ([validateExplicitRoots])
     * instead, and the validated/returned path is [mainContentRoot], never the ignored legacy
     * [contentDir].
     */
    fun requireContentDir(): Path {
        // ONE implementation, TWO shapes (C5 S1.4): the matrix below COLLECTS every failure as a value, and
        // this throws the FIRST of them - so boot's operator-facing message is byte-identical to what it has
        // always been (RootsValidationTest's message assertions are the proof), while [bootRefusals] hands the
        // shared boot gate a COMPLETE set. A complete set is not a nicety: the CLI diffs the candidate's
        // refusals against the current config's, and a validator that stops at its first failure lets a
        // pre-existing fault MASK a new one - so `root add` would write a fresh nesting violation and report
        // success (C5 D-C5-17.3).
        topologyRefusals().firstOrNull()?.let { throw IllegalArgumentException(it.message) }
        // Object mode IGNORES CONTENT_DIR (Q10 - the bucket is the authority), but the local mirror/CLI seams
        // still need a defined Path, so the legacy field is what it returns.
        if (storage.backend == StorageBackend.OBJECT) return contentDir
        if (roots.origin == RootsOrigin.EXPLICIT) return requireNotNull(roots.main.localPath)
        return contentDir
    }

    /**
     * Every refusal `serve()` raises from CONFIG + FILESYSTEM, as VALUES (C5 D-C5-17): the topology matrix
     * [requireContentDir] throws from, plus the ADR-0008 bind guard. COMPLETE - no short-circuit - because
     * the CLI's baseline diff needs to know whether the candidate introduced a SECOND fault behind a first.
     *
     * The per-root git gate is NOT here: it needs the wired stores and history providers, so it is folded in
     * by `evaluateBootGate`, which is the one thing both `serve()` and `plainbase root` call.
     */
    internal fun bootRefusals(): List<BootRefusal> =
        topologyRefusals() +
            listOfNotNull(bindGuardRefusal()?.let { BootRefusal(BootRefusal.Kind.BIND_GUARD, emptySet(), it) })

    /**
     * The CONFIG+FILESYSTEM topology matrix, by the same backend/origin dispatch [requireContentDir] has
     * always done: the object required-key matrix, the strict explicit-roots matrix (ADR-0011 D9), or the two
     * legacy guards. Never throws.
     *
     * The legacy and explicit arms word the SAME faults differently - and that is exactly why a refusal
     * carries a KIND. A legacy `DATA_DIR == CONTENT_DIR` and the same install after one `root add` produce
     * DIFFERENT PROSE for one unchanged fault, so a diff over messages would call it NEW and refuse an add
     * that introduced nothing. Both arms emit `ROOT_VS_DATA_DIR` on `{main}`, so the KEY is stable across the
     * arm switch (C5 D-C5-17.3, misclassification 2).
     *
     * **Different prose is safe; a different CONDITION is not.** Both arms probe main through the ONE
     * [mainFault] predicate for the same reason: an arm that raised `MAIN_UNUSABLE` on a condition the other
     * arm cannot even test would put a fault the operator ALREADY HAS on the candidate side of the CLI's diff
     * alone, and `plainbase root` would refuse an add that introduced nothing - the failure mode the key diff
     * was built to make impossible, reintroduced one predicate lower down.
     */
    private fun topologyRefusals(): List<BootRefusal> = when {
        storage.backend == StorageBackend.OBJECT -> objectKeyRefusals()
        roots.origin == RootsOrigin.EXPLICIT -> explicitRootRefusals()
        else -> legacyRefusals()
    }

    /**
     * The Q9 object required-key matrix. fromEnv/fromEnvAndFile already fail fast at LOAD with these same
     * messages; this arm re-asserts them for a directly-constructed object config (tests/embedded) through
     * the one funnel `serve()` runs. Unreachable from `plainbase root`: an explicit `roots {}` plus object
     * storage does not load at all, and the CLI's candidate always carries a roots block.
     */
    private fun objectKeyRefusals(): List<BootRefusal> = buildList {
        fun refuse(message: String) = add(BootRefusal(BootRefusal.Kind.OBJECT_KEYS, emptySet(), message))
        if (roots.origin == RootsOrigin.EXPLICIT) {
            refuse("roots {} cannot be combined with storage.backend=object in this release (ADR-0011 D10)")
        }
        if (storage.endpoint == null) refuse("storage.object.endpoint is required when storage.backend=object (the R2/S3 endpoint URL)")
        if (storage.bucket == null) refuse("storage.object.bucket is required when storage.backend=object")
        if (storage.accessKeyId == null || storage.secretAccessKey == null) refuse(MISSING_S3_CREDENTIALS_MESSAGE)
    }

    /**
     * The two back-compat guards a SYNTHESIZED (legacy) config gets, and nothing else (ADR-0011 D9): the
     * byte-identical mandate lives here. Both are keyed on `{main}` with the SAME kind the explicit matrix
     * produces for the same fault, deliberately - the arms word a fault differently, they never key it
     * differently ([mainFault] is the shared predicate that makes the MAIN_UNUSABLE half of that true).
     */
    private fun legacyRefusals(): List<BootRefusal> = buildList {
        mainFault(contentDir)?.let { fault ->
            val message = when (fault) {
                MainFault.NOT_A_DIRECTORY -> "CONTENT_DIR does not exist or is not a directory: $contentDir"
                MainFault.NOT_TRAVERSABLE ->
                    "CONTENT_DIR is not readable/searchable: $contentDir (fix its permissions so the server can serve it)"
            }
            add(BootRefusal(BootRefusal.Kind.MAIN_UNUSABLE, setOf(RootName.MAIN), message))
        }
        if (dataDir.toAbsolutePath().normalize() == contentDir.toAbsolutePath().normalize()) {
            add(
                BootRefusal(
                    BootRefusal.Kind.ROOT_VS_DATA_DIR,
                    setOf(RootName.MAIN),
                    "DATA_DIR and CONTENT_DIR must be different directories (both are $contentDir): app-owned state " +
                        "(plainbase.db, search.db) inside the user-owned content root would re-trigger the watcher " +
                        "after every rebuild - a self-sustaining rebuild loop (§4 separation)",
                ),
            )
        }
    }

    /**
     * The strict filesystem matrix for an explicit `roots {}` block (ADR-0011 D9): main must exist
     * and be readable;
     * paths canonicalize via toRealPath for the COMPARISONS only (D8 - served paths keep their
     * declared form); no duplicate roots, no nested roots, and DATA_DIR may neither equal nor
     * contain a root. DATA_DIR strictly inside a root stays legal - it feeds that root's watcher
     * exclusion in C4, as main's already does via ContentModule. An unavailable path (missing, not
     * a directory, or any I/O failure while canonicalizing) is fatal for main but keeps an EXTRA
     * participating in every comparison via its best-effort canonical form
     * ([bestEffortCanonical] - the deepest existing ancestor resolved, remainder appended; D13,
     * [rootsWarnings] names it).
     *
     * **Every failure, in matrix order, NEVER throwing** (C5 S1.4). The order is load-bearing twice:
     * [requireContentDir] throws the FIRST, so it is what an operator sees at boot and what
     * `RootsValidationTest` already pins; and a stable order makes the CLI's WARN output stable. Main's
     * canonicalization failure records `MAIN_UNUSABLE` and falls back to [bestEffortCanonical] rather than
     * throwing, so the pairwise and DATA_DIR checks below it still run and still report - which is exactly
     * the completeness the baseline diff needs. (The rethrow in [requireContentDir] drops the IOException
     * `cause` the old throw carried; nothing asserts on it, and a nullable cause on [BootRefusal] would be a
     * field one caller reads.)
     */
    private fun explicitRootRefusals(): List<BootRefusal> = buildList {
        val mainPath = requireNotNull(roots.main.localPath) // parse rejects non-local backends in an explicit block
        fun mainUnusable(message: String) = add(BootRefusal(BootRefusal.Kind.MAIN_UNUSABLE, setOf(RootName.MAIN), message))
        val fault = mainFault(mainPath)
        when (fault) {
            MainFault.NOT_A_DIRECTORY -> mainUnusable("roots.main.path does not exist or is not a directory: $mainPath")
            MainFault.NOT_TRAVERSABLE -> mainUnusable(
                "roots.main.path is not readable/searchable: $mainPath (fix its permissions so the server can serve it)",
            )
            null -> Unit
        }
        val canonical = roots.list.map { root ->
            val declared = requireNotNull(root.localPath)
            val comparable = if (root.name == RootName.MAIN) {
                try {
                    declared.toRealPath()
                } catch (e: IOException) {
                    // Only worth reporting when main OTHERWISE looked fine (a race, an exotic filesystem): a
                    // main that is simply not there is already named above, and saying it twice says nothing more.
                    if (fault == null) mainUnusable("roots.main.path cannot be resolved: $declared (${e.message})")
                    bestEffortCanonical(declared)
                }
            } else {
                // D13: an unavailable extra still participates via its best-effort canonical form.
                canonicalRootPathOrNull(declared) ?: bestEffortCanonical(declared)
            }
            Triple(root.name, declared, comparable)
        }
        // First boot: DATA_DIR is only created later (DataDirLock.tryAcquire), so a missing one gets
        // the same best-effort fallback as an unavailable extra - resolving EXISTING symlinked
        // ancestors matters here, or a DATA_DIR declared through an alias into a root would pass
        // validation and then be physically created inside the served tree.
        val dataDirComparable = try {
            dataDir.toRealPath()
        } catch (_: IOException) {
            bestEffortCanonical(dataDir)
        }
        // Keyed by the PAIR as a SET, so a pre-existing violation between (a, b) cannot mask a new one between
        // (a, c). The `when` is the same short-circuit the require chain had: two roots at ONE path also
        // trivially "nest" both ways, and saying so three times helps nobody.
        canonical.forEachIndexed { i, (aName, _, aPath) ->
            canonical.drop(i + 1).forEach { (bName, _, bPath) ->
                fun pair(message: String) = add(BootRefusal(BootRefusal.Kind.ROOT_PAIR, setOf(aName, bName), message))
                when {
                    aPath == bPath -> pair("roots.${aName.value} and roots.${bName.value} resolve to the same directory: $aPath")
                    aPath.startsWith(bPath) -> pair(
                        "roots.${aName.value} ($aPath) is nested inside roots.${bName.value} ($bPath): roots must be disjoint directories",
                    )
                    bPath.startsWith(aPath) -> pair(
                        "roots.${bName.value} ($bPath) is nested inside roots.${aName.value} ($aPath): roots must be disjoint directories",
                    )
                }
            }
        }
        val dataDirDeclared = dataDir.toAbsolutePath().normalize()
        canonical.forEach { (name, declared, comparable) ->
            fun vsDataDir(message: String) = add(BootRefusal(BootRefusal.Kind.ROOT_VS_DATA_DIR, setOf(name), message))
            when {
                comparable == dataDirComparable -> vsDataDir(
                    "roots.${name.value} and DATA_DIR must be different directories (both are $comparable): app-owned state " +
                        "(plainbase.db, search.db) inside a docs root would re-trigger the watcher after every rebuild (§4 separation)",
                )
                comparable.startsWith(dataDirComparable) -> vsDataDir(
                    "roots.${name.value} ($comparable) is inside DATA_DIR ($dataDirComparable): app-owned state must not contain a docs root",
                )
                // DATA_DIR strictly inside a root is legal ONLY when the DECLARED forms nest too: the store's
                // DATA_DIR exclusion is lexical over the declared paths, so a symlink-aliased nesting would
                // dodge it and index/serve app state as content.
                dataDirComparable.startsWith(comparable) && !dataDirDeclared.startsWith(declared) -> vsDataDir(
                    "DATA_DIR ($dataDirDeclared) is inside roots.${name.value} on disk but not by its declared path " +
                        "($declared): declare the root and DATA_DIR through consistent paths so the app-state exclusion can apply",
                )
            }
        }
    }

    /**
     * Operator-facing storage-config warnings (Q9/Q10), logged once by `serve()` (the [bindGuardRefusal]
     * pure-accessor idiom: no logger here, so it unit-tests like the guards). NEVER fatal:
     * - local mode names any configured-but-ignored `storage.object.*` keys (a shared plainbase.conf
     *   across deploys stays legal);
     * - object mode warns when CONTENT_DIR was EXPLICITLY set (env/file per [contentDirSource]),
     *   because object mode ignores it entirely.
     */
    fun storageWarnings(): List<String> = buildList {
        if (storage.backend == StorageBackend.LOCAL && storage.ignoredObjectKeys.isNotEmpty()) {
            add(
                "storage.backend=local ignores the configured object-storage key(s): " +
                    "${storage.ignoredObjectKeys.joinToString(", ")} (set storage.backend=object to use them)",
            )
        }
        // live from C4: object mode is real now (the hybrid store hydrates a DATA_DIR mirror), so this
        // explicitly-set-CONTENT_DIR warning is reachable on a real object boot - not dead pre-C4 code.
        if (storage.backend == StorageBackend.OBJECT && contentDirSource != ConfigSource.DEFAULT) {
            add(
                "storage.backend=object ignores CONTENT_DIR (explicitly set via ${contentDirSource.name.lowercase()}): " +
                    "the bucket is the authority and the local mirror lives inside DATA_DIR",
            )
        }
    }

    /**
     * Operator-facing multi-root warnings (ADR-0011 D11-D13), logged once by `serve()` like
     * [storageWarnings] (same pure-accessor idiom, kept SEPARATE so the storage warnings and their
     * tests stay untouched). Empty for every synthesized (legacy) config. Unlike [storageWarnings]
     * this probes the filesystem for extra-root availability - through the SAME probe the validation
     * fallback uses, so an unavailable extra can never be visible to one and silently skipped by
     * the other.
     */
    fun rootsWarnings(): List<String> = buildList {
        if (roots.origin != RootsOrigin.EXPLICIT) return@buildList
        // Gated on main having actually been DECLARED (C5 D-C5-3), not merely on EXPLICIT. A `roots.conf`-only
        // topology is EXPLICIT with main SYNTHESIZED from contentDir, so CONTENT_DIR is the very thing main's
        // path comes from - telling a docker/systemd operator it is ignored would be a LIE whose natural
        // remedy (delete the "ignored" env var) silently repoints main at ./content.
        if (roots.mainDeclared && contentDirSource != ConfigSource.DEFAULT) {
            add(
                "roots {} is configured: the explicitly set CONTENT_DIR/contentDir (via ${contentDirSource.name.lowercase()}) " +
                    "is ignored - main's path comes from roots.main.path",
            )
        }
        // The C1 "extras are configured but unserved" and "editable/history are recorded but dormant" warnings are
        // RETIRED as of C4: extras ARE served, and editable/history ARE enforced.
        roots.extras.forEach { extra ->
            val declared = requireNotNull(extra.localPath)
            if (canonicalRootPathOrNull(declared) == null) {
                add(
                    "roots.${extra.name.value}.path does not exist or is not a readable/searchable directory: $declared " +
                        "- the root will serve 503 for every request until the path is restored AND the server is " +
                        "restarted (its pages, aliases and checkpoints are left untouched in the meantime)",
                )
            }
        }
        // An operator trap, not an error: a direct-commit glob on a read-only root can never authorize anything,
        // because the editable gate denies before the glob is ever consulted. Silently doing nothing is exactly how
        // an operator ends up believing an agent has write access it does not have.
        //
        // Walked from the ROOTS side, not from the by-root glob map: main's globs live in their own key (D6 -
        // `agentDirectCommit.globs`, the env var, or `roots.main`, never in the by-root map, which excludes main by
        // construction), so a map-keyed walk would leave `roots.main { editable = false }` - the likeliest trap of
        // the lot, since main is the root every glob was written for - the one case it could not see.
        roots.list
            .filter { !it.editable && globbedRoots().contains(it.name) }
            .forEach { root ->
                add(
                    "auth.agentDirectCommit declares direct-commit globs for root '${root.name.value}', but " +
                        "roots.${root.name.value} is editable = false - the globs can never authorize anything there, " +
                        "because the root refuses page writes outright. Set editable = true, or drop the globs.",
                )
            }
    }

    /** Every root carrying at least one direct-commit glob, across BOTH homes (main's own key + the per-root block). */
    private fun globbedRoots(): Set<RootName> =
        buildSet {
            if (auth.agentDirectCommitGlobs.isNotEmpty()) add(RootName.MAIN)
            auth.agentDirectCommitGlobsByRoot.forEach { (root, globs) -> if (globs.isNotEmpty()) add(root) }
        }

    /**
     * ADR-0008 fail-closed bind guard. Returns an operator-actionable refusal MESSAGE when the bind is
     * non-loopback AND there is no trusted-proxy config AND no explicit insecure override - else null (start
     * permitted). Pure (no socket, no exit) so it unit-tests like [requireContentDir]; `serve()` prints the
     * message + `exitProcess(1)`.
     *
     * Loopback HTTP is always allowed (dev). The guard runs for EVERY mode, `off` included: `off` is the MOST
     * dangerous mode (fully unauthenticated), so a non-loopback `off` bind without an override is the open
     * internet serving an open surface - exactly what must be refused, never exempted.
     */
    fun bindGuardRefusal(): String? {
        // A4b: a PROXY-mode misconfig is refused even on a LOOPBACK bind - a loopback PROXY with no CIDR/secret still
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
     * The `Secure` attribute for the `pb_session` cookie (ADR-0008). True whenever the transport is TLS-fronted
     * - MIRRORING the bind guard's "proxy declared ⇒ TLS upstream" logic: a non-loopback bind is fronted by TLS, AND
     * the canonical production deployment (LOOPBACK bind behind a TLS-terminating proxy, [bindGuardRefusal]) declares
     * [AuthConfig.trustedProxyCidrs] - that too is TLS-fronted, so the cookie must carry `Secure`. ONLY pure
     * loopback-dev with NO trusted proxy stays false (a `Secure` cookie would never be sent back over plain
     * http://localhost, breaking dev login).
     *
     * Deliberately NOT relaxed by [AuthConfig.insecureHttp] (`PLAINBASE_INSECURE_HTTP`, review I): that flag is only
     * the bind-guard escape for loopback-dev / agent-bearer scenarios - it lets the server bind plaintext, it does NOT
     * make credentialed builtin HUMAN auth work over a plaintext network. A non-loopback insecure-http bind still
     * marks the cookie `Secure` (so a browser won't send it over the plaintext), AND [isSecureContext] refuses the
     * credential per-request regardless - so credentialed human login over insecure-http simply does not function by
     * design. Serve human auth over loopback or behind a TLS-terminating reverse proxy; we do NOT make plaintext human
     * auth easy.
     */
    fun secureCookie(): Boolean = isNonLoopbackBind() || auth.trustedProxyCidrs.isNotEmpty()

    /**
     * The P3 MCP DNS-rebinding HOST allowlist, fail-closed (the [secureCookie] accessor idiom): the operator value
     * when set, ELSE a conservative default derived from the bind host (NOT empty, NOT a wildcard) plus loopback. The
     * SDK matches the request `Host` header's HOSTNAME (port stripped) against this, so bare hostnames suffice; an
     * operator behind a reverse proxy adds their external host. The bind host is the natural default - a request whose
     * `Host` is the host we bind is the only one we serve by default.
     */
    fun mcpHostAllowlist(): List<String> = auth.mcpAllowedHosts.ifEmpty { (listOf(host) + MCP_LOOPBACK_HOSTS).distinct() }

    /**
     * The P3 MCP DNS-rebinding ORIGIN allowlist, fail-closed: the operator value when set, ELSE the bind-host origins
     * (http+https) plus the loopback origins. The SDK extracts the request `Origin` header's host for the match, so
     * these full origins normalize to their hostnames; an operator adds their external origin behind a reverse proxy.
     */
    fun mcpOriginAllowlist(): List<String> = auth.mcpAllowedOrigins.ifEmpty {
        (listOf("http://$host:$port", "https://$host:$port") + MCP_LOOPBACK_HOSTS.map { "http://$it:$port" }).distinct()
    }

    /**
     * The validated agent direct-commit globs as parsed [CommitGlob]s, FLAT — each carrying the root whose config key
     * declared it (the [mcpHostAllowlist] accessor idiom). Re-parsing is safe because config load already validated
     * every pattern, so this never throws at request time.
     *
     * The two sources are `auth.agentDirectCommit.globs` (main's list, unchanged meaning) and
     * `auth.agentDirectCommit.roots.<name>` (the per-root block). The matcher then filters by the TARGET root, so a
     * pattern declared for main authorizes nothing in an extra root and vice versa.
     */
    fun agentDirectCommitGlobs(): List<CommitGlob> =
        auth.agentDirectCommitGlobs.map { CommitGlob.parse(it, RootName.MAIN) } +
            auth.agentDirectCommitGlobsByRoot.flatMap { (root, globs) -> globs.map { CommitGlob.parse(it, root) } }

    /**
     * Main's content root on the local filesystem: roots.main's path for a Local backend, contentDir
     * otherwise (object mode ignores it, but the mirror/CLI seams still need a defined Path).
     * Identical to contentDir for every legacy (synthesized) config.
     */
    fun mainContentRoot(): Path = roots.main.localPath ?: contentDir

    companion object {
        // C5 item 8: self-report tracks the release tag (root build.gradle.kts `-PreleaseVersion` ->
        // project.version -> generated com.plainbase.BuildInfo) instead of a hardcoded literal.
        const val VERSION: String = BuildInfo.VERSION

        const val DEFAULT_PORT: Int = 8080

        /**
         * The machine-managed roots file (C5 D-C5-1), in DATA_DIR beside `plainbase.conf`. Owned end-to-end by
         * `plainbase root`: rewritten in full by every `add`/`remove`, deleted when its last root goes. It
         * declares EXTRAS ONLY - `main` is never CLI-managed (D-C5-2), because CONTENT_DIR is routinely an
         * environment variable and freezing the value one `root add` happened to see would silently repoint
         * main on every container that boots with a different one.
         */
        const val MANAGED_ROOTS_FILE: String = "roots.conf"

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

        /** Default Git author/committer identity - Phase 3 has no principal. */
        const val DEFAULT_GIT_AUTHOR_NAME: String = "Plainbase"
        const val DEFAULT_GIT_AUTHOR_EMAIL: String = "plainbase@localhost"

        /** A4b default proxy identity header (the IdP subject the trusted proxy stamps); operator-configurable. */
        const val DEFAULT_PROXY_IDENTITY_HEADER: String = "X-Forwarded-User"

        /** The loopback hosts always added to the fail-closed MCP DNS-rebinding default (dev/test always reach these). */
        private val MCP_LOOPBACK_HOSTS: List<String> = listOf("127.0.0.1", "localhost")

        /** Q9 default signing region: `auto` (R2, the primary provider). */
        const val DEFAULT_S3_REGION: String = "auto"

        /** Q9 default watch/reconcile poll interval (seconds). */
        const val DEFAULT_S3_POLL_SECONDS: Long = 60

        /** The Q9 combined credentials failure (one message for both halves - they only make sense together). */
        private const val MISSING_S3_CREDENTIALS_MESSAGE: String =
            "PLAINBASE_S3_ACCESS_KEY_ID and PLAINBASE_S3_SECRET_ACCESS_KEY are required when storage.backend=object " +
                "(secrets stay in env, never plainbase.conf)"

        /**
         * Every non-credential object-storage key as env-name -> HOCON-path (Q9), probed for presence in
         * local mode so the ignored+warn startup warning can NAME what it is ignoring. Credentials are
         * deliberately absent: in local mode they are ignored silently (never named, never logged).
         */
        private val OBJECT_STORAGE_KEYS: List<Pair<String, String>> = listOf(
            "PLAINBASE_S3_ENDPOINT" to "storage.object.endpoint",
            "PLAINBASE_S3_BUCKET" to "storage.object.bucket",
            "PLAINBASE_S3_REGION" to "storage.object.region",
            "PLAINBASE_S3_PREFIX" to "storage.object.prefix",
            "PLAINBASE_S3_PATH_STYLE" to "storage.object.pathStyle",
            "PLAINBASE_S3_POLL_SECONDS" to "storage.object.pollSeconds",
        )

        /**
         * Env-only construction (the credential-free `spike` fast path only; every DATA_DIR-sharing CLI now
         * uses [fromEnvAndFile]). No file is read; this is exactly the
         * env-and-defaults behavior [fromEnvAndFile] falls back to when no `plainbase.conf` is present.
         */
        fun fromEnv(env: Map<String, String> = System.getenv()): PlainbaseConfig =
            build(env, ConfigFactory.empty())

        /**
         * Where DATA_DIR comes from, in ONE spelling (C5 D-C5-9). [dataDir] locates every config file and so is
         * the one field that can never come from one: it is resolved from env/default exactly as [fromEnv]
         * does, never file-derived. `plainbase root` needs it BEFORE the lock - and therefore before any config
         * - and this is the same value `config.dataDir` will hold, by construction rather than by luck.
         */
        internal fun dataDirFrom(env: Map<String, String> = System.getenv()): Path =
            Path.of(env["DATA_DIR"] ?: "./data").toAbsolutePath().normalize()

        /**
         * [fromEnvAndFile]'s body, over the TWO files the roots topology now merges (C5 D-C5-1).
         * [managedOverride] non-null is the CLI's in-memory CANDIDATE `roots.conf` - the bytes it is ABOUT to
         * write; null reads `DATA_DIR/roots.conf` from disk, which is what boot does.
         *
         * ONE [build], so a refusal the real loader raises is a refusal the candidate load raises. There is no
         * second loader to drift, which is the entire point: the CLI does not know what those refusals ARE, and
         * must not (D-C5-17).
         */
        private fun fromSources(env: Map<String, String>, managedOverride: Config?): PlainbaseConfig {
            val dataDir = dataDirFrom(env)
            val file = parseIfRegularFile(dataDir.resolve("plainbase.conf"))
            val managed = managedOverride ?: parseIfRegularFile(dataDir.resolve(MANAGED_ROOTS_FILE))
            return build(env, file, managed)
        }

        /**
         * `.resolve()` so the ADR-0009 `${?…}` substitution the docs advertise actually resolves instead of
         * throwing ConfigException.NotResolved at the first typed getter (B3). ConfigResolveOptions.defaults()
         * resolves within-file refs then falls back to the JVM system ENV (not system properties); the optional
         * `${?…}` form drops silently when its var is unset (a bare `${…}` still throws by design). Shared by
         * both files: a file the loader parses must be parsed ONE way, not two.
         */
        private fun parseIfRegularFile(path: Path): Config =
            if (Files.isRegularFile(path)) {
                ConfigFactory.parseFile(path.toFile()).resolve(ConfigResolveOptions.defaults())
            } else {
                ConfigFactory.empty()
            }

        /**
         * The candidate `roots.conf` TEXT, through the same resolve as [parseIfRegularFile] - which is the
         * whole reason this lives here rather than in the caller. The CLI validates a STRING and then writes
         * that string to the file the next boot parses, so the two must go through ONE pipeline: an unresolved
         * candidate and a resolved file are two parsers, and a divergence between them is a config the CLI
         * certified and the server reads differently. Null is "there is no roots.conf" (the `remove`-the-last
         * delete), which parses to the same empty config an absent file does.
         */
        private fun parseCandidate(text: String?): Config =
            if (text == null) ConfigFactory.empty() else ConfigFactory.parseString(text).resolve(ConfigResolveOptions.defaults())

        /**
         * Layered construction (ADR-0009): read `DATA_DIR/plainbase.conf` (HOCON) and the machine-managed
         * `DATA_DIR/roots.conf` THEN overlay env - **env always wins**, the file only supplies values env
         * omits. A missing file on either side is a clean no-op (identical to [fromEnv]).
         *
         * **This is the ONLY code in the repository that parses `roots.conf`** (C5 D-C5-10, criterion 22).
         * `plainbase root list` and the mutating verbs read the managed roots off the [RootsConfig] snapshot
         * this produces, never from a second parse: `root add` replaces that file atomically, so two reads of
         * it are two OBSERVATIONS of a changing file, not one snapshot of it.
         */
        fun fromEnvAndFile(env: Map<String, String> = System.getenv()): PlainbaseConfig = fromSources(env, null)

        /**
         * The CLI's candidate-validation seam (C5 D-C5-17): the `roots.conf` that is ABOUT to be written,
         * supplied as the TEXT it will hold (null = the file will not exist) instead of read from DATA_DIR.
         * Everything else - the parse, the resolve, the operator's `plainbase.conf`, env, the whole [build]
         * chain and EVERY refusal it raises - is identical to [fromEnvAndFile], which is the entire point.
         * TEXT rather than a parsed `Config`, because a caller that parses is a caller that chooses resolve
         * options, and the CLI choosing them is how the validated artifact and the consumed one drift apart.
         *
         * The tempting shortcut, `config.copy(roots = candidate)`, is UNSOUND and must never be used: the
         * refusals that matter are raised INSIDE [build], from cross-field validation a field-wise copy skips.
         * [buildDirectCommitGlobsByRoot] refuses when a glob key names no configured root, and its verdict is
         * BAKED INTO the constructed config - so a copy-based candidate would sail `root remove x` past an
         * `auth.agentDirectCommit.roots.x` that still exists and write a config that bricks the next boot.
         */
        fun fromEnvAndCandidateRoots(
            managedRootsText: String?,
            env: Map<String, String> = System.getenv(),
        ): PlainbaseConfig = fromSources(env, parseCandidate(managedRootsText))

        /**
         * Resolves config for a `serve`/CLI entry point, funneling a bad config into a clean `<command>:`
         * stderr line + null (the caller exits 1) instead of a raw stack trace. TWO failure classes are
         * caught: an [IllegalArgumentException] from the Q9/auth validation, AND a HOCON [ConfigException]
         * (malformed `plainbase.conf`, an unresolved `${...}`, a wrong-typed file value). [resolve] and [err]
         * are injectable so a test drives a bad config without touching real env/stderr - resolving OUTSIDE
         * any DI graph keeps the thrown error unwrapped (a Koin `single {}` would wrap it and dodge the catch).
         */
        fun loadForCommand(
            command: String,
            err: (String) -> Unit = System.err::println,
            resolve: () -> PlainbaseConfig = { fromEnvAndFile() },
        ): PlainbaseConfig? =
            try {
                resolve()
            } catch (e: IllegalArgumentException) {
                err("$command: ${e.message}")
                null
            } catch (e: ConfigException) {
                err("$command: ${e.message}")
                null
            }

        /**
         * The single env-wins fallback chain shared by [fromEnv] and [fromEnvAndFile]: each field reads
         * `env[KEY] ?: file."path" ?: default`, so the env-always-wins invariant lives in ONE place. Typed
         * getters only (no `unwrapped()` reflection, no serialized data class) - that is what keeps it
         * native-safe.
         */
        private fun build(env: Map<String, String>, file: Config, managed: Config = ConfigFactory.empty()): PlainbaseConfig {
            // The one place the env/file/default arms are still distinguishable (Q10 source tracking):
            // capture the source BEFORE the chain collapses into a normalized Path.
            val contentDirEnv = env["CONTENT_DIR"]
            val contentDirFile = file.stringOrNull("contentDir")
            // Parsed once and shared: the SAME insecure-http override the bind guard uses (auth.insecureHttp)
            // also relaxes the object-endpoint https gate, so operators never learn a second knob.
            val insecureHttp = env.boolStrict("PLAINBASE_INSECURE_HTTP") ?: file.boolStrict("auth.insecureHttp") ?: false
            // Hoisted because buildRoots needs both; the constructor's roots default cannot read the
            // HOCON file, so an explicit `roots {}` block is only ever parsed by passing it here.
            val contentDir = Path.of(contentDirEnv ?: contentDirFile ?: "./content").toAbsolutePath().normalize()
            val storage = buildStorage(env, file, insecureHttp)
            // `roots` and `auth` are SIBLING named arguments of the one constructor call below, so `auth` cannot see
            // the parsed roots from in there - and the per-root glob block has to validate its keys against them.
            // Hoist.
            val roots = buildRoots(file, managed, contentDir, storage)
            requireCoherentMainHistory(roots, env.boolStrict("PLAINBASE_GIT_ENABLED") ?: file.boolStrict("git.enabled"))
            return PlainbaseConfig(
                contentDir = contentDir,
                contentDirSource = when {
                    contentDirEnv != null -> ConfigSource.ENV
                    contentDirFile != null -> ConfigSource.FILE
                    else -> ConfigSource.DEFAULT
                },
                storage = storage,
                roots = roots,
                dataDir = dataDirFrom(env),
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
                    insecureHttp = insecureHttp,
                    agentDirectCommitGlobs = requireParseableGlobs(mainDirectCommitGlobs(env, file)),
                    agentDirectCommitGlobsByRoot = buildDirectCommitGlobsByRoot(file, roots),
                    // A secret SHOULD come from env (the "secrets stay in env" rule), but the file path is allowed for
                    // completeness; the deploy docs steer operators to env.
                    proxySecret = env["PLAINBASE_PROXY_SECRET"] ?: file.stringOrNull("auth.proxySecret"),
                    proxyIdentityHeader = (env["PLAINBASE_PROXY_IDENTITY_HEADER"] ?: file.stringOrNull("auth.proxyIdentityHeader"))
                        ?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_PROXY_IDENTITY_HEADER,
                    // P3 MCP DNS-rebinding allowlists. Empty here → the fail-closed bind-host default (see mcpHostAllowlist);
                    // reuse the SAME comma-or-list parser the trustedProxyCidrs path uses (never hand-roll a second one).
                    mcpAllowedHosts = env["PLAINBASE_MCP_ALLOWED_HOSTS"]?.toCommaList()
                        ?: file.stringListOrNull("auth.mcpAllowedHosts") ?: emptyList(),
                    mcpAllowedOrigins = env["PLAINBASE_MCP_ALLOWED_ORIGINS"]?.toCommaList()
                        ?: file.stringListOrNull("auth.mcpAllowedOrigins") ?: emptyList(),
                ),
            )
        }

        /**
         * The Q9 storage matrix, strict env-wins like every other field. Object mode fail-fasts its
         * required keys with the tabled operator-actionable messages; local mode only TRACKS which
         * `storage.object.*` keys are present ([StorageConfig.ignoredObjectKeys], for the one
         * ignored+warn startup warning) and validates nothing - never fatal, so a shared plainbase.conf
         * across deploys stays legal. Credentials are ENV-ONLY (secrets stay in env, never the file).
         */
        private fun buildStorage(env: Map<String, String>, file: Config, insecureHttp: Boolean): StorageConfig {
            val backend = StorageBackend.parse(env["PLAINBASE_STORAGE_BACKEND"] ?: file.stringOrNull("storage.backend"))
            if (backend == StorageBackend.LOCAL) {
                val ignored = OBJECT_STORAGE_KEYS.mapNotNull { (envKey, filePath) ->
                    if (env[envKey] != null) envKey else filePath.takeIf { file.hasPath(it) }
                }
                return StorageConfig(backend = backend, ignoredObjectKeys = ignored)
            }
            val endpoint = env["PLAINBASE_S3_ENDPOINT"] ?: file.stringOrNull("storage.object.endpoint")
                ?: throw IllegalArgumentException(
                    "storage.object.endpoint is required when storage.backend=object (the R2/S3 endpoint URL)",
                )
            require(isAbsoluteHttpUrl(endpoint)) { "storage.object.endpoint is not an absolute http(s) URL: '$endpoint'" }
            // Cleartext http would put SigV4 credentials on the wire in the clear. Refuse http:// unless the
            // SAME explicit insecure override the bind guard honors is set (a loopback test proxy, say) - never
            // a silent downgrade on a typo.
            require(insecureHttp || isHttpsUrl(endpoint)) {
                "storage.object.endpoint must be https to protect S3 credentials in transit: '$endpoint' " +
                    "(set PLAINBASE_INSECURE_HTTP=1 to knowingly send credentials over plaintext)"
            }
            val bucket = (env["PLAINBASE_S3_BUCKET"] ?: file.stringOrNull("storage.object.bucket"))?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("storage.object.bucket is required when storage.backend=object")
            val accessKeyId = env["PLAINBASE_S3_ACCESS_KEY_ID"]?.takeIf { it.isNotBlank() }
            val secretAccessKey = env["PLAINBASE_S3_SECRET_ACCESS_KEY"]?.takeIf { it.isNotBlank() }
            if (accessKeyId == null || secretAccessKey == null) throw IllegalArgumentException(MISSING_S3_CREDENTIALS_MESSAGE)
            val prefix = env["PLAINBASE_S3_PREFIX"] ?: file.stringOrNull("storage.object.prefix") ?: ""
            if (prefix.isNotEmpty()) requireTreePathPrefix(prefix)
            return StorageConfig(
                backend = backend,
                endpoint = endpoint,
                bucket = bucket,
                region = env["PLAINBASE_S3_REGION"] ?: file.stringOrNull("storage.object.region") ?: DEFAULT_S3_REGION,
                prefix = prefix,
                pathStyle = env.boolStrict("PLAINBASE_S3_PATH_STYLE") ?: file.boolStrict("storage.object.pathStyle") ?: true,
                pollSeconds = env.positiveLongStrict("PLAINBASE_S3_POLL_SECONDS")
                    ?: file.longOrNull("storage.object.pollSeconds")?.takeIf { it > 0 } ?: DEFAULT_S3_POLL_SECONDS,
                accessKeyId = accessKeyId,
                secretAccessKey = secretAccessKey,
            )
        }

        /**
         * The multi-root roots parse (ADR-0011), the [buildStorage] sibling, over the TWO sources C5 merges:
         * the operator's `roots {}` block in `plainbase.conf` ([file]) and the machine-managed `roots.conf`
         * ([managed]). Neither present synthesizes the back-compat main ([RootsConfig.synthesized] - today's
         * defaults, byte-identical). Roots stay FILE-ONLY (no env grammar).
         *
         * **BLOCK PRESENCE IS THE DISPATCH KEY FOR THE OPERATOR FILE, NEVER LIST EMPTINESS.** An operator who
         * writes an explicitly EMPTY `roots {}` block must keep hitting the required-main refusal (and, with
         * `storage.backend=object`, the object refusal) exactly as they do today - and an `isEmpty()` dispatch
         * cannot tell an ABSENT block from an EMPTY one, so it would route the empty block into the synthesize
         * arm and SILENTLY REVERT the install to legacy CONTENT_DIR mode, dropping both refusals.
         *
         * The MANAGED file needs no such care, and the asymmetry is the point: no refusal hangs off ITS
         * presence (its one rule, "must not declare main", is vacuous when empty), so for `roots.conf`
         * emptiness IS absence - a leftover empty machine file returns the install to SYNTHESIZED rather than
         * forcing it into the strict EXPLICIT matrix for no reason.
         *
         * Entry order is origin-line-with-name-tiebreak (D7), applied PER FILE ([parseRootBlock]). `ConfigObject`
         * is map-backed and does NOT preserve insertion order, but every value carries its origin line.
         */
        private fun buildRoots(file: Config, managed: Config, contentDir: Path, storage: StorageConfig): RootsConfig {
            val declaredPresent = file.hasPath("roots")
            val declared = parseRootBlock(file)
            val managedRoots = parseRootBlock(managed)
            if (!declaredPresent && managedRoots.isEmpty()) return RootsConfig.synthesized(contentDir, storage)
            require(storage.backend != StorageBackend.OBJECT) {
                "roots {} cannot be combined with storage.backend=object in this release: the bucket is main's content " +
                    "authority and a roots block cannot describe it - remove the roots block to keep the object deployment"
            }
            // Redundant with RootsConfig.of's own check, deliberately: this one is OPERATOR-facing and names the
            // config key. `of`'s is the type-level backstop for programmatic construction. NARROWED to a PRESENT
            // operator block - only the machine file is allowed to omit main, and D-C5-3's synthesis is what
            // makes a roots.conf-only topology legal.
            if (declaredPresent) {
                require(declared.any { it.name == RootName.MAIN }) {
                    "roots {} must declare a root named '${RootName.MAIN}' (the required, reserved primary): roots.main { path = ... }"
                }
            }
            require(managedRoots.none { it.name == RootName.MAIN }) {
                "$MANAGED_ROOTS_FILE must not declare 'main': main's directory comes from CONTENT_DIR, or from a roots {} " +
                    "block you wrote yourself in plainbase.conf. `plainbase root` never manages main."
            }
            // The house idiom, copied from mainDirectCommitGlobs: two spellings of ONE thing refuse the boot rather
            // than guess a winner. Merging two declarations field-wise could silently take `editable` from one file
            // and `path` from the other; picking a winner drops a declaration the operator wrote.
            val overlap = declared.map { it.name }.intersect(managedRoots.map { it.name }.toSet())
            require(overlap.isEmpty()) {
                "root(s) ${overlap.joinToString(", ") { it.value }} are declared BOTH in plainbase.conf and in " +
                    "$MANAGED_ROOTS_FILE. Declare each root ONCE - Plainbase will not guess which declaration you meant, and " +
                    "merging them field-wise could silently take `editable` from one file and `path` from the other. Remove " +
                    "the duplicate from plainbase.conf, or run `plainbase root remove <name>`."
            }
            // D-C5-4: FILE ORDER, each block whole and in its own D7 order. main sits WHERE IT WAS DECLARED and is
            // NEVER hoisted. Rank decides the cross-root duplicate-id winner (lowest index wins -
            // PageIdentityService), i.e. WHICH ROOT'S PAGE KEEPS A PERMALINK - so `listOf(main) + extras` would force
            // main to rank 0 and silently reassign every shared id to main's page, with no error and no log line. An
            // operator who deliberately declared `roots { zeta {…} main {…} }` would have had zeta demoted by a CLI
            // change that never touched zeta. When no block was written, the synthesized main is the SOLE file-1
            // entry and so ranks first by arithmetic, not by policy - which is byte-identical legacy behavior.
            val fromDeclaredFile = if (declaredPresent) declared else listOf(RootsConfig.synthesized(contentDir, storage).main)
            return RootsConfig.of(
                list = fromDeclaredFile + managedRoots,
                origin = RootsOrigin.EXPLICIT,
                // Gates the CONTENT_DIR-is-ignored warning: under a roots.conf-only topology main is SYNTHESIZED
                // FROM contentDir, so that warning would be a lie (D-C5-3 consequence 2).
                mainDeclared = declaredPresent,
                // Provenance, captured at the ONE moment both files are in hand - which is the only moment they
                // ever are. A caller that re-reads roots.conf to answer "where did this root come from" is making a
                // SECOND observation of a file `root add` replaces atomically, and two observations are not one
                // snapshot (D-C5-10).
                managed = managedRoots.map { it.name }.toSet(),
            )
        }

        /**
         * The D7 origin-line-with-name-tiebreak parse of ONE file's `roots {}` block; empty when absent. Each
         * file is sorted INDEPENDENTLY, which is what makes the per-file line-number reset a non-issue: the two
         * files' line numbers are never compared with each other, so there is no collision for a tiebreak to
         * resolve. (ADR-0011 D7's aside sketches a cross-file `(line, name)` sort. It is REJECTED: it would let a
         * CLI-added root at `roots.conf` line 4 outrank a hand-declared incumbent at `plainbase.conf` line 8 and
         * take its permalinks - and it is not even stable, since `root add aardvark` shifts the line numbers of
         * roots the operator never touched.)
         *
         * Duplicate `roots.x {}` blocks WITHIN one file never reach here: HOCON merges duplicate keys FIELD-WISE,
         * so per-name uniqueness is structural. The TWO-file merge is the first way to produce a duplicate name,
         * and [buildRoots]'s overlap refusal is where that is caught.
         *
         * PRIVATE, and it stays private: [fromEnvAndFile] is the only thing in the repository that parses
         * `roots.conf`, so there is no second parser to keep in step (D-C5-10).
         */
        private fun parseRootBlock(file: Config): List<Root> =
            if (!file.hasPath("roots")) {
                emptyList()
            } else {
                file.getObject("roots").entries
                    .sortedWith(compareBy({ it.value.origin().lineNumber() }, { it.key }))
                    .map { (key, value) -> parseRoot(key, value) }
            }

        private fun parseRoot(key: String, value: ConfigValue): Root {
            val name = RootName.of(key) ?: throw IllegalArgumentException(
                "roots.$key is not a valid root name (a lowercase slug [a-z0-9][a-z0-9-]*, max 32 chars)",
            )
            val entry = (value as? ConfigObject)?.toConfig()
                ?: throw IllegalArgumentException("roots.$key must be a block: roots.$key { path = ... }")
            val backend = entry.stringOrNull("backend")?.trim() ?: "local"
            require(backend.equals("local", ignoreCase = true)) {
                "Unknown roots.$key.backend '$backend' - the only legal value in this release is local " +
                    "(object-backed roots are a recorded v1 scope cut)"
            }
            // Blank is rejected BEFORE Path.of: Path.of("") resolves to the process working directory,
            // so a typo'd empty path would silently serve/index the CWD.
            val raw = entry.stringOrNull("path")?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("roots.$key.path is required and must be a non-blank directory path")
            val isMain = name == RootName.MAIN
            val history = parseHistoryMode("roots.$key.history", entry.stringOrNull("history"))
                ?: if (isMain) HistoryMode.AUTO else HistoryMode.OFF
            // AUTO on an EXTRA is a boot error (ADR-0011 D4). Auto's semantics - detect a repo, maybe `git init` one,
            // accept a `.git`-as-a-file worktree - are precisely what D4 exists to deny an extra root, where a wrong
            // guess means Plainbase commits into a repository that exists for somebody else's reasons. An extra either
            // CLAIMS its repo explicitly (`native`, strictly guarded at boot) or stays `off`.
            require(isMain || history != HistoryMode.AUTO) {
                "roots.$key.history = auto is not allowed on an extra root: auto detects a repository and may create " +
                    "one, which Plainbase will not do in a tree it does not own. Use `native` to claim an existing " +
                    "repository at that path (Plainbase then refuses to start if it is a linked worktree, a submodule, " +
                    "or somebody else's checkout), or `off` for no history."
            }
            return Root(
                name = name,
                backend = RootBackend.Local(Path.of(raw).toAbsolutePath().normalize()),
                editable = entry.boolStrict("editable", "roots.$key.editable") ?: isMain,
                history = history,
            )
        }

        /**
         * MAIN's history has two knobs — `roots.main.history` and the `git.enabled` tri-state — and when both are set
         * explicitly they can CONTRADICT. Refuse, naming both keys, rather than pick a silent winner: whichever way
         * Plainbase guessed, half the operators who wrote that config would get the opposite of what they asked for,
         * and "history silently off" is not a failure anyone notices until they need the history.
         *
         * `history = auto` (the default, and what every synthesized config produces) is COMPATIBLE with either
         * `git.enabled` value — that is exactly what auto means, and the tri-state keeps its full current meaning
         * inside that arm. So this can only fire on a config that explicitly declares main's history non-auto.
         */
        private fun requireCoherentMainHistory(roots: RootsConfig, gitEnabled: Boolean?) {
            val main = roots.main
            require(!(main.history == HistoryMode.NATIVE && gitEnabled == false)) {
                "roots.main.history = native and git.enabled = false contradict each other: one claims main's git " +
                    "repository, the other turns git off. Set exactly one of them."
            }
            require(!(main.history == HistoryMode.OFF && gitEnabled == true)) {
                "roots.main.history = off and git.enabled = true contradict each other: one turns main's history off, " +
                    "the other forces it on. Set exactly one of them."
            }
        }

        /**
         * Parses a per-root `history` value case-insensitively (the [AuthMode.parse] idiom); absent
         * is null so the caller applies the per-root default (AUTO for main, OFF for extras).
         */
        private fun parseHistoryMode(key: String, raw: String?): HistoryMode? {
            val token = raw?.trim()
            if (token.isNullOrEmpty()) return null
            return HistoryMode.entries.firstOrNull { it.name.equals(token, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Unknown $key '$token' - legal values: ${HistoryMode.entries.joinToString(", ") { it.name.lowercase() }}",
                )
        }

        /**
         * Main's fatal filesystem fault, or null when main is usable. **ONE predicate for BOTH topology arms**,
         * and that is the whole reason it exists as a value rather than as two inline `when`s.
         *
         * The arms word this fault differently on purpose (`CONTENT_DIR ...` vs `roots.main.path ...`), and
         * `BootRefusal` is built to absorb exactly that - diff the KEY, print the MESSAGE. But a key is only
         * stable across the arm switch if both arms RAISE the fault on the same condition. They did not: the
         * legacy arm probed `isDirectory` alone, so a readable-but-not-searchable main was silently fine there
         * and `MAIN_UNUSABLE` in the explicit matrix. `plainbase root add` then read a fault the operator
         * already had as one IT had introduced, and refused an add it should permit - the exact hostage-taking
         * the structured diff exists to prevent.
         *
         * The order is the same short-circuit both arms want: one fault, one message. The traversability probe
         * is not a second opinion on a missing directory - it is the check that a main which EXISTS but lacks
         * the read bit (execute-only) or the execute bit (read-only, and a directory needs `x` to be traversed)
         * fails at the controlled `serve:` refusal rather than in the first scan.
         */
        private enum class MainFault { NOT_A_DIRECTORY, NOT_TRAVERSABLE }

        private fun mainFault(path: Path): MainFault? = when {
            !Files.isDirectory(path) -> MainFault.NOT_A_DIRECTORY
            !(Files.isReadable(path) && Files.isExecutable(path)) -> MainFault.NOT_TRAVERSABLE
            else -> null
        }

        /**
         * The ONE probe that decides "this extra root is unavailable", shared by the validation
         * fallback and the [rootsWarnings] D13 warning so neither can silently disagree with the
         * other: a usable root is a readable, searchable directory (the same read+execute bits the
         * main guard demands) whose path canonicalizes cleanly. Returns the toRealPath form for the
         * validation comparisons, or null when unavailable (the caller falls back to the declared
         * normalized form).
         *
         * **Textually PAIRED with `LocalContentStore`'s `rootIsTraversable`**, the RUNTIME liveness probe
         * (`ContentStore.available`): the same three predicates, deliberately. A root that boots as "available" and
         * a root the runtime keeps calling available must be the same set, or the config's promise and the server's
         * behavior fork. Change one, change the other.
         */
        private fun canonicalRootPathOrNull(path: Path): Path? =
            try {
                if (Files.isDirectory(path) && Files.isReadable(path) && Files.isExecutable(path)) path.toRealPath() else null
            } catch (_: IOException) {
                null
            }

        /**
         * Best-effort canonical form for a path [canonicalRootPathOrNull] could not resolve whole
         * (missing, unreadable, mid-probe failure): resolve the DEEPEST EXISTING ancestor with
         * toRealPath and append the remaining components. normalize() alone would leave an EXISTING
         * symlinked ancestor unresolved - a first-boot DATA_DIR declared through an alias into a
         * root would then dodge the nesting checks and be physically created inside the served
         * tree. Any I/O failure still falls back to the plain normalized form.
         */
        private fun bestEffortCanonical(path: Path): Path {
            val normalized = path.toAbsolutePath().normalize()
            var existing = normalized
            while (existing.parent != null && !Files.exists(existing)) existing = existing.parent
            return try {
                existing.toRealPath().resolve(existing.relativize(normalized))
            } catch (_: IOException) {
                normalized
            }
        }

        /**
         * True iff [value] parses as an absolute http/https URL with a host (the Q9 endpoint gate).
         * `internal` so the `s3-smoke` CLI reuses the SAME endpoint validation over its own env keys.
         */
        internal fun isAbsoluteHttpUrl(value: String): Boolean {
            val uri = try {
                URI(value)
            } catch (_: URISyntaxException) {
                return false
            }
            return (uri.scheme == "http" || uri.scheme == "https") && uri.host != null
        }

        /**
         * True iff [value] is an `https` URL (the cleartext-credentials gate; [isAbsoluteHttpUrl] already ran).
         * `internal` so the `s3-smoke` CLI applies the SAME https-only rule to its endpoint.
         */
        internal fun isHttpsUrl(value: String): Boolean =
            try {
                URI(value).scheme == "https"
            } catch (_: URISyntaxException) {
                false
            }

        /**
         * The Q9 prefix funnel: a non-empty `storage.object.prefix` must be a valid [TreePath] (relative,
         * no `.`/`..`/empty segments) so every bucket key stays inside the content key space. Fail-fast at
         * load, naming the key (the [requireParseableCidrs] idiom).
         */
        private fun requireTreePathPrefix(prefix: String) {
            requireNotNull(TreePath.of(prefix)) {
                "storage.object.prefix is not a valid key prefix: '$prefix' (a relative /-joined path, no . or .. segments)"
            }
        }

        /**
         * Fail-fast on a malformed `trustedProxyCidrs` entry (A1-amber): a present-but-unparseable CIDR (a bare
         * address with no `/prefix`, or an out-of-range prefix) is rejected at LOAD - not silently dropped (which
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
         * P5: fail-fast on a malformed `agentDirectCommit.globs` entry at LOAD (the [requireParseableCidrs] idiom) -
         * a blank/empty pattern, a `.`/`..` segment, or an empty segment. [CommitGlob.parse] throws naming the bad
         * pattern; after this, "an entry survived load" provably means "a parseable glob". The validated strings are
         * returned unchanged (the frozen `AuthConfig.agentDirectCommitGlobs: List<String>` keeps its shape); the parsed
         * form is exposed via [agentDirectCommitGlobs].
         */
        private fun requireParseableGlobs(globs: List<String>): List<String> {
            globs.forEach { CommitGlob.parse(it) }
            return globs
        }

        /**
         * MAIN's direct-commit glob list — env-wins over the file key, exactly as it always has.
         *
         * **Main's list has exactly ONE key, and it has three possible SPELLINGS** (ADR-0011 D6): the env var, the
         * file's `globs`, and now `roots.main` inside the per-root block. Two sources naming the same list is either a
         * silent UNION (a widening of what an agent may commit without review - precisely what this design exists to
         * prevent) or a silent WINNER (which drops an authorization the operator wrote). This is an authorization
         * surface, so "unspecified" is not an option: declaring `roots.main` alongside EITHER other spelling refuses
         * the boot, naming both keys. It costs no back-compat at all — `roots.main` is a key C4 invents, so this can
         * never fire on a config that is legal today. (`globs` + the env var stay the ORIGINAL one key with its
         * original env-wins rule, untouched.)
         */
        private fun mainDirectCommitGlobs(env: Map<String, String>, file: Config): List<String> {
            val fromEnv = env["PLAINBASE_AGENT_DIRECT_COMMIT_GLOBS"]?.toCommaList()
            val fromFile = file.stringListOrNull("auth.agentDirectCommit.globs")
            val fromBlock = file.stringListOrNull("auth.agentDirectCommit.roots.${RootName.MAIN}")
            if (fromBlock != null) {
                require(fromEnv == null) {
                    "auth.agentDirectCommit.roots.${RootName.MAIN} and PLAINBASE_AGENT_DIRECT_COMMIT_GLOBS both declare " +
                        "main's direct-commit globs. Declare main's list ONCE - Plainbase will not guess which of the two " +
                        "you meant, and neither unioning them (which would widen what an agent may commit unreviewed) nor " +
                        "picking a winner (which would drop the other) is safe on an authorization surface."
                }
                require(fromFile == null) {
                    "auth.agentDirectCommit.globs and auth.agentDirectCommit.roots.${RootName.MAIN} both declare main's " +
                        "direct-commit globs. Declare main's list ONCE (see the note on the roots block)."
                }
                return fromBlock
            }
            return fromEnv ?: fromFile ?: emptyList()
        }

        /**
         * The per-root direct-commit glob block: `auth.agentDirectCommit.roots.<name> = [...]`, keyed by root name
         * exactly like the top-level `roots {}` itself. Every key must be a legal slug AND name a REGISTERED root -
         * an unknown one refuses at boot rather than sitting there authorizing nothing (a glob nobody notices is dead
         * is a glob an operator believes is live). `main` is handled by [mainDirectCommitGlobs] and excluded here, so
         * its list has exactly one home.
         */
        private fun buildDirectCommitGlobsByRoot(file: Config, roots: RootsConfig): Map<RootName, List<String>> {
            if (!file.hasPath("auth.agentDirectCommit.roots")) return emptyMap()
            val registered = roots.list.map { it.name }.toSet()
            return file.getObject("auth.agentDirectCommit.roots").keys
                .mapNotNull { key ->
                    val name = RootName.of(key) ?: throw IllegalArgumentException(
                        "auth.agentDirectCommit.roots.$key is not a valid root name " +
                            "(a lowercase slug [a-z0-9][a-z0-9-]*, max 32 chars)",
                    )
                    require(name in registered) {
                        "auth.agentDirectCommit.roots.$key names no configured root (declared roots: " +
                            "${registered.joinToString(", ") { it.value }}). A direct-commit glob for a root that does not " +
                            "exist authorizes nothing - fix the name, or remove the entry."
                    }
                    if (name == RootName.MAIN) return@mapNotNull null // owned by mainDirectCommitGlobs
                    name to requireParseableGlobs(file.stringListOrNull("auth.agentDirectCommit.roots.$key").orEmpty())
                }
                .toMap()
        }

        /**
         * Strict env-wins numeric read: if [key] is ABSENT returns null (fall through to file/default); if it is
         * PRESENT it MUST parse, else fail-fast (env-wins means a present env value is authoritative - silently
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
         * (`1`/`0`, `true`/`false`, case-insensitive) - the bind-guard remedy tells operators
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
 * [label] lets a sub-`Config` read (a `roots {}` entry) fail naming the FULL offending key, not its relative path.
 */
private fun Config.boolStrict(path: String, label: String = path): Boolean? {
    val raw = stringOrNull(path) ?: return null
    return when (raw.trim().lowercase()) {
        "1", "true" -> true
        "0", "false" -> false
        else -> throw IllegalArgumentException("$label must be one of 1/0/true/false, got '$raw'")
    }
}

/** Where a collapsed env-wins config value came from (Q10 source tracking): env beats file beats default. */
enum class ConfigSource {
    ENV,
    FILE,
    DEFAULT,
}

/**
 * Which backend holds the authoritative content bytes (Q9). Restart-only (§0.9).
 * - [LOCAL] - the CONTENT_DIR directory IS the authority (the default; exactly today's behavior).
 * - [OBJECT] - an S3-compatible bucket is the authority; CONTENT_DIR is ignored (Q10) and the local
 *   mirror is DATA_DIR-owned derived state served by the `ObjectContentStore` hybrid (C4).
 */
enum class StorageBackend {
    LOCAL,
    OBJECT,
    ;

    companion object {
        /**
         * Parses [raw] (env or HOCON) case-insensitively (the [AuthMode.parse] idiom). A blank/absent
         * value defaults to [LOCAL]; a NON-blank unknown value fails fast naming the legal values - a
         * typo'd backend must never silently serve the wrong authority.
         */
        fun parse(raw: String?): StorageBackend {
            val token = raw?.trim()
            if (token.isNullOrEmpty()) return LOCAL
            return entries.firstOrNull { it.name.equals(token, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Unknown storage.backend '$token' - legal values: ${entries.joinToString(", ") { it.name.lowercase() }}",
                )
        }
    }
}

/**
 * Storage-backend config (Q9), all restart-only (§0.9). Object-mode required keys are validated
 * fail-fast at load with operator-actionable messages; in local mode every `storage.object.*` key is
 * ignored, tracked in [ignoredObjectKeys] for the one startup warning (never fatal).
 *
 * [accessKeyId]/[secretAccessKey] come ONLY from env (`PLAINBASE_S3_ACCESS_KEY_ID` /
 * `PLAINBASE_S3_SECRET_ACCESS_KEY` - secrets stay in env, never plainbase.conf) and are never logged.
 */
data class StorageConfig(
    val backend: StorageBackend = StorageBackend.LOCAL,
    /** Object mode: the R2/S3 endpoint URL. REQUIRED; must be an absolute http(s) URL. */
    val endpoint: String? = null,
    /** Object mode: the bucket name. REQUIRED, non-blank. */
    val bucket: String? = null,
    /** Object mode: the signing region; default `auto` (R2, the primary provider). */
    val region: String = PlainbaseConfig.DEFAULT_S3_REGION,
    /** Object mode: the key prefix all content lives under; default none. Non-empty values pass the [TreePath] funnel. */
    val prefix: String = "",
    /** Object mode: path-style addressing; default true (R2 account-endpoint addressing). */
    val pathStyle: Boolean = true,
    /** Object mode: the watch/reconcile poll interval in seconds; default 60. */
    val pollSeconds: Long = PlainbaseConfig.DEFAULT_S3_POLL_SECONDS,
    val accessKeyId: String? = null,
    val secretAccessKey: String? = null,
    /** The `storage.object.*` keys present while backend=local - named by the ignored+warn startup warning. */
    val ignoredObjectKeys: List<String> = emptyList(),
)

/**
 * Where the root topology came from (ADR-0011 D9): [SYNTHESIZED] configs keep today's exact startup
 * guard (the back-compat grandfathering, like HistoryMode.AUTO's); [EXPLICIT] blocks carry no
 * back-compat obligation and get the full strict validation matrix.
 */
enum class RootsOrigin {
    SYNTHESIZED,
    EXPLICIT,
}

/**
 * The root topology (multi-root C1): every configured root in origin-line-with-name-tiebreak order
 * (ADR-0011 D7 - the order `RootRegistry` preserves and C2's duplicate-id winner inherits).
 *
 * [of] takes a DEFENSIVE COPY, the same discipline `RootRegistry.of` has always had: [list], [main] and
 * [extras] are three views of ONE snapshot and can never disagree, whatever the caller does afterwards with
 * the list it handed in.
 */
@ConsistentCopyVisibility
data class RootsConfig private constructor(
    val list: List<Root>,
    val origin: RootsOrigin,
    /**
     * Did main come from a hand-written `roots {}` block? False when it was SYNTHESIZED from CONTENT_DIR -
     * which a `roots.conf`-only topology is, despite being EXPLICIT (C5 D-C5-3). The distinction is not
     * cosmetic: it is what stops [PlainbaseConfig.rootsWarnings] telling a docker operator their CONTENT_DIR
     * is ignored while it is still the thing main's path comes from.
     */
    val mainDeclared: Boolean,
    /**
     * The roots that came from `DATA_DIR/roots.conf` (C5 D-C5-10). Captured HERE, where both files are in
     * hand, because it is the only place they ever are: a caller that re-reads `roots.conf` to answer "where
     * did this root come from" is making a second observation of a file `root add` replaces atomically, and
     * two atomic reads of a mutating file are not one atomic read. `plainbase root list` reads provenance off
     * THIS snapshot, and `add`/`remove` take the managed subset off it too.
     */
    val managed: Set<RootName>,
) {

    /**
     * The reserved primary root; a construction-time guarantee from [of]. NOT necessarily `list.first()`:
     * D7 order is preserved verbatim (`RootRegistry.rank` inherits it, and rank decides the cross-root
     * duplicate-id winner). A typed ACCESSOR, never a promotion.
     */
    val main: Root = list.first { it.name == RootName.MAIN }

    /** Every root except [main], in D7 order. A partition of [list], NOT a reordering. */
    val extras: List<Root> = list.filter { it.name != RootName.MAIN }

    companion object {

        /** Snapshots [list] and validates it once, so nothing downstream has to search for main. */
        fun of(
            list: List<Root>,
            origin: RootsOrigin,
            mainDeclared: Boolean = origin == RootsOrigin.EXPLICIT,
            managed: Set<RootName> = emptySet(),
        ): RootsConfig {
            val snapshot = list.toList()
            require(snapshot.any { it.name == RootName.MAIN }) {
                "no 'main' root in the roots list (parse and synthesis both guarantee one; a directly-constructed " +
                    "RootsConfig must include it)"
            }
            // The type-level backstop, mirroring RootRegistry.of. Within ONE file HOCON merges duplicate keys
            // field-wise, so a duplicate name was structurally impossible - the C5 TWO-file merge is the first way
            // to produce one, and buildRoots' operator-facing overlap refusal is the message that should fire first.
            val duplicates = snapshot.groupBy { it.name }.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) { "duplicate root name(s): ${duplicates.joinToString(", ") { it.value }}" }
            return RootsConfig(snapshot, origin, mainDeclared, managed)
        }

        /**
         * The back-compat rule (ADR-0011): a config with no `roots {}` block IS a single-root
         * deployment, so it synthesizes `main` from today's fields with today's defaults -
         * editable, history AUTO, and [contentDir] (or the bucket descriptor in object mode,
         * shape-only in v1). Byte-identical to pre-multi-root behavior by construction.
         */
        fun synthesized(contentDir: Path, storage: StorageConfig): RootsConfig {
            val backend = when (storage.backend) {
                StorageBackend.LOCAL -> RootBackend.Local(contentDir)
                StorageBackend.OBJECT -> RootBackend.Object(storage.bucket.orEmpty(), storage.prefix)
            }
            return of(
                list = listOf(Root(name = RootName.MAIN, backend = backend, editable = true, history = HistoryMode.AUTO)),
                origin = RootsOrigin.SYNTHESIZED,
            )
        }
    }
}

/**
 * Git-history config (ADR-0006). [enabled] is a tri-state: `null` auto-detects a repo in main's
 * content root ([PlainbaseConfig.mainContentRoot] - contentDir for every legacy config; the
 * detection lives in `historyModule`, not here); `true`/`false` override either direction.
 * [authorName]/[authorEmail] are the commit identity (Phase 3 default `Plainbase <plainbase@localhost>`;
 * the author/committer split is plumbed for Phase 4). There is no amend/squash knob - one commit per save, always (fix D).
 */
data class GitConfig(
    val enabled: Boolean? = null,
    val authorName: String = PlainbaseConfig.DEFAULT_GIT_AUTHOR_NAME,
    val authorEmail: String = PlainbaseConfig.DEFAULT_GIT_AUTHOR_EMAIL,
)

/**
 * How requests authenticate (ADR-0008). Restart-only (§0.9). A1 ships the enum + the bind guard's use of it;
 * A3/A4 add the live extraction/enforcement.
 * - [OFF] - no human auth (loopback dev); the MOST dangerous mode, so a non-loopback bind is still
 *   subject to the fail-closed bind guard (refused without proxy/TLS config or `PLAINBASE_INSECURE_HTTP`).
 * - [BUILTIN] - built-in password login (A4a).
 * - [PROXY] - a trusted reverse-proxy asserts identity via a header (A4b).
 */
enum class AuthMode {
    OFF,
    BUILTIN,
    PROXY,
    ;

    companion object {
        /**
         * Parses [raw] (env or HOCON) case-insensitively. A blank/absent value defaults to [OFF]; a NON-blank
         * unknown value fails fast naming the legal values - a typo'd `auth.mode` must never silently disable
         * auth (risk #9).
         */
        fun parse(raw: String?): AuthMode {
            val token = raw?.trim()
            if (token.isNullOrEmpty()) return OFF
            return entries.firstOrNull { it.name.equals(token, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Unknown auth.mode '$token' - legal values: ${entries.joinToString(", ") { it.name.lowercase() }}",
                )
        }
    }
}

/**
 * Phase-4 auth substrate config (ADR-0008), all restart-only (§0.9).
 * - [mode] - the [AuthMode] above; default [AuthMode.OFF].
 * - [trustedProxyCidrs] - proxy source CIDRs whose `X-Forwarded-Proto: https` is trusted (secure-context,
 *   WI 5; A4b spoof check). Empty = no trusted proxy.
 * - [insecureHttp] - the explicit, knowing override that lets the bind guard serve credentials over plaintext.
 * - [agentDirectCommitGlobs] - LIVE as of P5 (§0.7): RestModule threads this into the route context and
 *   [com.plainbase.frameworks.ktor.GuardedMutatingFacade] consults it on every agent PUT. A COMMIT-mode agent
 *   writing INSIDE a glob direct-commits (200); OUTSIDE it degrades to a proposal (202). The default `[]` degrades
 *   EVERY agent write. Humans and the proposal-apply path are never glob-checked. Default `[]`. MAIN-SCOPED.
 * - [agentDirectCommitGlobsByRoot] - the per-root block (`auth.agentDirectCommit.roots.<name> = [...]`), the ONLY
 *   way to grant an EXTRA root. See its own doc: this is an authorization surface and the split is deliberate.
 */
data class AuthConfig(
    val mode: AuthMode = AuthMode.OFF,
    val trustedProxyCidrs: List<String> = emptyList(),
    val insecureHttp: Boolean = false,
    val agentDirectCommitGlobs: List<String> = emptyList(),
    /**
     * Per-root agent direct-commit globs, keyed by root name (ADR-0011 D6).
     *
     * **The upgrade invariant, and it is the whole reason this is a BLOCK rather than a `<root>:<pattern>` string
     * prefix: no config that authorizes X today may authorize anything different after the upgrade.** A colon is a
     * legal path-segment character AND a legal glob character, so an operator's existing `archive:2024/...` pattern
     * (over a folder literally named `archive:2024`) would be silently RETARGETED by an in-string grammar the day
     * they add a root named `archive` - revoked in main, and granted, unasked, inside the new root. No printable
     * separator is unambiguous against the path charset, so the root arrives structurally instead. The old `globs`
     * key means exactly what it has always meant - main - forever; this block is opt-in and cannot be entered by
     * accident. Every key must name a REGISTERED root (an unknown one refuses at boot).
     */
    val agentDirectCommitGlobsByRoot: Map<RootName, List<String>> = emptyMap(),
    /**
     * A4b PROXY mode: the shared secret the trusted proxy stamps as `X-Plainbase-Proxy-Secret`. REQUIRED in proxy
     * mode (the [bindGuardRefusal] enforces it) - it is the real trust anchor: a CIDR alone trusts a whole subnet,
     * so a sibling on a shared net could stamp the identity header. Stays in env, never logged.
     */
    val proxySecret: String? = null,
    /** A4b PROXY mode: the operator-configurable identity header name (default `X-Forwarded-User`). */
    val proxyIdentityHeader: String = PlainbaseConfig.DEFAULT_PROXY_IDENTITY_HEADER,
    /**
     * P3 MCP DNS-rebinding allowlists. An EMPTY list here means "use the fail-closed bind-host default"
     * ([PlainbaseConfig.mcpHostAllowlist]/[PlainbaseConfig.mcpOriginAllowlist]) - NEVER "allow none" and NEVER a
     * wildcard. An operator behind a reverse proxy adds their external host/origin explicitly (the trustedProxyCidrs
     * idiom). Parsed as a comma-or-list value exactly like trustedProxyCidrs.
     */
    val mcpAllowedHosts: List<String> = emptyList(),
    val mcpAllowedOrigins: List<String> = emptyList(),
)
