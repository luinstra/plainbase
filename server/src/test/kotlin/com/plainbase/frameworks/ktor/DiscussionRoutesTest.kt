package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.ContentRead
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionPageSource
import com.plainbase.domain.discussion.DiscussionRows
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.discussion.EntryListing
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.discussion.IdentityDigest
import com.plainbase.domain.discussion.RowUpdate
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.principal.SubjectKey
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.repository.Role
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootedPageId
import com.plainbase.domain.root.RootedPath
import com.plainbase.domain.root.UnavailableCause
import com.plainbase.domain.service.AnchorMatches
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.ContentWriteMonitor
import com.plainbase.domain.service.DiscussionFullReads
import com.plainbase.domain.service.DiscussionIdProvider
import com.plainbase.domain.service.DiscussionPageResolver
import com.plainbase.domain.service.DiscussionReads
import com.plainbase.domain.service.DiscussionSyncState
import com.plainbase.domain.service.DiscussionWriter
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.ProposalAuthorLabeler
import com.plainbase.domain.service.SyncedDiscussionIndex
import com.plainbase.domain.service.UuidV7IdProvider
import com.plainbase.frameworks.discussion.DiscussionDb
import com.plainbase.frameworks.discussion.JdbcDiscussionRows
import com.plainbase.frameworks.discussion.seedTransportDiscussion
import com.plainbase.frameworks.filesystem.LocalDiscussionStore
import com.plainbase.frameworks.git.NoOpHistoryProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.testing.testApplication
import io.mockk.spyk
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

private class DiscussionHttpFixture(val harness: MultiRootRestHarness, rootPath: Path, enforced: Boolean = false) : AutoCloseable {
    private val dataDir = Files.createTempDirectory("plainbase-c4-http")
    private val db = DiscussionDb(dataDir.resolve("discussions.db"))
    private val rows: DiscussionRows = JdbcDiscussionRows(db)
    private val sync = DiscussionSyncState(harness.registry.roots.filter { it.supportsDiscussions }.map { it.name })
    private val localStore = LocalDiscussionStore(
        harness.registry.roots.filter { it.supportsDiscussions }.associate { it.name to (it.localPath ?: rootPath) },
    )
    val storeCalls = AtomicInteger()
    val failedListing = AtomicReference<DiscussionId?>(null)
    private val store: DiscussionStore = object : DiscussionStore by localStore {
        override fun read(root: RootName, id: DiscussionId, only: Set<EntryName>?) =
            localStore.read(root, id, only).also { storeCalls.incrementAndGet() }

        override fun listEntries(root: RootName, id: DiscussionId) =
            (if (id == failedListing.get()) EntryListing.Failed("private filesystem detail") else localStore.listEntries(root, id))
                .also { storeCalls.incrementAndGet() }

        override fun visit(root: RootName, visitor: (DiscussionId, Boolean) -> Unit) =
            localStore.visit(root, visitor).also { storeCalls.incrementAndGet() }
    }
    private val fullReads = DiscussionFullReads(store)
    private val index = SyncedDiscussionIndex(rows, store, fullReads, sync)
    val reads = spyk(DiscussionReads(rows, store, fullReads, sync, harness.availability))
    private val ids = object : DiscussionIdProvider {
        private val discussion = AtomicInteger(1)
        private val comment = AtomicInteger(101)
        override fun nextDiscussion() = com.plainbase.domain.discussion.DiscussionId.require(uuid(discussion.getAndIncrement()))
        override fun nextComment(): CommentId = CommentId.require(uuid(comment.getAndIncrement()))
    }
    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-09-28T12:00:00Z")
    }
    val facade: GuardedDiscussionFacade

    init {
        val source = DiscussionPageSource { root, ref ->
            val page = harness.builder.current.pageAt(RootedPageId(root, ref.pageId))
            if (page == null) {
                ContentRead.ConfirmedAbsent
            } else {
                harness.index.absence.read(harness.store(root.value), RootedPath(root, page.path))
            }
        }
        val writer = DiscussionWriter(ContentWriteMonitor(), store, source, { NoOpHistoryProvider }, index, ids, clock)
        val policy = PolicyService(
            roles = harness.index.roleRepository,
            apiTokens = harness.index.apiTokenRepository,
            audit = harness.audit,
            idProvider = UuidV7IdProvider(),
            clock = clock,
            enforced = enforced,
            editableOf = { harness.registry.byName(it)?.editable == true },
            objectBackendOf = { harness.registry.byName(it)?.backend is RootBackend.Object },
            discussionsEnabledOf = { harness.registry.byName(it)?.discussionsEnabled == true },
        )
        val projection = DiscussionReadProjection(
            reads, DiscussionPageResolver(sync, harness.availability, harness.index.absence),
            AnchorMatches(rows, store, fullReads, sync), harness.index.absence, harness.stores(),
        )
        facade = GuardedDiscussionFacade(
            policy, writer, reads, harness.registry, harness.availability, harness.index.resolver,
            harness.index.absence, harness.builder, harness.stores(),
            ProposalAuthorLabeler(harness.index.apiTokenRepository, harness.index.userRepository),
            CitationFactory(), projection,
        )
    }

    override fun close() {
        db.close()
        dataDir.toFile().deleteRecursively()
    }

    fun publish(root: RootName, id: DiscussionId) {
        index.publish(root, id, markerChanged = true) { store.read(root, id) }
    }

    fun failFacts(root: RootName, id: DiscussionId) {
        rows.writing { apply(root, id, RowUpdate.Failed("private facts failure"), null, false) }
    }

    private fun uuid(number: Int) = "01900000-0000-7000-8000-${number.toString(16).padStart(12, '0')}"
}

