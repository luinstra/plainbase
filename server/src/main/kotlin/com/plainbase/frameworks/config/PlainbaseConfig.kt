package com.plainbase.frameworks.config

import com.plainbase.BuildInfo
import com.plainbase.domain.root.BootRefusal
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.CommitGlob
import java.io.IOException
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
     * carries no back-compat obligation, so it gets the full strict matrix ([explicitRootRefusals])
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
        if (roots.origin == RootsOrigin.EXPLICIT) return requireNotNull(roots.primary.localPath)
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
     * **Different prose is safe; a different CONDITION is not.** Both arms probe the primary through the ONE
     * [primaryFault] predicate for the same reason: an arm that raised `PRIMARY_UNUSABLE` on a condition the other
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
     * The back-compat guards a SYNTHESIZED (legacy) config gets (ADR-0011 D9): the byte-identical mandate lives
     * here. Every one is keyed on the primary with the SAME kind AND the SAME condition the explicit matrix uses for
     * the same fault - the arms word a fault differently, they never key it differently, and they never raise it on
     * different evidence ([primaryFault] and [dataDirFault] are the shared predicates that make that true).
     */
    private fun legacyRefusals(): List<BootRefusal> = buildList {
        primaryFault(contentDir)?.let { fault ->
            val message = when (fault) {
                PrimaryFault.NOT_A_DIRECTORY -> "CONTENT_DIR does not exist or is not a directory: $contentDir"
                PrimaryFault.NOT_TRAVERSABLE ->
                    "CONTENT_DIR is not readable/searchable: $contentDir (fix its permissions so the server can serve it)"
            }
            add(BootRefusal(BootRefusal.Kind.PRIMARY_UNUSABLE, setOf(RootName.PRIMARY), message))
        }
        val declared = contentDir.toAbsolutePath().normalize()
        dataDirFault(declared, comparableRootPath(declared))?.let { fault ->
            val message = when (fault) {
                DataDirFault.SAME_DIRECTORY ->
                    "DATA_DIR and CONTENT_DIR must be different directories (both are $contentDir): app-owned state " +
                        "(plainbase.db, search.db) inside the user-owned content root would re-trigger the watcher " +
                        "after every rebuild - a self-sustaining rebuild loop (§4 separation)"
                DataDirFault.ALIASED_NESTING ->
                    "DATA_DIR (${dataDirDeclared()}) is inside CONTENT_DIR on disk but not by its declared path " +
                        "($declared): declare CONTENT_DIR and DATA_DIR through consistent paths so the app-state " +
                        "exclusion can apply"
            }
            add(BootRefusal(BootRefusal.Kind.ROOT_VS_DATA_DIR, setOf(RootName.PRIMARY), message))
        }
    }

    /**
     * The strict filesystem matrix for an explicit `roots {}` block (ADR-0011 D9): the primary must exist
     * and be readable;
     * paths canonicalize via toRealPath for the COMPARISONS only (D8 - served paths keep their
     * declared form); no duplicate roots, no nested roots, and DATA_DIR may neither equal nor
     * contain a root. DATA_DIR strictly inside a root stays legal - it feeds that root's watcher
     * exclusion in C4, as the primary's already does via ContentModule. An unavailable path (missing, not
     * a directory, or any I/O failure while canonicalizing) is fatal for the primary but keeps an EXTRA
     * participating in every comparison via its best-effort canonical form
     * ([bestEffortCanonical] - the deepest existing ancestor resolved, remainder appended; D13,
     * [rootsWarnings] names it).
     *
     * **Every failure, in matrix order, NEVER throwing** (C5 S1.4). The order is load-bearing twice:
     * [requireContentDir] throws the FIRST, so it is what an operator sees at boot and what
     * `RootsValidationTest` already pins; and a stable order makes the CLI's WARN output stable. Main's
     * canonicalization failure records `PRIMARY_UNUSABLE` and falls back to [bestEffortCanonical] rather than
     * throwing, so the pairwise and DATA_DIR checks below it still run and still report - which is exactly
     * the completeness the baseline diff needs. (The rethrow in [requireContentDir] drops the IOException
     * `cause` the old throw carried; nothing asserts on it, and a nullable cause on [BootRefusal] would be a
     * field one caller reads.)
     */
    private fun explicitRootRefusals(): List<BootRefusal> = buildList {
        val primaryPath = requireNotNull(roots.primary.localPath) // parse rejects non-local backends in an explicit block
        fun primaryUnusable(message: String) =
            add(BootRefusal(BootRefusal.Kind.PRIMARY_UNUSABLE, setOf(RootName.PRIMARY), message))
        val fault = primaryFault(primaryPath)
        when (fault) {
            PrimaryFault.NOT_A_DIRECTORY ->
                primaryUnusable("roots.docs.path does not exist or is not a directory: $primaryPath")
            PrimaryFault.NOT_TRAVERSABLE -> primaryUnusable(
                "roots.docs.path is not readable/searchable: $primaryPath (fix its permissions so the server can serve it)",
            )
            null -> Unit
        }
        val canonical = roots.list.map { root ->
            val declared = requireNotNull(root.localPath)
            val comparable = if (root.name == RootName.PRIMARY) {
                try {
                    declared.toRealPath()
                } catch (e: IOException) {
                    // Only worth reporting when the primary OTHERWISE looked fine (a race, an exotic filesystem): a
                    // primary that is simply not there is already named above, and saying it twice says nothing more.
                    if (fault == null) primaryUnusable("roots.docs.path cannot be resolved: $declared (${e.message})")
                    bestEffortCanonical(declared)
                }
            } else {
                // D13: an unavailable extra still participates via its best-effort canonical form.
                canonicalRootPathOrNull(declared) ?: bestEffortCanonical(declared)
            }
            Triple(root.name, declared, comparable)
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
        canonical.forEach { (name, declared, comparable) ->
            dataDirFault(declared, comparable)?.let { fault ->
                val message = when (fault) {
                    DataDirFault.SAME_DIRECTORY ->
                        "roots.${name.value} and DATA_DIR must be different directories (both are $comparable): app-owned " +
                            "state (plainbase.db, search.db) inside a docs root would re-trigger the watcher after every " +
                            "rebuild (§4 separation)"
                    DataDirFault.ALIASED_NESTING ->
                        "DATA_DIR (${dataDirDeclared()}) is inside roots.${name.value} on disk but not by its declared path " +
                            "($declared): declare the root and DATA_DIR through consistent paths so the app-state exclusion can apply"
                }
                add(BootRefusal(BootRefusal.Kind.ROOT_VS_DATA_DIR, setOf(name), message))
            }
        }
    }

    /**
     * A root's FATAL relationship to DATA_DIR - **ONE predicate for BOTH topology arms**, and it exists as a value
     * for the [primaryFault] reason, one predicate lower down: an arm that RAISES a fault the other cannot even test
     * puts a fault the operator ALREADY HAS on the candidate side of `plainbase root`'s baseline diff alone, and
     * the CLI then refuses an `add` that introduced nothing. **The primary is not special here**; it is a root like any
     * other, which is the same fact the rank contract turns on.
     *
     * The line between fatal and merely alarming is drawn at WHAT THE APP ITSELF WRITES:
     * - [SAME_DIRECTORY]: plainbase.db/search.db (and their -wal/-journal siblings, none of them dotfiles) land
     *   INSIDE the watched tree and no exclusion can save them - every checkpoint re-triggers the watcher, which
     *   is a self-sustaining rebuild loop.
     * - [ALIASED_NESTING]: DATA_DIR is physically inside the root but was DECLARED through an alias, so the store's
     *   LEXICAL DATA_DIR exclusion never matches and the app's own state gets indexed and served as content. (A
     *   nesting whose declared forms agree is fine, and stays legal - the exclusion applies.)
     *
     * **A root strictly INSIDE DATA_DIR is deliberately NOT fatal**, and its absence here is the load-bearing part.
     * Nothing the app writes lands under such a root - app state sits directly in DATA_DIR, a SIBLING of it - so
     * there is no loop and nothing is mis-served. The exposure is an operator one: DATA_DIR is app-owned scratch
     * space whose contents ADR-0004 declares disposable PIECEMEAL (`search.db`, `mirror`, `mirror-state` - never
     * the directory itself, which also holds the durable `plainbase.db`), and it is the directory an operator
     * wipes and recreates without thinking twice. Content living inside it is content parked in the one place
     * nobody treats as precious. That is a trap, not a config fault, so it is a loud WARN ([rootsWarnings])
     * instead - named for EVERY root, in BOTH arms, which is more than the legacy arm has ever said about it.
     *
     * Refusing it in the explicit arm ALONE is what used to happen, and it was not a stricter version of the same
     * rule - it was a different rule. It bricked `plainbase root add` on any legacy install whose CONTENT_DIR sat
     * inside DATA_DIR (a single-volume `DATA_DIR=/data CONTENT_DIR=/data/content` deploy, which the legacy arm has
     * always permitted and which boots fine): the baseline saw no fault, the explicit candidate saw one, so the CLI
     * read a layout the operator has been running for months as a fault IT had just introduced, and refused. The
     * install could never gain a root.
     */
    private enum class DataDirFault { SAME_DIRECTORY, ALIASED_NESTING }

    private fun dataDirFault(declared: Path, comparable: Path): DataDirFault? {
        val dataDirComparable = dataDirComparable()
        return when {
            comparable == dataDirComparable -> DataDirFault.SAME_DIRECTORY
            dataDirComparable.startsWith(comparable) && !dataDirDeclared().startsWith(declared) -> DataDirFault.ALIASED_NESTING
            else -> null
        }
    }

    /**
     * DATA_DIR's canonical form. First boot: DATA_DIR is only created later (DataDirLock.tryAcquire), so a missing
     * one gets the same best-effort fallback an unavailable root does - resolving EXISTING symlinked ancestors
     * matters here, or a DATA_DIR declared through an alias into a root would pass validation and then be
     * physically created inside the served tree.
     */
    private fun dataDirComparable(): Path = try {
        dataDir.toRealPath()
    } catch (_: IOException) {
        bestEffortCanonical(dataDir)
    }

    private fun dataDirDeclared(): Path = dataDir.toAbsolutePath().normalize()

    /** A root path's comparable (canonical) form, with the D13 declared-form fallback when it cannot be resolved. */
    private fun comparableRootPath(declared: Path): Path = canonicalRootPathOrNull(declared) ?: bestEffortCanonical(declared)

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
        // BOTH arms, main included - these two are the only warnings a LEGACY config can raise, and they are above
        // the EXPLICIT guard for exactly that reason.
        addAll(dataDirContainmentWarnings())
        managedRootsBackupWarning()?.let { add(it) }
        if (roots.origin != RootsOrigin.EXPLICIT) return@buildList
        // Gated on main having actually been DECLARED (C5 D-C5-3), not merely on EXPLICIT. A `roots.conf`-only
        // topology is EXPLICIT with main SYNTHESIZED from contentDir, so CONTENT_DIR is the very thing main's
        // path comes from - telling a docker/systemd operator it is ignored would be a LIE whose natural
        // remedy (delete the "ignored" env var) silently repoints main at ./content.
        if (roots.primaryDeclared && contentDirSource != ConfigSource.DEFAULT) {
            add(
                "roots {} is configured: the explicitly set CONTENT_DIR/contentDir (via ${contentDirSource.name.lowercase()}) " +
                    "is ignored - primary's path comes from roots.docs.path",
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
        // Walked from the ROOTS side, not from the by-root glob map: the primary's globs live in their own key
        // (`agentDirectCommit.globs`, the env var, or `roots.docs`, never in the by-root map, which excludes the
        // primary by construction), so a map-keyed walk would leave `roots.docs { editable = false }` - the likeliest
        // trap of the lot, since the primary is the root every glob was written for - the one case it could not see.
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

    /**
     * The demoted half of [DataDirFault]: a root living strictly INSIDE DATA_DIR. It breaks nothing (nothing the
     * app writes lands under it), so it is not a refusal - but DATA_DIR is app-owned scratch space, the directory
     * an operator wipes and recreates without thinking twice, and a root inside it is a corpus parked where
     * nothing is treated as precious. Every root, main included, in BOTH topology arms.
     */
    private fun dataDirContainmentWarnings(): List<String> = buildList {
        if (storage.backend == StorageBackend.OBJECT) return@buildList // the bucket is the authority; no local root to contain
        val dataDirComparable = dataDirComparable()
        roots.list.forEach { root ->
            val declared = root.localPath ?: return@forEach
            val comparable = comparableRootPath(declared)
            if (comparable != dataDirComparable && comparable.startsWith(dataDirComparable)) {
                add(
                    "roots.${root.name.value} ($comparable) is INSIDE DATA_DIR ($dataDirComparable). This serves correctly, " +
                        "but DATA_DIR is app-owned state whose contents are routinely wiped and rebuilt (`search.db` and the " +
                        "object mirror are explicitly disposable) - a wipe here takes this root's content with it. Move the " +
                        "root outside DATA_DIR.",
                )
            }
        }
    }

    /**
     * A `roots.conf.bak` beside a `roots.conf` that IS intact: the copy-replace fallback leaves one behind only when
     * a promote failed, so the restore worked and the topology is sound - but the operator has not been told a
     * `plainbase root` command died on them, and the leftover is the only evidence left that it did. (A backup beside
     * a MISSING or unparseable `roots.conf` is a different animal entirely and never reaches here: it refuses the
     * boot outright - see `loadManagedRoots`.)
     */
    private fun managedRootsBackupWarning(): String? {
        val backup = ManagedRootsFile.backupPath(managedRootsPath)
        if (!Files.isRegularFile(backup)) return null
        return "$backup is left over from an interrupted `plainbase root` promote. $managedRootsPath itself is intact and is " +
            "the topology being served; remove the backup once you have satisfied yourself that is the topology you want."
    }

    /** Every root carrying at least one direct-commit glob, across BOTH homes (main's own key + the per-root block). */
    private fun globbedRoots(): Set<RootName> =
        buildSet {
            if (auth.agentDirectCommitGlobs.isNotEmpty()) add(RootName.PRIMARY)
            auth.agentDirectCommitGlobsByRoot.forEach { (root, globs) -> if (globs.isNotEmpty()) add(root) }
        }

    /** Temporary compatibility delegates; [TransportSecurityPolicy] owns these derivations. */
    fun bindGuardRefusal(): String? = TransportSecurityPolicy.derive(this).bindRefusal

    fun isNonLoopbackBind(): Boolean = TransportSecurityPolicy.derive(this).nonLoopbackBind

    fun secureCookie(): Boolean = TransportSecurityPolicy.derive(this).secureCookie

    fun mcpHostAllowlist(): List<String> = TransportSecurityPolicy.derive(this).effectiveMcpHosts

    fun mcpOriginAllowlist(): List<String> = TransportSecurityPolicy.derive(this).effectiveMcpOrigins

    /** Compatibility delegate; [ConfigValuePolicy] owns the pure parsing projection. */
    fun agentDirectCommitGlobs(): List<CommitGlob> = ConfigValuePolicy.agentDirectCommitGlobs(this)

    /**
     * The primary content root on the local filesystem: `roots.docs.path` for a Local backend, and `contentDir`
     * otherwise. Object mode ignores it, but the mirror and CLI seams still need a defined Path. It is identical to
     * `contentDir` for every legacy synthesized config.
     */
    fun mainContentRoot(): Path = roots.primary.localPath ?: contentDir

    private enum class PrimaryFault { NOT_A_DIRECTORY, NOT_TRAVERSABLE }

    private fun primaryFault(path: Path): PrimaryFault? = when {
        !Files.isDirectory(path) -> PrimaryFault.NOT_A_DIRECTORY
        !(Files.isReadable(path) && Files.isExecutable(path)) -> PrimaryFault.NOT_TRAVERSABLE
        else -> null
    }

    /** Shared availability probe for topology validation and warnings. */
    private fun canonicalRootPathOrNull(path: Path): Path? =
        try {
            if (Files.isDirectory(path) && Files.isReadable(path) && Files.isExecutable(path)) path.toRealPath() else null
        } catch (_: IOException) {
            null
        }

    /** Resolves the deepest existing ancestor for comparisons when the full path is unavailable. */
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

        /** Q9 default signing region: `auto` (R2, the primary provider). */
        const val DEFAULT_S3_REGION: String = "auto"

        /** Q9 default watch/reconcile poll interval (seconds). */
        const val DEFAULT_S3_POLL_SECONDS: Long = 60

        /** Env-only compatibility delegate; [ConfigLoader] owns loading. */
        fun fromEnv(env: Map<String, String> = System.getenv()): PlainbaseConfig = ConfigLoader.fromEnv(env)

        /** Compatibility delegate; [ConfigValuePolicy] owns the single data-directory derivation. */
        internal fun dataDirFrom(env: Map<String, String> = System.getenv()): Path =
            ConfigValuePolicy.dataDirFrom(env)

        /** Layered-loading compatibility delegate; [ConfigLoader] owns the implementation. */
        fun fromEnvAndFile(env: Map<String, String> = System.getenv()): PlainbaseConfig = ConfigLoader.fromEnvAndFile(env)

        /** Candidate-loading compatibility delegate; [ConfigLoader] owns the implementation. */
        fun fromEnvAndCandidateRoots(
            managedRootsText: String?,
            env: Map<String, String> = System.getenv(),
        ): PlainbaseConfig = ConfigLoader.fromEnvAndCandidateRoots(managedRootsText, env)

        /** Command-loading compatibility delegate; [ConfigLoader] owns the error funnel. */
        fun loadForCommand(
            command: String,
            err: (String) -> Unit,
            resolve: () -> PlainbaseConfig = { fromEnvAndFile() },
        ): PlainbaseConfig? = ConfigLoader.loadForCommand(command, err, resolve)
    }
}
