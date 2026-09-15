package com.plainbase.frameworks.config

import com.plainbase.domain.root.BootRefusal
import com.plainbase.domain.root.RootName
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Baseline observations retained across real filesystem changes through the stateless boot inspector. */
@Tag("native")
class ConfigBootInspectorFreshnessTest {

    @Test
    fun `same loaded config observes extra availability changing without a reload`() {
        val base = Files.createTempDirectory("pb-boot-inspector-freshness")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val main = Files.createDirectory(base.resolve("main"))
            val stable = Files.createDirectories(data.resolve("stable"))
            val extra = base.resolve("extra")
            Files.writeString(
                data.resolve("plainbase.conf"),
                """
                roots {
                  docs   { path = "$main" }
                  stable { path = "$stable" }
                  extra  { path = "$extra" }
                }
                """.trimIndent(),
            )
            val config = ConfigLoader.fromEnvAndFile(mapOf("DATA_DIR" to data.toString()))
            val containment =
                "roots.stable (${stable.toRealPath()}) is INSIDE DATA_DIR (${data.toRealPath()}). This serves correctly, " +
                    "but DATA_DIR is app-owned state whose contents are routinely wiped and rebuilt " +
                    "(`search.db` and the object mirror are explicitly disposable) - a wipe here takes this root's " +
                    "content with it. Move the root outside DATA_DIR."
            val unavailable = unavailableWarning("extra", extra)
            val initialWarnings = listOf(containment, unavailable)

            assertFalse(Files.exists(extra))
            assertEquals(initialWarnings, ConfigBootInspector.rootsWarnings(config))
            assertEquals(emptyList<BootRefusal>(), ConfigBootInspector.bootRefusals(config))

            Files.createDirectory(extra)
            assertTrue(Files.isDirectory(extra))
            assertEquals(listOf(containment), ConfigBootInspector.rootsWarnings(config))
            assertEquals(emptyList<BootRefusal>(), ConfigBootInspector.bootRefusals(config))

            Files.delete(extra)
            assertFalse(Files.exists(extra))
            assertEquals(initialWarnings, ConfigBootInspector.rootsWarnings(config))
            assertEquals(emptyList<BootRefusal>(), ConfigBootInspector.bootRefusals(config))
        } finally {
            deleteTree(base)
        }
    }

    @Test
    fun `same loaded config observes a live roots backup appearing and disappearing without a reload`() {
        val base = Files.createTempDirectory("pb-boot-inspector-backup")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val main = Files.createDirectory(base.resolve("main"))
            val archive = Files.createDirectory(base.resolve("archive"))
            Files.writeString(
                data.resolve("plainbase.conf"),
                "roots { docs { path = \"$main\" } }",
            )
            val rootsFile = data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE)
            Files.writeString(rootsFile, "roots { archive { path = \"$archive\" } }")
            val backup = ManagedRootsFile.backupPath(rootsFile)
            val config = ConfigLoader.fromEnvAndFile(mapOf("DATA_DIR" to data.toString()))
            val warning =
                "$backup is left over from an interrupted `plainbase root` promote. $rootsFile itself is intact and is " +
                    "the topology being served; remove the backup once you have satisfied yourself that is the topology you want."

            assertEquals(emptyList<String>(), ConfigBootInspector.rootsWarnings(config))
            assertFalse(Files.exists(backup))
            Files.copy(rootsFile, backup, StandardCopyOption.REPLACE_EXISTING)
            assertTrue(Files.isRegularFile(backup))
            assertEquals(listOf(warning), ConfigBootInspector.rootsWarnings(config))
            Files.delete(backup)
            assertFalse(Files.exists(backup))
            assertEquals(emptyList<String>(), ConfigBootInspector.rootsWarnings(config))
        } finally {
            deleteTree(base)
        }
    }

    @Test
    fun `same loaded config re-evaluates canonical duplicate topology after symlink retargeting`() {
        val base = Files.createTempDirectory("pb-boot-inspector-canonical")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val primary = Files.createDirectory(base.resolve("primary"))
            val extraTarget = Files.createDirectory(base.resolve("extra-target"))
            val extraLink = Files.createSymbolicLink(base.resolve("extra-link"), extraTarget)
            Files.writeString(
                data.resolve("plainbase.conf"),
                """
                roots {
                  docs  { path = "$primary" }
                  extra { path = "$extraLink" }
                }
                """.trimIndent(),
            )
            val config = ConfigLoader.fromEnvAndFile(mapOf("DATA_DIR" to data.toString()))
            val original = ConfigBootInspector.bootRefusals(config)
            assertEquals(emptyList<BootRefusal>(), original)
            assertEquals(extraTarget, Files.readSymbolicLink(extraLink))
            assertEquals(extraTarget.toRealPath(), extraLink.toRealPath())

            Files.delete(extraLink)
            Files.createSymbolicLink(extraLink, primary)
            val duplicate = ConfigBootInspector.bootRefusals(config)
            val expectedPair = setOf(RootName.PRIMARY, RootName.require("extra"))
            assertEquals(listOf(BootRefusal.Kind.ROOT_PAIR to expectedPair), duplicate.map { it.key })
            assertEquals(
                listOf("roots.docs and roots.extra resolve to the same directory: ${primary.toRealPath()}"),
                duplicate.map { it.message },
            )
            assertEquals(primary, Files.readSymbolicLink(extraLink))
            assertEquals(primary.toRealPath(), extraLink.toRealPath())

            Files.delete(extraLink)
            Files.createSymbolicLink(extraLink, extraTarget)
            assertEquals(original, ConfigBootInspector.bootRefusals(config))
            assertEquals(extraTarget, Files.readSymbolicLink(extraLink))
            assertEquals(extraTarget.toRealPath(), extraLink.toRealPath())
        } finally {
            deleteTree(base)
        }
    }

    @Test
    fun `retained candidate and baseline configs do not share refusal or warning observations`() {
        val base = Files.createTempDirectory("pb-boot-inspector-isolation")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val main = Files.createDirectory(base.resolve("main"))
            val baselineMissing = base.resolve("baseline-missing")
            val candidateMissing = base.resolve("candidate-missing")
            Files.writeString(data.resolve("plainbase.conf"), "roots { docs { path = \"$main\" } }")
            val baselineRoots = "roots { baseline { path = \"$baselineMissing\" } }"
            Files.writeString(data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE), baselineRoots)
            val baselineEnv = mapOf("DATA_DIR" to data.toString(), "PLAINBASE_HOST" to "127.0.0.1")
            val candidateEnv = baselineEnv + ("PLAINBASE_HOST" to "0.0.0.0")
            val baseline = ConfigLoader.fromEnvAndFile(baselineEnv)
            val candidate = ConfigLoader.fromEnvAndCandidateRoots(
                """
                roots {
                  candidate { path = "$main" }
                  other { path = "$candidateMissing" }
                }
                """.trimIndent(),
                candidateEnv,
            )
            val baselineWarning = unavailableWarning("baseline", baselineMissing)
            val candidateWarning = unavailableWarning("other", candidateMissing)
            val pair = setOf(RootName.PRIMARY, RootName.require("candidate"))
            val candidateBind =
                "binds 0.0.0.0 with auth.mode=off but no TLS/trusted-proxy and no insecure override. " +
                    "Remedies: (1) front with a TLS proxy and set PLAINBASE_TRUSTED_PROXY CIDRs; " +
                    "(2) bind loopback (PLAINBASE_HOST=127.0.0.1) behind the proxy; " +
                    "(3) set PLAINBASE_INSECURE_HTTP=1 to knowingly serve plaintext."

            val candidateRefusals = listOf(
                BootRefusal(
                    BootRefusal.Kind.ROOT_PAIR,
                    pair,
                    "roots.docs and roots.candidate resolve to the same directory: ${main.toRealPath()}",
                ),
                BootRefusal(BootRefusal.Kind.BIND_GUARD, emptySet(), candidateBind),
            )
            assertEquals(candidateRefusals, ConfigBootInspector.bootRefusals(candidate))
            assertEquals(listOf(candidateWarning), ConfigBootInspector.rootsWarnings(candidate))

            assertEquals(emptyList<BootRefusal>(), ConfigBootInspector.bootRefusals(baseline))
            assertEquals(listOf(baselineWarning), ConfigBootInspector.rootsWarnings(baseline))

            assertEquals(candidateRefusals, ConfigBootInspector.bootRefusals(candidate))
            assertEquals(listOf(candidateWarning), ConfigBootInspector.rootsWarnings(candidate))
        } finally {
            deleteTree(base)
        }
    }

    @Test
    fun `legacy warning list stays ordered and excludes explicit-only warnings`() {
        val base = Files.createTempDirectory("pb-boot-inspector-legacy-warnings")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val content = Files.createDirectory(data.resolve("content"))
            val rootsFile = data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE)
            Files.writeString(rootsFile, "roots {}")
            val backup = ManagedRootsFile.backupPath(rootsFile)
            Files.copy(rootsFile, backup)
            val config = ConfigLoader.fromEnvAndFile(
                mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to content.toString()),
            )
            val containment =
                "roots.docs (${content.toRealPath()}) is INSIDE DATA_DIR (${data.toRealPath()}). This serves correctly, " +
                    "but DATA_DIR is app-owned state whose contents are routinely wiped and rebuilt " +
                    "(`search.db` and the object mirror are explicitly disposable) - a wipe here takes this root's " +
                    "content with it. Move the root outside DATA_DIR."
            val backupWarning =
                "$backup is left over from an interrupted `plainbase root` promote. $rootsFile itself is intact and is " +
                    "the topology being served; remove the backup once you have satisfied yourself that is the topology you want."

            assertEquals(RootsOrigin.SYNTHESIZED, config.roots.origin)
            assertEquals(listOf(containment, backupWarning), ConfigBootInspector.rootsWarnings(config))
        } finally {
            deleteTree(base)
        }
    }

    @Test
    fun `explicit warning list preserves topology order then backup ignored unavailable and glob warnings`() {
        val base = Files.createTempDirectory("pb-boot-inspector-explicit-warnings")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val docs = Files.createDirectory(data.resolve("docs"))
            val zeta = data.resolve("zeta")
            val alpha = data.resolve("alpha")
            val ignored = base.resolve("ignored-content")
            val rootsFile = data.resolve(PlainbaseConfig.MANAGED_ROOTS_FILE)
            Files.writeString(rootsFile, "roots {}")
            val backup = ManagedRootsFile.backupPath(rootsFile)
            Files.copy(rootsFile, backup)
            Files.writeString(
                data.resolve("plainbase.conf"),
                """
                auth.agentDirectCommit.roots {
                  docs = ["docs/**"]
                  zeta = ["zeta/**"]
                  alpha = ["alpha/**"]
                }
                roots {
                  zeta  { path = "$zeta", editable = false }
                  docs  { path = "$docs", editable = false }
                  alpha { path = "$alpha", editable = false }
                }
                """.trimIndent(),
            )
            val config = ConfigLoader.fromEnvAndFile(
                mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to ignored.toString()),
            )
            val dataReal = data.toRealPath()
            val expected = listOf(
                containmentWarning("zeta", dataReal.resolve("zeta"), dataReal),
                containmentWarning("docs", docs.toRealPath(), dataReal),
                containmentWarning("alpha", dataReal.resolve("alpha"), dataReal),
                "$backup is left over from an interrupted `plainbase root` promote. $rootsFile itself is intact and is " +
                    "the topology being served; remove the backup once you have satisfied yourself that is the topology you want.",
                "roots {} is configured: the explicitly set CONTENT_DIR/contentDir (via env) is ignored - primary's path " +
                    "comes from roots.docs.path",
                unavailableWarning("zeta", zeta),
                unavailableWarning("alpha", alpha),
                globWarning("zeta"),
                globWarning("docs"),
                globWarning("alpha"),
            )

            assertEquals(listOf("zeta", "docs", "alpha"), config.roots.list.map { it.name.value })
            assertEquals(expected, ConfigBootInspector.rootsWarnings(config))
            assertTrue(ConfigBootInspector.rootsWarnings(config).none { it.startsWith("roots.docs.path does not exist") })
        } finally {
            deleteTree(base)
        }
    }

    private fun unavailableWarning(name: String, path: Path): String =
        "roots.$name.path does not exist or is not a readable/searchable directory: $path - the root will serve 503 " +
            "for every request until the path is restored AND the server is restarted (its pages, aliases and " +
            "checkpoints are left untouched in the meantime)"

    private fun containmentWarning(name: String, path: Path, data: Path): String =
        "roots.$name ($path) is INSIDE DATA_DIR ($data). This serves correctly, but DATA_DIR is app-owned state whose " +
            "contents are routinely wiped and rebuilt (`search.db` and the object mirror are explicitly disposable) - " +
            "a wipe here takes this root's content with it. Move the root outside DATA_DIR."

    private fun globWarning(name: String): String =
        "auth.agentDirectCommit declares direct-commit globs for root '$name', but roots.$name is editable = false - " +
            "the globs can never authorize anything there, because the root refuses page writes outright. Set editable = " +
            "true, or drop the globs."

    private fun deleteTree(base: Path) {
        Files.walk(base).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