class DiscussionRoutesTest : FunSpec({
    test("read-only plus configured false preserves HTTP topology precedence without discussion I/O") {
        val root = Files.createTempDirectory("plainbase-discussions-readonly-disabled-rest")
        try {
            seedPage(root, "guide/example.md", "Example")
            MultiRootRestHarness(listOf(testRoot("docs", root).copy(editable = false, discussionsEnabled = false))).use { harness ->
                harness.boot()
                val page = harness.builder.current.pages.single()
                val id = DiscussionId.require("01900000-0000-7000-8000-000000000099")
                val comment = CommentId.require("01900000-0000-7000-8000-000000000098")
                seedTransportDiscussion(root, page, id, comment)
                val before = discussionFiles(root)
                DiscussionHttpFixture(harness, root).use { discussion ->
                    val context = harness.services.withExtract({ PrincipalExtraction.Resolved(Principal.Anonymous) }, discussion.facade)
                    testApplication {
                        application { plainbaseModule(context) }
                        listOf(
                            "/api/v1/discussions?root=docs",
                            "/api/v1/pages/${page.id.value}/discussions?root=docs",
                            "/api/v1/discussions/${id.value}?root=docs",
                        ).forEach { url ->
                            val response = client.get(url)
                            response.status shouldBe HttpStatusCode.OK
                            val envelope = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                            envelope.getValue("reason").jsonPrimitive.content shouldBe "read_only_root"
                            envelope.getValue("discussions_available").jsonPrimitive.content shouldBe "false"
                            envelope.getValue("next").jsonPrimitive.content shouldBe "null"
                            if (url.startsWith("/api/v1/discussions/${id.value}?")) {
                                envelope.getValue("discussion").jsonPrimitive.content shouldBe "null"
                                envelope.getValue("comments").jsonArray.size shouldBe 0
                            } else {
                                envelope.getValue("discussions").jsonArray.size shouldBe 0
                            }
                        }
                        val quote = """{"kind":"quote","content_hash":"${page.contentHash}","selected_text":"Example"}"""
                        val preview = client.post("/api/v1/pages/${page.id.value}/discussions/anchor-preview?root=docs") {
                            contentType(ContentType.Application.Json)
                            setBody(quote)
                        }
                        preview.status shouldBe HttpStatusCode.Forbidden
                        errorCode(preview.bodyAsText()) shouldBe "root_not_editable"
                        harness.audit.recent(10) shouldBe emptyList()
                        val purge = client.post("/api/v1/discussions/${id.value}/comments/${comment.value}/purge?root=docs") {
                            contentType(ContentType.Application.Json)
                            setBody("{}")
                        }
                        purge.status shouldBe HttpStatusCode.Forbidden
                        errorCode(purge.bodyAsText()) shouldBe "root_not_editable"
                        harness.audit.recent(10).single().decision shouldBe "denied"
                    }
                    discussion.storeCalls.get() shouldBe 0
                    verify(exactly = 0) { discussion.reads.claim(any(), any(), any()) }
                    verify(exactly = 0) { discussion.reads.detail(any(), any(), any(), any()) }
                    verify(exactly = 0) { discussion.reads.rootDiscussions(any(), any(), any(), any()) }
                    verify(exactly = 0) { discussion.reads.pageDiscussions(any(), any(), any(), any(), any()) }
                    discussionFiles(root) shouldBe before
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("disabled REST reads preview and eight writes preserve files and the missing-ID audit gate") {
        val root = Files.createTempDirectory("plainbase-discussions-disabled-rest")
        try {
            seedPage(root, "guide/example.md", "Example")
            MultiRootRestHarness(listOf(testRoot("docs", root).copy(discussionsEnabled = false))).use { harness ->
                harness.boot()
                val page = harness.builder.current.pages.single()
                val id = DiscussionId.require("01900000-0000-7000-8000-000000000099")
                val comment = CommentId.require("01900000-0000-7000-8000-000000000098")
                seedTransportDiscussion(root, page, id, comment)
                val before = discussionFiles(root)
                DiscussionHttpFixture(harness, root).use { discussion ->
                    val active = AtomicReference<Principal>(Principal.Anonymous)
                    val context = harness.services.withExtract({ PrincipalExtraction.Resolved(active.get()) }, discussion.facade)
                    testApplication {
                        application { plainbaseModule(context) }
                        listOf(
                            "/api/v1/discussions?root=docs",
                            "/api/v1/pages/${page.id.value}/discussions?root=docs",
                            "/api/v1/discussions/${id.value}?root=docs",
                        ).forEach { url ->
                            val response = client.get(url)
                            response.status shouldBe HttpStatusCode.OK
                            response.bodyAsText() shouldContain "disabled_by_config"
                            response.bodyAsText().contains("Preserved comment") shouldBe false
                        }
                        client.get("/api/v1/discussions/${id.value}").status shouldBe HttpStatusCode.NotFound
                        val anchor = """{"kind":"page","content_hash":"${page.contentHash}"}"""
                        val quote = """{"kind":"quote","content_hash":"${page.contentHash}","selected_text":"Example"}"""
                        val preview = client.post("/api/v1/pages/${page.id.value}/discussions/anchor-preview?root=docs") {
                            contentType(ContentType.Application.Json)
                            setBody(quote)
                        }
                        preview.status shouldBe HttpStatusCode.Forbidden
                        errorCode(preview.bodyAsText()) shouldBe "discussions_disabled"
                        harness.audit.recent(100).size shouldBe 0
                        val writes = listOf(
                            "/pages/${page.id.value}/discussions" to """{"anchor":$anchor,"body":"start"}""",
                            "/discussions/${id.value}/comments" to """{"body":"reply"}""",
                            "/discussions/${id.value}/comments/${comment.value}/edit" to """{"body":"edit"}""",
                            "/discussions/${id.value}/comments/${comment.value}/retract" to "{}",
                            "/discussions/${id.value}/resolve" to "{}",
                            "/discussions/${id.value}/reopen" to "{}",
                            "/discussions/${id.value}/reattach" to """{"anchor":$quote}""",
                            "/discussions/${id.value}/comments/${comment.value}/purge" to "{}",
                        )
                        writes.forEachIndexed { index, (url, body) ->
                            val response = client.post("/api/v1$url?root=docs") {
                                contentType(ContentType.Application.Json)
                                setBody(body)
                            }
                            response.status shouldBe HttpStatusCode.Forbidden
                            errorCode(response.bodyAsText()) shouldBe "discussions_disabled"
                            harness.audit.recent(100).size shouldBe index + 1
                            harness.audit.recent(1).single().decision shouldBe "denied"
                        }
                        writes.drop(1).forEach { (url, body) ->
                            val response = client.post("/api/v1$url") {
                                contentType(ContentType.Application.Json)
                                setBody(body)
                            }
                            response.status shouldBe HttpStatusCode.NotFound
                            errorCode(response.bodyAsText()) shouldBe "discussion_not_found"
                            harness.audit.recent(1).single().decision shouldBe "allowed"
                            harness.audit.recent(1).single().resource.startsWith("docs:") shouldBe false
                        }
                        active.set(Principal.Agent(harness.index.apiTokens.mint("read", AgentMode.READ_ONLY).id))
                        writes.drop(1).forEach { (url, body) ->
                            client.post("/api/v1$url") {
                                contentType(ContentType.Application.Json)
                                setBody(body)
                            }.status shouldBe HttpStatusCode.Forbidden
                            harness.audit.recent(1).single().decision shouldBe "denied"
                        }
                    }
                    discussion.storeCalls.get() shouldBe 0
                    discussionFiles(root) shouldBe before
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
    listOf(
        "arrays" to ("[".repeat(12_000) + "0" + "]".repeat(12_000)),
        "objects" to ("{\"next\":".repeat(12_000) + "0" + "}".repeat(12_000)),
    ).forEach { (kind, nested) ->
        test("deep $kind return invalid_request_body before audit or files") {
            val root = Files.createTempDirectory("plainbase-c4-deep-json")
            try {
                seedPage(root, "guide/example.md", "Example")
                MultiRootRestHarness(listOf(testRoot("docs", root))).use { harness ->
                    harness.boot()
                    DiscussionHttpFixture(harness, root).use { discussion ->
                        val page = harness.builder.current.byPath.getValue(
                            RootedPath(RootName.PRIMARY, TreePath.require("guide/example.md")),
                        )
                        val context = harness.services.withExtract(fixedPrincipal(Principal.Anonymous), discussion.facade)
                        testApplication {
                            application { plainbaseModule(context) }
                            val body = """{"anchor":{"kind":"page","content_hash":"${page.contentHash}"},"body":$nested}"""
                            (body.encodeToByteArray().size < 524_288) shouldBe true
                            val response = client.post("/api/v1/pages/${page.id.value}/discussions") {
                                contentType(ContentType.Application.Json)
                                setBody(body)
                            }
                            response.status shouldBe HttpStatusCode.BadRequest
                            errorCode(response.bodyAsText()) shouldBe "invalid_request_body"
                            harness.audit.recent(10).size shouldBe 0
                            discussionFiles(root) shouldBe emptyMap()
                        }
                    }
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    test("JSON delimiters escapes and duplicate body fields retain the final string") {
        val root = Files.createTempDirectory("plainbase-c4-json-string")
        try {
            seedPage(root, "guide/example.md", "Example")
            MultiRootRestHarness(listOf(testRoot("docs", root))).use { harness ->
                harness.boot()
                DiscussionHttpFixture(harness, root).use { discussion ->
                    val page = harness.builder.current.byPath.getValue(
                        RootedPath(RootName.PRIMARY, TreePath.require("guide/example.md")),
                    )
                    val context = harness.services.withExtract(fixedPrincipal(Principal.Anonymous), discussion.facade)
                    testApplication {
                        application { plainbaseModule(context) }
                        val delimiters = "{}[]".repeat(100)
                        val anchor = """{"kind":"page","content_hash":"${page.contentHash}"}"""
                        val body = """{"anchor":$anchor,"body":"old","body":"$delimiters \" \\ \u005b"}"""
                        val started = client.post("/api/v1/pages/${page.id.value}/discussions") {
                            contentType(ContentType.Application.Json)
                            setBody(body)
                        }
                        started.status shouldBe HttpStatusCode.Created
                        val id = Json.parseToJsonElement(started.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
                        val detail = client.get("/api/v1/discussions/$id?root=docs")
                        detail.status shouldBe HttpStatusCode.OK
                        Json.parseToJsonElement(detail.bodyAsText()).jsonObject.getValue("comments").jsonArray.single()
                            .jsonObject.getValue("markdown").jsonPrimitive.content shouldBe "$delimiters \" \\ ["
                        harness.audit.recent(10).size shouldBe 1
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("page path id wins over a valid query id for list and start") {
        val root = Files.createTempDirectory("plainbase-c4-page-path")
        try {
            seedPage(root, "guide/one.md", "One")
            seedPage(root, "guide/two.md", "Two")
            MultiRootRestHarness(listOf(testRoot("docs", root))).use { harness ->
                harness.boot()
                DiscussionHttpFixture(harness, root).use { discussion ->
                    val first = harness.builder.current.byPath.getValue(RootedPath(RootName.PRIMARY, TreePath.require("guide/one.md")))
                    val other = harness.builder.current.byPath.getValue(RootedPath(RootName.PRIMARY, TreePath.require("guide/two.md")))
                    val context = harness.services.withExtract(fixedPrincipal(Principal.Anonymous), discussion.facade)
                    testApplication {
                        application { plainbaseModule(context) }
                        val firstUrl = "/api/v1/pages/${first.id.value}/discussions"
                        val created = client.post(firstUrl) {
                            contentType(ContentType.Application.Json)
                            setBody("""{"anchor":{"kind":"page","content_hash":"${first.contentHash}"},"body":"first"}""")
                        }
                        created.status shouldBe HttpStatusCode.Created
                        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
                        val list = client.get("$firstUrl?id=${other.id.value}")
                        list.status shouldBe HttpStatusCode.OK
                        listDiscussionIds(list.bodyAsText()) shouldBe listOf(id)
                        val another = client.post("$firstUrl?id=${other.id.value}") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"anchor":{"kind":"page","content_hash":"${first.contentHash}"},"body":"second"}""")
                        }
                        another.status shouldBe HttpStatusCode.Created
                        listDiscussionIds(client.get(firstUrl).bodyAsText()).size shouldBe 2
                        listDiscussionIds(client.get("/api/v1/pages/${other.id.value}/discussions").bodyAsText()) shouldBe emptyList()
                        harness.audit.recent(10).first().resource shouldBe "docs:${first.id.value}/discussions"
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("discussion detail path id wins over a valid query id") {
        val root = Files.createTempDirectory("plainbase-c4-detail-path")
        try {
            seedPage(root, "guide/example.md", "Example")
            MultiRootRestHarness(listOf(testRoot("docs", root))).use { harness ->
                harness.boot()
                DiscussionHttpFixture(harness, root).use { discussion ->
                    val page = harness.builder.current.byPath.getValue(RootedPath(RootName.PRIMARY, TreePath.require("guide/example.md")))
                    val context = harness.services.withExtract(fixedPrincipal(Principal.Anonymous), discussion.facade)
                    testApplication {
                        application { plainbaseModule(context) }
                        suspend fun start(body: String): String {
                            val response = client.post("/api/v1/pages/${page.id.value}/discussions") {
                                contentType(ContentType.Application.Json)
                                setBody("""{"anchor":{"kind":"page","content_hash":"${page.contentHash}"},"body":"$body"}""")
                            }
                            response.status shouldBe HttpStatusCode.Created
                            return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
                        }
                        val first = start("first")
                        val other = start("other")
                        val detail = client.get("/api/v1/discussions/$first?id=$other")
                        detail.status shouldBe HttpStatusCode.OK
                        Json.parseToJsonElement(detail.bodyAsText()).jsonObject.getValue("discussion")
                            .jsonObject.getValue("id").jsonPrimitive.content shouldBe first
                        harness.audit.recent(10).size shouldBe 2
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    listOf("edit", "retract", "purge").forEach { action ->
        test("comment $action uses the path comment id despite a valid query id") {
            val root = Files.createTempDirectory("plainbase-c4-comment-path")
            try {
                seedPage(root, "guide/example.md", "Example")
                MultiRootRestHarness(listOf(testRoot("docs", root))).use { harness ->
                    harness.boot()
                    DiscussionHttpFixture(harness, root).use { discussion ->
                        val page = harness.builder.current.byPath.getValue(
                            RootedPath(RootName.PRIMARY, TreePath.require("guide/example.md")),
                        )
                        val context = harness.services.withExtract(fixedPrincipal(Principal.Anonymous), discussion.facade)
                        testApplication {
                            application { plainbaseModule(context) }
                            val started = client.post("/api/v1/pages/${page.id.value}/discussions") {
                                contentType(ContentType.Application.Json)
                                setBody("""{"anchor":{"kind":"page","content_hash":"${page.contentHash}"},"body":"first"}""")
                            }
                            started.status shouldBe HttpStatusCode.Created
                            val created = Json.parseToJsonElement(started.bodyAsText()).jsonObject
                            val id = created.getValue("id").jsonPrimitive.content
                            val target = created.getValue("comment_id").jsonPrimitive.content
                            val added = client.post("/api/v1/discussions/$id/comments") {
                                contentType(ContentType.Application.Json)
                                setBody("""{"body":"other"}""")
                            }
                            added.status shouldBe HttpStatusCode.Created
                            val other = Json.parseToJsonElement(added.bodyAsText()).jsonObject.getValue("comment_id").jsonPrimitive.content
                            val targetBytes = discussionFiles(root).filterKeys { it.contains(target) }
                            targetBytes.isNotEmpty() shouldBe true
                            val otherBytes = discussionFiles(root).filterKeys { it.contains(other) }
                            otherBytes.isNotEmpty() shouldBe true
                            val response = client.post("/api/v1/discussions/$id/comments/$target/$action?commentId=$other") {
                                contentType(ContentType.Application.Json)
                                if (action == "edit") setBody("""{"body":"changed"}""")
                            }
                            response.status shouldBe HttpStatusCode.OK
                            val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                            result.getValue("id").jsonPrimitive.content shouldBe id
                            result.getValue("comment_id").toString() shouldBe "null"
                            (discussionFiles(root).filterKeys { it.contains(target) } == targetBytes) shouldBe false
                            discussionFiles(root).filterKeys { it.contains(other) } shouldBe otherBytes
                            val audit = harness.audit.recent(10).first()
                            audit.resource shouldBe "docs:discussion/$id/comment/$target"
                            audit.action shouldBe if (action == "purge") "PURGE" else "DISCUSS"
                            harness.audit.recent(10).size shouldBe 3
                        }
                    }
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    test("discussion path id wins over query id for comment and malformed paths remain invalid") {
        val root = Files.createTempDirectory("plainbase-c4-discussion-path")
        try {
            seedPage(root, "guide/example.md", "Example")
            MultiRootRestHarness(listOf(testRoot("docs", root))).use { harness ->
                harness.boot()
                DiscussionHttpFixture(harness, root).use { discussion ->
                    val page = harness.builder.current.byPath.getValue(RootedPath(RootName.PRIMARY, TreePath.require("guide/example.md")))
                    val context = harness.services.withExtract(fixedPrincipal(Principal.Anonymous), discussion.facade)
                    testApplication {
                        application { plainbaseModule(context) }
                        suspend fun start(body: String): String {
                            val response = client.post("/api/v1/pages/${page.id.value}/discussions") {
                                contentType(ContentType.Application.Json)
                                setBody("""{"anchor":{"kind":"page","content_hash":"${page.contentHash}"},"body":"$body"}""")
                            }
                            response.status shouldBe HttpStatusCode.Created
                            return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
                        }
                        val first = start("first")
                        val other = start("other")
                        val otherBytes = discussionFiles(root).filterKeys { it.startsWith(other) }
                        otherBytes.isNotEmpty() shouldBe true
                        val added = client.post("/api/v1/discussions/$first/comments?id=$other") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"body":"new"}""")
                        }
                        added.status shouldBe HttpStatusCode.Created
                        Json.parseToJsonElement(added.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content shouldBe first
                        harness.audit.recent(10).first().resource shouldBe "docs:discussion/$first/comment"
                        discussionFiles(root).filterKeys { it.startsWith(other) } shouldBe otherBytes
                        val beforeInvalid = discussionFiles(root)
                        val auditCount = harness.audit.recent(10).size
                        val invalid = client.post("/api/v1/discussions/not-a-uuid/resolve?id=$first") {
                            contentType(ContentType.Application.Json)
                        }
                        invalid.status shouldBe HttpStatusCode.BadRequest
                        errorCode(invalid.bodyAsText()) shouldBe "invalid_request_body"
                        discussionFiles(root) shouldBe beforeInvalid
                        harness.audit.recent(10).size shouldBe auditCount
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("malformed Content-Type is a JSON 415 before audit") {
        val root = Files.createTempDirectory("plainbase-c4-media")
        try {
            seedPage(root, "guide/example.md", "Example")
            MultiRootRestHarness(listOf(testRoot("docs", root))).use { harness ->
                harness.boot()
                DiscussionHttpFixture(harness, root).use { discussion ->
                    val page = harness.builder.current.byPath.getValue(RootedPath(RootName.PRIMARY, TreePath.require("guide/example.md")))
                    val context = harness.services.withExtract(fixedPrincipal(Principal.Anonymous), discussion.facade)
                    val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { plainbaseModule(context) }
                    server.start(wait = false)
                    try {
                        val port = server.engine.resolvedConnectors().first().port
                        val response = Socket("127.0.0.1", port).use { socket ->
                            socket.getOutputStream().write(
                                "POST /api/v1/pages/${page.id.value}/discussions HTTP/1.1\r\n".toByteArray() +
                                    "Host: localhost\r\nContent-Type: not a content type\r\n".toByteArray() +
                                    "Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                            )
                            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
                        }
                        response.lineSequence().first() shouldContain " 415 "
                        response shouldContain "\"code\":\"unsupported_media_type\""
                        harness.audit.recent(10).size shouldBe 0
                        discussionFiles(root) shouldBe emptyMap()
                    } finally {
                        server.stopSuspend()
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("unknown discussion and comment targets refuse with precise codes before mutation") {
        val root = Files.createTempDirectory("plainbase-c4-missing")
        try {
            seedPage(root, "guide/example.md", "Example")
            MultiRootRestHarness(listOf(testRoot("docs", root))).use { harness ->
                harness.boot()
                DiscussionHttpFixture(harness, root).use { discussion ->
                    val page = harness.builder.current.byPath.getValue(RootedPath(RootName.PRIMARY, TreePath.require("guide/example.md")))
                    val context = harness.services.withExtract(fixedPrincipal(Principal.Anonymous), discussion.facade)
                    testApplication {
                        application { plainbaseModule(context) }
                        val missing = "01900000-0000-7000-8000-000000000999"
                        val absentDetail = client.get("/api/v1/discussions/$missing?root=docs")
                        absentDetail.status shouldBe HttpStatusCode.NotFound
                        errorCode(absentDetail.bodyAsText()) shouldBe "discussion_not_found"
                        val absentComment = client.post("/api/v1/discussions/$missing/comments?root=docs") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"body":"missing"}""")
                        }
                        absentComment.status shouldBe HttpStatusCode.NotFound
                        errorCode(absentComment.bodyAsText()) shouldBe "discussion_not_found"
                        val started = client.post("/api/v1/pages/${page.id.value}/discussions?root=docs") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"anchor":{"kind":"page","content_hash":"${page.contentHash}"},"body":"first"}""")
                        }
                        started.status shouldBe HttpStatusCode.Created
                        val id = Json.parseToJsonElement(started.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
                        val before = discussionFiles(root)
                        val absentEdit = client.post("/api/v1/discussions/$id/comments/$missing/edit?root=docs") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"body":"changed"}""")
                        }
                        absentEdit.status shouldBe HttpStatusCode.NotFound
                        errorCode(absentEdit.bodyAsText()) shouldBe "comment_not_found"
                        val auditCount = harness.audit.recent(10).size
                        val malformedPurge = client.post("/api/v1/discussions/$id/comments/malformed/purge?root=docs") {
                            contentType(ContentType.Application.Json)
                        }
                        malformedPurge.status shouldBe HttpStatusCode.BadRequest
                        errorCode(malformedPurge.bodyAsText()) shouldBe "invalid_request_body"
                        harness.audit.recent(10).size shouldBe auditCount
                        discussionFiles(root) shouldBe before
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("known duplicate discussion ids across roots are ambiguous until pinned") {
        val root = Files.createTempDirectory("plainbase-c4-duplicate-docs")
        val other = Files.createTempDirectory("plainbase-c4-duplicate-draft")
        try {
            seedPage(root, "guide/example.md", "Example")
            MultiRootRestHarness(listOf(testRoot("docs", root), testRoot("draft", other))).use { harness ->
                harness.boot()
                DiscussionHttpFixture(harness, root).use { discussion ->
                    val page = harness.builder.current.byPath.getValue(RootedPath(RootName.PRIMARY, TreePath.require("guide/example.md")))
                    val context = harness.services.withExtract(fixedPrincipal(Principal.Anonymous), discussion.facade)
                    testApplication {
                        application { plainbaseModule(context) }
                        val started = client.post("/api/v1/pages/${page.id.value}/discussions?root=docs") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"anchor":{"kind":"page","content_hash":"${page.contentHash}"},"body":"first"}""")
                        }
                        started.status shouldBe HttpStatusCode.Created
                        val id = Json.parseToJsonElement(started.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
                        val source = root.resolve(".plainbase/discussions/$id")
                        val target = other.resolve(".plainbase/discussions/$id")
                        Files.createDirectories(target)
                        Files.list(source).use { entries ->
                            entries.forEach { Files.copy(it, target.resolve(it.fileName), StandardCopyOption.COPY_ATTRIBUTES) }
                        }
                        discussion.publish(RootName.require("draft"), DiscussionId.require(id))
                        val beforeDocs = discussionFiles(root)
                        val beforeDraft = discussionFiles(other)
                        val detail = client.get("/api/v1/discussions/$id")
                        detail.status shouldBe HttpStatusCode.Conflict
                        errorCode(detail.bodyAsText()) shouldBe "ambiguous_discussion_id"
                        val comment = client.post("/api/v1/discussions/$id/comments") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"body":"no target"}""")
                        }
                        comment.status shouldBe HttpStatusCode.Conflict
                        errorCode(comment.bodyAsText()) shouldBe "ambiguous_discussion_id"
                        harness.audit.recent(10).first().resource shouldBe "discussion/$id/comment"
                        discussionFiles(root) shouldBe beforeDocs
                        discussionFiles(other) shouldBe beforeDraft
                        client.get("/api/v1/discussions/$id?root=docs").status shouldBe HttpStatusCode.OK
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
            other.toFile().deleteRecursively()
        }
    }

    test("failed detail read and failed indexed facts map to content_unreadable with a short retry") {
        val root = Files.createTempDirectory("plainbase-c4-failed-read")
        try {
            seedPage(root, "guide/example.md", "Example")
            MultiRootRestHarness(listOf(testRoot("docs", root))).use { harness ->
                harness.boot()
                DiscussionHttpFixture(harness, root).use { discussion ->
                    val page = harness.builder.current.byPath.getValue(RootedPath(RootName.PRIMARY, TreePath.require("guide/example.md")))
                    val context = harness.services.withExtract(fixedPrincipal(Principal.Anonymous), discussion.facade)
                    testApplication {
                        application { plainbaseModule(context) }
                        val started = client.post("/api/v1/pages/${page.id.value}/discussions?root=docs") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"anchor":{"kind":"page","content_hash":"${page.contentHash}"},"body":"first"}""")
                        }
                        started.status shouldBe HttpStatusCode.Created
                        val id = Json.parseToJsonElement(started.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
                        val parsed = DiscussionId.require(id)
                        val before = discussionFiles(root)
                        discussion.failedListing.set(parsed)
                        val detail = client.get("/api/v1/discussions/$id?root=docs")
                        detail.status shouldBe HttpStatusCode.ServiceUnavailable
                        errorCode(detail.bodyAsText()) shouldBe "content_unreadable"
                        detail.headers[HttpHeaders.RetryAfter] shouldBe "30"
                        discussion.failedListing.set(null)
                        discussion.failFacts(RootName.PRIMARY, parsed)
                        val comment = client.post("/api/v1/discussions/$id/comments?root=docs") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"body":"blocked"}""")
                        }
                        comment.status shouldBe HttpStatusCode.ServiceUnavailable
                        errorCode(comment.bodyAsText()) shouldBe "content_unreadable"
                        comment.headers[HttpHeaders.RetryAfter] shouldBe "30"
                        harness.audit.recent(10).first().resource shouldBe "docs:discussion/$id/comment"
                        discussionFiles(root) shouldBe before
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("resolved discussion rejects a new comment but permits the author's edit and retract") {
        val root = Files.createTempDirectory("plainbase-c4-resolved")
        try {
            seedPage(root, "guide/example.md", "Example")
            MultiRootRestHarness(listOf(testRoot("docs", root)), enforced = true).use { harness ->
                harness.boot()
                DiscussionHttpFixture(harness, root, enforced = true).use { discussion ->
                    val page = harness.builder.current.byPath.getValue(RootedPath(RootName.PRIMARY, TreePath.require("guide/example.md")))
                    val alice = Principal.Human("builtin", "alice")
                    val proposer = Principal.Agent(harness.index.apiTokens.mint("proposer", AgentMode.PROPOSE).id)
                    harness.index.roleRepository.upsert("builtin", "alice", Role.VIEWER, Clock.System.now())
                    val active = AtomicReference<Principal>(alice)
                    val context = harness.services.withExtract({ PrincipalExtraction.Resolved(active.get()) }, discussion.facade)
                    testApplication {
                        application { plainbaseModule(context) }
                        val started = client.post("/api/v1/pages/${page.id.value}/discussions?root=docs") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"anchor":{"kind":"page","content_hash":"${page.contentHash}"},"body":"first"}""")
                        }
                        started.status shouldBe HttpStatusCode.Created
                        val created = Json.parseToJsonElement(started.bodyAsText()).jsonObject
                        val id = created.getValue("id").jsonPrimitive.content
                        val commentId = created.getValue("comment_id").jsonPrimitive.content
                        active.set(proposer)
                        client.post("/api/v1/discussions/$id/resolve?root=docs") {
                            contentType(ContentType.Application.Json)
                        }.status shouldBe HttpStatusCode.OK
                        active.set(alice)
                        val refused = client.post("/api/v1/discussions/$id/comments?root=docs") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"body":"later"}""")
                        }
                        refused.status shouldBe HttpStatusCode.Conflict
                        errorCode(refused.bodyAsText()) shouldBe "discussion_resolved"
                        val commentUrl = "/api/v1/discussions/$id/comments/$commentId"
                        client.post("$commentUrl/edit?root=docs") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"body":"edited"}""")
                        }.status shouldBe HttpStatusCode.OK
                        client.post("$commentUrl/retract?root=docs") {
                            contentType(ContentType.Application.Json)
                        }.status shouldBe HttpStatusCode.OK
                        val audits = harness.audit.recent(10)
                        audits.size shouldBe 5
                        audits.map { it.resource } shouldBe listOf(
                            "docs:discussion/$id/comment/$commentId", "docs:discussion/$id/comment/$commentId",
                            "docs:discussion/$id/comment", "docs:discussion/$id", "docs:${page.id.value}/discussions",
                        )
                        audits.all { it.decision == "allowed" } shouldBe true
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("real HTTP writes publish immediately and preserve mutation nulls") {
        val root = Files.createTempDirectory("plainbase-c4-routes")
        try {
            seedPage(root, "guide/example.md", "Example", "A target sentence.")
            MultiRootRestHarness(listOf(testRoot("docs", root))).use { harness ->
                harness.boot()
                DiscussionHttpFixture(harness, root).use { discussion ->
                    val page = harness.builder.current.byPath.getValue(RootedPath(RootName.PRIMARY, TreePath.require("guide/example.md")))
                    val context = harness.services.withExtract(fixedPrincipal(Principal.Anonymous), discussion.facade)
                    testApplication {
                        application { plainbaseModule(context) }
                        val base = "/api/v1/pages/${page.id.value}/discussions"
                        val start = client.post(base) {
                            contentType(ContentType.Application.Json)
                            setBody("""{"anchor":{"kind":"page","content_hash":"${page.contentHash}"},"body":"old","body":"first"}""")
                        }
                        start.status shouldBe HttpStatusCode.Created
                        val started = Json.parseToJsonElement(start.bodyAsText()).jsonObject
                        started.getValue("comment_id").jsonPrimitive.content.length shouldBe 36
                        started.getValue("commit").toString() shouldBe "null"
                        val id = started.getValue("id").jsonPrimitive.content
                        started shouldBe RestGolden.load(
                            "discussion-mutation.json",
                            mapOf("discussion_id" to id, "comment_id" to started.getValue("comment_id").jsonPrimitive.content),
                        )
                        val list = client.get(base)
                        list.status shouldBe HttpStatusCode.OK
                        val contentHash = RestGolden.contentHashOf(root.resolve("guide/example.md"))
                        contentHash shouldBe page.contentHash
                        val substitutions = mapOf(
                            "discussion_id" to id,
                            "comment_id" to started.getValue("comment_id").jsonPrimitive.content,
                            "page_id" to page.id.value,
                            "content_hash" to contentHash,
                            "author_key" to IdentityDigest.of(SubjectKey.of(Principal.Anonymous)),
                        )
                        Json.parseToJsonElement(list.bodyAsText()) shouldBe
                            RestGolden.load("discussion-list-page.json", substitutions)
                        Json.parseToJsonElement(client.get("/api/v1/discussions?root=docs").bodyAsText()) shouldBe
                            RestGolden.load("discussion-list-page.json", substitutions)
                        val detail = client.get("/api/v1/discussions/$id?limit=1")
                        detail.status shouldBe HttpStatusCode.OK
                        Json.parseToJsonElement(detail.bodyAsText()) shouldBe
                            RestGolden.load("discussion-detail-page.json", substitutions)
                        val firstComments = Json.parseToJsonElement(detail.bodyAsText()).jsonObject.getValue("comments").jsonArray
                        firstComments.size shouldBe 1
                        firstComments.first().jsonObject.getValue("markdown").jsonPrimitive.content shouldBe "first"
                        val comment = client.post("/api/v1/discussions/$id/comments") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"body":"second"}""")
                        }
                        comment.status shouldBe HttpStatusCode.Created
                        val commentId = Json.parseToJsonElement(comment.bodyAsText()).jsonObject
                            .getValue("comment_id").jsonPrimitive.content
                        val edit = client.post("/api/v1/discussions/$id/comments/$commentId/edit") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"body":"changed"}""")
                        }
                        edit.status shouldBe HttpStatusCode.OK
                        Json.parseToJsonElement(edit.bodyAsText()).jsonObject.getValue("comment_id").toString() shouldBe "null"
                        client.post("/api/v1/discussions/$id/comments/$commentId/retract") {
                            contentType(ContentType.Application.Json)
                        }.status shouldBe HttpStatusCode.OK
                        client.post("/api/v1/discussions/$id/comments/$commentId/purge") {
                            contentType(ContentType.Application.Json)
                            setBody("{}")
                        }.status shouldBe HttpStatusCode.OK
                        client.post("/api/v1/discussions/$id/resolve") {
                            contentType(ContentType.Application.Json)
                        }.status shouldBe HttpStatusCode.OK
                        client.post("/api/v1/discussions/$id/reopen") {
                            contentType(ContentType.Application.Json)
                            setBody("{}")
                        }.status shouldBe HttpStatusCode.OK
                        val quote = """{"kind":"quote","content_hash":"${page.contentHash}","selected_text":"A target sentence."}"""
                        val preview = client.post("$base/anchor-preview") {
                            contentType(ContentType.Application.Json)
                            setBody(quote)
                        }
                        preview.status shouldBe HttpStatusCode.OK
                        Json.parseToJsonElement(preview.bodyAsText()) shouldBe
                            RestGolden.load("discussion-preview.json", substitutions)
                        val quoted = client.post(base) {
                            contentType(ContentType.Application.Json)
                            setBody("""{"anchor":$quote,"body":"quote thread"}""")
                        }
                        quoted.status shouldBe HttpStatusCode.Created
                        val quoteId = Json.parseToJsonElement(quoted.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
                        client.post("/api/v1/discussions/$quoteId/reattach") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"anchor":$quote}""")
                        }.status shouldBe HttpStatusCode.OK
                        client.post("/api/v1/discussions/$id/reattach") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"anchor":$quote}""")
                        }.status shouldBe HttpStatusCode.UnprocessableEntity
                        client.get("/api/v1/discussions?root=docs&state=page_level").status shouldBe HttpStatusCode.OK
                        harness.audit.recent(50).count { it.action == "DISCUSS" } shouldBe 9
                        harness.audit.recent(50).count { it.action == "PURGE" } shouldBe 1
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("body cap and malformed JSON stop before the audited facade") {
        val root = Files.createTempDirectory("plainbase-c4-preflight")
        try {
            seedPage(root, "guide/example.md", "Example")
            MultiRootRestHarness(listOf(testRoot("docs", root))).use { harness ->
                harness.boot()
                DiscussionHttpFixture(harness, root).use { discussion ->
                    val page = harness.builder.current.byPath
                        .getValue(RootedPath(RootName.PRIMARY, TreePath.require("guide/example.md")))
                    val pageId: PageId = page.id
                    val context = harness.services.withExtract(fixedPrincipal(Principal.Anonymous), discussion.facade)
                    testApplication {
                        application { plainbaseModule(context) }
                        val base = "/api/v1/pages/${pageId.value}/discussions"
                        client.post("$base?root=bad+root") {
                            contentType(ContentType.Application.Json)
                            setBody("x".repeat(524_289))
                        }.status shouldBe HttpStatusCode.PayloadTooLarge
                        client.post(base) {
                            contentType(ContentType.Application.Json)
                            setBody("{")
                        }.status shouldBe HttpStatusCode.BadRequest
                        harness.audit.recent(50).count { it.action == "DISCUSS" } shouldBe 0
                        val unregistered = client.post("$base?root=other") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"anchor":{"kind":"page","content_hash":"${page.contentHash}"},"body":"hello"}""")
                        }
                        unregistered.status shouldBe HttpStatusCode.BadRequest
                        Json.parseToJsonElement(unregistered.bodyAsText()) shouldBe RestGolden.load("discussion-invalid-root.json")
                        val rows = harness.audit.recent(50).filter { it.action == "DISCUSS" }
                        rows.size shouldBe 1
                        rows.single().resource shouldBe "${page.id.value}/discussions"
                        rows.single().decision shouldBe "allowed"
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("POST extraction and preflight refuse before gate or file access") {
        val root = Files.createTempDirectory("plainbase-c4-preflight-matrix")
        try {
            seedPage(root, "guide/example.md", "Example", "A target sentence.")
            MultiRootRestHarness(listOf(testRoot("docs", root)), enforced = true).use { harness ->
                harness.boot()
                DiscussionHttpFixture(harness, root, enforced = true).use { discussion ->
                    val page = harness.builder.current.byPath
                        .getValue(RootedPath(RootName.PRIMARY, TreePath.require("guide/example.md")))
                    val viewer = Principal.Human("builtin", "viewer")
                    harness.index.roleRepository.upsert("builtin", "viewer", Role.VIEWER, Clock.System.now())
                    val extraction = AtomicReference<PrincipalExtraction>(PrincipalExtraction.Resolved(viewer))
                    val context = harness.services.withExtract({ extraction.get() }, discussion.facade)
                    val base = "/api/v1/pages/${page.id.value}/discussions"
                    val valid = """{"anchor":{"kind":"page","content_hash":"${page.contentHash}"},"body":"hello"}"""
                    testApplication {
                        application { plainbaseModule(context) }
                        extraction.set(PrincipalExtraction.InsecureTransportRefused)
                        client.post("$base?root=bad+root") {
                            contentType(ContentType.Text.Plain)
                            setBody("not json")
                        }.status shouldBe HttpStatusCode.fromValue(421)
                        extraction.set(PrincipalExtraction.Resolved(viewer, ByteArray(32) { 7 }, Source.COOKIE))
                        val csrf = client.post(base) {
                            contentType(ContentType.Text.Plain)
                            setBody("not json")
                        }
                        csrf.status shouldBe HttpStatusCode.Forbidden
                        errorCode(csrf.bodyAsText()) shouldBe "csrf_failed"
                        extraction.set(PrincipalExtraction.Resolved(viewer))
                        val media = client.post(base) { setBody(valid) }
                        media.status shouldBe HttpStatusCode.UnsupportedMediaType
                        errorCode(media.bodyAsText()) shouldBe "unsupported_media_type"
                        val invalidCases = listOf(
                            "" to "invalid_request_body",
                            "{" to "invalid_request_body",
                            """{"anchor":{"kind":"page","content_hash":"${page.contentHash}"},"body":"hi","commit":"client"}""" to
                                "invalid_request_body",
                            """{"anchor":{"kind":"page","content_hash":"${page.contentHash}"},"body":"\uD800"}""" to
                                "invalid_utf8",
                        )
                        invalidCases.forEach { (body, expectedCode) ->
                            val response = client.post(base) {
                                contentType(ContentType.Application.Json)
                                setBody(body)
                            }
                            response.status shouldBe HttpStatusCode.BadRequest
                            errorCode(response.bodyAsText()) shouldBe expectedCode
                        }
                        val invalidBytes = client.post(base) {
                            contentType(ContentType.Application.Json)
                            setBody(byteArrayOf(0xC3.toByte(), 0x28))
                        }
                        invalidBytes.status shouldBe HttpStatusCode.BadRequest
                        errorCode(invalidBytes.bodyAsText()) shouldBe "invalid_utf8"
                        val oversizedSelection = client.post("$base/anchor-preview") {
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"kind":"quote","content_hash":"${page.contentHash}","selected_text":"${"q".repeat(16_385)}"}""",
                            )
                        }
                        oversizedSelection.status shouldBe HttpStatusCode.UnprocessableEntity
                        errorCode(oversizedSelection.bodyAsText()) shouldBe "anchor_too_large"
                        val oversizedComment = client.post(base) {
                            contentType(ContentType.Application.Json)
                            setBody(valid.replace("hello", "q".repeat(65_537)))
                        }
                        oversizedComment.status shouldBe HttpStatusCode.UnprocessableEntity
                        errorCode(oversizedComment.bodyAsText()) shouldBe "comment_too_large"
                        val tooLarge = client.post("$base?root=bad+root") {
                            contentType(ContentType.Application.Json)
                            setBody("x".repeat(524_289))
                        }
                        tooLarge.status shouldBe HttpStatusCode.PayloadTooLarge
                        errorCode(tooLarge.bodyAsText()) shouldBe "body_too_large"
                        harness.audit.recent(100).size shouldBe 0
                        discussionFiles(root) shouldBe emptyMap()
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("read-only topology returns unavailable reads without consulting discussion files") {
        val root = Files.createTempDirectory("plainbase-c4-readonly")
        try {
            seedPage(root, "guide/example.md", "Example")
            MultiRootRestHarness(listOf(testRoot("docs", root, editable = false))).use { harness ->
                harness.boot()
                DiscussionHttpFixture(harness, root).use { discussion ->
                    val page = harness.builder.current.byPath
                        .getValue(RootedPath(RootName.PRIMARY, TreePath.require("guide/example.md")))
                    val context = harness.services.withExtract(fixedPrincipal(Principal.Anonymous), discussion.facade)
                    testApplication {
                        application { plainbaseModule(context) }
                        val pageUrl = "/api/v1/pages/${page.id.value}/discussions"
                        listOf(pageUrl, "$pageUrl?root=docs", "/api/v1/discussions?root=docs").forEach { url ->
                            val response = client.get(url)
                            response.status shouldBe HttpStatusCode.OK
                            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                            body.getValue("discussions_available").jsonPrimitive.content shouldBe "false"
                            body.getValue("reason").jsonPrimitive.content shouldBe "read_only_root"
                        }
                        val detail = client.get("/api/v1/discussions/01900000-0000-7000-8000-000000000001?root=docs")
                        detail.status shouldBe HttpStatusCode.OK
                        Json.parseToJsonElement(detail.bodyAsText()).jsonObject
                            .getValue("discussions_available").jsonPrimitive.content shouldBe "false"
                        val quote = """{"kind":"quote","content_hash":"${page.contentHash}","selected_text":"body."}"""
                        client.post("$pageUrl/anchor-preview") {
                            contentType(ContentType.Application.Json)
                            setBody(quote)
                        }.status shouldBe HttpStatusCode.Forbidden
                        client.post(pageUrl) {
                            contentType(ContentType.Application.Json)
                            setBody("""{"anchor":{"kind":"page","content_hash":"${page.contentHash}"},"body":"hello"}""")
                        }.status shouldBe HttpStatusCode.Forbidden
                        discussion.storeCalls.get() shouldBe 0
                        val rows = harness.audit.recent(100)
                        rows.size shouldBe 1
                        rows.single().decision shouldBe "denied"
                        rows.single().resource shouldBe "docs:${page.id.value}/discussions"
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("revoked and expired bearer credentials refuse before discussion facade") {
        val root = Files.createTempDirectory("plainbase-c4-bearer")
        try {
            seedPage(root, "guide/example.md", "Example")
            MultiRootRestHarness(listOf(testRoot("docs", root)), enforced = true).use { harness ->
                harness.boot()
                DiscussionHttpFixture(harness, root, enforced = true).use { discussion ->
                    val page = harness.builder.current.byPath
                        .getValue(RootedPath(RootName.PRIMARY, TreePath.require("guide/example.md")))
                    val context = harness.services.withExtract(harness.services.extract, discussion.facade)
                    val token = harness.index.apiTokens.mint("revoked", AgentMode.PROPOSE)
                    val expired = harness.index.apiTokens.mint("expired", AgentMode.PROPOSE, Duration.ZERO)
                    val body = """{"anchor":{"kind":"page","content_hash":"${page.contentHash}"},"body":"hello"}"""
                    testApplication {
                        application { plainbaseModule(context) }
                        val url = "/api/v1/pages/${page.id.value}/discussions?root=docs"
                        client.get(url) { header(HttpHeaders.Authorization, "Bearer ${token.plaintext}") }
                            .status shouldBe HttpStatusCode.OK
                        harness.index.apiTokens.revoke(token.id)
                        listOf("Bearer ${token.plaintext}", "bEaReR  ${expired.plaintext}").forEach { authorization ->
                            val response = client.post(url) {
                                header(HttpHeaders.Authorization, authorization)
                                contentType(ContentType.Application.Json)
                                setBody(body)
                            }
                            response.status shouldBe HttpStatusCode.Unauthorized
                            client.get(url) { header(HttpHeaders.Authorization, authorization) }
                                .status shouldBe HttpStatusCode.Unauthorized
                        }
                        harness.audit.recent(100).size shouldBe 0
                        discussionFiles(root) shouldBe emptyMap()
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("configured body cap wins over valid comment size") {
        val root = Files.createTempDirectory("plainbase-c4-small-cap")
        try {
            seedPage(root, "guide/example.md", "Example")
            MultiRootRestHarness(listOf(testRoot("docs", root))).use { harness ->
                harness.boot()
                DiscussionHttpFixture(harness, root).use { discussion ->
                    val page = harness.builder.current.byPath
                        .getValue(RootedPath(RootName.PRIMARY, TreePath.require("guide/example.md")))
                    val context = harness.services.withExtract(fixedPrincipal(Principal.Anonymous), discussion.facade, writeBodyCap = 32)
                    testApplication {
                        application { plainbaseModule(context) }
                        val response = client.post("/api/v1/pages/${page.id.value}/discussions") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"anchor":{"kind":"page","content_hash":"${page.contentHash}"},"body":"hi"}""")
                        }
                        response.status shouldBe HttpStatusCode.PayloadTooLarge
                        val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
                        error.getValue("code").jsonPrimitive.content shouldBe "body_too_large"
                        error.getValue("max_bytes").jsonPrimitive.content shouldBe "32"
                        harness.audit.recent(100).size shouldBe 0
                        discussionFiles(root) shouldBe emptyMap()
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("page root and detail cursors preserve immediate visibility and preview ranges") {
        val root = Files.createTempDirectory("plainbase-c4-pagination")
        try {
            seedPage(root, "guide/example.md", "Example", "A target sentence.")
            MultiRootRestHarness(listOf(testRoot("docs", root))).use { harness ->
                harness.boot()
                DiscussionHttpFixture(harness, root).use { discussion ->
                    val page = harness.builder.current.byPath
                        .getValue(RootedPath(RootName.PRIMARY, TreePath.require("guide/example.md")))
                    val context = harness.services.withExtract(fixedPrincipal(Principal.Anonymous), discussion.facade)
                    testApplication {
                        application { plainbaseModule(context) }
                        val base = "/api/v1/pages/${page.id.value}/discussions"
                        suspend fun start(anchor: String): String {
                            val response = client.post(base) {
                                contentType(ContentType.Application.Json)
                                setBody("""{"anchor":$anchor,"body":"first"}""")
                            }
                            response.status shouldBe HttpStatusCode.Created
                            return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
                        }
                        val pageAnchor = """{"kind":"page","content_hash":"${page.contentHash}"}"""
                        val quote = """{"kind":"quote","content_hash":"${page.contentHash}","selected_text":"A target sentence."}"""
                        val preview = client.post("$base/anchor-preview") {
                            contentType(ContentType.Application.Json)
                            setBody(quote)
                        }
                        preview.status shouldBe HttpStatusCode.OK
                        val previewBody = Json.parseToJsonElement(preview.bodyAsText()).jsonObject
                        val first = start(pageAnchor)
                        start(pageAnchor)
                        val quoted = start(quote)
                        val firstPage = Json.parseToJsonElement(client.get("$base?limit=1").bodyAsText()).jsonObject
                        firstPage.getValue("discussions").jsonArray.size shouldBe 1
                        val cursor = firstPage.getValue("next").jsonPrimitive.content
                        val secondPage = Json.parseToJsonElement(client.get("$base?limit=1&cursor=$cursor").bodyAsText()).jsonObject
                        secondPage.getValue("discussions").jsonArray.size shouldBe 1
                        val rootPage = Json.parseToJsonElement(client.get("/api/v1/discussions?root=docs&limit=1").bodyAsText()).jsonObject
                        rootPage.getValue("next").jsonPrimitive.content shouldBe cursor
                        val pageLevel = Json.parseToJsonElement(
                            client.get("/api/v1/discussions?root=docs&state=page_level").bodyAsText(),
                        ).jsonObject.getValue("discussions").jsonArray
                        pageLevel.size shouldBe 2
                        val exact = Json.parseToJsonElement(
                            client.get("/api/v1/discussions?root=docs&state=exact").bodyAsText(),
                        ).jsonObject.getValue("discussions").jsonArray
                        exact.size shouldBe 1
                        exact.single().jsonObject.getValue("id").jsonPrimitive.content shouldBe quoted
                        exact.single().jsonObject.getValue("range_content_hash").jsonPrimitive.content shouldBe page.contentHash
                        pageLevel.forEach { it.jsonObject.getValue("range_content_hash").toString() shouldBe "null" }
                        val quotedDetail = Json.parseToJsonElement(client.get("/api/v1/discussions/$quoted").bodyAsText()).jsonObject
                        quotedDetail.getValue("discussion").jsonObject.getValue("range_content_hash").jsonPrimitive.content shouldBe
                            page.contentHash
                        val range = exact.single().jsonObject.getValue("range").jsonObject
                        range.getValue("byte_start") shouldBe previewBody.getValue("byte_start")
                        range.getValue("byte_end") shouldBe previewBody.getValue("byte_end")
                        repeat(7) { number ->
                            client.post("/api/v1/discussions/$first/comments") {
                                contentType(ContentType.Application.Json)
                                setBody("""{"body":"comment $number"}""")
                            }.status shouldBe HttpStatusCode.Created
                        }
                        val detailOne = Json.parseToJsonElement(client.get("/api/v1/discussions/$first?limit=1").bodyAsText()).jsonObject
                        detailOne.getValue("comments").jsonArray.size shouldBe 1
                        val commentCursor = detailOne.getValue("next").jsonPrimitive.content
                        val detailSeven = Json.parseToJsonElement(
                            client.get("/api/v1/discussions/$first?limit=7&cursor=$commentCursor").bodyAsText(),
                        ).jsonObject
                        detailSeven.getValue("comments").jsonArray.size shouldBe 7
                        detailSeven.getValue("next").toString() shouldBe "null"
                        val beforeStale = discussionFiles(root)
                        val stale = client.post(base) {
                            contentType(ContentType.Application.Json)
                            setBody("""{"anchor":{"kind":"page","content_hash":"sha256:${"0".repeat(64)}"},"body":"stale"}""")
                        }
                        stale.status shouldBe HttpStatusCode.Conflict
                        errorCode(stale.bodyAsText()) shouldBe "page_changed"
                        discussionFiles(root) shouldBe beforeStale
                        val audits = harness.audit.recent(100).filter { it.action == "DISCUSS" }
                        audits.size shouldBe 11
                        audits.first().decision shouldBe "allowed"
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("unknown claimant outranks a known holder until a root pin resolves it") {
        val root = Files.createTempDirectory("plainbase-c4-known")
        val other = Files.createTempDirectory("plainbase-c4-unknown")
        try {
            seedPage(root, "guide/example.md", "Example")
            MultiRootRestHarness(listOf(testRoot("docs", root), testRoot("draft", other))).use { harness ->
                harness.boot()
                DiscussionHttpFixture(harness, root).use { discussion ->
                    val page = harness.builder.current.byPath
                        .getValue(RootedPath(RootName.PRIMARY, TreePath.require("guide/example.md")))
                    val context = harness.services.withExtract(fixedPrincipal(Principal.Anonymous), discussion.facade)
                    testApplication {
                        application { plainbaseModule(context) }
                        val started = client.post("/api/v1/pages/${page.id.value}/discussions?root=docs") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"anchor":{"kind":"page","content_hash":"${page.contentHash}"},"body":"first"}""")
                        }
                        started.status shouldBe HttpStatusCode.Created
                        val id = Json.parseToJsonElement(started.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
                        val before = discussionFiles(root)
                        harness.availability.markUnavailable(RootName.require("draft"), UnavailableCause.VANISHED)
                        val unknown = client.post("/api/v1/discussions/$id/comments") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"body":"ambiguous ownership"}""")
                        }
                        unknown.status shouldBe HttpStatusCode.ServiceUnavailable
                        errorCode(unknown.bodyAsText()) shouldBe "root_unavailable"
                        unknown.headers[HttpHeaders.RetryAfter] shouldBe "300"
                        discussionFiles(root) shouldBe before
                        harness.audit.recent(100).first().resource shouldBe "discussion/$id/comment"
                        harness.audit.recent(100).first().decision shouldBe "allowed"
                        val pinned = client.post("/api/v1/discussions/$id/comments?root=docs") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"body":"known holder"}""")
                        }
                        pinned.status shouldBe HttpStatusCode.Created
                        harness.audit.recent(100).first().resource shouldBe "docs:discussion/$id/comment"
                        harness.availability.markUnavailable(RootName.PRIMARY, UnavailableCause.VANISHED)
                        val lost = client.post("/api/v1/discussions/$id/comments?root=docs") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"body":"root lost"}""")
                        }
                        lost.status shouldBe HttpStatusCode.ServiceUnavailable
                        errorCode(lost.bodyAsText()) shouldBe "root_unavailable"
                        lost.headers[HttpHeaders.RetryAfter] shouldBe "300"
                        harness.audit.recent(100).first().resource shouldBe "docs:discussion/$id/comment"
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
            other.toFile().deleteRecursively()
        }
    }
})

class DiscussionAuthzTest : FunSpec({
    test("enforced anonymous and read-only agent writes audit one denial without touching files") {
        val root = Files.createTempDirectory("plainbase-c4-authz")
        try {
            seedPage(root, "guide/example.md", "Example")
            MultiRootRestHarness(listOf(testRoot("docs", root)), enforced = true).use { harness ->
                harness.boot()
                DiscussionHttpFixture(harness, root, enforced = true).use { discussion ->
                    val page = harness.builder.current.byPath
                        .getValue(RootedPath(RootName.PRIMARY, TreePath.require("guide/example.md")))
                    val readonly = Principal.Agent(harness.index.apiTokens.mint(label = "read", mode = AgentMode.READ_ONLY).id)
                    val body = """{"anchor":{"kind":"page","content_hash":"${page.contentHash}"},"body":"hello"}"""
                    listOf(Principal.Anonymous to HttpStatusCode.Unauthorized, readonly to HttpStatusCode.Forbidden)
                        .forEach { (principal, expected) ->
                            val ctx = harness.services.withExtract(fixedPrincipal(principal), discussion.facade)
                            testApplication {
                                application { plainbaseModule(ctx) }
                                val url = "/api/v1/pages/${page.id.value}/discussions?root=docs"
                                client.post(url) {
                                    contentType(ContentType.Application.Json)
                                    setBody(body)
                                }.status shouldBe expected
                                client.get(url).status shouldBe if (principal is Principal.Anonymous) {
                                    HttpStatusCode.Unauthorized
                                } else {
                                    HttpStatusCode.OK
                                }
                            }
                        }
                    val rows = harness.audit.recent(50).filter { it.action == "DISCUSS" }
                    rows.size shouldBe 2
                    rows.map { it.decision } shouldBe listOf("denied", "denied")
                    rows.map { it.resource } shouldBe listOf(
                        "docs:${page.id.value}/discussions", "docs:${page.id.value}/discussions",
                    )
                    Files.exists(root.resolve(".plainbase/discussions")) shouldBe false
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("enforced role and agent decisions use one rooted audit row and preserve denied files") {
        val root = Files.createTempDirectory("plainbase-c4-authz-matrix")
        try {
            seedPage(root, "guide/example.md", "Example", "A target sentence.")
            MultiRootRestHarness(listOf(testRoot("docs", root)), enforced = true).use { harness ->
                harness.boot()
                DiscussionHttpFixture(harness, root, enforced = true).use { discussion ->
                    val page = harness.builder.current.byPath
                        .getValue(RootedPath(RootName.PRIMARY, TreePath.require("guide/example.md")))
                    val alice = Principal.Human("builtin", "alice")
                    val bob = Principal.Human("builtin", "bob")
                    val admin = Principal.Human("builtin", "admin")
                    harness.index.roleRepository.upsert("builtin", "alice", Role.VIEWER, Clock.System.now())
                    harness.index.roleRepository.upsert("builtin", "bob", Role.VIEWER, Clock.System.now())
                    harness.index.roleRepository.upsert("builtin", "admin", Role.ADMIN, Clock.System.now())
                    val readonly = Principal.Agent(harness.index.apiTokens.mint("read", AgentMode.READ_ONLY).id)
                    val propose = Principal.Agent(harness.index.apiTokens.mint("propose", AgentMode.PROPOSE).id)
                    val commit = Principal.Agent(harness.index.apiTokens.mint("commit", AgentMode.COMMIT).id)
                    val active = AtomicReference<Principal>(alice)
                    val context = harness.services.withExtract({ PrincipalExtraction.Resolved(active.get()) }, discussion.facade)
                    testApplication {
                        application { plainbaseModule(context) }
                        val base = "/api/v1/pages/${page.id.value}/discussions?root=docs"
                        val startBody = """{"anchor":{"kind":"page","content_hash":"${page.contentHash}"},"body":"first"}"""
                        suspend fun postAs(principal: Principal, url: String, body: String = "{}"): io.ktor.client.statement.HttpResponse {
                            active.set(principal)
                            val before = harness.audit.recent(100).size
                            val files = discussionFiles(root)
                            val response = client.post(url) {
                                contentType(ContentType.Application.Json)
                                setBody(body)
                            }
                            val rows = harness.audit.recent(100)
                            rows.size shouldBe before + 1
                            val expectedDecision = if (
                                response.status == HttpStatusCode.Forbidden || response.status == HttpStatusCode.Unauthorized
                            ) {
                                "denied"
                            } else {
                                "allowed"
                            }
                            rows.first().decision shouldBe expectedDecision
                            rows.first().action shouldBe if (url.substringBefore('?').endsWith("/purge")) {
                                "PURGE"
                            } else {
                                "DISCUSS"
                            }
                            if (rows.first().decision == "denied") discussionFiles(root) shouldBe files
                            return response
                        }
                        val started = postAs(alice, base, startBody)
                        started.status shouldBe HttpStatusCode.Created
                        val id = Json.parseToJsonElement(started.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
                        val discussionUrl = "/api/v1/discussions/$id?root=docs"
                        harness.audit.recent(100).first().resource shouldBe "docs:${page.id.value}/discussions"
                        postAs(readonly, "/api/v1/discussions/$id/comments?root=docs", """{"body":"no"}""").status shouldBe
                            HttpStatusCode.Forbidden
                        active.set(readonly)
                        val beforeReads = harness.audit.recent(100).size
                        client.get(base).status shouldBe HttpStatusCode.OK
                        harness.audit.recent(100).size shouldBe beforeReads
                        val commented = postAs(bob, "/api/v1/discussions/$id/comments?root=docs", """{"body":"bob's"}""")
                        commented.status shouldBe HttpStatusCode.Created
                        val commentId = Json.parseToJsonElement(commented.bodyAsText()).jsonObject
                            .getValue("comment_id").jsonPrimitive.content
                        val commentUrl = "/api/v1/discussions/$id/comments/$commentId"
                        harness.audit.recent(100).first().resource shouldBe "docs:discussion/$id/comment"
                        postAs(admin, "$commentUrl/edit?root=docs", """{"body":"admin edit"}""").status shouldBe
                            HttpStatusCode.Forbidden
                        harness.audit.recent(100).first().resource shouldBe "docs:discussion/$id/comment/$commentId"
                        postAs(bob, "$commentUrl/edit?root=docs", """{"body":"bob edit"}""").status shouldBe HttpStatusCode.OK
                        postAs(bob, "/api/v1/discussions/$id/resolve?root=docs").status shouldBe HttpStatusCode.Forbidden
                        postAs(propose, "/api/v1/discussions/$id/resolve?root=docs").status shouldBe HttpStatusCode.OK
                        postAs(commit, "/api/v1/discussions/$id/reopen?root=docs").status shouldBe HttpStatusCode.OK
                        val quote = """{"kind":"quote","content_hash":"${page.contentHash}","selected_text":"A target sentence."}"""
                        postAs(commit, "/api/v1/discussions/$id/reattach?root=docs", """{"anchor":$quote}""").status shouldBe
                            HttpStatusCode.Forbidden
                        postAs(alice, "/api/v1/discussions/$id/reattach?root=docs", """{"anchor":$quote}""").status shouldBe
                            HttpStatusCode.UnprocessableEntity
                        postAs(alice, "$commentUrl/purge?root=docs").status shouldBe HttpStatusCode.Forbidden
                        postAs(admin, "$commentUrl/purge?root=docs").status shouldBe HttpStatusCode.OK
                        harness.audit.recent(100).first().resource shouldBe "docs:discussion/$id/comment/$commentId"
                        active.set(alice)
                        val beforePreview = harness.audit.recent(100).size
                        client.post("/api/v1/pages/${page.id.value}/discussions/anchor-preview?root=docs") {
                            contentType(ContentType.Application.Json)
                            setBody(quote)
                        }.status shouldBe HttpStatusCode.OK
                        client.get(discussionUrl).status shouldBe HttpStatusCode.OK
                        harness.audit.recent(100).size shouldBe beforePreview
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("off mode permits anonymous purge but still restricts agents") {
        val root = Files.createTempDirectory("plainbase-c4-authz-off")
        try {
            seedPage(root, "guide/example.md", "Example")
            MultiRootRestHarness(listOf(testRoot("docs", root))).use { harness ->
                harness.boot()
                DiscussionHttpFixture(harness, root).use { discussion ->
                    val page = harness.builder.current.byPath
                        .getValue(RootedPath(RootName.PRIMARY, TreePath.require("guide/example.md")))
                    val active = AtomicReference<Principal>(Principal.Anonymous)
                    val context = harness.services.withExtract({ PrincipalExtraction.Resolved(active.get()) }, discussion.facade)
                    testApplication {
                        application { plainbaseModule(context) }
                        val started = client.post("/api/v1/pages/${page.id.value}/discussions?root=docs") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"anchor":{"kind":"page","content_hash":"${page.contentHash}"},"body":"hello"}""")
                        }
                        started.status shouldBe HttpStatusCode.Created
                        val created = Json.parseToJsonElement(started.bodyAsText()).jsonObject
                        val id = created.getValue("id").jsonPrimitive.content
                        val commentId = created.getValue("comment_id").jsonPrimitive.content
                        val agent = Principal.Agent(harness.index.apiTokens.mint("agent", AgentMode.COMMIT).id)
                        active.set(agent)
                        val before = harness.audit.recent(100).size
                        val denied = client.post("/api/v1/discussions/$id/comments/$commentId/purge?root=docs") {
                            contentType(ContentType.Application.Json)
                        }
                        denied.status shouldBe HttpStatusCode.Forbidden
                        harness.audit.recent(100).size shouldBe before + 1
                        harness.audit.recent(100).first().action shouldBe "PURGE"
                        harness.audit.recent(100).first().decision shouldBe "denied"
                        active.set(Principal.Anonymous)
                        client.post("/api/v1/discussions/$id/comments/$commentId/purge?root=docs") {
                            contentType(ContentType.Application.Json)
                        }.status shouldBe HttpStatusCode.OK
                        harness.audit.recent(100).first().decision shouldBe "allowed"
                        harness.audit.recent(100).first().resource shouldBe "docs:discussion/$id/comment/$commentId"
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})

private fun discussionFiles(root: Path): Map<String, String> {
    val directory = root.resolve(".plainbase/discussions")
    if (!Files.exists(directory)) return emptyMap()
    return Files.walk(directory).use { entries ->
        entries.filter(Files::isRegularFile).toList().associate { path ->
            directory.relativize(path).toString() to java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(path))
        }
    }
}

private fun errorCode(body: String): String = Json.parseToJsonElement(body).jsonObject
    .getValue("error").jsonObject.getValue("code").jsonPrimitive.content

private fun listDiscussionIds(body: String): List<String> = Json.parseToJsonElement(body).jsonObject
    .getValue("discussions").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
