package com.plainbase.frameworks.koin

import com.plainbase.domain.discussion.CommentId
import com.plainbase.domain.discussion.DiscussionId
import com.plainbase.domain.discussion.DiscussionStore
import com.plainbase.domain.discussion.EntryName
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.AuditRepository
import com.plainbase.domain.root.ObservationEpoch
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.AccessDenied
import com.plainbase.domain.service.DenyReason
import com.plainbase.domain.service.DiscussionPageResolver
import com.plainbase.domain.service.DiscussionReads
import com.plainbase.domain.service.DiscussionSyncState
import com.plainbase.domain.service.DiscussionWriteOutcome
import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.RootedResource
import com.plainbase.domain.service.withTempTree
import com.plainbase.domain.service.writePage
import com.plainbase.frameworks.config.ConfigLoader
import com.plainbase.frameworks.discussion.DiscussionBoot
import com.plainbase.frameworks.discussion.seedTransportDiscussion
import com.plainbase.frameworks.filesystem.LocalDiscussionStore
import com.plainbase.frameworks.ktor.DiscussionReadProjection
import com.plainbase.frameworks.lifecycle.ServerResourceOwner
import com.plainbase.frameworks.protocol.DiscussionTransportFacade
import com.plainbase.frameworks.runtime.RootStores
import com.plainbase.frameworks.runtime.ServerOpeners
import com.plainbase.frameworks.runtime.prepareRootBootInputs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.spyk
import io.mockk.verify
import org.koin.core.Koin
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path

class DiscussionConfigWiringTest : FunSpec({
    test("production policy and facade honor disabled detail and PURGE with an enabled sibling") {
        withDiscussionConfigTree { base ->
            withDiscussionConfigGraph(base, enabled = false) { koin ->
                val snapshot = koin.get<IndexBuilder>().rebuild()
                snapshot.pages.forEach { page ->
                    seedTransportDiscussion(base.resolve(page.root.value), page, CONFIG_DISCUSSION, CONFIG_COMMENT)
                }
                koin.get<DiscussionBoot>().run()
                val policy = koin.get<PolicyService>()
                val facade = koin.get<DiscussionTransportFacade>()
                val reads = koin.get<DiscussionReads>()
                val projection = koin.get<DiscussionReadProjection>()
                val pages = koin.get<DiscussionPageResolver>()
                val store = koin.get<DiscussionStore>()
                val audit = koin.get<AuditRepository>()
                val disabled = RootName.require("extra")
                clearMocks(reads, projection, pages, store, answers = false)

                policy.checkDiscussionRead(Principal.Anonymous, RootedResource(disabled, "discussion/id")) shouldBe
                    DenyReason.DISCUSSIONS_DISABLED
                policy.checkDiscussionRead(Principal.Anonymous, RootedResource(RootName.PRIMARY, "discussion/id")) shouldBe null
                val detail = facade.detail(Principal.Anonymous, CONFIG_DISCUSSION, disabled, null, 50)
                detail.reason shouldBe "disabled_by_config"
                detail.discussionsAvailable shouldBe false
                detail.discussion shouldBe null
                detail.comments shouldBe emptyList()
                detail.next shouldBe null
                audit.recent(10) shouldBe emptyList()
                shouldThrow<AccessDenied> {
                    facade.purge(Principal.Anonymous, CONFIG_DISCUSSION, disabled, CONFIG_COMMENT)
                }.reason shouldBe DenyReason.DISCUSSIONS_DISABLED
                audit.recent(10).single().decision shouldBe "denied"
                verify(exactly = 0) { reads.claim(any(), any(), any()) }
                verify(exactly = 0) { projection.detail(any(), any(), any(), any(), any()) }
                verify(exactly = 0) { pages.resolve(any(), any(), any(), any()) }
                verify(exactly = 0) { reads.detail(any(), any(), any(), any()) }
                verify(exactly = 0) { store.read(any(), any(), any()) }
                verify(exactly = 0) { store.purge(any(), any(), any()) }

                val enabled = facade.detail(Principal.Anonymous, CONFIG_DISCUSSION, RootName.PRIMARY, null, 50)
                enabled.comments.single().markdown shouldBe "Preserved comment\n"
                facade.purge(Principal.Anonymous, CONFIG_DISCUSSION, RootName.PRIMARY, CONFIG_COMMENT)
                    .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
                audit.recent(10).map { it.decision } shouldBe listOf("allowed", "denied")
                Files.exists(configCommentPath(base.resolve("extra"))) shouldBe true
                Files.exists(configCommentPath(base.resolve("docs"))) shouldBe false
                koin.get<DiscussionSyncState>().scopeRoots shouldBe setOf(RootName.PRIMARY)
            }
        }
    }

    test("production restart restores IDs comments and writes from preserved files after re-enable") {
        withDiscussionConfigTree { base ->
            withDiscussionConfigGraph(base, enabled = true) { koin ->
                val snapshot = koin.get<IndexBuilder>().rebuild()
                snapshot.pages.forEach { page ->
                    seedTransportDiscussion(base.resolve(page.root.value), page, CONFIG_DISCUSSION, CONFIG_COMMENT)
                }
                koin.get<DiscussionBoot>().run()
                koin.get<DiscussionTransportFacade>().detail(Principal.Anonymous, CONFIG_DISCUSSION, RootName.require("extra"), null, 50)
                    .comments.single().markdown shouldBe "Preserved comment\n"
            }
            val paths = configFileSnapshot(base.resolve("extra"))
            withDiscussionConfigGraph(base, enabled = false) { koin ->
                koin.get<IndexBuilder>().rebuild().pages.size shouldBe 2
                koin.get<DiscussionBoot>().run()
                koin.get<DiscussionTransportFacade>().rootList(Principal.Anonymous, RootName.require("extra"), null, 50, null)
                    .reason shouldBe "disabled_by_config"
                val store = koin.get<DiscussionStore>()
                verify(exactly = 0) { store.sweepBootResidue(RootName.require("extra"), any(), any()) }
                verify(exactly = 0) { store.visit(RootName.require("extra"), any()) }
                configFileSnapshot(base.resolve("extra")) shouldBe paths
            }
            withDiscussionConfigGraph(base, enabled = true) { koin ->
                koin.get<IndexBuilder>().rebuild()
                koin.get<DiscussionBoot>().run()
                val facade = koin.get<DiscussionTransportFacade>()
                val root = RootName.require("extra")
                facade.detail(Principal.Anonymous, CONFIG_DISCUSSION, root, null, 50).comments.single().markdown shouldBe
                    "Preserved comment\n"
                facade.comment(Principal.Anonymous, CONFIG_DISCUSSION, root, "After re-enable")
                    .shouldBeInstanceOf<DiscussionWriteOutcome.Done>()
                facade.detail(Principal.Anonymous, CONFIG_DISCUSSION, root, null, 50).comments.size shouldBe 2
            }
        }
    }
})

