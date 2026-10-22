package com.plainbase.domain.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.plainbase.domain.principal.MintedToken
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.principal.TokenMinter
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.repository.ApiTokenRepository
import com.plainbase.domain.repository.Role
import com.plainbase.domain.repository.RoleRepository
import com.plainbase.domain.root.RootName
import com.plainbase.frameworks.security.TokenHasher
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightApiTokenRepository
import com.plainbase.frameworks.sqldelight.SqlDelightAuditRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRoleRepository
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Instant

class ReaderPolicyMatrixTest : FunSpec({
    val readCalls = listOf(
        PolicyCall("page read") { policy, principal, resource -> policy.checkRead(principal, resource.audit) },
        PolicyCall("discussion read") { policy, principal, resource -> policy.checkDiscussionRead(principal, resource, preview = false) },
    )
    val writeCalls = listOf(
        WriteClass.PageEdit, WriteClass.AssetWrite, WriteClass.PageCreate, WriteClass.Discussion, WriteClass.DiscussionPurge,
    ).flatMap { writeClass ->
        when (writeClass) {
            WriteClass.PageEdit, WriteClass.AssetWrite -> listOf(
                PolicyCall(writeClass.toString()) { policy, principal, resource -> policy.checkEdit(principal, writeClass, resource) },
            )
            WriteClass.PageCreate -> listOf(
                PolicyCall("PageCreate") { policy, principal, resource -> policy.checkCreate(principal, writeClass, resource) },
            )
            WriteClass.Discussion -> DiscussionAction.entries.filter { it != DiscussionAction.PURGE }.map { operation ->
                PolicyCall("discussion $operation") { policy, principal, resource ->
                    policy.checkDiscussion(principal, operation, DiscussionFacts.Unknown, resource)
                }
            }
            WriteClass.DiscussionPurge -> listOf(
                PolicyCall("discussion PURGE") { policy, principal, resource ->
                    policy.checkDiscussion(principal, DiscussionAction.PURGE, DiscussionFacts.Unknown, resource)
                },
            )
        }
    } + listOf(
        PolicyCall("manage") { policy, principal, _ -> policy.checkManage(principal) },
        PolicyCall("approve") { policy, principal, resource -> policy.checkApprove(principal, resource.audit) },
        PolicyCall("explicit deny") { policy, principal, resource ->
            policy.deny(principal, Action.EDIT, resource.audit, DenyReason.ROOT_NOT_EDITABLE)
        },
    )
    val resources = listOf("editable", "readonly", "object", null).map { root ->
        RootedResource(root?.let(RootName::require), "page")
    }
    val principalNames = listOf("admin", "read-only agent", "commit agent", "anonymous")

    fun exercise(world: ReaderPolicyWorld, calls: List<PolicyCall>) {
        principalNames.forEach { name ->
            resources.forEach { resource ->
                calls.forEach { call ->
                    try {
                        call.invoke(world.policy, world.principals.getValue(name), resource)
                    } catch (_: AccessDenied) {
                        // Denial effects are inspected separately from the per-call outcome matrix.
                    }
                }
            }
        }
    }

    principalNames.forEach { name ->
        resources.forEach { resource ->
            val root = resource.root?.value ?: "unrooted"
            readCalls.forEach { call ->
                test("reader $name at $root should allow ${call.name}") {
                    ReaderPolicyWorld(PolicyMode.READER).use { world ->
                        call.invoke(world.policy, world.principals.getValue(name), resource)
                    }
                }
            }
            writeCalls.forEach { call ->
                test("reader $name at $root should refuse ${call.name} with policy reason") {
                    ReaderPolicyWorld(PolicyMode.READER).use { world ->
                        shouldThrow<AccessDenied> {
                            call.invoke(world.policy, world.principals.getValue(name), resource)
                        }.reason shouldBe DenyReason.POLICY
                    }
                }
            }
        }
    }

    test("reader decisions should leave the audit repository empty across the matrix") {
        ReaderPolicyWorld(PolicyMode.READER).use { world ->
            exercise(world, readCalls + writeCalls)
            world.audit.recent(Int.MAX_VALUE).shouldBeEmpty()
        }
    }

    test("reader mutations should each emit one policy debug line and no warning across the matrix") {
        ReaderPolicyWorld(PolicyMode.READER).use { world ->
            exercise(world, readCalls + writeCalls)
            world.debugDenials() shouldHaveSize principalNames.size * resources.size * writeCalls.size
            world.warnings().shouldBeEmpty()
        }
    }

    listOf("admin" to "Human", "commit agent" to "Agent").forEach { (name, kind) ->
        test("an isolated reader denial should emit exactly one debug line with only the $kind principal kind") {
            ReaderPolicyWorld(PolicyMode.READER).use { world ->
                val principal = world.principals.getValue(name)
                shouldThrow<AccessDenied> { world.policy.deny(principal, Action.EDIT, "editable:page") }
                val message = world.debugDenials().single().formattedMessage
                world.warnings().shouldBeEmpty()
                message shouldContain "EDIT"
                message shouldContain "editable:page"
                message shouldContain kind
                message shouldNotContain "private-issuer"
                message shouldNotContain "private-admin"
                message shouldNotContain "pb_COMMIT"
            }
        }
    }

    listOf("reads" to readCalls, "mutations" to writeCalls).forEach { (name, calls) ->
        test("reader $name should skip both role and live token lookups") {
            ReaderPolicyWorld(PolicyMode.READER).use { world ->
                exercise(world, calls)
                assertSoftly {
                    world.roleLookups shouldBe 0
                    world.tokenLookups shouldBe 0
                }
            }
        }
    }

    listOf(
        "admin" to "PageEdit", "admin" to "manage", "admin" to "approve", "commit agent" to "PageEdit",
    ).forEach { (name, callName) ->
        test("enforced $name should still allow $callName with one audit row and no policy warning") {
            ReaderPolicyWorld(PolicyMode.ENFORCED).use { world ->
                writeCalls.single { it.name == callName }.invoke(world.policy, world.principals.getValue(name), resources.first())
                world.audit.recent(Int.MAX_VALUE).single().decision shouldBe "allowed"
                world.warnings().shouldBeEmpty()
                world.debugDenials().shouldBeEmpty()
                world.roleLookups shouldBe if (name == "admin") 1 else 0
                world.tokenLookups shouldBe if (name == "commit agent") 1 else 0
            }
        }
    }

    test("enforced anonymous read should still refuse without an audit row or policy warning") {
        ReaderPolicyWorld(PolicyMode.ENFORCED).use { world ->
            shouldThrow<AccessDenied> { world.policy.checkRead(Principal.Anonymous, "page") }
            world.audit.recent(Int.MAX_VALUE).shouldBeEmpty()
            world.warnings().shouldBeEmpty()
            world.debugDenials().shouldBeEmpty()
        }
    }

    test("enforced anonymous mutation should still write one denied audit row without a policy warning") {
        ReaderPolicyWorld(PolicyMode.ENFORCED).use { world ->
            shouldThrow<AccessDenied> { world.policy.checkEdit(Principal.Anonymous, WriteClass.PageEdit, resources.first()) }
            world.audit.recent(Int.MAX_VALUE).single().decision shouldBe "denied"
            world.warnings().shouldBeEmpty()
            world.debugDenials().shouldBeEmpty()
        }
    }

    test("reader discussion preview should preserve the topology refusal without audit or warning") {
        ReaderPolicyWorld(PolicyMode.READER).use { world ->
            shouldThrow<AccessDenied> {
                world.policy.checkDiscussionRead(Principal.Anonymous, resources.single { it.root?.value == "object" }, preview = true)
            }.reason shouldBe DenyReason.DISCUSSIONS_UNSUPPORTED
            world.audit.recent(Int.MAX_VALUE).shouldBeEmpty()
            world.warnings().shouldBeEmpty()
            world.debugDenials().shouldBeEmpty()
        }
    }
})

