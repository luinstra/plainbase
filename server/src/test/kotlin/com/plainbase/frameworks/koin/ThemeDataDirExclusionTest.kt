package com.plainbase.frameworks.koin

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.page.ProposalId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.ProposalOperation
import com.plainbase.domain.repository.ProposalRepository
import com.plainbase.domain.repository.ProposalRow
import com.plainbase.domain.repository.ProposalStatus
import com.plainbase.domain.root.ObservationEpoch
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.IdProvider
import com.plainbase.domain.service.TestIdProvider
import com.plainbase.frameworks.config.ConfigBootInspector
import com.plainbase.frameworks.config.ConfigLoader
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.fixedPrincipal
import com.plainbase.frameworks.ktor.plainbaseModule
import com.plainbase.frameworks.ktor.restClient
import com.plainbase.frameworks.ktor.routes.bundleResourceBytes
import com.plainbase.frameworks.ktor.withExtract
import com.plainbase.frameworks.lifecycle.ServerResourceOwner
import com.plainbase.frameworks.runtime.ObservedIndexRuntime
import com.plainbase.frameworks.runtime.ServerOpeners
import com.plainbase.frameworks.runtime.prepareRootBootInputs
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.time.Instant

private val themeDataLogger = KotlinLogging.logger {}

private enum class ThemeDataLayout { PLAIN, SYMLINKED_ROOT }

private data class ThemeDataDirFixture(
    val root: Path,
    val dataDir: Path,
    val indexId: String,
    val paper: ByteArray,
    val custom: ByteArray,
)

private fun ThemeDataDirFixture.childNames(stage: String): Set<String> {
    val raw = Files.list(dataDir).use { children -> children.map { it.fileName.toString() }.sorted().toList() }
    val sqlite = setOf("plainbase.db", "search.db", "discussions.db").flatMap { name ->
        listOf(name, "$name-wal", "$name-shm", "$name-journal")
    }.toSet()
    val filtered = raw.filterNot { it in sqlite }.toSet()
    themeDataLogger.info { "Theme data children $stage: raw=$raw, filtered=$filtered" }
    return filtered
}

private fun ByteArray.containsThemeBytes(needle: ByteArray): Boolean =
    indices.any { offset -> offset + needle.size <= size && needle.indices.all { this[offset + it] == needle[it] } }

private suspend fun withThemeDataDir(
    layout: ThemeDataLayout = ThemeDataLayout.PLAIN,
    plant: (ThemeDataDirFixture) -> Unit = {},
    prepareProposals: (ProposalRepository) -> Unit = {},
    block: suspend ApplicationTestBuilder.(ThemeDataDirFixture) -> Unit,
) {
    val base = Files.createTempDirectory("plainbase-theme-data").toRealPath()
    val owner = ServerResourceOwner()
    try {
        val root = Files.createDirectories(base.resolve(if (layout == ThemeDataLayout.PLAIN) "R" else "real"))
        val declaredRoot = when (layout) {
            ThemeDataLayout.PLAIN -> root
            ThemeDataLayout.SYMLINKED_ROOT -> Files.createSymbolicLink(base.resolve("alias"), root)
        }
        val dataDir = Files.createDirectories(root.resolve("data"))
        val visible = Files.createDirectories(root.resolve("visible"))
        val indexId = "01900000-0000-7000-8000-0000000000a1"
        Files.writeString(root.resolve("index.md"), "---\nid: $indexId\n---\n# Home\n")
        Files.writeString(dataDir.resolve("plainbase.conf"), "theme.light = \"paper.css\"\n")
        val paper = "/* data-light */".toByteArray()
        val custom = "/* data-overr */".toByteArray()
        Files.write(dataDir.resolve("paper.css"), paper)
        Files.write(dataDir.resolve("custom.css"), custom)
        Files.writeString(visible.resolve("paper.css"), "/* view-light */")
        Files.writeString(visible.resolve("custom.css"), "/* view-overr */")
        val fixture = ThemeDataDirFixture(root, dataDir, indexId, paper, custom)
        plant(fixture)
        val config = ConfigLoader.fromEnvAndFile(
            mapOf(
                "CONTENT_DIR" to declaredRoot.toString(),
                "DATA_DIR" to declaredRoot.resolve("data").toString(),
                "PLAINBASE_GIT_ENABLED" to "false",
            ),
        )
        val refusals = ConfigBootInspector.bootRefusals(config)
        themeDataLogger.info {
            "Theme data layout=$layout, contentDir=${config.mainContentRoot()}, dataDir=${config.dataDir}, bootRefusals=$refusals"
        }
        refusals.shouldBeEmpty()
        val openers = ServerOpeners()
        val inputs = prepareRootBootInputs(config, openers.openLocal)
        val ids = TestIdProvider()
        val app = createOwnedTestKoinApplication(
            owner,
            listOf(
                module { single { config } },
                createContentModule(config, inputs, openers.openObject, { it.close() }, owner),
                repositoryModule(owner),
                securityModule,
                createHistoryModule(config, inputs.history, owner),
                indexModule,
                module { single<IdProvider> { ids } },
                checkpointModule,
                searchModule(owner),
                createDiscussionModule(owner),
                restModule(owner),
            ),
        )
        inputs.signals.arm(app.koin.get<ObservationEpoch>()::broke)
        app.koin.get<ObservedIndexRuntime>().builder.rebuild()
        prepareProposals(app.koin.get<ProposalRepository>())
        val context = app.koin.get<RouteContext>().withExtract(fixedPrincipal(Principal.Human("builtin", "theme-probe")))
        testApplication {
            application { plainbaseModule(context) }
            block(fixture)
        }
    } finally {
        try {
            owner.close()
        } finally {
            base.toFile().deleteRecursively()
        }
    }
}