private val CONFIG_DISCUSSION = DiscussionId.require("01900000-0000-7000-8000-000000000991")
private val CONFIG_COMMENT = CommentId.require("01900000-0000-7000-8000-000000000992")

private fun withDiscussionConfigTree(block: (Path) -> Unit) = withTempTree(seed = { base ->
    writePage(base.resolve("docs"), "page.md", "# Enabled\n\nEnabled page.\n")
    writePage(base.resolve("extra"), "page.md", "# Extra\n\nPreserved page.\n")
}) { base -> block(base) }

private fun withDiscussionConfigGraph(base: Path, enabled: Boolean, block: (Koin) -> Unit) {
    val data = Files.createDirectories(base.resolve("data"))
    Files.writeString(
        data.resolve("plainbase.conf"),
        """
        auth.mode = off
        roots {
          docs { path = "${base.resolve("docs")}", history = off }
          extra { path = "${base.resolve("extra")}", editable = true, history = off, discussionsEnabled = $enabled }
        }
    """.trimIndent(),
    )
    val config = ConfigLoader.fromEnvAndFile(mapOf("DATA_DIR" to data.toString()))
    val openers = ServerOpeners()
    val inputs = prepareRootBootInputs(config, openers.openLocal)
    val owner = ServerResourceOwner()
    val app = createOwnedTestKoinApplication(
        owner,
        listOf(
            module { single { config } },
            createContentModule(config, inputs, openers.openObject, { it.close() }, owner),
            repositoryModule(owner), securityModule, createHistoryModule(config, inputs.history, owner), indexModule,
            checkpointModule, searchModule(owner), createDiscussionModule(owner), createRestModule(owner),
            module {
                single<DiscussionStore> { spyk(get<LocalDiscussionStore>()) }
                single { spyk(DiscussionReads(get(), get(), get(), get(), get())) }
                single { spyk(DiscussionPageResolver(get(), get(), get())) }
                single { spyk(DiscussionReadProjection(get(), get(), get(), get(), get<RootStores>()::get)) }
            },
        ),
    )
    try {
        inputs.signals.arm(app.koin.get<ObservationEpoch>()::broke)
        block(app.koin)
    } finally {
        owner.close()
    }
}

private fun configCommentPath(root: Path): Path =
    root.resolve(".plainbase/discussions/${CONFIG_DISCUSSION.value}/${EntryName.Comment(CONFIG_COMMENT).fileName}")

private fun configFileSnapshot(root: Path): Map<String, List<Byte>> = Files.walk(root).use { paths ->
    paths.filter(Files::isRegularFile).toList().associate { root.relativize(it).toString() to Files.readAllBytes(it).toList() }
}
