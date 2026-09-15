package com.plainbase.frameworks.config

import com.plainbase.domain.root.BootRefusal
import com.plainbase.domain.root.RootName
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Stateless CONFIG + FILESYSTEM inspection used by serving and topology commands. */
internal object ConfigBootInspector {
    /** Returns the serving root or throws the first topology refusal, preserving the legacy entry-point contract. */
    fun requireContentDir(config: PlainbaseConfig): Path {
        topologyRefusals(config).firstOrNull()?.let { throw IllegalArgumentException(it.message) }
        if (config.storage.backend == StorageBackend.OBJECT) return config.contentDir
        if (config.roots.origin == RootsOrigin.EXPLICIT) return requireNotNull(config.roots.primary.localPath)
        return config.contentDir
    }

    /**
     * Collects every independent refusal before consumers partition them; a first fault must not mask a new candidate
     * fault in the `plainbase root` baseline diff.
     */
    fun bootRefusals(config: PlainbaseConfig): List<BootRefusal> =
        topologyRefusals(config) +
            listOfNotNull(
                TransportSecurityPolicy.derive(config).bindRefusal?.let {
                    BootRefusal(BootRefusal.Kind.BIND_GUARD, emptySet(), it)
                },
            )

    /** Emits filesystem observations in their established order and delegates pure warning text to its owner. */
    fun rootsWarnings(config: PlainbaseConfig): List<String> = buildList {
        addAll(dataDirContainmentWarnings(config))
        managedRootsBackupWarning(config)?.let { add(it) }
        if (config.roots.origin != RootsOrigin.EXPLICIT) return@buildList
        ConfigValuePolicy.ignoredContentDirWarning(config)?.let { add(it) }
        config.roots.extras.forEach { extra ->
            val declared = requireNotNull(extra.localPath)
            if (canonicalRootPathOrNull(declared) == null) {
                add(
                    "roots.${extra.name.value}.path does not exist or is not a readable/searchable directory: $declared " +
                        "- the root will serve 503 for every request until the path is restored AND the server is " +
                        "restarted (its pages, aliases and checkpoints are left untouched in the meantime)",
                )
            }
        }
        addAll(ConfigValuePolicy.editableGlobWarnings(config))
    }

    /** Legacy and explicit wording names different paths, but shared predicates and structured kinds/keys keep faults aligned. */
    private fun topologyRefusals(config: PlainbaseConfig): List<BootRefusal> = when {
        config.storage.backend == StorageBackend.OBJECT -> objectKeyRefusals(config)
        config.roots.origin == RootsOrigin.EXPLICIT -> explicitRootRefusals(config)
        else -> legacyRefusals(config)
    }

    private fun objectKeyRefusals(config: PlainbaseConfig): List<BootRefusal> = buildList {
        fun refuse(message: String) = add(BootRefusal(BootRefusal.Kind.OBJECT_KEYS, emptySet(), message))
        if (config.roots.origin == RootsOrigin.EXPLICIT) {
            refuse("roots {} cannot be combined with storage.backend=object in this release (ADR-0011 D10)")
        }
        if (config.storage.endpoint == null) {
            refuse("storage.object.endpoint is required when storage.backend=object (the R2/S3 endpoint URL)")
        }
        if (config.storage.bucket == null) refuse("storage.object.bucket is required when storage.backend=object")
        if (config.storage.accessKeyId == null || config.storage.secretAccessKey == null) {
            refuse(MISSING_S3_CREDENTIALS_MESSAGE)
        }
    }

    private fun legacyRefusals(config: PlainbaseConfig): List<BootRefusal> = buildList {
        primaryFault(config.contentDir)?.let { fault ->
            val message = when (fault) {
                PrimaryFault.NOT_A_DIRECTORY ->
                    "CONTENT_DIR does not exist or is not a directory: ${config.contentDir}"
                PrimaryFault.NOT_TRAVERSABLE ->
                    "CONTENT_DIR is not readable/searchable: ${config.contentDir} " +
                        "(fix its permissions so the server can serve it)"
            }
            add(BootRefusal(BootRefusal.Kind.PRIMARY_UNUSABLE, setOf(RootName.PRIMARY), message))
        }
        val declared = config.contentDir.toAbsolutePath().normalize()
        dataDirFault(config, declared, comparableRootPath(declared))?.let { fault ->
            val message = when (fault) {
                DataDirFault.SAME_DIRECTORY ->
                    "DATA_DIR and CONTENT_DIR must be different directories (both are ${config.contentDir}): " +
                        "app-owned state (plainbase.db, search.db) inside the user-owned content root would " +
                        "re-trigger the watcher after every rebuild - a self-sustaining rebuild loop (§4 separation)"
                DataDirFault.ALIASED_NESTING ->
                    "DATA_DIR (${dataDirDeclared(config)}) is inside CONTENT_DIR on disk but not by its declared path " +
                        "($declared): declare CONTENT_DIR and DATA_DIR through consistent paths so the app-state " +
                        "exclusion can apply"
            }
            add(BootRefusal(BootRefusal.Kind.ROOT_VS_DATA_DIR, setOf(RootName.PRIMARY), message))
        }
    }

