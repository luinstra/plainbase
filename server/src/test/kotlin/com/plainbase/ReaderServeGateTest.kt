package com.plainbase

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.service.AccessDenied
import com.plainbase.frameworks.config.ConfigLoader
import com.plainbase.frameworks.config.DeploymentProfile
import com.plainbase.frameworks.ktor.PrincipalExtraction
import com.plainbase.frameworks.ktor.ReaderSurface
import com.plainbase.frameworks.ktor.ReaderSurfaceContract
import com.plainbase.frameworks.ktor.routeInventory
import com.plainbase.frameworks.lifecycle.ServerRunControl
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.ktor.server.application.ApplicationCall
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Files
import java.sql.DriverManager

class ReaderServeGateTest : FunSpec({
    test("reader retained state refuses under the data directory lock before any opener") {
        withReader { world ->
            Files.writeString(world.data.resolve("plainbase.db"), "")
            val status = runServer(world.config(), world.output.output, world.openers)
            assertSoftly {
                status shouldBe 1
                world.errors().single() shouldContain "serve: "
                world.errors().single() shouldContain "plainbase.db"
                world.driverOpens.get() shouldBe 0
                world.objectOpens.get() shouldBe 0
                Files.exists(world.data.resolve("plainbase.lock")) shouldBe true
            }
        }
    }

    test("reader git enablement refuses before any opener") {
        withReader { world ->
            val config = ConfigLoader.fromEnvAndFile(world.env + ("PLAINBASE_GIT_ENABLED" to "1"))
            val status = runServer(config, world.output.output, world.openers)
            assertSoftly {
                status shouldBe 1
                world.errors().single() shouldContain "PLAINBASE_GIT_ENABLED / git.enabled"
                world.driverOpens.get() shouldBe 0
                world.objectOpens.get() shouldBe 0
                Files.exists(world.data.resolve("plainbase.lock")) shouldBe false
            }
        }
    }

    test("reader checks retained state introduced before the lock before opening the driver") {
        withReader { world ->
            val status = runServer(
                world.config(),
                world.output.output,
                world.openers,
                ServerRunControl(onBootAvailability = { Files.writeString(world.data.resolve("plainbase.db"), "") }),
            )
            assertSoftly {
                status shouldBe 1
                world.errors().single() shouldContain "plainbase.db"
                world.driverOpens.get() shouldBe 0
                world.objectOpens.get() shouldBe 0
                Files.exists(world.data.resolve("plainbase.lock")) shouldBe true
            }
        }
    }

    test("reader accepts a preexisting process lock file and reaches object hydration") {
        withReader { world ->
            Files.writeString(world.data.resolve("plainbase.lock"), "")
            val status = runServer(world.config(), world.output.output, world.openers)
            assertSoftly {
                status shouldBe 1
                world.errors().single() shouldContain "object storage endpoint is unreachable"
                world.driverOpens.get() shouldBe 1
                world.objectOpens.get() shouldBe 1
            }
        }
    }

    test("environment reader boots without an operator file through the command loader and freshness gate") {
        withReader { world ->
            Files.delete(world.data.resolve("plainbase.conf"))
            world.useFakeObjectStore = true
            var starts = 0
            val status = ConfigLoader.loadForCommand("serve", world.output.output::error) {
                ConfigLoader.fromEnvAndFile(world.env)
            }?.let { config ->
                config.profile shouldBe DeploymentProfile.READER
                config.roots.primary.editable shouldBe false
                config.roots.primary.discussionsEnabled shouldBe false
                runServer(config, world.output.output, world.openers, ServerRunControl(startServer = { starts++ }))
            } ?: 1
            assertSoftly {
                status shouldBe 0
                starts shouldBe 1
                world.errors() shouldBe emptyList()
                world.driverOpens.get() shouldBe 1
                world.objectOpens.get() shouldBe 1
                Files.exists(world.data.resolve("plainbase.lock")) shouldBe true
                world.bucket.listCount shouldBe 1
                world.bucket.putCount shouldBe 0
            }
        }
    }

    test("reader production graph preserves the surface, release, policy and anonymous extraction without audit writes") {
        withReader { world ->
            world.useFakeObjectStore = true
            var inventory: Result<Set<String>>? = null
            var reader: Result<ReaderSurface?>? = null
            var manage: Result<Unit>? = null
            var extraction: Result<PrincipalExtraction>? = null
            val call = mockk<ApplicationCall>()
            val status = runServer(
                world.config(),
                world.output.output,
                world.openers,
                ServerRunControl(
                    startServer = {},
                    onRuntimeContext = { ctx ->
                        inventory = runCatching { routeInventory(ctx) }
                        reader = runCatching { ctx.reader }
                        manage = runCatching {
                            ctx.auth.admin.listRoles(Principal.Anonymous)
                            Unit
                        }
                        extraction = runCatching { ctx.extract.invoke(call) }
                    },
                ),
            )
            status shouldBe 0
            val callUntouched = runCatching { verify { call wasNot Called } }
            val database = world.data.resolve("plainbase.db").toAbsolutePath().normalize()
            val auditCount = DriverManager.getConnection("jdbc:sqlite:file:$database?mode=ro").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM audit_log").use { rows ->
                        rows.next() shouldBe true
                        rows.getInt(1)
                    }
                }
            }
            assertSoftly {
                world.errors() shouldBe emptyList()
                withClue("production reader inventory") {
                    inventory?.exceptionOrNull() shouldBe null
                    inventory?.getOrNull() shouldBe ReaderSurfaceContract
                }
                withClue("production reader release") {
                    reader?.exceptionOrNull() shouldBe null
                    reader?.getOrNull() shouldBe ReaderSurface(release = "r1")
                }
                withClue("production reader manage attempt: $manage") {
                    (manage?.exceptionOrNull() is AccessDenied) shouldBe true
                }
                withClue("production reader extraction") {
                    extraction?.exceptionOrNull() shouldBe null
                    extraction?.getOrNull() shouldBe PrincipalExtraction.Resolved(Principal.Anonymous)
                    (extraction?.getOrNull() as? PrincipalExtraction.Resolved)?.principal shouldBeSameInstanceAs Principal.Anonymous
                    callUntouched.isSuccess shouldBe true
                }
                withClue("production reader audit count") { auditCount shouldBe 0 }
            }
        }
    }

    test("fresh reader accepts its own lock and reaches object hydration") {
        withReader { world ->
            val status = runServer(world.config(), world.output.output, world.openers)
            assertSoftly {
                status shouldBe 1
                world.errors().single() shouldContain "object storage endpoint is unreachable"
                world.driverOpens.get() shouldBe 1
                world.objectOpens.get() shouldBe 1
                Files.exists(world.data.resolve("plainbase.lock")) shouldBe true
            }
        }
    }
})
