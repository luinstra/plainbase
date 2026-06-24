package com.plainbase.domain.service

import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.MintedToken
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.principal.TokenMinter
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.repository.Role
import com.plainbase.frameworks.security.TokenHasher
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightApiTokenRepository
import com.plainbase.frameworks.sqldelight.SqlDelightAuditRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRoleRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The P1a `checkApprove` matrix (closes hole #4 — CI/smoke run `auth.mode=off`, where `allows` short-circuits and
 * the whole matrix is INVISIBLE, so this MUST construct `PolicyService(enforced = true)`). ADMIN-only (D1 — APPROVE
 * rides `Role.ADMIN -> true`; VIEWER/EDITOR/agents/Anonymous all denied). The denied audit row is written BEFORE the
 * throw (the `gate` ordering). Mirrors [PolicyServiceTest]'s construction. JVM floor; the §WI-11 native item adds a
 * native deny+allow assertion.
 */
class CheckApproveMatrixTest : FunSpec({

    val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)
    }

    fun <T> withPolicy(block: (PolicyService, SqlDelightRoleRepository, SqlDelightApiTokenRepository, SqlDelightAuditRepository) -> T): T =
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val db = DatabaseFactory.createDatabase(driver)
            val roles = SqlDelightRoleRepository(db)
            val tokens = SqlDelightApiTokenRepository(db)
            val audit = SqlDelightAuditRepository(db)
            var n = 0
            val ids = IdProvider { PageId.of("0190aaaa-bbbb-7ccc-8ddd-%012d".format(n++))!! }
            block(PolicyService(roles, tokens, audit, ids, fixedClock, enforced = true), roles, tokens, audit)
        }

    fun human(externalId: String) = Principal.Human(issuer = "builtin", externalId = externalId)

    fun seedAgent(tokens: SqlDelightApiTokenRepository, mode: AgentMode): Principal.Agent {
        val minter = object : TokenMinter {
            override fun mint() = MintedToken(id = "pb_${mode.name}", plaintext = "pb_x", secretHash = ByteArray(32))
        }
        val svc = ApiTokenService(minter = minter, hasher = TokenHasher(), tokens = tokens, clock = fixedClock)
        return Principal.Agent(tokenId = svc.mint(label = "agent", mode = mode).id)
    }

    test("ADMIN gets an ApproveGrant and the audit row is action=APPROVE decision=allowed") {
        withPolicy { policy, roles, _, audit ->
            roles.upsert("builtin", "admin", Role.ADMIN, fixedClock.now())
            policy.checkApprove(human("admin"), "proposal:p1:approve") // returns a grant, no throw
            val rows = audit.recent(10)
            rows shouldHaveSize 1
            rows.single().action shouldBe "APPROVE"
            rows.single().decision shouldBe "allowed"
            rows.single().resource shouldBe "proposal:p1:approve"
        }
    }

    test("VIEWER, EDITOR, every agent mode, and Anonymous are DENIED — the deny row is written BEFORE the throw") {
        withPolicy { policy, roles, tokens, audit ->
            roles.upsert("builtin", "viewer", Role.VIEWER, fixedClock.now())
            roles.upsert("builtin", "editor", Role.EDITOR, fixedClock.now())
            val proposeAgent = seedAgent(tokens, AgentMode.PROPOSE)
            val commitAgent = seedAgent(tokens, AgentMode.COMMIT)
            val readOnlyAgent = seedAgent(tokens, AgentMode.READ_ONLY)

            val denied = listOf(
                human("viewer"),
                human("editor"),
                proposeAgent,
                commitAgent,
                readOnlyAgent,
                Principal.Anonymous,
            )
            for (principal in denied) {
                shouldThrow<AccessDenied> { policy.checkApprove(principal, "proposal:p1:approve") }
            }
            // One denied audit row per attempt, all action=APPROVE decision=denied (written before each throw).
            val rows = audit.recent(20)
            rows shouldHaveSize denied.size
            rows.map { it.action }.toSet() shouldBe setOf("APPROVE")
            rows.map { it.decision }.toSet() shouldBe setOf("denied")
        }
    }

    test("OFF mode (enforced=false) authorizes EVERY principal incl. Anonymous (the E1 short-circuit)") {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val db = DatabaseFactory.createDatabase(driver)
            var n = 0
            val ids = IdProvider { PageId.of("0190aaaa-bbbb-7ccc-8ddd-%012d".format(n++))!! }
            val policy = PolicyService(
                SqlDelightRoleRepository(db),
                SqlDelightApiTokenRepository(db),
                SqlDelightAuditRepository(db),
                ids,
                fixedClock,
                enforced = false,
            )
            // No throw — off-mode opens the choke point (this is exactly why an enforced=true matrix is mandatory).
            policy.checkApprove(Principal.Anonymous, "proposal:p1:approve")
            policy.checkApprove(human("nobody"), "proposal:p1:approve")
        }
    }
})
