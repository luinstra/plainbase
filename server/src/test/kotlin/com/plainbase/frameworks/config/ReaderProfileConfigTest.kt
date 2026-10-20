package com.plainbase.frameworks.config

import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.typesafe.config.ConfigException
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

class ReaderProfileConfigTest : FunSpec({
    fun withDataDir(conf: String?, block: (Map<String, String>) -> Unit) {
        val data = Files.createTempDirectory("pb-reader-config")
        try {
            if (conf != null) Files.writeString(data.resolve("plainbase.conf"), conf)
            block(mapOf("DATA_DIR" to data.toString()))
        } finally {
            data.toFile().deleteRecursively()
        }
    }

    listOf(false, true).forEach { profileOnly ->
        context(if (profileOnly) "profile-only loading" else "full configuration loading") {
            fun profile(env: Map<String, String>): DeploymentProfile =
                if (profileOnly) ConfigLoader.profileFrom(env) else ConfigLoader.fromEnvAndFile(env).profile

            listOf(
                null to DeploymentProfile.WORKSPACE,
                "host = localhost" to DeploymentProfile.WORKSPACE,
                "profile = workspace" to DeploymentProfile.WORKSPACE,
                "profile = reader" to DeploymentProfile.READER,
                "profile = Reader" to DeploymentProfile.READER,
            ).forEach { (conf, expected) ->
                test("should decode $expected from ${conf ?: "an absent file"}") {
                    withDataDir(conf) { env -> profile(env) shouldBe expected }
                }
            }

            listOf("public", "\"\"", "null", "123", "[]", "{}").forEach { value ->
                test("should refuse profile = $value naming the legal values") {
                    withDataDir("profile = $value") { env ->
                        val failure = shouldThrow<IllegalArgumentException> { profile(env) }
                        failure.message shouldContain "profile"
                        failure.message shouldContain "workspace"
                        failure.message shouldContain "reader"
                    }
                }
            }

            listOf("reader", "Reader", " reader ").forEach { value ->
                test("should accept the reader environment value '$value' when the file selects reader") {
                    withDataDir("profile = reader") { env ->
                        profile(env + ("PLAINBASE_PROFILE" to value)) shouldBe DeploymentProfile.READER
                    }
                }
            }

            listOf("workspace" to "profile = reader", "public" to null).forEach { (value, conf) ->
                test("should refuse the environment profile value '$value'") {
                    withDataDir(conf) { env ->
                        val failure = shouldThrow<IllegalArgumentException> { profile(env + ("PLAINBASE_PROFILE" to value)) }
                        failure.message shouldContain "PLAINBASE_PROFILE"
                        failure.message shouldContain "only accepted value is `reader`"
                    }
                }
            }

            listOf("", " \t ").forEach { value ->
                listOf(null, "host = localhost", "profile = workspace", "profile = reader").forEach { conf ->
                    test("should leave ${conf ?: "an absent file"} in control when the environment profile is '$value'") {
                        withDataDir(conf) { env ->
                            profile(env + ("PLAINBASE_PROFILE" to value)) shouldBe
                                if (conf == "profile = reader") DeploymentProfile.READER else DeploymentProfile.WORKSPACE
                        }
                    }
                }
            }

            test("should select reader from the environment when the present file has no profile key") {
                withDataDir("host = localhost") { env ->
                    profile(env + ("PLAINBASE_PROFILE" to "reader")) shouldBe DeploymentProfile.READER
                }
            }

            test("should select reader from the environment when the operator file is missing") {
                withDataDir(null) { env ->
                    profile(env + ("PLAINBASE_PROFILE" to "reader")) shouldBe DeploymentProfile.READER
                }
            }

            test("should select reader from the environment when the file selects workspace") {
                withDataDir("profile = workspace") { env ->
                    profile(env + ("PLAINBASE_PROFILE" to " ReAdEr ")) shouldBe DeploymentProfile.READER
                }
            }

            listOf("${'$'}{?PB_UNSET_PROFILE}", "${'$'}{PATH}", "reader${'$'}{?PB_UNSET_PROFILE}").forEach { value ->
                test("should refuse a substituted profile $value") {
                    System.getenv("PB_UNSET_PROFILE") shouldBe null
                    withDataDir("profile = $value") { env ->
                        val failure = shouldThrow<IllegalArgumentException> { profile(env) }
                        failure.message shouldContain "must be a literal"
                        failure.message shouldContain "a ${'$'}{...} substitution is refused"
                    }
                }
            }
        }
    }

    fun objectEnv(env: Map<String, String>): Map<String, String> = env + mapOf(
        "PLAINBASE_STORAGE_BACKEND" to "object",
        "PLAINBASE_S3_ENDPOINT" to "https://acct.example.com",
        "PLAINBASE_S3_BUCKET" to "docs",
        "PLAINBASE_S3_ACCESS_KEY_ID" to "dummy-key",
        "PLAINBASE_S3_SECRET_ACCESS_KEY" to "dummy-secret",
        "PLAINBASE_S3_PREFIX" to "releases/env",
    )

    context("root capabilities") {
        listOf(false, true).forEach { objectBacked ->
            listOf(false, true).forEach { reader ->
                val backend = if (objectBacked) "object" else "local"
                val selected = if (reader) "reader" else "workspace"
                test("should derive synthesized $backend root capabilities for $selected") {
                    withDataDir(if (reader) "profile = reader" else null) { env ->
                        val content = Path.of(env.getValue("DATA_DIR")).resolve("content")
                        val inputs = if (objectBacked) {
                            objectEnv(env) + ("PLAINBASE_S3_PREFIX" to "releases/r1")
                        } else {
                            env + ("CONTENT_DIR" to content.toString())
                        }
                        val config = ConfigLoader.fromEnvAndFile(inputs)
                        config.profile shouldBe if (reader) DeploymentProfile.READER else DeploymentProfile.WORKSPACE
                        config.roots.primary.backend shouldBe if (objectBacked) {
                            RootBackend.Object("docs", "releases/r1")
                        } else {
                            RootBackend.Local(content)
                        }
                        assertSoftly {
                            config.roots.primary.editable shouldBe !reader
                            config.roots.primary.discussionsEnabled shouldBe !reader
                            config.roots.primary.history shouldBe if (reader) HistoryMode.OFF else HistoryMode.AUTO
                        }
                    }
                }
            }
        }

        listOf(false, true).forEach { reader ->
            test("should derive explicit local root defaults with reader selected $reader") {
                withDataDir(null) { env ->
                    val data = Path.of(env.getValue("DATA_DIR"))
                    val content = data.resolve("content")
                    val profile = if (reader) "profile = reader\n" else ""
                    Files.writeString(data.resolve("plainbase.conf"), profile + "roots { docs { path = \"$content\" } }")
                    val config = ConfigLoader.fromEnvAndFile(env)
                    config.profile shouldBe if (reader) DeploymentProfile.READER else DeploymentProfile.WORKSPACE
                    assertSoftly {
                        config.roots.primary.editable shouldBe !reader
                        config.roots.primary.discussionsEnabled shouldBe !reader
                        config.roots.primary.history shouldBe if (reader) HistoryMode.OFF else HistoryMode.AUTO
                    }
                }
            }
        }

        test("should preserve explicit reader root capabilities for boot inspection") {
            withDataDir(null) { env ->
                val data = Path.of(env.getValue("DATA_DIR"))
                Files.writeString(
                    data.resolve("plainbase.conf"),
                    "profile = reader\nroots { docs { path = \"${data.resolve("content")}\", " +
                        "editable = true, discussionsEnabled = true, history = native } }",
                )
                val root = ConfigLoader.fromEnvAndFile(env).roots.primary
                root.editable shouldBe true
                root.discussionsEnabled shouldBe true
                root.history shouldBe HistoryMode.NATIVE
            }
        }

        test("should decode reader git enablement for the boot refusal channel") {
            withDataDir("profile = reader") { env ->
                val config = ConfigLoader.fromEnvAndFile(env + ("PLAINBASE_GIT_ENABLED" to "1"))
                config.profile shouldBe DeploymentProfile.READER
                config.git.enabled shouldBe true
            }
        }

        test("should derive reader defaults for managed roots and the synthesized primary") {
            withDataDir("profile = reader") { env ->
                val data = Path.of(env.getValue("DATA_DIR"))
                Files.writeString(data.resolve("roots.conf"), "roots { notes { path = \"${data.resolve("notes")}\" } }")
                val config = ConfigLoader.fromEnvAndFile(env)
                config.roots.managed shouldBe setOf(RootName.of("notes"))
                config.roots.list.forEach { root ->
                    assertSoftly {
                        root.editable shouldBe false
                        root.discussionsEnabled shouldBe false
                        root.history shouldBe HistoryMode.OFF
                    }
                }
            }
        }

        test("should derive reader capabilities in default configuration construction") {
            withDataDir(null) { env ->
                val data = Path.of(env.getValue("DATA_DIR"))
                val config = PlainbaseConfig(data.resolve("content"), data, "127.0.0.1", 8080, profile = DeploymentProfile.READER)
                assertSoftly {
                    config.roots.primary.editable shouldBe false
                    config.roots.primary.discussionsEnabled shouldBe false
                    config.roots.primary.history shouldBe HistoryMode.OFF
                }
            }
        }
    }

    test("should keep prefix provenance false for an environment-only prefix") {
        withDataDir(null) { env ->
            val storage = ConfigLoader.fromEnvAndFile(objectEnv(env)).storage
            storage.prefix shouldBe "releases/env"
            storage.prefixDeclaredInFile shouldBe false
        }
    }

    listOf("\"releases/file\"", "null").forEach { value ->
        test("should retain file prefix presence for $value while the environment wins") {
            withDataDir("storage.object.prefix = $value") { env ->
                val storage = ConfigLoader.fromEnvAndFile(objectEnv(env)).storage
                storage.prefix shouldBe "releases/env"
                storage.prefixDeclaredInFile shouldBe true
            }
        }
    }

    test("should ignore file prefix provenance under local storage") {
        withDataDir("storage.object.prefix = releases/file") { env ->
            val storage = ConfigLoader.fromEnvAndFile(env).storage
            storage.prefixDeclaredInFile shouldBe false
            storage.ignoredObjectKeys shouldContain "storage.object.prefix"
        }
    }

    listOf(
        "https://docs.plainbase.sh", "https://docs.plainbase.sh:8443", "https://docs.plainbase.sh:1",
        "https://docs.plainbase.sh:65535",
    ).forEach { origin ->
        test("should accept canonical origin $origin") {
            withDataDir("site.canonicalOrigin = \"$origin\"") { env ->
                ConfigLoader.fromEnvAndFile(env).site.canonicalOrigin shouldBe origin
            }
        }
    }

    listOf(
        "http://docs.plainbase.sh", "https://docs.plainbase.sh/", "https://docs.plainbase.sh/docs",
        "https://u@docs.plainbase.sh", "https://docs.plainbase.sh?x=1", "https://docs.plainbase.sh#f",
        "docs.plainbase.sh", "https:docs.plainbase.sh", "https://docs plainbase.sh", "https://docs.plainbase.sh:",
        "https://docs.plainbase.sh:443", "https://docs.plainbase.sh:08443", "https://docs.plainbase.sh:0",
        "https://docs.plainbase.sh:65536", "https://Docs.Plainbase.sh",
    ).forEach { origin ->
        listOf("workspace", "reader").forEach { selected ->
            test("should refuse noncanonical origin ${origin.replace("#", " fragment ")} under $selected") {
                withDataDir("profile = $selected\nsite.canonicalOrigin = \"$origin\"") { env ->
                    val failure = shouldThrow<IllegalArgumentException> { ConfigLoader.fromEnvAndFile(env) }
                    failure.message shouldContain "site.canonicalOrigin"
                    failure.message shouldContain "site.canonicalOrigin = \"https://docs.example.com\""
                }
            }
        }
    }

    test("should funnel an unquoted origin parser failure through the command error channel") {
        withDataDir("site { canonicalOrigin = https://docs.plainbase.sh }") { env ->
            shouldThrow<ConfigException.Parse> { ConfigLoader.fromEnvAndFile(env) }
            val errors = mutableListOf<String>()
            ConfigLoader.loadForCommand("serve", errors::add) { ConfigLoader.fromEnvAndFile(env) } shouldBe null
            errors.size shouldBe 1
            errors.single() shouldContain "serve: "
        }
    }

    test("should trim the site name") {
        withDataDir("site.name = \"  Plainbase docs  \"") { env ->
            ConfigLoader.fromEnvAndFile(env).site.name shouldBe "Plainbase docs"
        }
    }

    listOf("\"  \"", "\"docs\\u0001\"").forEach { value ->
        test("should refuse the invalid site name $value") {
            withDataDir("site.name = $value") { env ->
                shouldThrow<IllegalArgumentException> { ConfigLoader.fromEnvAndFile(env) }.message shouldContain "site.name"
            }
        }
    }

    test("should keep absent site settings optional") {
        withDataDir(null) { env -> ConfigLoader.fromEnvAndFile(env).site shouldBe SiteConfig() }
    }
})