private suspend fun verifyThemeDataCreate(layout: ThemeDataLayout) {
    withThemeDataDir(layout) { fixture ->
        val before = fixture.childNames("create $layout before")
        val client = restClient()
        val control = client.post("/api/v1/pages") {
            contentType(ContentType.Application.Json)
            setBody("""{"root":"docs","title":"Probe","folder":"visible"}""")
        }
        val attempt = client.post("/api/v1/pages") {
            contentType(ContentType.Application.Json)
            setBody("""{"root":"docs","title":"Probe","folder":"data"}""")
        }
        val errorCode = Json.parseToJsonElement(attempt.bodyAsText()).jsonObject["error"]
            ?.jsonObject?.get("code")?.jsonPrimitive?.content
        val controlExists = Files.exists(fixture.root.resolve("visible/probe.md"), NOFOLLOW_LINKS)
        val attemptAbsent = Files.notExists(fixture.dataDir.resolve("probe.md"), NOFOLLOW_LINKS)
        val after = fixture.childNames("create $layout after")
        themeDataLogger.info {
            "Theme data create layout=$layout: controlStatus=${control.status.value}, controlExists=$controlExists, " +
                "attemptStatus=${attempt.status.value}, errorCode=$errorCode, attemptAbsent=$attemptAbsent"
        }
        assertSoftly {
            withClue("visible create status") { control.status shouldBe HttpStatusCode.Created }
            withClue("visible create destination") { controlExists shouldBe true }
            withClue("data create status") { attempt.status shouldBe HttpStatusCode.BadRequest }
            withClue("data create error code") { errorCode shouldBe "invalid_create_request" }
            withClue("data create destination absent") { attemptAbsent shouldBe true }
            withClue("data children unchanged") { after shouldBe before }
        }
    }
}

private fun pendingThemeCreate(id: ProposalId, pageId: PageId, folder: String) = ProposalRow(
    id = id,
    operation = ProposalOperation.CREATE,
    pageId = pageId,
    root = RootName.require("docs"),
    baseHash = null,
    targetPath = TreePath.require("$folder/probe-apply.md"),
    proposedContent = "---\nid: ${pageId.value}\n---\n# Probe apply\n".toByteArray(),
    rationale = "Create a page",
    diffArtifact = "",
    status = ProposalStatus.PENDING,
    authorIssuer = "builtin",
    authorExternalId = "theme-probe",
    authorLabel = "theme-probe",
    approverIssuer = null,
    approverExternalId = null,
    decisionComment = null,
    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    decidedAt = null,
    appliedCommit = null,
    statusReason = null,
)

