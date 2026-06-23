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
 * The A3 authorization core (WI 2): the FULL role×action matrix over BOTH identity sources (a Human's
 * `subject_role` row and an Agent's token `mode`), the mint-on-allow / throw-on-deny grant contract, and the
 * pre-effect audit row (allowed AND denied, MUTATING only — reads are not audited). Runs ENFORCED (auth-on);
 * the OFF (loopback-dev) open behavior is exercised by the route harnesses.
 */
class PolicyServiceTest : FunSpec({

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

    // Mint a real agent token row with the given mode via ApiTokenService, returning its Principal.Agent.
    fun seedAgent(tokens: SqlDelightApiTokenRepository, mode: AgentMode): Principal.Agent {
        val minter = object : TokenMinter {
            override fun mint() = MintedToken(id = "pb_${mode.name}", plaintext = "pb_x", secretHash = ByteArray(32))
        }
        val svc = ApiTokenService(minter = minter, hasher = TokenHasher(), tokens = tokens, clock = fixedClock)
        val minted = svc.mint(label = "agent", mode = mode)
        return Principal.Agent(tokenId = minted.id)
    }

    test("the full role x action matrix for a Human subject") {
        withPolicy { policy, roles, _, _ ->
            roles.upsert("builtin", "viewer", Role.VIEWER, fixedClock.now())
            roles.upsert("builtin", "editor", Role.EDITOR, fixedClock.now())
            roles.upsert("builtin", "admin", Role.ADMIN, fixedClock.now())

            // VIEWER: read only.
            policy.checkRead(human("viewer"), "p") // no throw
            shouldThrow<AccessDenied> { policy.checkEdit(human("viewer"), "p") }
            shouldThrow<AccessDenied> { policy.checkCreate(human("viewer"), "p") }
            shouldThrow<AccessDenied> { policy.checkManage(human("viewer")) }

            // EDITOR: read + edit + create, NOT manage.
            policy.checkRead(human("editor"), "p")
            policy.checkEdit(human("editor"), "p")
            policy.checkCreate(human("editor"), "p")
            shouldThrow<AccessDenied> { policy.checkManage(human("editor")) }

            // ADMIN: everything.
            policy.checkRead(human("admin"), "p")
            policy.checkEdit(human("admin"), "p")
            policy.checkCreate(human("admin"), "p")
            policy.checkManage(human("admin"))
        }
    }

    test("anonymous and an unknown identity (no subject_role row) deny everything") {
        withPolicy { policy, _, _, _ ->
            shouldThrow<AccessDenied> { policy.checkRead(Principal.Anonymous, "p") }
            shouldThrow<AccessDenied> { policy.checkEdit(Principal.Anonymous, "p") }
            shouldThrow<AccessDenied> { policy.checkRead(human("ghost"), "p") }
            shouldThrow<AccessDenied> { policy.checkManage(human("ghost")) }
        }
    }

    test("an Agent's mode maps onto the role axis: READ_ONLY -> VIEWER, PROPOSE/COMMIT -> EDITOR") {
        withPolicy { policy, _, tokens, _ ->
            val readOnly = seedAgent(tokens, AgentMode.READ_ONLY)
            policy.checkRead(readOnly, "p")
            shouldThrow<AccessDenied> { policy.checkEdit(readOnly, "p") }

            val propose = seedAgent(tokens, AgentMode.PROPOSE)
            policy.checkEdit(propose, "p")
            policy.checkCreate(propose, "p")
            shouldThrow<AccessDenied> { policy.checkManage(propose) }

            val commit = seedAgent(tokens, AgentMode.COMMIT)
            policy.checkEdit(commit, "p")
            shouldThrow<AccessDenied> { policy.checkManage(commit) }
        }
    }

    test("checkEdit returns a grant on allow and audits exactly one 'allowed' row; checkRead audits nothing") {
        withPolicy { policy, roles, _, audit ->
            roles.upsert("builtin", "editor", Role.EDITOR, fixedClock.now())
            policy.checkEdit(human("editor"), "page-1") // returns a non-null EditGrant (no throw)
            policy.checkRead(human("editor"), "page-1") // reads are NOT audited
            val rows = audit.recent(10)
            rows shouldHaveSize 1
            rows.single().decision shouldBe "allowed"
            rows.single().action shouldBe "EDIT"
            rows.single().resource shouldBe "page-1"
            rows.single().principalKind shouldBe "human"
            rows.single().externalId shouldBe "editor"
        }
    }

    test("a denied mutating check audits exactly one 'denied' row before throwing") {
        withPolicy { policy, roles, _, audit ->
            roles.upsert("builtin", "viewer", Role.VIEWER, fixedClock.now())
            shouldThrow<AccessDenied> { policy.checkEdit(human("viewer"), "page-9") }
            val rows = audit.recent(10)
            rows shouldHaveSize 1
            rows.single().decision shouldBe "denied"
            rows.single().resource shouldBe "page-9"
        }
    }

    test("non-escalation: a 'role: admin' claim has ZERO effect — privilege is only the subject_role row") {
        withPolicy { policy, roles, _, _ ->
            // The subject is an EDITOR in the DB. The ONLY place "admin" appears is a CLAIM — modeled here as the
            // resource string (the closest a non-escalation attacker can get to influencing check() through its
            // inputs). The verdict must come ONLY from the subject_role row, never the claim:
            roles.upsert("builtin", "editor", Role.EDITOR, fixedClock.now())
            // An admin-screaming resource does NOT grant manage to an EDITOR.
            shouldThrow<AccessDenied> { policy.checkManage(Principal.Human("builtin", "editor")) }
            // And the EDITOR's genuine edit capability is unaffected by the claim — no throw (sanity that the
            // claim neither elevates nor demotes; the role row is the sole authority).
            policy.checkEdit(Principal.Human("builtin", "editor"), "role: admin")
        }
    }
})
