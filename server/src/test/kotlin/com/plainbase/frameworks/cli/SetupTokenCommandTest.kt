package com.plainbase.frameworks.cli

import com.plainbase.domain.service.BootstrapOutcome
import com.plainbase.frameworks.config.AuthConfig
import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.config.PlainbaseConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * `plainbase admin setup-token [--force]` (A4a WI-13, §5): mints WITHOUT `--force` only on an empty / no-enabled
 * -admin DB; `--force` re-mints regardless; non-builtin mode refuses with exit 2; the printed token consumes
 * successfully via SetupService (the round-trip proof that the CLI mint and the route consume share the machinery).
 */
class SetupTokenCommandTest : FunSpec({

    // These tests call AdminCommand.run directly (not via main()), so replicate main()'s one-line banner suppression:
    // KotlinLogging prints a startup banner to stdout on its FIRST init unless this is false — and the lock acquired
    // by setup-token logs, so without this the banner would land in the captured stdout AHEAD of the printed token.
    System.setProperty("kotlin-logging.logStartupMessage", "false")

    fun config(dataDir: java.nio.file.Path, mode: AuthMode) =
        PlainbaseConfig(
            contentDir = dataDir.resolve("content"),
            dataDir = dataDir,
            host = "127.0.0.1",
            port = 0,
            auth = AuthConfig(mode = mode),
        )

    fun <T> withData(block: (java.nio.file.Path) -> T): T {
        val data = Files.createTempDirectory("pb-setup-token")
        return try {
            block(data)
        } finally {
            data.toFile().deleteRecursively()
        }
    }

    fun capture(block: (CommandOutput) -> Int): Pair<Int, String> {
        val io = CommandOutputFixture()
        return block(io.output) to io.stdout
    }

    test("setup-token mints on an empty builtin DB and the printed token consumes successfully") {
        withData { data ->
            val (code, out) = capture { AdminCommand.run(listOf("setup-token"), config(data, AuthMode.BUILTIN), it) }
            code shouldBe 0
            val token = out.lineSequence().first { it.isNotBlank() }

            // The printed token consumes through a fresh SetupService over the SAME on-disk DB.
            com.plainbase.frameworks.sqldelight.DatabaseFactory.createDriver(config(data, AuthMode.BUILTIN).appDatabasePath).use { driver ->
                val db = com.plainbase.frameworks.sqldelight.DatabaseFactory.createDatabase(driver)
                val (setup, _) = com.plainbase.domain.service.setupServiceFixture(db)
                (setup.consumeBootstrap(token, "alice", "pw".toCharArray()) is BootstrapOutcome.Created) shouldBe true
            }
        }
    }

    test("a second setup-token WITHOUT --force on a now-non-empty DB refuses (exit 2)") {
        withData { data ->
            val cfg = config(data, AuthMode.BUILTIN)
            val (code1, out1) = capture { AdminCommand.run(listOf("setup-token"), cfg, it) }
            code1 shouldBe 0
            // Consume it so an enabled admin now exists.
            val token = out1.lineSequence().first { it.isNotBlank() }
            com.plainbase.frameworks.sqldelight.DatabaseFactory.createDriver(cfg.appDatabasePath).use { driver ->
                val db = com.plainbase.frameworks.sqldelight.DatabaseFactory.createDatabase(driver)
                com.plainbase.domain.service.setupServiceFixture(db).first.consumeBootstrap(token, "alice", "pw".toCharArray())
            }
            AdminCommand.run(listOf("setup-token"), cfg, CommandOutputFixture().output) shouldBe 2 // enabled admin → refuse
        }
    }

    test("--force mints regardless of DB state") {
        withData { data ->
            val cfg = config(data, AuthMode.BUILTIN)
            val out1 = capture { AdminCommand.run(listOf("setup-token"), cfg, it) }.second
            val token = out1.lineSequence().first { it.isNotBlank() }
            com.plainbase.frameworks.sqldelight.DatabaseFactory.createDriver(cfg.appDatabasePath).use { driver ->
                val db = com.plainbase.frameworks.sqldelight.DatabaseFactory.createDatabase(driver)
                com.plainbase.domain.service.setupServiceFixture(db).first.consumeBootstrap(token, "alice", "pw".toCharArray())
            }
            AdminCommand.run(listOf("setup-token", "--force"), cfg, CommandOutputFixture().output) shouldBe 0 // re-mints despite the admin
        }
    }

    test("non-builtin mode refuses (exit 2)") {
        withData { data ->
            AdminCommand.run(listOf("setup-token"), config(data, AuthMode.PROXY), CommandOutputFixture().output) shouldBe 2
            AdminCommand.run(listOf("setup-token"), config(data, AuthMode.OFF), CommandOutputFixture().output) shouldBe 2
        }
    }

    // Fix F: accept ONLY [] or [--force]; trailing junk (or a bad flag) is a usage error, never silently ignored.
    test("trailing junk after --force is a usage error (exit 2), not silently accepted") {
        withData { data ->
            val cfg = config(data, AuthMode.BUILTIN)
            AdminCommand.run(listOf("setup-token", "--force", "extra"), cfg, CommandOutputFixture().output) shouldBe 2
            AdminCommand.run(listOf("setup-token", "extra"), cfg, CommandOutputFixture().output) shouldBe 2
            AdminCommand.run(listOf("setup-token", "--frce"), cfg, CommandOutputFixture().output) shouldBe 2
        }
    }
})
