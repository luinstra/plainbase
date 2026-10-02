package com.plainbase.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

abstract class VerifySharedBuildVersion : DefaultTask() {
    @get:Input
    abstract val expectedVersion: Property<String>

    @get:Internal
    abstract val installedDistribution: DirectoryProperty

    init {
        doNotTrackState("The installed launcher must execute and report its version on every invocation")
    }

    @TaskAction
    fun verify() {
        verifyLauncher(installedDistribution.get().asFile.toPath().resolve("bin/plainbase"), expectedVersion.get())
        logger.lifecycle("Shared launcher reports version ${expectedVersion.get()}")
    }

    companion object {
        internal fun verifyLauncher(launcher: Path, version: String, timeoutSeconds: Long = 30) {
            val temporary = Files.createTempDirectory("plainbase-version-")
            var process: Process? = null
            try {
                val content = Files.createDirectory(temporary.resolve("content"))
                val data = Files.createDirectory(temporary.resolve("data"))
                val port = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
                val builder = ProcessBuilder(launcher.toAbsolutePath().toString(), "serve")
                    .directory(temporary.toFile()).redirectErrorStream(true).redirectOutput(temporary.resolve("server.log").toFile())
                builder.environment().keys.removeIf { it.startsWith("PLAINBASE_") || it in setOf("CONTENT_DIR", "DATA_DIR", "JAVA_OPTS") }
                builder.environment().putAll(
                    mapOf(
                        "CONTENT_DIR" to content.toString(), "DATA_DIR" to data.toString(), "PLAINBASE_HOST" to "127.0.0.1",
                        "PLAINBASE_PORT" to port.toString(), "PLAINBASE_AUTH_MODE" to "off", "PLAINBASE_LOG_LEVEL" to "INFO",
                    ),
                )
                val child = builder.start()
                process = child
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
                var verified = false
                while (System.nanoTime() < deadline && !verified) {
                    check(child.isAlive) {
                        "Installed launcher exited during version verification: ${Files.readString(temporary.resolve("server.log"))}"
                    }
                    val log = Files.readString(temporary.resolve("server.log"))
                    check(!log.contains("BindException")) { "Installed launcher failed to bind during version verification" }
                    if (!log.contains("Responding at http://127.0.0.1:$port")) {
                        Thread.sleep(100)
                        continue
                    }
                    val connection = URI("http://127.0.0.1:$port/healthz").toURL().openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 250
                        connection.readTimeout = 500
                        val body = try {
                            if (connection.responseCode == 200) connection.inputStream.bufferedReader().use { it.readText() } else null
                        } catch (_: IOException) {
                            null
                        }
                        if (body != null) {
                            val reported = Regex("\"version\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
                            check(reported == version) { "Installed launcher version mismatch: expected $version, got $reported" }
                            // A foreign listener must not mask a child that lost the ephemeral port race.
                            Thread.sleep(200)
                            check(child.isAlive && !Files.readString(temporary.resolve("server.log")).contains("BindException")) {
                                "Installed launcher failed to bind during version verification"
                            }
                            verified = true
                        } else {
                            Thread.sleep(100)
                        }
                    } finally {
                        connection.disconnect()
                    }
                }
                check(verified) {
                    "Installed launcher health/version verification timed out: ${Files.readString(temporary.resolve("server.log"))}"
                }
            } finally {
                val child = process
                if (child != null) {
                    child.destroy()
                    if (!child.waitFor(5, TimeUnit.SECONDS)) {
                        child.destroyForcibly()
                        check(child.waitFor(5, TimeUnit.SECONDS)) { "Could not reap installed launcher" }
                    }
                }
                SharedBuildFiles.deleteTree(temporary)
            }
        }
    }
}
