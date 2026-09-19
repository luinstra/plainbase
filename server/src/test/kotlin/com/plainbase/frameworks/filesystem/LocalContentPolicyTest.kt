package com.plainbase.frameworks.filesystem

import com.plainbase.domain.content.CasResult
import com.plainbase.domain.content.ContentPathPolicy
import com.plainbase.domain.content.CreateResult
import com.plainbase.domain.content.StoreRead
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.principal.grantForTests
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.config.RootsConfig
import com.plainbase.frameworks.config.RootsOrigin
import com.plainbase.frameworks.config.StorageBackend
import com.plainbase.frameworks.config.StorageConfig
import com.plainbase.frameworks.runtime.LocalStoreInputs
import com.plainbase.frameworks.runtime.RootStoreFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class LocalContentPolicyTest : FunSpec({
    fun policy(
        includes: List<String>? = null,
        excludes: List<String> = emptyList(),
        legacyIgnores: List<String> = emptyList(),
    ) = localContentPathPolicy(
        rootConfig = Root(
            name = RootName.require("docs"),
            backend = RootBackend.Local(Path.of("/content")),
            editable = true,
            history = HistoryMode.OFF,
            includes = includes,
            excludes = excludes,
        ),
        root = Path.of("/content"),
        ignoreRules = IgnoreRules(legacyIgnores),
        exclusions = emptyList(),
    )

    test("wildcard exclude prefixes do not prune unrelated traversal") {
        val policy = policy(excludes = listOf("**/secret/**", "docs/*/private/**"))

        policy.mayTraverse(TreePath.require("public")) shouldBe true
        policy.mayTraverse(TreePath.require("docs/team")) shouldBe true
        policy.allowsFile(TreePath.require("docs/team/page.md")) shouldBe true
        policy.allowsFile(TreePath.require("docs/team/private/page.md")) shouldBe false
        policy.allowsFile(TreePath.require("nested/secret/page.md")) shouldBe false
    }

    test("file eligibility independently enforces configured and legacy ancestor exclusions") {
        val policy = policy(
            excludes = listOf("private", "docs/*"),
            legacyIgnores = listOf("archive"),
        )

        policy.allowsFile(TreePath.require("private/page.md")) shouldBe false
        policy.allowsFile(TreePath.require("docs/team/page.md")) shouldBe false
        policy.allowsFile(TreePath.require("archive/page.md")) shouldBe false
        policy.allowsFile(TreePath.require("public/page.md")) shouldBe true
    }

    test("literal subtree optimization prunes traversal without changing JDK file-match semantics") {
        val policy = policy(excludes = listOf("secret/**"))

        policy.mayTraverse(TreePath.require("secret")) shouldBe false
        policy.allowsFile(TreePath.require("secret")) shouldBe true
        policy.allowsFile(TreePath.require("secret/page.md")) shouldBe false
    }

    test("hidden authorization requires the full literal prefix through the hidden segment") {
        val policy = policy(includes = listOf("allowed/.private/**", "**/.private/**"))

        policy.allowsFile(TreePath.require("allowed/.private/page.md")) shouldBe true
        policy.allowsFile(TreePath.require("other/.private/page.md")) shouldBe false
    }

    test("metadata follows folder reachability but honors an explicit sidecar exclusion") {
        val allowed = policy(includes = listOf("docs/**/*.md"))
        val excluded = policy(
            includes = listOf("docs/**/*.md"),
            excludes = listOf("docs/_folder.yaml"),
        )

        allowed.allowsMetadata(TreePath.require("docs")) shouldBe true
        excluded.allowsMetadata(TreePath.require("docs")) shouldBe false
    }

    test("shared policy construction excludes a DATA_DIR nested under the local root") {
        val content = Files.createTempDirectory("pb-policy-construction")
        try {
            val data = content.resolve("state")
            val configured = Root(RootName.PRIMARY, RootBackend.Local(content), editable = true, history = HistoryMode.OFF)
            val config = PlainbaseConfig(
                contentDir = content,
                dataDir = data,
                host = "127.0.0.1",
                port = 8080,
                roots = RootsConfig.of(listOf(configured), RootsOrigin.EXPLICIT),
            )
            val policy = contentPolicies(RootRegistry.of(config.roots.list), config, IgnoreRules()).getValue(RootName.PRIMARY)

            policy.mayTraverse(TreePath.require("state")) shouldBe false
            policy.allowsFile(TreePath.require("state/plainbase.db")) shouldBe false
            policy.allowsFile(TreePath.require("page.md")) shouldBe true
        } finally {
            content.toFile().deleteRecursively()
        }
    }

    test("shared policy construction maps an object primary to ALL") {
        val config = PlainbaseConfig(
            contentDir = Path.of("unused-content"),
            dataDir = Path.of("unused-data"),
            host = "127.0.0.1",
            port = 8080,
            storage = StorageConfig(backend = StorageBackend.OBJECT),
        )
        val policy = contentPolicies(RootRegistry.of(config.roots.list), config, IgnoreRules()).getValue(RootName.PRIMARY)

        policy shouldBeSameInstanceAs ContentPathPolicy.ALL
    }

    test("production policy and store exclude a physical DATA_DIR through a symlinked root") {
        val base = Files.createTempDirectory("pb-policy-alias-root")
        try {
            val real = Files.createDirectories(base.resolve("real"))
            val alias = base.resolve("alias")
            try {
                Files.createSymbolicLink(alias, real)
            } catch (_: IOException) {
                return@test
            }
            val data = Files.createDirectories(real.resolve("state"))
            val databaseBytes = "database sentinel".encodeToByteArray()
            val pageBytes = "# Allowed\n".encodeToByteArray()
            Files.write(data.resolve("plainbase.db"), databaseBytes)
            Files.write(real.resolve("page.md"), pageBytes)
            val configured = Root(RootName.PRIMARY, RootBackend.Local(alias), editable = true, history = HistoryMode.OFF)
            val config = PlainbaseConfig(
                contentDir = alias,
                dataDir = data,
                host = "127.0.0.1",
                port = 8080,
                roots = RootsConfig.of(listOf(configured), RootsOrigin.EXPLICIT),
            )
            val policy = contentPolicies(RootRegistry.of(config.roots.list), config, IgnoreRules()).getValue(RootName.PRIMARY)
            val store = RootStoreFactory.local(
                LocalStoreInputs(
                    root = alias,
                    ignoreRules = IgnoreRules(),
                    exclusions = listOf(data),
                    rootName = RootName.PRIMARY,
                    onRootUnavailable = {},
                    onIdentityRebind = {},
                    policy = policy,
                ),
            )
            val hidden = TreePath.require("state/plainbase.db")
            val allowed = TreePath.require("page.md")

            policy.mayTraverse(TreePath.require("state")) shouldBe false
            policy.allowsFile(hidden) shouldBe false
            policy.allowsFile(allowed) shouldBe true
            store.scan().files.map { it.path.value } shouldContainExactly listOf("page.md")
            store.list(null).map { it.path.value } shouldContainExactly listOf("page.md")
            store.read(hidden).shouldBeNull()
            store.readClassified(hidden) shouldBe StoreRead.NoBytes
            store.stat(hidden).shouldBeNull()
            store.createExclusive(TreePath.require("state/new.md"), "blocked".encodeToByteArray()) { it.size.toString() }
                .shouldBeInstanceOf<CreateResult.Rejected>()
            store.writeAssetExclusive(
                grantForTests(),
                TreePath.require("state/new.bin"),
                "blocked asset".encodeToByteArray(),
            ) { it.size.toString() }.shouldBeInstanceOf<CreateResult.Rejected>()
            store.compareAndSwapWrite(hidden, databaseBytes.size.toString(), "changed".encodeToByteArray()) { it.size.toString() } shouldBe
                CasResult.Deleted
            Files.readAllBytes(data.resolve("plainbase.db")).toList() shouldBe databaseBytes.toList()
            Files.exists(data.resolve("new.md"), LinkOption.NOFOLLOW_LINKS) shouldBe false
            Files.exists(data.resolve("new.bin"), LinkOption.NOFOLLOW_LINKS) shouldBe false

            store.read(allowed).shouldNotBeNull().toList() shouldBe pageBytes.toList()
            store.stat(allowed).shouldNotBeNull()
            store.createExclusive(TreePath.require("new.md"), "# New\n".encodeToByteArray()) { it.size.toString() }
                .shouldBeInstanceOf<CreateResult.Created>()
        } finally {
            base.toFile().deleteRecursively()
        }
    }
})