class ThemeDataDirExclusionTest : FunSpec({
    test("the production graph serves selected and override themes from the data directory") {
        withThemeDataDir { fixture ->
            val client = restClient()
            val light = client.get("/_plainbase/theme-light.css")
            val custom = client.get("/_plainbase/custom.css")
            val dark = client.get("/_plainbase/theme-dark.css")
            assertSoftly {
                withClue("selected theme status") { light.status shouldBe HttpStatusCode.OK }
                withClue("selected theme bytes") { light.bodyAsBytes() shouldBe fixture.paper }
                withClue("override status") { custom.status shouldBe HttpStatusCode.OK }
                withClue("override bytes") { custom.bodyAsBytes() shouldBe fixture.custom }
                withClue("unselected theme status") { dark.status shouldBe HttpStatusCode.OK }
                withClue("unselected theme bytes") {
                    dark.bodyAsBytes() shouldBe requireNotNull(bundleResourceBytes("_plainbase/plainbase-theme-dark.css"))
                }
            }
        }
    }

    test("a root asset upload does not replace the data directory override") {
        withThemeDataDir { fixture ->
            val uploaded = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)
            val client = restClient()
            val upload = client.post("/api/v1/pages/${fixture.indexId}/assets?filename=custom.css") { setBody(uploaded) }
            val custom = client.get("/_plainbase/custom.css")
            assertSoftly {
                withClue("upload status") { upload.status shouldBe HttpStatusCode.Created }
                withClue("uploaded asset bytes") { Files.readAllBytes(fixture.root.resolve("custom.css")) shouldBe uploaded }
                withClue("override status") { custom.status shouldBe HttpStatusCode.OK }
                withClue("override bytes") { custom.bodyAsBytes() shouldBe fixture.custom }
            }
        }
    }

    test("data directory stylesheets are unavailable as content assets") {
        withThemeDataDir { fixture ->
            val before = fixture.childNames("read before")
            val client = restClient()
            val controls = listOf("custom.css", "paper.css").associateWith { name -> client.get("/assets/docs/visible/$name") }
            val attempts = listOf("custom.css", "paper.css").associateWith { name -> client.get("/assets/docs/data/$name") }
            val after = fixture.childNames("read after")
            assertSoftly {
                for ((name, expected) in listOf("custom.css" to fixture.custom, "paper.css" to fixture.paper)) {
                    val control = controls.getValue(name)
                    val attempt = attempts.getValue(name)
                    val visible = if (name == "custom.css") "/* view-overr */" else "/* view-light */"
                    withClue("visible $name status") { control.status shouldBe HttpStatusCode.OK }
                    withClue("visible $name content type") { control.contentType()?.withoutParameters() shouldBe ContentType.Text.CSS }
                    withClue("visible $name bytes") { control.bodyAsBytes() shouldBe visible.toByteArray() }
                    withClue("data $name status") { attempt.status shouldBe HttpStatusCode.NotFound }
                    withClue("data $name not served as CSS") { attempt.contentType()?.withoutParameters() shouldNotBe ContentType.Text.CSS }
                    withClue("data $name has no sentinel bytes") { attempt.bodyAsBytes().containsThemeBytes(expected) shouldBe false }
                }
                withClue("data children unchanged") { after shouldBe before }
            }
        }
    }

    test("page creation refuses the data directory") {
        verifyThemeDataCreate(ThemeDataLayout.PLAIN)
    }

    test("page creation refuses the data directory through a symlinked content root") {
        verifyThemeDataCreate(ThemeDataLayout.SYMLINKED_ROOT)
    }

    test("asset upload refuses a page planted in the data directory") {
        val ids = TestIdProvider(101)
        val attemptId = ids.next()
        val controlId = ids.next()
        withThemeDataDir(
            plant = { fixture ->
                for ((folder, id) in listOf("data" to attemptId, "visible" to controlId)) {
                    Files.writeString(fixture.root.resolve("$folder/probe.md"), "---\nid: ${id.value}\n---\n# Probe\n")
                }
            },
        ) { fixture ->
            val before = fixture.childNames("upload before")
            val uploaded = "/* uploaded asset */".toByteArray()
            val client = restClient()
            val control = client.post("/api/v1/pages/${controlId.value}/assets?filename=probe-asset.css") { setBody(uploaded) }
            val attempt = client.post("/api/v1/pages/${attemptId.value}/assets?filename=probe-asset.css") { setBody(uploaded) }
            val errorCode = Json.parseToJsonElement(attempt.bodyAsText()).jsonObject["error"]
                ?.jsonObject?.get("code")?.jsonPrimitive?.content
            val controlExists = Files.exists(fixture.root.resolve("visible/probe-asset.css"), NOFOLLOW_LINKS)
            val attemptAbsent = Files.notExists(fixture.dataDir.resolve("probe-asset.css"), NOFOLLOW_LINKS)
            val after = fixture.childNames("upload after")
            themeDataLogger.info {
                "Theme data upload: controlStatus=${control.status.value}, controlExists=$controlExists, " +
                    "attemptStatus=${attempt.status.value}, errorCode=$errorCode, attemptAbsent=$attemptAbsent"
            }
            assertSoftly {
                withClue("visible upload status") { control.status shouldBe HttpStatusCode.Created }
                withClue("visible upload destination") { controlExists shouldBe true }
                withClue("data upload status") { attempt.status shouldBe HttpStatusCode.NotFound }
                withClue("data upload error code") { errorCode shouldBe "page_not_found" }
                withClue("data upload destination absent") { attemptAbsent shouldBe true }
                withClue("data children unchanged") { after shouldBe before }
            }
        }
    }

    test("proposal approval refuses a create targeting the data directory") {
        val ids = TestIdProvider(201)
        val attemptPageId = ids.next()
        val controlPageId = ids.next()
        val attemptId = ProposalId.require(ids.next().value)
        val controlId = ProposalId.require(ids.next().value)
        lateinit var proposals: ProposalRepository
        withThemeDataDir(
            prepareProposals = { repository ->
                proposals = repository
                proposals.insert(pendingThemeCreate(attemptId, attemptPageId, "data"))
                proposals.insert(pendingThemeCreate(controlId, controlPageId, "visible"))
            },
        ) { fixture ->
            val before = fixture.childNames("apply before")
            val client = restClient()
            val control = client.post("/api/v1/changes/${controlId.value}/approve")
            val attempt = client.post("/api/v1/changes/${attemptId.value}/approve")
            val errorCode = Json.parseToJsonElement(attempt.bodyAsText()).jsonObject["error"]
                ?.jsonObject?.get("code")?.jsonPrimitive?.content
            val controlExists = Files.exists(fixture.root.resolve("visible/probe-apply.md"), NOFOLLOW_LINKS)
            val attemptAbsent = Files.notExists(fixture.dataDir.resolve("probe-apply.md"), NOFOLLOW_LINKS)
            val attemptStatus = proposals.findById(attemptId)?.status
            val after = fixture.childNames("apply after")
            themeDataLogger.info {
                "Theme data apply: controlStatus=${control.status.value}, controlExists=$controlExists, " +
                    "attemptStatus=${attempt.status.value}, errorCode=$errorCode, " +
                    "attemptAbsent=$attemptAbsent, proposalStatus=$attemptStatus"
            }
            assertSoftly {
                withClue("visible apply status") { control.status shouldBe HttpStatusCode.OK }
                withClue("visible apply destination") { controlExists shouldBe true }
                withClue("data apply status") { attempt.status shouldBe HttpStatusCode.NotFound }
                withClue("data apply error code") { errorCode shouldBe "not_found" }
                withClue("data apply destination absent") { attemptAbsent shouldBe true }
                withClue("data apply proposal remains pending") { attemptStatus shouldBe ProposalStatus.PENDING }
                withClue("data children unchanged") { after shouldBe before }
            }
        }
    }
})
