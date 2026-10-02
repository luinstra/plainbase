package com.plainbase.domain.service

import com.plainbase.domain.discussion.IdentityDigest
import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.MintedToken
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.principal.SubjectKey
import com.plainbase.domain.principal.TokenMinter
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.repository.Role
import com.plainbase.domain.root.RootName
import com.plainbase.frameworks.security.TokenHasher
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightApiTokenRepository
import com.plainbase.frameworks.sqldelight.SqlDelightAuditRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRoleRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import kotlin.time.Instant

class DiscussionPolicyTest : FunSpec({
    val root = RootName.PRIMARY
    val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)
    }
    val owner = Principal.Human("builtin", "owner")
    val other = Principal.Human("builtin", "other")
    val author = IdentityDigest.of(SubjectKey.of(owner))
    val facts = DiscussionFacts.Known("ok", null, "open", author, author)

    test("subject conversion matches persisted discussion digests") {
        SubjectKey.of(owner) shouldBe SubjectKey("builtin", "owner")
        SubjectKey.of(Principal.Agent("token")) shouldBe SubjectKey("agent", "token")
        SubjectKey.of(Principal.Anonymous) shouldBe SubjectKey("anonymous", "local")
    }

    test("viewer ownership allows only matching mutations and records one decision") {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val db = DatabaseFactory.createDatabase(driver)
            val roles = SqlDelightRoleRepository(db)
            val audit = SqlDelightAuditRepository(db)
            var serial = 0
            val policy = PolicyService(
                roles, SqlDelightApiTokenRepository(db), audit,
                IdProvider { PageId.of("0190aaaa-bbbb-7ccc-8ddd-%012d".format(serial++))!! },
                clock, enforced = true,
            )
            roles.upsert("builtin", "owner", Role.VIEWER, clock.now())
            roles.upsert("builtin", "other", Role.VIEWER, clock.now())
            val resource = RootedResource(root, "discussion/d/comment/c")

            val grant = policy.checkDiscussion(owner, DiscussionAction.EDIT, facts, resource)
            grant.reliedOn.author shouldBe SubjectKey.of(owner)
            shouldThrow<AccessDenied> {
                policy.checkDiscussion(other, DiscussionAction.EDIT, facts, resource)
            }
            audit.recent(10).map { Triple(it.action, it.resource, it.decision) } shouldBe listOf(
                Triple("DISCUSS", "${root.value}:discussion/d/comment/c", "denied"),
                Triple("DISCUSS", "${root.value}:discussion/d/comment/c", "allowed"),
            )
        }
    }

    test("human discussion matrix keeps edits author-owned and viewer status starter-owned") {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val db = DatabaseFactory.createDatabase(driver)
            val roles = SqlDelightRoleRepository(db)
            val tokens = SqlDelightApiTokenRepository(db)
            val audit = SqlDelightAuditRepository(db)
            var serial = 0
            val policy = PolicyService(
                roles, tokens, audit,
                IdProvider { PageId.require("0190aaaa-bbbb-7ccc-8ddd-%012d".format(serial++)) },
                clock, enforced = true,
            )
            listOf("viewer" to Role.VIEWER, "editor" to Role.EDITOR, "admin" to Role.ADMIN).forEach { (id, role) ->
                roles.upsert("builtin", id, role, clock.now())
            }
            val otherFacts = DiscussionFacts.Known(
                "ok", null, "open", IdentityDigest.of(SubjectKey("builtin", "starter")),
                IdentityDigest.of(SubjectKey("builtin", "author")),
            )
            val resource = RootedResource(root, "discussion/d")

            listOf("viewer", "editor", "admin").forEach { id ->
                val principal = Principal.Human("builtin", id)
                policy.checkDiscussion(principal, DiscussionAction.START, DiscussionFacts.Missing, resource)
                policy.checkDiscussion(principal, DiscussionAction.COMMENT, otherFacts, resource)
                shouldThrow<AccessDenied> { policy.checkDiscussion(principal, DiscussionAction.EDIT, otherFacts, resource) }
                shouldThrow<AccessDenied> { policy.checkDiscussion(principal, DiscussionAction.RETRACT, otherFacts, resource) }
            }
            shouldThrow<AccessDenied> {
                policy.checkDiscussion(Principal.Human("builtin", "viewer"), DiscussionAction.RESOLVE, otherFacts, resource)
            }
            policy.checkDiscussion(Principal.Human("builtin", "editor"), DiscussionAction.RESOLVE, otherFacts, resource)
            policy.checkDiscussion(Principal.Human("builtin", "admin"), DiscussionAction.REOPEN, otherFacts, resource)
            shouldThrow<AccessDenied> {
                policy.checkDiscussion(Principal.Human("builtin", "viewer"), DiscussionAction.REATTACH, otherFacts, resource)
            }
            policy.checkDiscussion(Principal.Human("builtin", "editor"), DiscussionAction.REATTACH, otherFacts, resource)
            listOf("viewer", "editor").forEach { id ->
                shouldThrow<AccessDenied> {
                    policy.checkDiscussion(Principal.Human("builtin", id), DiscussionAction.PURGE, otherFacts, resource)
                }
            }
            policy.checkDiscussion(Principal.Human("builtin", "admin"), DiscussionAction.PURGE, DiscussionFacts.Unknown, resource)
            audit.recent(50).size shouldBe 20
        }
    }

    test("agent mode remains live and separate from the human DISCUSS role") {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val db = DatabaseFactory.createDatabase(driver)
            val tokens = SqlDelightApiTokenRepository(db)
            val audit = SqlDelightAuditRepository(db)
            var serial = 0
            val policy = PolicyService(
                SqlDelightRoleRepository(db), tokens, audit,
                IdProvider { PageId.require("0190aaaa-bbbb-7ccc-8ddd-%012d".format(serial++)) },
                clock, enforced = true,
            )
            fun agent(mode: AgentMode): Principal.Agent {
                val minter = object : TokenMinter {
                    override fun mint() = MintedToken("pb_${mode.name}", "pb_x", ByteArray(32))
                }
                val issued = ApiTokenService(minter, TokenHasher(), tokens, clock).mint("agent", mode)
                return Principal.Agent(issued.id)
            }
            val readOnly = agent(AgentMode.READ_ONLY)
            val propose = agent(AgentMode.PROPOSE)
            val commit = agent(AgentMode.COMMIT)
            val resource = RootedResource(root, "discussion/d")
            val otherFacts = DiscussionFacts.Known(
                "ok", null, "open", IdentityDigest.of(SubjectKey("builtin", "other")), null,
            )

            policy.checkDiscussionRead(readOnly, resource) shouldBe null
            shouldThrow<AccessDenied> { policy.checkDiscussion(readOnly, DiscussionAction.COMMENT, otherFacts, resource) }
            listOf(propose, commit).forEach { principal ->
                policy.checkDiscussion(principal, DiscussionAction.COMMENT, otherFacts, resource)
                policy.checkDiscussion(principal, DiscussionAction.RESOLVE, otherFacts, resource)
                shouldThrow<AccessDenied> { policy.checkDiscussion(principal, DiscussionAction.REATTACH, otherFacts, resource) }
                shouldThrow<AccessDenied> { policy.checkDiscussion(principal, DiscussionAction.PURGE, otherFacts, resource) }
            }
            tokens.revoke(propose.tokenId, clock.now())
            shouldThrow<AccessDenied> { policy.checkDiscussionRead(propose, resource) }
            shouldThrow<AccessDenied> { policy.checkDiscussion(propose, DiscussionAction.COMMENT, otherFacts, resource) }
        }
    }

    test("off-mode anonymous bypasses ownership and purge with no relied fact") {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val db = DatabaseFactory.createDatabase(driver)
            val audit = SqlDelightAuditRepository(db)
            var serial = 0
            val policy = PolicyService(
                SqlDelightRoleRepository(db), SqlDelightApiTokenRepository(db), audit,
                IdProvider { PageId.require("0190aaaa-bbbb-7ccc-8ddd-%012d".format(serial++)) },
                clock, enforced = false,
            )
            val resource = RootedResource(root, "discussion/d")
            val otherFacts = DiscussionFacts.Known(
                "ok", null, "open", IdentityDigest.of(SubjectKey("builtin", "starter")),
                IdentityDigest.of(SubjectKey("builtin", "author")),
            )
            listOf(
                DiscussionAction.EDIT, DiscussionAction.RETRACT, DiscussionAction.RESOLVE,
                DiscussionAction.REOPEN, DiscussionAction.REATTACH,
            ).forEach { action ->
                policy.checkDiscussion(Principal.Anonymous, action, otherFacts, resource).reliedOn shouldBe ReliedOn()
            }
            policy.checkDiscussion(Principal.Anonymous, DiscussionAction.PURGE, DiscussionFacts.Unknown, resource).reliedOn shouldBe
                ReliedOn()
            audit.recent(10).all { it.decision == "allowed" } shouldBe true
        }
    }

    test("off-mode agents still require live tokens and discussion mode capability") {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val db = DatabaseFactory.createDatabase(driver)
            val tokens = SqlDelightApiTokenRepository(db)
            val audit = SqlDelightAuditRepository(db)
            var serial = 0
            val policy = PolicyService(
                SqlDelightRoleRepository(db), tokens, audit,
                IdProvider { PageId.require("0190aaaa-bbbb-7ccc-8ddd-%012d".format(serial++)) },
                clock, enforced = false,
            )
            fun agent(mode: AgentMode): Principal.Agent {
                val minter = object : TokenMinter {
                    override fun mint() = MintedToken("pb_off_${mode.name}", "pb_x", ByteArray(32))
                }
                return Principal.Agent(ApiTokenService(minter, TokenHasher(), tokens, clock).mint("agent", mode).id)
            }
            val readOnly = agent(AgentMode.READ_ONLY)
            val propose = agent(AgentMode.PROPOSE)
            val commit = agent(AgentMode.COMMIT)
            val resource = RootedResource(root, "discussion/d")
            val otherFacts = DiscussionFacts.Known(
                "ok", null, "open", IdentityDigest.of(SubjectKey("builtin", "other")),
                IdentityDigest.of(SubjectKey("builtin", "other")),
            )

            policy.checkDiscussionRead(readOnly, resource) shouldBe null
            shouldThrow<AccessDenied> { policy.checkDiscussion(readOnly, DiscussionAction.COMMENT, otherFacts, resource) }
            listOf(propose, commit).forEach { principal ->
                policy.checkDiscussion(principal, DiscussionAction.RESOLVE, otherFacts, resource)
                shouldThrow<AccessDenied> { policy.checkDiscussion(principal, DiscussionAction.EDIT, otherFacts, resource) }
                shouldThrow<AccessDenied> { policy.checkDiscussion(principal, DiscussionAction.REATTACH, otherFacts, resource) }
                shouldThrow<AccessDenied> { policy.checkDiscussion(principal, DiscussionAction.PURGE, otherFacts, resource) }
            }
            tokens.revoke(propose.tokenId, clock.now())
            shouldThrow<AccessDenied> { policy.checkDiscussionRead(propose, resource) }
            shouldThrow<AccessDenied> { policy.checkDiscussion(propose, DiscussionAction.COMMENT, otherFacts, resource) }
            audit.recent(20).count { it.decision == "allowed" } shouldBe 2
            audit.recent(20).count { it.decision == "denied" } shouldBe 8
        }
    }
})