    /**
     * Order controls the first-only error and CLI diagnostics. Record primary canonicalization failure and continue
     * collecting independent faults so the candidate/baseline comparison remains complete.
     */
    private fun explicitRootRefusals(config: PlainbaseConfig): List<BootRefusal> = buildList {
        val primaryPath = requireNotNull(config.roots.primary.localPath)
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
        val canonical = config.roots.list.map { root ->
            val declared = requireNotNull(root.localPath)
            val comparable = if (root.name == RootName.PRIMARY) {
                try {
                    declared.toRealPath()
                } catch (e: IOException) {
                    if (fault == null) {
                        primaryUnusable("roots.docs.path cannot be resolved: $declared (${e.message})")
                    }
                    bestEffortCanonical(declared)
                }
            } else {
                canonicalRootPathOrNull(declared) ?: bestEffortCanonical(declared)
            }
            Triple(root.name, declared, comparable)
        }
        canonical.forEachIndexed { i, (aName, _, aPath) ->
            canonical.drop(i + 1).forEach { (bName, _, bPath) ->
                fun pair(message: String) =
                    add(BootRefusal(BootRefusal.Kind.ROOT_PAIR, setOf(aName, bName), message))
                when {
                    aPath == bPath -> pair(
                        "roots.${aName.value} and roots.${bName.value} resolve to the same directory: $aPath",
                    )
                    aPath.startsWith(bPath) -> pair(
                        "roots.${aName.value} ($aPath) is nested inside roots.${bName.value} ($bPath): " +
                            "roots must be disjoint directories",
                    )
                    bPath.startsWith(aPath) -> pair(
                        "roots.${bName.value} ($bPath) is nested inside roots.${aName.value} ($aPath): " +
                            "roots must be disjoint directories",
                    )
                }
            }
        }
        canonical.forEach { (name, declared, comparable) ->
            dataDirFault(config, declared, comparable)?.let { fault ->
                val message = when (fault) {
                    DataDirFault.SAME_DIRECTORY ->
                        "roots.${name.value} and DATA_DIR must be different directories (both are $comparable): " +
                            "app-owned state (plainbase.db, search.db) inside a docs root would re-trigger the " +
                            "watcher after every rebuild (§4 separation)"
                    DataDirFault.ALIASED_NESTING ->
                        "DATA_DIR (${dataDirDeclared(config)}) is inside roots.${name.value} on disk but not by its " +
                            "declared path ($declared): declare the root and DATA_DIR through consistent paths so the " +
                            "app-state exclusion can apply"
                }
                add(BootRefusal(BootRefusal.Kind.ROOT_VS_DATA_DIR, setOf(name), message))
            }
        }
    }

    private enum class PrimaryFault { NOT_A_DIRECTORY, NOT_TRAVERSABLE }

    private enum class DataDirFault { SAME_DIRECTORY, ALIASED_NESTING }

    private fun primaryFault(path: Path): PrimaryFault? = when {
        !Files.isDirectory(path) -> PrimaryFault.NOT_A_DIRECTORY
        !(Files.isReadable(path) && Files.isExecutable(path)) -> PrimaryFault.NOT_TRAVERSABLE
        else -> null
    }

    /** Equality and hidden canonical DATA_DIR-inside-root nesting are fatal; root-inside-DATA_DIR stays warning-only. */
    private fun dataDirFault(config: PlainbaseConfig, declared: Path, comparable: Path): DataDirFault? {
        val dataDirComparable = dataDirComparable(config)
        return when {
            comparable == dataDirComparable -> DataDirFault.SAME_DIRECTORY
            dataDirComparable.startsWith(comparable) && !dataDirDeclared(config).startsWith(declared) ->
                DataDirFault.ALIASED_NESTING
            else -> null
        }
    }

    private fun dataDirComparable(config: PlainbaseConfig): Path = try {
        config.dataDir.toRealPath()
    } catch (_: IOException) {
        bestEffortCanonical(config.dataDir)
    }

    private fun dataDirDeclared(config: PlainbaseConfig): Path = config.dataDir.toAbsolutePath().normalize()

    private fun comparableRootPath(declared: Path): Path = canonicalRootPathOrNull(declared) ?: bestEffortCanonical(declared)

    private fun canonicalRootPathOrNull(path: Path): Path? =
        try {
            if (Files.isDirectory(path) && Files.isReadable(path) && Files.isExecutable(path)) path.toRealPath() else null
        } catch (_: IOException) {
            null
        }

    /** Resolves the deepest existing ancestor so missing paths still participate in canonical comparisons. */
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

    /** A root inside DATA_DIR remains warning-only: single-volume installs serve, but disposable state may be wiped. */
    private fun dataDirContainmentWarnings(config: PlainbaseConfig): List<String> = buildList {
        if (config.storage.backend == StorageBackend.OBJECT) return@buildList
        val dataDirComparable = dataDirComparable(config)
        config.roots.list.forEach { root ->
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

    private fun managedRootsBackupWarning(config: PlainbaseConfig): String? {
        val backup = ManagedRootsFile.backupPath(config.managedRootsPath)
        if (!Files.isRegularFile(backup)) return null
        return "$backup is left over from an interrupted `plainbase root` promote. ${config.managedRootsPath} itself is intact and is " +
            "the topology being served; remove the backup once you have satisfied yourself that is the topology you want."
    }
}