private class PolicyCall(val name: String, val invoke: (PolicyService, Principal, RootedResource) -> Unit)

private class ReaderPolicyWorld(mode: PolicyMode) : AutoCloseable {
    private val driver = DatabaseFactory.createInMemoryDriver()
    private val database = DatabaseFactory.createDatabase(driver)
    private val roles = SqlDelightRoleRepository(database)
    private val tokens = SqlDelightApiTokenRepository(database)
    val audit = SqlDelightAuditRepository(database)
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)
    }
    private val root = LoggerFactory.getLogger(ROOT_LOGGER_NAME) as Logger
    private val policyLogger = LoggerFactory.getLogger(PolicyService::class.java) as Logger
    private val previousLevel = policyLogger.level
    private val appender = ListAppender<ILoggingEvent>().apply { start() }
    var roleLookups = 0
        private set
    var tokenLookups = 0
        private set

    init {
        roles.upsert("private-issuer", "private-admin", Role.ADMIN, clock.now())
        root.addAppender(appender)
        policyLogger.level = Level.DEBUG
    }

    private fun agent(mode: AgentMode): Principal.Agent {
        val minter = object : TokenMinter {
            override fun mint() = MintedToken("pb_${mode.name}", "pb_x", ByteArray(32))
        }
        val token = ApiTokenService(minter, TokenHasher(), tokens, clock).mint("agent", mode)
        return Principal.Agent(token.id)
    }

    val principals = mapOf(
        "admin" to Principal.Human("private-issuer", "private-admin"),
        "read-only agent" to agent(AgentMode.READ_ONLY),
        "commit agent" to agent(AgentMode.COMMIT),
        "anonymous" to Principal.Anonymous,
    )
    val policy = PolicyService(
        roles = object : RoleRepository by roles {
            override fun roleOf(issuer: String, externalId: String): Role? {
                roleLookups++
                return roles.roleOf(issuer, externalId)
            }
        },
        apiTokens = object : ApiTokenRepository by tokens {
            override fun modeOf(id: String, now: Instant): AgentMode? {
                tokenLookups++
                return tokens.modeOf(id, now)
            }
        },
        audit = audit,
        idProvider = TestIdProvider(),
        clock = clock,
        mode = mode,
        editableOf = { it.value != "readonly" },
        objectBackendOf = { it.value == "object" },
        discussionsEnabledOf = { true },
    )

    fun warnings(): List<ILoggingEvent> = appender.list.filter {
        it.loggerName == PolicyService::class.java.name && it.level == Level.WARN
    }

    fun debugDenials(): List<ILoggingEvent> = appender.list.filter {
        it.loggerName == PolicyService::class.java.name && it.level == Level.DEBUG
    }

    override fun close() {
        policyLogger.level = previousLevel
        root.detachAppender(appender)
        appender.stop()
        driver.close()
    }
}
