package com.plainbase

import com.plainbase.domain.page.PageId
import com.plainbase.domain.service.TestIdProvider
import com.plainbase.frameworks.cli.CommandOutputFixture
import com.plainbase.frameworks.config.ConfigLoader
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.plainbaseModule
import com.plainbase.frameworks.objectstore.FakeObjectStore
import com.plainbase.frameworks.objectstore.ObjectContentStore
import com.plainbase.frameworks.objectstore.ObjectContentStoreFactory
import com.plainbase.frameworks.runtime.ServerOpeners
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

internal fun withReader(
    workspace: Boolean = false,
    extraEnv: Map<String, String> = emptyMap(),
    block: (ReaderServeWorld) -> Unit,
) {
    val base = Files.createTempDirectory("pb-reader-serve")
    try {
        val data = Files.createDirectory(base.resolve("data"))
        val content = Files.createDirectory(base.resolve("content"))
        Files.writeString(data.resolve("plainbase.conf"), if (workspace) "" else "profile = reader")
        val closedPort = ServerSocket(0).use { it.localPort }
        val env = mapOf(
            "DATA_DIR" to data.toString(),
            "CONTENT_DIR" to content.toString(),
            "PLAINBASE_STORAGE_BACKEND" to "object",
            "PLAINBASE_S3_ENDPOINT" to "https://127.0.0.1:$closedPort",
            "PLAINBASE_S3_BUCKET" to "docs",
            "PLAINBASE_S3_ACCESS_KEY_ID" to "dummy-key",
            "PLAINBASE_S3_SECRET_ACCESS_KEY" to "dummy-secret",
            "PLAINBASE_S3_PREFIX" to "releases/r1",
            "PLAINBASE_GIT_ENABLED" to "false",
            "PLAINBASE_HOST" to "127.0.0.1",
        ) + (if (workspace) emptyMap() else mapOf("PLAINBASE_PROFILE" to "reader")) + extraEnv
        val world = ReaderServeWorld(data, env)
        world.seedRelease("releases/r1", mapOf("index.md" to ReaderReleases.r1.getValue("index.md")))
        block(world)
    } finally {
        base.toFile().deleteRecursively()
    }
}

internal class ReaderServeWorld(
    val data: Path,
    val env: Map<String, String>,
    val bucket: FakeObjectStore = FakeObjectStore(),
) {
    val output = CommandOutputFixture()
    val driverOpens = AtomicInteger()
    val objectOpens = AtomicInteger()
    val stores = mutableListOf<ObjectContentStore>()
    var useFakeObjectStore = false
    private val defaults = ServerOpeners()
    val openers = ServerOpeners(
        openDriver = { path ->
            driverOpens.incrementAndGet()
            defaults.openDriver(path)
        },
        openObject = { config, ignore, dirty, isDirty, rows ->
            objectOpens.incrementAndGet()
            if (useFakeObjectStore) {
                ObjectContentStoreFactory.buildWithClient(config, ignore, dirty, isDirty, rows, clientFactory = { bucket })
            } else {
                defaults.openObject(config, ignore, dirty, isDirty, rows)
            }.also { stores += it }
        },
    )

    fun config(): PlainbaseConfig = ConfigLoader.fromEnvAndFile(env)

    fun errors(): List<String> = output.stderr.lineSequence().filter { it.isNotBlank() }.toList()

    fun seedRelease(prefix: String, files: Map<String, ByteArray>) {
        files.forEach { (path, bytes) -> bucket.seed("$prefix/$path", bytes) }
    }

    fun fresh(extraEnv: Map<String, String> = emptyMap()): ReaderServeWorld {
        val freshData = Files.createTempDirectory(data.parent, "fresh-data-")
        Files.copy(data.resolve("plainbase.conf"), freshData.resolve("plainbase.conf"))
        return ReaderServeWorld(freshData, env + extraEnv + ("DATA_DIR" to freshData.toString()), bucket).also {
            it.useFakeObjectStore = useFakeObjectStore
        }
    }

    fun answers(ctx: RouteContext, requests: List<ReaderRequest>): Map<ReaderRequest, ReaderAnswer> {
        val answers = linkedMapOf<ReaderRequest, ReaderAnswer>()
        testApplication {
            application { plainbaseModule(ctx) }
            val probe = createClient { followRedirects = false }
            requests.forEach { request ->
                val response = probe.get(request.path) {
                    if (request.markdown) header(HttpHeaders.Accept, "text/markdown")
                }
                answers[request] = ReaderAnswer(response.status, response.bodyAsText(), response.headers[HttpHeaders.Location])
            }
        }
        return answers
    }
}

internal data class ReaderRequest(val path: String, val markdown: Boolean = false)

internal data class ReaderAnswer(val status: HttpStatusCode, val body: String, val location: String?)

internal object ReaderReleases {
    private val ids = TestIdProvider()
    private val homeId = ids.next()
    val guideId = ids.next()
    val goneId = ids.next()
    private val newId = ids.next()

    val r1 = mapOf(
        "index.md" to page(homeId, "Home"),
        "guides/_folder.yaml" to "title: Guides\n".toByteArray(),
        "guides/alpha.md" to page(guideId, "Alpha"),
        "guides/gone.md" to page(goneId, "Gone"),
        "assets/logo.svg" to "<svg xmlns=\"http://www.w3.org/2000/svg\"/>\n".toByteArray(),
        "meta-only/_folder.yaml" to "title: Meta\n".toByteArray(),
    )
    val r2 = mapOf(
        "index.md" to r1.getValue("index.md"),
        "guides/beta.md" to page(guideId, "Beta", listOf("/guides/alpha")),
        "guides/new.md" to page(newId, "New"),
        "assets/logo.svg" to r1.getValue("assets/logo.svg"),
    )
    val r3 = mapOf(
        "index.md" to r1.getValue("index.md"),
        "guides/gamma.md" to page(guideId, "Gamma", listOf("/guides/alpha", "/guides/beta")),
        "guides/new.md" to r2.getValue("guides/new.md"),
    )

    private fun page(id: PageId, title: String, redirects: List<String> = emptyList()): ByteArray = buildString {
        append("---\nid: ${id.value}\ntitle: $title\n")
        if (redirects.isNotEmpty()) append("redirect_from: [${redirects.joinToString(", ")}]\n")
        append("---\n\n# $title\n\n$title release content.\n")
    }.toByteArray()
}
