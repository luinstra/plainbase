package com.plainbase.frameworks.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** The already-resolved operator and managed inputs selected by one load observation. */
internal class ConfigSources(
    val operator: Config,
    val managed: Config,
)

/** Owns file selection, candidate parsing, and the narrow command error funnel. */
internal object ConfigLoader {
    fun fromEnv(env: Map<String, String> = System.getenv()): PlainbaseConfig =
        ConfigDecoder.decode(env, ConfigSources(ConfigFactory.empty(), ConfigFactory.empty()))

    internal fun dataDirFrom(
        env: Map<String, String> = System.getenv(),
        legacyProbeDir: Path = Path.of("").toAbsolutePath(),
    ): Path {
        val selected = ConfigValuePolicy.dataDirFrom(env)
        if ("DATA_DIR" !in env) inspectLegacy(legacyProbeDir.resolve("data"), selected)
        return selected
    }

    fun fromEnvAndFile(
        env: Map<String, String> = System.getenv(),
        legacyProbeDir: Path = Path.of("").toAbsolutePath(),
    ): PlainbaseConfig = fromSources(env, null, legacyProbeDir)

    fun fromEnvAndCandidateRoots(
        managedRootsText: String?,
        env: Map<String, String> = System.getenv(),
        legacyProbeDir: Path = Path.of("").toAbsolutePath(),
    ): PlainbaseConfig = fromSources(env, parseCandidate(managedRootsText), legacyProbeDir)

    fun loadForCommand(
        command: String,
        err: (String) -> Unit,
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

    private fun fromSources(env: Map<String, String>, managedOverride: Config?, legacyProbeDir: Path): PlainbaseConfig {
        val dataDir = dataDirFrom(env, legacyProbeDir)
        val operator = parseIfRegularFile(dataDir.resolve("plainbase.conf"))
        val managed = managedOverride ?: loadManagedRoots(dataDir.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE))
        return ConfigDecoder.decode(env, ConfigSources(operator, managed))
    }

    private val legacyMarkers = listOf(
        "plainbase.db", "plainbase.db-wal", "plainbase.db-shm", "plainbase.conf",
        "roots.conf", "roots.conf.bak", "custom.css", "themes",
    )

    /** Only definite absence is safe to ignore. Marker entries count without opening their contents. */
    private fun inspectLegacy(old: Path, selected: Path) {
        val remedy = "Legacy state at $old cannot be implicitly switched to $selected. " +
            "Set an explicit absolute DATA_DIR to continue the old instance, or follow the offline migration guide " +
            "at https://github.com/luinstra/plainbase/blob/main/docs/operating-plainbase.md#offline-migration-to-the-home-directory."
        try {
            val entry = attributesOrAbsent(old, LinkOption.NOFOLLOW_LINKS) ?: return
            val directory = if (entry.isSymbolicLink) {
                attributesOrAbsent(old) ?: throw IllegalArgumentException(remedy)
            } else {
                entry
            }
            if (!directory.isDirectory) return
            if (legacyMarkers.none { attributesOrAbsent(old.resolve(it), LinkOption.NOFOLLOW_LINKS) != null }) return
            // Never call isSameFile on a missing target: some providers can return true for identical spellings.
            val same = try {
                attributesOrAbsent(selected) != null && Files.isSameFile(old, selected)
            } catch (_: IOException) {
                false
            }
            require(same) { remedy }
        } catch (_: IOException) {
            throw IllegalArgumentException("Cannot inspect legacy directory/markers. $remedy")
        } catch (_: SecurityException) {
            // A filesystem provider may reject attribute/identity access independently of a SecurityManager.
            throw IllegalArgumentException("Cannot inspect legacy directory/markers. $remedy")
        }
    }

    private fun attributesOrAbsent(path: Path, vararg options: LinkOption): BasicFileAttributes? =
        try {
            Files.readAttributes(path, BasicFileAttributes::class.java, *options)
        } catch (_: NoSuchFileException) {
            null
        }

    private fun loadManagedRoots(path: Path): Config {
        val backup = ManagedRootsFile.backupPath(path)
        val hasBackup = Files.isRegularFile(backup)
        if (!Files.isRegularFile(path)) {
            if (hasBackup) throw IllegalArgumentException(damagedRootsMessage(path, backup, "it is MISSING"))
            return ConfigFactory.empty()
        }
        val parsed = runCatching {
            ConfigFactory.parseFile(path.toFile()).resolve(ConfigResolveOptions.defaults())
        }.getOrElse { failure ->
            when (failure) {
                is ConfigException -> throw IllegalArgumentException(
                    damagedRootsMessage(path, backup.takeIf { hasBackup }, "it does not parse: ${failure.message}"),
                    failure,
                )
                else -> throw failure
            }
        }
        if (!parsed.hasPath("roots")) {
            throw IllegalArgumentException(
                damagedRootsMessage(path, backup.takeIf { hasBackup }, "it carries no roots {} block (empty, or truncated)"),
            )
        }
        return parsed
    }

    private fun damagedRootsMessage(path: Path, backup: Path?, fault: String): String = buildString {
        append("$path is the machine-managed roots file and $fault. ")
        append("Refusing to start rather than serve a topology that may have lost roots: booting without them would ")
        append("404 every page they hold, which reads as deleted rather than as an outage. Remedies: ")
        append(if (backup != null) "restore the last-known-good with `mv $backup $path`" else "restore it from a backup")
        append("; or, to accept a CONTENT_DIR-only topology and re-add roots with `plainbase root add`, delete ")
        append(if (Files.exists(path)) "$path." else "$backup.")
    }

    private fun parseIfRegularFile(path: Path): Config =
        if (Files.isRegularFile(path)) {
            ConfigFactory.parseFile(path.toFile()).resolve(ConfigResolveOptions.defaults())
        } else {
            ConfigFactory.empty()
        }

    private fun parseCandidate(text: String?): Config =
        if (text == null) ConfigFactory.empty() else ConfigFactory.parseString(text).resolve(ConfigResolveOptions.defaults())
}
