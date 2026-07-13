package com.plainbase

import com.plainbase.domain.root.BootRefusal
import com.plainbase.domain.root.RootName
import com.plainbase.frameworks.config.PlainbaseConfig
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **THE REFACTOR SUPPRESSES NO WARNING.**
 *
 * `serve()` used to interleave its refusals and its warnings by construction: it refused at `requireContentDir`,
 * then printed the config warnings, then refused at the bind guard, then walked the roots printing an
 * unavailable-WARN for each missing extra and exiting on the first git failure. Routing all of that through one
 * COMPLETE gate makes it trivially easy to consume at a single `refusals.firstOrNull()` at the top - which would
 * exit on ANY refusal before boot had printed the warnings that precede it TODAY.
 *
 * **An operator losing a boot warning is information loss on the exact surface this feature exists to make
 * visible.** A git-gate failure on the rank-2 root would swallow the rank-1 root's "not available, will serve
 * 503" WARN - and that WARN is how they find out their disk is unmounted.
 *
 * Two things keep it honest, and both are asserted here:
 *  1. **The stages PARTITION the kinds and never overlap**, so a git refusal cannot be consumed by the topology
 *     stage and jump the queue. (`BootRefusalLedgerTest` pins the partition; this pins the SEPARATION that
 *     matters - GIT_GATE is reached through the verdicts, not through the refusals.)
 *  2. **The verdicts come back in REGISTRY (rank) order**, which is what makes `serve()`'s replay reproduce
 *     today's output exactly: the warns for roots BEFORE the refused one print, and the roots after it printed
 *     nothing then either.
 *
 * (The end-to-end emitted SEQUENCE - the actual stderr of a real boot - is asserted by
 * `scripts/ci/multi-root-smoke.sh` against the native binary. `serve()` calls `exitProcess`, so it is not
 * unit-testable in-process; that is a settled property of this codebase, not a gap this chunk introduced.)
 *
 * `@Tag("native")`: producing a real `Refused` verdict means shelling out to real `git`.
 */
@Tag("native")
class BootGateOrderingTest {

    @Test
    fun `an UNAVAILABLE root at rank 1 precedes a REFUSED root at rank 2 - so its WARN still prints`() {
        val base = Files.createTempDirectory("pb-gate-order")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val main = Files.createDirectory(base.resolve("main"))
            Files.writeString(main.resolve("readme.md"), "---\ntitle: R\n---\n\n# R\n")
            val missing = base.resolve("not-mounted") // rank 1: unavailable, WARNs, boot CONTINUES
            val notARepo = Files.createDirectory(base.resolve("claimed")) // rank 2: git gate REFUSES

            Files.writeString(
                data.resolve("plainbase.conf"),
                """
                roots {
                  main    { path = "$main" }
                  absent  { path = "$missing" }
                  claimed { path = "$notARepo", history = native }
                }
                """.trimIndent(),
            )
            val config = PlainbaseConfig.fromEnvAndFile(mapOf("DATA_DIR" to data.toString()))
            val gate = bootGateFor(config)

            // Registry (rank) order, which is the order `serve()` replays them in.
            assertEquals(
                listOf(RootName.MAIN, RootName.require("absent"), RootName.require("claimed")),
                gate.verdicts.map { it.root },
                "the verdicts must come back in RANK order, or replaying them cannot reproduce today's boot output",
            )
            assertTrue(gate.verdicts[1] is RootGateVerdict.Unavailable, "rank 1 is the missing extra: it WARNs and boot carries on")
            assertTrue(gate.verdicts[2] is RootGateVerdict.Refused, "rank 2 is the bad repo: it exits 1")
            // The rank-1 WARN comes BEFORE the rank-2 refusal. That is the whole ordering claim.
            assertTrue(
                gate.verdicts.indexOfFirst { it is RootGateVerdict.Unavailable } <
                    gate.verdicts.indexOfFirst { it is RootGateVerdict.Refused },
                "the refusal jumped ahead of the unavailable WARN - an operator would never learn their disk is gone",
            )
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a GIT_GATE refusal is NOT consumed by the topology or bind stages, so it cannot swallow the config warnings`() {
        val base = Files.createTempDirectory("pb-gate-order-stages")
        try {
            val data = Files.createDirectory(base.resolve("data"))
            val main: Path = Files.createDirectory(base.resolve("main"))
            Files.writeString(main.resolve("readme.md"), "---\ntitle: R\n---\n\n# R\n")
            val notARepo = Files.createDirectory(base.resolve("claimed"))

            Files.writeString(
                data.resolve("plainbase.conf"),
                """
                roots {
                  main    { path = "$main" }
                  claimed { path = "$notARepo", history = native }
                }
                """.trimIndent(),
            )
            // An explicitly-set CONTENT_DIR on a hand-declared main is a rootsWarnings() line - the warning that
            // must survive. It prints BETWEEN the topology stage and the bind stage.
            val config = PlainbaseConfig.fromEnvAndFile(
                mapOf("DATA_DIR" to data.toString(), "CONTENT_DIR" to base.resolve("ignored").toString()),
            )
            assertTrue(config.rootsWarnings().isNotEmpty(), "precondition: there must BE a warning to lose")

            val gate = bootGateFor(config)
            assertTrue(gate.refusals.any { it.kind == BootRefusal.Kind.GIT_GATE }, "precondition: the git gate must have refused")

            // The topology stage sees nothing here, so `serve()` does NOT exit before printing the warning...
            assertFalse(
                gate.refusals.any { it.kind in TOPOLOGY_REFUSAL_KINDS },
                "a git failure must not be consumable by the topology stage",
            )
            // ...and neither does the bind stage.
            assertFalse(
                gate.refusals.any { it.kind in BIND_REFUSAL_KINDS },
                "a git failure must not be consumable by the bind stage",
            )
            // It belongs to the VERDICT stage, which is reached only after every warning has printed.
            assertTrue(BootRefusal.Kind.GIT_GATE in VERDICT_REFUSAL_KINDS)
        } finally {
            base.toFile().deleteRecursively()
        }
    }
}
