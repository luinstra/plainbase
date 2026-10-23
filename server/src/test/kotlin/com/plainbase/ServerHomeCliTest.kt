package com.plainbase

import com.typesafe.config.ConfigUtil
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Actual CWD/env entry points, isolated from the parent environment and any user state. */
class ServerHomeCliTest : FunSpec({
    test("legacy state refuses before creating home state") {
        withHomeFixture { base ->
            val old = Files.createDirectory(base.resolve("data"))
            Files.writeString(old.resolve("custom.css"), "/* durable theme */")
            val commands = listOf(
                listOf("serve"), emptyList(), listOf("admin"), listOf("adopt"), listOf("reindex"),
                listOf("root", "list"), listOf("root", "add", "extra", base.resolve("extra").toString()),
                listOf("root", "remove", "extra"),
            )
            commands.forEach { args ->
                val (status, diagnostic) = command(base, args)
                withClue("$args: $diagnostic") {
                    status shouldBe 1
                    val prefix = if (args == listOf("root", "list")) "root list" else args.firstOrNull() ?: "serve"
                    diagnostic shouldContain "$prefix: Legacy state"
                    diagnostic shouldContain "absolute DATA_DIR"
                    diagnostic shouldContain old.toString()
                    diagnostic shouldContain base.resolve("home/.plainbase").toString()
                    diagnostic shouldContain "https://github.com/luinstra/plainbase/blob/main/docs/operating-plainbase.md"
                    diagnostic shouldNotContain "Exception"
                    Files.exists(base.resolve("home")) shouldBe false
                    Files.list(old).use { it.map { p -> p.fileName.toString() }.toList() } shouldBe listOf("custom.css")
                }
            }
        }
    }

    test("serve and no subcommand boot using distinguishable home config") {
        withHomeFixture { base ->
            val content = Files.createDirectory(base.resolve("content"))
            val extra = Files.createDirectory(base.resolve("extra"))
            val data = Files.createDirectories(base.resolve("home/.plainbase"))
            // Unique to this disposable fixture, so an unrelated listener cannot satisfy the health assertion.
            val rootName = "home-" + base.fileName.toString().removePrefix("pb-home-cli")
            Files.writeString(
                data.resolve("plainbase.conf"),
                "host = \"127.0.0.1\"\nport = 0\ngit.enabled = false\nauth.mode = off\n" +
                    "contentDir = ${ConfigUtil.quoteString(content.toString())}",
            )
            Files.writeString(
                data.resolve("roots.conf"),
                "roots { ${ConfigUtil.quoteString(rootName)} { path = ${ConfigUtil.quoteString(extra.toString())} } }",
            )
            listOf(listOf("serve"), emptyList()).forEach { args ->
                withChild(base, args, mapOf("CONTENT_DIR" to null)) { process, log ->
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
                    var health: String? = null
                    while (System.nanoTime() < deadline && health == null) {
                        val output = Files.readString(log)
                        withClue("exited before health: $output") { process.isAlive shouldBe true }
                        // Ktor reports the resolved connector after binding port zero; never reserve/release a port.
                        val port = boundEndpoint.find(output)?.groupValues?.get(1)?.toInt()
                        if (port != null) health = readHealth(port)
                        if (health == null) Thread.sleep(100)
                    }
                    withClue("health deadline or child exit: ${Files.readString(log)}") {
                        process.isAlive shouldBe true
                        health.shouldNotBeNull() shouldContain rootName
                    }
                    Files.isRegularFile(data.resolve("plainbase.db")) shouldBe true
                    Files.exists(base.resolve("data")) shouldBe false
                }
            }
        }
    }

    test("root list add and remove share home managed config") {
        withHomeFixture { base ->
            Files.createDirectory(base.resolve("content"))
            val extra = Files.createDirectory(base.resolve("extra"))
            val data = Files.createDirectories(base.resolve("home/.plainbase"))
            Files.writeString(data.resolve("plainbase.conf"), "git.enabled = false")
            command(base, listOf("root", "list")).first shouldBe 0
            val added = command(base, listOf("root", "add", "extra", extra.toString()))
            withClue(added.second) { added.first shouldBe 0 }
            Files.isRegularFile(data.resolve("roots.conf")) shouldBe true
            val listed = command(base, listOf("root", "list"))
            withClue(listed.second) {
                listed.first shouldBe 0
                listed.second.lines().any { it.startsWith("extra") && it.contains("roots.conf") } shouldBe true
            }
            val removed = command(base, listOf("root", "remove", "extra"))
            withClue(removed.second) { removed.first shouldBe 0 }
            Files.exists(data.resolve("roots.conf")) shouldBe false
            Files.exists(base.resolve("data")) shouldBe false
        }
    }

    test("admin adopt and reindex select malformed home HOCON before mutation") {
        withHomeFixture { base ->
            val data = Files.createDirectories(base.resolve("home/.plainbase"))
            Files.writeString(data.resolve("plainbase.conf"), "auth { mode =")
            listOf("admin", "adopt", "reindex").forEach { verb ->
                val (status, diagnostic) = command(base, listOf(verb))
                withClue(diagnostic) {
                    status shouldBe 1
                    diagnostic shouldContain "$verb:"
                    diagnostic shouldContain data.resolve("plainbase.conf").toString()
                    Files.exists(data.resolve("plainbase.db")) shouldBe false
                    Files.exists(data.resolve("plainbase.lock")) shouldBe false
                    Files.exists(base.resolve("data")) shouldBe false
                }
            }
        }
    }

    test("explicit DATA_DIR operates with HOME absent and bypasses legacy evidence") {
        withHomeFixture { base ->
            val old = Files.createDirectory(base.resolve("data"))
            Files.writeString(old.resolve("custom.css"), "/* legacy */")
            val selected = Files.createDirectory(base.resolve("explicit"))
            Files.writeString(selected.resolve("plainbase.conf"), "contentDir = \"explicit-content\"")
            val result = command(
                base, listOf("root", "list"), mapOf("HOME" to null, "DATA_DIR" to selected.toString(), "CONTENT_DIR" to null),
            )
            withClue(result.second) {
                result.first shouldBe 0
                result.second shouldContain base.resolve("explicit-content").toString()
                Files.exists(base.resolve("home")) shouldBe false
            }
        }
    }

    test("missing HOME and blank DATA_DIR use concise prelock root errors") {
        withHomeFixture { base ->
            listOf(mapOf("HOME" to null), mapOf("DATA_DIR" to " \t ")).forEach { override ->
                val result = command(base, listOf("root", "add", "extra", base.resolve("extra").toString()), override)
                withClue(result.second) {
                    result.first shouldBe 1
                    result.second shouldStartWith "root:"
                    result.second shouldNotContain "Exception"
                    Files.exists(base.resolve("home")) shouldBe false
                    Files.exists(base.resolve("data")) shouldBe false
                }
            }
        }
    }
})

