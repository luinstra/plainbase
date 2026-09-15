package com.plainbase.frameworks.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import java.nio.file.Files
import java.nio.file.Path

/** The already-resolved operator and managed inputs selected by one load observation. */
internal class ConfigSources(
    val operator: Config,
    val managed: Config,
)

/** Owns file selection, candidate parsing, and the narrow command error funnel. */
internal object ConfigLoader {
    fun fromEnv(env: Map<String, String> = System.getenv()): PlainbaseConfig =
        ConfigDecoder.decode(env, ConfigSources(ConfigFactory.empty(), ConfigFactory.empty()))

    internal fun dataDirFrom(env: Map<String, String> = System.getenv()): Path = ConfigValuePolicy.dataDirFrom(env)

    fun fromEnvAndFile(env: Map<String, String> = System.getenv()): PlainbaseConfig = fromSources(env, null)

    fun fromEnvAndCandidateRoots(
        managedRootsText: String?,
        env: Map<String, String> = System.getenv(),
    ): PlainbaseConfig = fromSources(env, parseCandidate(managedRootsText))

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

    private fun fromSources(env: Map<String, String>, managedOverride: Config?): PlainbaseConfig {
        val dataDir = dataDirFrom(env)
        val operator = parseIfRegularFile(dataDir.resolve("plainbase.conf"))
        val managed = managedOverride ?: loadManagedRoots(dataDir.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE))
        return ConfigDecoder.decode(env, ConfigSources(operator, managed))
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
