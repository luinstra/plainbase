package com.plainbase.frameworks.cli

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPath
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.RootsConfig
import com.plainbase.frameworks.config.RootsOrigin
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightIdMapRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

/**
 * `plainbase admin force-retire <root> <id>` (§0.3.1, the operator un-wedge hatch): usage errors (bad root / bad id /
 * wrong arity) exit 2 with the DATA_DIR lock FREE (the CLI-test condition, since the handler validates POST-lock like
 * `mintToken`); an UNREGISTERED root exits 1 with the named message; a registered root with no live binding exits 1;
 * a live binding reaps (exit 0) and a second call is idempotent (exit 0, "already retired"). The exit-1 unregistered
 * answer is ALSO the wiring proof: were `force-retire` NOT in `LOCKED_SUBCOMMANDS` it would be rejected pre-lock as an
 * unknown subcommand and exit 2. Over a real temp `plainbase.db` through `AdminCommand.run` (the A2 command-test shape).
 */
class ForceRetireCommandTest : FunSpec({

    val id = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"

    fun <T> withConfig(block: (PlainbaseConfig) -> T): T {
        val data = Files.createTempDirectory("pb-force-retire")
        return try {
            val content = Files.createDirectories(data.resolve("content"))
            val base = PlainbaseConfig(contentDir = content, dataDir = data, host = "127.0.0.1", port = 0)
            block(
                base.copy(
                    roots = RootsConfig.of(
                        list = listOf(Root(RootName.MAIN, RootBackend.Local(content), editable = true, history = HistoryMode.OFF)),
                        origin = RootsOrigin.EXPLICIT,
                    ),
                ),
            )
        } finally {
            data.toFile().deleteRecursively()
        }
    }

    fun seedBinding(config: PlainbaseConfig, rel: String) {
        DatabaseFactory.createDriver(config.appDatabasePath).use { driver ->
            SqlDelightIdMapRepository(DatabaseFactory.createDatabase(driver))
                .bind(RootedPath(RootName.MAIN, TreePath.require(rel)), PageId.require(id), materialized = false)
        }
    }

    test("a malformed root slug -> exit 2 (usage), lock free") {
        withConfig { config ->
            AdminCommand.run(listOf("force-retire", "a/b", id), config, CommandOutputFixture().output) shouldBe 2
        }
    }

    test("a malformed id -> exit 2 (usage)") {
        withConfig { config ->
            AdminCommand.run(listOf("force-retire", "main", "not-a-uuid"), config, CommandOutputFixture().output) shouldBe 2
        }
    }

    test("wrong arity (too few / too many) -> exit 2") {
        withConfig { config ->
            AdminCommand.run(listOf("force-retire", "main"), config, CommandOutputFixture().output) shouldBe 2
            AdminCommand.run(listOf("force-retire", "main", id, "extra"), config, CommandOutputFixture().output) shouldBe 2
        }
    }

    test("an UNREGISTERED root -> exit 1 with the named message (this is ALSO the LOCKED_SUBCOMMANDS wiring proof: not exit 2)") {
        withConfig { config ->
            val out = CommandOutputFixture()
            AdminCommand.run(listOf("force-retire", "ghost", id), config, out.output) shouldBe 1
            out.stderr shouldContain "root 'ghost' is not registered"
        }
    }

    test("a registered root with no live binding for the id -> exit 1") {
        withConfig { config ->
            val out = CommandOutputFixture()
            AdminCommand.run(listOf("force-retire", "main", id), config, out.output) shouldBe 1
            out.stderr shouldContain "no live binding"
        }
    }

    test("a live binding reaps (exit 0, force-retired); a second call is idempotent (exit 0, already retired)") {
        withConfig { config ->
            seedBinding(config, "guides/a.md")

            val reap = CommandOutputFixture()
            AdminCommand.run(listOf("force-retire", "main", id), config, reap.output) shouldBe 0
            reap.stdout shouldContain "force-retired"

            val again = CommandOutputFixture()
            AdminCommand.run(listOf("force-retire", "main", id), config, again.output) shouldBe 0
            again.stdout shouldContain "already retired"
        }
    }

    test("a held roots.lock refuses force-retire without mutation, then the same command succeeds after release") {
        withConfig { config ->
            seedBinding(config, "guides/a.md")
            val rootsLock = checkNotNull(DataDirLock.tryAcquire(config.dataDir, DataDirLock.ROOTS_LOCK_FILE_NAME))

            rootsLock.use {
                val refused = CommandOutputFixture()
                AdminCommand.run(listOf("force-retire", "main", id), config, refused.output) shouldBe 1
                refused.stderr shouldContain "root` command is holding"

                DatabaseFactory.createDriver(config.appDatabasePath).use { driver ->
                    SqlDelightIdMapRepository(DatabaseFactory.createDatabase(driver))
                        .bindingInRoot(RootName.MAIN, PageId.require(id)).shouldNotBeNull()
                }
            }

            val retried = CommandOutputFixture()
            AdminCommand.run(listOf("force-retire", "main", id), config, retried.output) shouldBe 0
            retried.stdout shouldContain "force-retired"
        }
    }

    test("a concurrent `root remove` that detaches the root before the retirement -> refuse (fresh registry re-read under roots.lock)") {
        val data = Files.createTempDirectory("pb-force-retire-race")
        try {
            val content = Files.createDirectories(data.resolve("content"))
            val extraContent = Files.createDirectories(data.resolve("extra"))
            val main = Root(RootName.MAIN, RootBackend.Local(content), editable = true, history = HistoryMode.OFF)
            val extra = Root(RootName.require("extra"), RootBackend.Local(extraContent), editable = true, history = HistoryMode.OFF)
            val base = PlainbaseConfig(contentDir = content, dataDir = data, host = "127.0.0.1", port = 0)
            // The snapshot the command started with: `extra` is still registered.
            val config = base.copy(roots = RootsConfig.of(list = listOf(main, extra), origin = RootsOrigin.EXPLICIT))
            // A live binding exists under `extra`.
            DatabaseFactory.createDriver(config.appDatabasePath).use { driver ->
                SqlDelightIdMapRepository(DatabaseFactory.createDatabase(driver))
                    .bind(RootedPath(RootName.require("extra"), TreePath.require("notes/b.md")), PageId.require(id), materialized = false)
            }
            // The fresh registry `roots.lock` re-reads: a concurrent `root remove extra` has since committed, detaching it.
            val afterRemove = config.copy(roots = RootsConfig.of(list = listOf(main), origin = RootsOrigin.EXPLICIT))

            val out = CommandOutputFixture()
            AdminCommand.run(listOf("force-retire", "extra", id), config, out.output, reloadConfig = { afterRemove }) shouldBe 1
            out.stderr shouldContain "root 'extra' is not registered"

            // The binding MUST still be live: nothing was retired into the detached root, so no false 410 was promised.
            DatabaseFactory.createDriver(config.appDatabasePath).use { driver ->
                SqlDelightIdMapRepository(DatabaseFactory.createDatabase(driver))
                    .bindingInRoot(RootName.require("extra"), PageId.require(id)).shouldNotBeNull()
            }
        } finally {
            data.toFile().deleteRecursively()
        }
    }

    test("an UNKNOWN subcommand -> exit 2 (the pre-lock usage gate), and force-retire is NOT one of them") {
        withConfig { config ->
            AdminCommand.run(listOf("flush-cache"), config, CommandOutputFixture().output) shouldBe 2
        }
    }
})
