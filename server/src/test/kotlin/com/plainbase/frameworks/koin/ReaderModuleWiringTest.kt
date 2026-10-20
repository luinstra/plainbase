package com.plainbase.frameworks.koin

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageIndex
import com.plainbase.domain.page.RootSection
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.ApiTokenRepository
import com.plainbase.domain.repository.AuditRepository
import com.plainbase.domain.root.ObservationEpoch
import com.plainbase.domain.service.AccessDenied
import com.plainbase.domain.service.CreateIntent
import com.plainbase.domain.service.DenyReason
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.SaveRequest
import com.plainbase.domain.service.TestIdProvider
import com.plainbase.domain.service.idLessPageWarning
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.config.ConfigLoader
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.lifecycle.ServerResourceOwner
import com.plainbase.frameworks.runtime.ServerOpeners
import com.plainbase.frameworks.runtime.prepareRootBootInputs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.spyk
import io.mockk.verify
import org.koin.core.Koin
import org.koin.dsl.module
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

class ReaderModuleWiringTest : FunSpec({
    fun withGraph(
        reader: Boolean = true,
        seed: (Path) -> Unit,
        block: (PlainbaseConfig, Koin, Path, ListAppender<ILoggingEvent>) -> Unit,
    ) {
        withTempTree(seed) { content ->
            withTempTree(seed = {}) { data ->
                Files.writeString(data.resolve("plainbase.conf"), "profile = ${if (reader) "reader" else "workspace"}")
                val config = ConfigLoader.fromEnvAndFile(
                    mapOf("CONTENT_DIR" to content.toString(), "DATA_DIR" to data.toString(), "PLAINBASE_GIT_ENABLED" to "false"),
                )
                val openers = ServerOpeners()
                val inputs = prepareRootBootInputs(config, openers.openLocal)
                val owner = ServerResourceOwner()
                val app = createOwnedTestKoinApplication(
                    owner,
                    listOf(
                        module { single { config } },
                        createContentModule(config, inputs, openers.openObject, { it.close() }, owner),
                        repositoryModule(owner),
                        securityModule,
                        createHistoryModule(config, inputs.history, owner),
                        indexModule,
                        checkpointModule,
                        searchModule(owner),
                        createDiscussionModule(owner),
                        restModule(owner),
                    ) + readerModules(config),
                )
                val rootLogger = LoggerFactory.getLogger(ROOT_LOGGER_NAME) as Logger
                val appender = ListAppender<ILoggingEvent>().apply { start() }
                rootLogger.addAppender(appender)
                try {
                    inputs.signals.arm(app.koin.get<ObservationEpoch>()::broke)
                    block(config, app.koin, content, appender)
                } finally {
                    rootLogger.detachAppender(appender)
                    appender.stop()
                    owner.close()
                }
            }
        }
    }

    fun warnings(appender: ListAppender<ILoggingEvent>): List<String> = appender.list
        .filter { it.level == Level.WARN && "no usable frontmatter id" in it.formattedMessage }
        .map { it.formattedMessage }

    fun mixedPages(root: Path) {
        val ids = TestIdProvider()
        val duplicate = ids.next().value
        val unique = ids.next().value
        writePage(root, "missing.md", "# Missing\n")
        writePage(root, "duplicate-one.md", "---\nid: $duplicate\n---\n# One\n")
        writePage(root, "duplicate-two.md", "---\nid: $duplicate\n---\n# Two\n")
        writePage(root, "unique.md", "---\nid: $unique\n---\n# Unique\n")
    }

    fun uniquePages(root: Path) {
        val id = TestIdProvider().next().value
        writePage(root, "unique.md", "---\nid: $id\n---\n# Unique\n")
    }

    listOf("save", "create").forEach { operation ->
        test("reader agent $operation should refuse without a live token lookup") {
            withGraph(seed = ::uniquePages) { _, koin, _, _ ->
                // Arrange
                val tokens = spyk(koin.get<ApiTokenRepository>())
                koin.loadModules(listOf(module { single<ApiTokenRepository> { tokens } }))
                val page = koin.get<IndexBuilder>().rebuild().pages.single()
                val mutate = koin.get<RouteContext>().mutate
                val principal = Principal.Agent("reader-agent")
                val bytes = "# Changed\n".toByteArray()

                // Act
                val failure = shouldThrow<AccessDenied> {
                    when (operation) {
                        "save" -> mutate.save(principal, SaveRequest(page.id, page.contentHash, bytes, expectedRoot = page.root))
                        "create" -> mutate.create(
                            principal,
                            CreateIntent(TestIdProvider().next(), page.root, TreePath.require("new.md"), bytes),
                        )
                    }
                }

                // Assert
                failure.reason shouldBe DenyReason.POLICY
                verify(exactly = 0) { tokens.modeOf(any(), any()) }
                koin.get<AuditRepository>().recent(Int.MAX_VALUE).shouldBeEmpty()
            }
        }
    }

    test("reader publication should warn once naming only pages without materialized identity") {
        withGraph(seed = ::mixedPages) { _, koin, _, events ->
            val snapshot = koin.get<IndexBuilder>().rebuild()
            val message = warnings(events).single()
            val unstable = snapshot.pages.filterNot { it.materialized }
            unstable shouldHaveSize 2
            snapshot.pages.filter { it.path.value.startsWith("duplicate-") }.count { !it.materialized } shouldBe 1
            unstable.forEach { message shouldContain "${it.root.value}:${it.path.value}" }
            snapshot.pages.filter { it.materialized }.forEach { message shouldNotContain "${it.root.value}:${it.path.value}" }
            message shouldContain "2 page(s)"
            message shouldContain "plainbase adopt --write-ids"
            message shouldContain "DATA_DIR/plainbase.db"
            message shouldContain "object readers use a fresh DATA_DIR on every start"
            message shouldContain "local readers retain ids across restarts"
        }
    }

    test("reader should evaluate only the first publication when unstable pages persist") {
        withGraph(seed = ::mixedPages) { _, koin, _, events ->
            val builder = koin.get<IndexBuilder>()
            builder.rebuild()
            warnings(events) shouldHaveSize 1
            builder.rebuild()
            warnings(events) shouldHaveSize 1
        }
    }

    test("reader should close the warning gate after a clean first publication") {
        withGraph(seed = ::uniquePages) { _, koin, root, events ->
            val builder = koin.get<IndexBuilder>()
            builder.rebuild()
            warnings(events).shouldBeEmpty()
            writePage(root, "late.md", "# Late\n")
            builder.rebuild()
            warnings(events).shouldBeEmpty()
        }
    }

    test("reader with unique identities should emit no identity warning") {
        withGraph(seed = ::uniquePages) { _, koin, _, events ->
            koin.get<IndexBuilder>().rebuild()
            warnings(events).shouldBeEmpty()
        }
    }

    test("workspace should have no reader module and reader should collect exactly one extra listener") {
        var workspaceListeners = 0
        withGraph(reader = false, seed = ::uniquePages) { config, koin, _, _ ->
            readerModules(config).shouldBeEmpty()
            workspaceListeners = koin.getAll<IndexBuilder.PublicationListener>().size
        }
        withGraph(seed = ::uniquePages) { _, koin, _, _ ->
            withClue("workspace listener count = $workspaceListeners") {
                koin.getAll<IndexBuilder.PublicationListener>() shouldHaveSize workspaceListeners + 1
            }
        }
    }

    test("identity warning should cap names at five with the remaining count") {
        withGraph(seed = { root -> (1..7).forEach { writePage(root, "missing-$it.md", "# Missing $it\n") } }) { _, koin, _, _ ->
            val snapshot = koin.get<IndexBuilder>().rebuild()
            val message = requireNotNull(idLessPageWarning(snapshot))
            snapshot.pages.take(5).forEach { message shouldContain "${it.root.value}:${it.path.value}" }
            snapshot.pages.drop(5).forEach { message shouldNotContain "${it.root.value}:${it.path.value}" }
            message shouldContain "7 page(s)"
            message shouldContain "+2 more"
        }
    }

    test("identity warning should include a page excluded from URL space") {
        withGraph(seed = { writePage(it, "missing.md", "# Missing\n") }) { _, koin, _, _ ->
            val page = koin.get<IndexBuilder>().rebuild().pages.single().copy(urlPath = null)
            page.materialized shouldBe false
            val snapshot = PageIndex(listOf(RootSection(page.root, listOf(page), emptyList(), emptySet())))
            requireNotNull(idLessPageWarning(snapshot)) shouldContain "${page.root.value}:${page.path.value}"
        }
    }
})