private val boundEndpoint = Regex("""Responding at http://127\.0\.0\.1:([1-9][0-9]{0,4})(?:\s|$)""")

private fun withHomeFixture(block: (Path) -> Unit) {
    val base = Files.createTempDirectory("pb-home-cli").toAbsolutePath()
    try {
        block(base)
    } finally {
        base.toFile().deleteRecursively()
    }
}

private fun command(base: Path, args: List<String>, overrides: Map<String, String?> = emptyMap()): Pair<Int, String> =
    withChild(base, args, overrides) { process, log ->
        withClue("child timed out: ${Files.readString(log)}") { process.waitFor(20, TimeUnit.SECONDS) shouldBe true }
        process.exitValue() to Files.readString(log)
    }

private fun <T> withChild(
    base: Path,
    args: List<String>,
    overrides: Map<String, String?> = emptyMap(),
    block: (Process, Path) -> T,
): T {
    val log = Files.createTempFile(base, "child-", ".log")
    val builder = ProcessBuilder(
        listOf(
            Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            requireNotNull(System.getProperty("plainbase.test.mainRuntimeClasspath")),
            "com.plainbase.ApplicationKt",
        ) + args,
    ).directory(base.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
    builder.environment().clear()
    val environment = mapOf("HOME" to base.resolve("home").toString(), "CONTENT_DIR" to base.resolve("content").toString()) + overrides
    environment.forEach { (key, value) -> if (value != null) builder.environment()[key] = value }
    val process = builder.start()
    process.outputStream.close()
    try {
        return block(process, log)
    } finally {
        process.destroy()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            check(process.waitFor(5, TimeUnit.SECONDS)) { "owned child did not terminate" }
        }
        process.inputStream.close()
        process.errorStream.close()
    }
}

private fun readHealth(port: Int): String? = runCatching {
    val connection = URI("http://127.0.0.1:$port/healthz").toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
    try {
        connection.connectTimeout = 500
        connection.readTimeout = 500
        if (connection.responseCode == 200) connection.inputStream.bufferedReader().use { it.readText() } else null
    } finally {
        connection.disconnect()
    }
}.getOrNull()
