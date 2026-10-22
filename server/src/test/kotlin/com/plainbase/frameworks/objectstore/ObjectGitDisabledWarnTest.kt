package com.plainbase.frameworks.objectstore

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.Commit
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.history.FileDiff
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.frameworks.config.ConfigLoader
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.config.StorageConfig
import com.plainbase.frameworks.git.NoOpHistoryProvider
import com.plainbase.objectModeGitDisabledWarning
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Workspace backup guidance is present only for object deployments with git history disabled.
 * Readers omit this advice because they never write the content authority. An enabled [HistoryProvider]
 * suppresses the warning for workspaces as well. The source scan also checks that no snapshot/manifest
 * writer or `storage.object.snapshot.*` configuration keys exist.
 */
class ObjectGitDisabledWarnTest : FunSpec({

    val objectConfig = ConfigLoader.fromEnv(emptyMap()).copy(
        storage = StorageConfig(
            backend = StorageBackend.OBJECT,
            endpoint = "https://acct.example.com",
            bucket = "docs",
            accessKeyId = "k",
            secretAccessKey = "s",
        ),
    )
    val localConfig = ConfigLoader.fromEnv(emptyMap())

    test("object mode + NoOp history (every C4-era object boot) => the WARN fires, naming the exposure") {
        val warning = objectModeGitDisabledWarning(objectConfig, NoOpHistoryProvider)
        warning.shouldNotBeNull()
        warning shouldContain "object mode"
        warning shouldContain "git disabled"
        warning shouldContain "backup"
    }

    test("object mode + an ENABLED history provider => no WARN (git-over-the-mirror, once it exists)") {
        objectModeGitDisabledWarning(objectConfig, enabledHistoryStub).shouldBeNull()
    }

    test("LOCAL mode never warns, git enabled or not") {
        objectModeGitDisabledWarning(localConfig, NoOpHistoryProvider).shouldBeNull()
        objectModeGitDisabledWarning(localConfig, enabledHistoryStub).shouldBeNull()
    }

    test("object reader with disabled history should omit workspace backup guidance") {
        val data = Files.createTempDirectory("pb-reader-backup-warning")
        try {
            Files.writeString(data.resolve("plainbase.conf"), "profile = reader")
            val config = ConfigLoader.fromEnvAndFile(
                mapOf(
                    "DATA_DIR" to data.toString(), "PLAINBASE_STORAGE_BACKEND" to "object",
                    "PLAINBASE_S3_ENDPOINT" to "https://acct.example.com", "PLAINBASE_S3_BUCKET" to "docs",
                    "PLAINBASE_S3_ACCESS_KEY_ID" to "key", "PLAINBASE_S3_SECRET_ACCESS_KEY" to "secret",
                    "PLAINBASE_S3_PREFIX" to "releases/r1", "PLAINBASE_GIT_ENABLED" to "false",
                ),
            )
            objectModeGitDisabledWarning(config, NoOpHistoryProvider).shouldBeNull()
        } finally {
            data.toFile().deleteRecursively()
        }
    }

    test("the accessor result, fed through the facade exactly as serve() does, logs EXACTLY ONE backup WARN") {
        // serve() does `objectModeGitDisabledWarning(config, history)?.let { logger.warn { it } }`; this
        // reproduces that single emit-through-the-facade step (not a full serve() boot) and asserts one WARN.
        val root = LoggerFactory.getLogger(ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            val logger = KotlinLogging.logger("ObjectGitDisabledWarnTest")
            objectModeGitDisabledWarning(objectConfig, NoOpHistoryProvider)?.let { logger.warn { it } }
            val warns = appender.list.filter { it.level == ch.qos.logback.classic.Level.WARN && "backup" in it.formattedMessage }
            warns.size shouldBe 1
        } finally {
            root.detachAppender(appender)
        }
    }

    test("no snapshot/manifest writer main source exists, and no storage.object.snapshot.* config keys (rev 3.4)") {
        val mainRoot = mainSourceRoot()
        val files = Files.walk(mainRoot).use { stream ->
            stream.filter { it.isRegularFile() && it.extension == "kt" }.toList()
        }
        val violations = files.flatMap { file ->
            file.readText().lineSequence()
                .filter { "SnapshotWriter" in it || "ManifestWriter" in it || "storage.object.snapshot" in it }
                .map { "${mainRoot.relativize(file)}: ${it.trim()}" }
        }
        violations.shouldBeEmpty()
    }
})

private val enabledHistoryStub = object : HistoryProvider {
    override val enabled: Boolean = true
    override fun commit(path: TreePath, bytes: ByteArray, author: CommitIdentity?, committer: CommitIdentity?): Commit? = null
    override fun commitChanges(
        changes: List<com.plainbase.domain.history.HistoryChange>,
        message: String,
        author: CommitIdentity,
        committer: CommitIdentity,
    ): com.plainbase.domain.history.CommitOutcome = error("unused in this test")
    override fun lastCommits(paths: List<TreePath>): Map<TreePath, Commit> = emptyMap()
    override fun log(path: TreePath, limit: Int?): List<Commit> = emptyList()
    override fun diff(from: String, to: String, path: TreePath): FileDiff = FileDiff(from, to, TreePath.require("x.md"), "")
    override fun prepare() = Unit
    override fun gateCheck() = Unit
    override fun currentHead(): String? = null
    override fun isAncestor(ancestor: String, descendant: String): Boolean = false
    override fun deletedIn(from: String, to: String): Set<TreePath>? = null
}

/** Locates `server/src/main/kotlin` (the [com.plainbase.DomainPurityTest] pattern). */
private fun mainSourceRoot(): java.nio.file.Path {
    var dir: java.nio.file.Path? = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath()
    while (dir != null) {
        for (candidate in listOf("src/main/kotlin", "server/src/main/kotlin")) {
            val resolved = dir.resolve(candidate)
            if (Files.isDirectory(resolved)) return resolved
        }
        dir = dir.parent
    }
    error("Could not locate the main source tree from ${System.getProperty("user.dir")}")
}
