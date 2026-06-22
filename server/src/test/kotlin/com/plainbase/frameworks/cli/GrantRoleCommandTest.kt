package com.plainbase.frameworks.cli

import com.plainbase.domain.repository.Role
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightRoleRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * `plainbase admin grant-role` (A4a WI-11, §4): the upsert lands for builtin AND proxy issuers; a bad role arg →
 * exit 2; re-grant overwrites. Over a real temp `plainbase.db` through `AdminCommand.run` (the A2 command-test
 * shape).
 */
class GrantRoleCommandTest : FunSpec({

    fun <T> withConfig(block: (PlainbaseConfig) -> T): T {
        val data = Files.createTempDirectory("pb-grant-role")
        return try {
            block(PlainbaseConfig(contentDir = data.resolve("content"), dataDir = data, host = "127.0.0.1", port = 0))
        } finally {
            data.toFile().deleteRecursively()
        }
    }

    fun roleOf(config: PlainbaseConfig, issuer: String, externalId: String): Role? =
        DatabaseFactory.createDriver(config.appDatabasePath).use { driver ->
            SqlDelightRoleRepository(DatabaseFactory.createDatabase(driver)).roleOf(issuer, externalId)
        }

    test("grant-role upserts a builtin role") {
        withConfig { config ->
            AdminCommand.run(listOf("grant-role", "builtin", "alice", "admin"), config) shouldBe 0
            roleOf(config, "builtin", "alice") shouldBe Role.ADMIN
        }
    }

    test("grant-role upserts a proxy-keyed role (the proxy first-admin seam)") {
        withConfig { config ->
            AdminCommand.run(listOf("grant-role", "proxy", "bob@corp", "editor"), config) shouldBe 0
            roleOf(config, "proxy", "bob@corp") shouldBe Role.EDITOR
        }
    }

    test("an unknown role arg → exit 2, no row written") {
        withConfig { config ->
            AdminCommand.run(listOf("grant-role", "builtin", "alice", "wizard"), config) shouldBe 2
            roleOf(config, "builtin", "alice") shouldBe null
        }
    }

    test("re-grant overwrites the role (idempotent upsert)") {
        withConfig { config ->
            AdminCommand.run(listOf("grant-role", "builtin", "alice", "viewer"), config) shouldBe 0
            AdminCommand.run(listOf("grant-role", "builtin", "alice", "admin"), config) shouldBe 0
            roleOf(config, "builtin", "alice") shouldBe Role.ADMIN
        }
    }

    test("missing args → exit 2") {
        withConfig { config ->
            AdminCommand.run(listOf("grant-role", "builtin"), config) shouldBe 2
        }
    }
})
