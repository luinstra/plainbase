package com.plainbase.frameworks.cli

import com.plainbase.frameworks.config.AuthConfig
import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.filesystem.DataDirLock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * B2c — the lock-guarded admin token/role group. `mint-token`/`revoke-token`/`list-tokens`/`grant-role` all trigger
 * `DatabaseFactory.createDriver`'s implicit non-idempotent migrate, so they MUST hold the `DataDirLock` first (exit
 * 1 when a server holds it) like `setup-token`/`reindex`. The subcommand-validity check runs BEFORE the lock, so an
 * unknown subcommand returns exit 2 (usage) even under a held lock — no exit-code shift.
 *
 * Also A2-amber: surplus positional args for `mint-token`/`revoke-token` are a usage error (exit 2), not silently
 * ignored.
 */
class AdminCommandLockTest : FunSpec({

    System.setProperty("kotlin-logging.logStartupMessage", "false")

    fun config(dataDir: Path) = PlainbaseConfig(
        contentDir = dataDir.resolve("content"),
        dataDir = dataDir,
        host = "127.0.0.1",
        port = 0,
        auth = AuthConfig(mode = AuthMode.OFF),
    )

    fun <T> withData(block: (Path) -> T): T {
        val data = Files.createTempDirectory("pb-admin-lock")
        return try {
            block(data)
        } finally {
            data.toFile().deleteRecursively()
        }
    }

    fun silently(block: () -> Int): Int {
        val prev = System.out
        System.setOut(PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8))
        return try {
            block()
        } finally {
            System.setOut(prev)
        }
    }

    test("mint-token refuses with exit 1 when a server holds the lock, then succeeds once released") {
        withData { data ->
            val cfg = config(data)
            val held = DataDirLock.tryAcquire(data)!!
            try {
                AdminCommand.run(listOf("mint-token", "ci"), cfg) shouldBe 1 // lock-held refusal
            } finally {
                held.close()
            }
            silently { AdminCommand.run(listOf("mint-token", "ci"), cfg) } shouldBe 0 // released → mint succeeds
        }
    }

    test("grant-role refuses with exit 1 when a server holds the lock, then succeeds once released") {
        withData { data ->
            val cfg = config(data)
            val held = DataDirLock.tryAcquire(data)!!
            try {
                AdminCommand.run(listOf("grant-role", "builtin", "u1", "admin"), cfg) shouldBe 1
            } finally {
                held.close()
            }
            silently { AdminCommand.run(listOf("grant-role", "builtin", "u1", "admin"), cfg) } shouldBe 0
        }
    }

    test("an UNKNOWN subcommand returns exit 2 (usage) even when a server holds the lock — no exit-code shift") {
        withData { data ->
            val cfg = config(data)
            val held = DataDirLock.tryAcquire(data)!!
            try {
                AdminCommand.run(listOf("bogus-subcommand"), cfg) shouldBe 2 // validated BEFORE the lock
            } finally {
                held.close()
            }
        }
    }

    test("surplus positional args are a usage error (exit 2); the valid arities succeed (A2-amber)") {
        withData { data ->
            val cfg = config(data)
            // Surplus args → exit 2 (no token minted / nothing revoked).
            AdminCommand.run(listOf("mint-token", "ci", "read-only", "extra"), cfg) shouldBe 2
            AdminCommand.run(listOf("revoke-token", "some-id", "extra"), cfg) shouldBe 2
            // Valid arities still succeed.
            silently { AdminCommand.run(listOf("mint-token", "ci"), cfg) } shouldBe 0
            silently { AdminCommand.run(listOf("mint-token", "ci", "commit"), cfg) } shouldBe 0
            silently { AdminCommand.run(listOf("revoke-token", "some-id"), cfg) } shouldBe 0
        }
    }
})
