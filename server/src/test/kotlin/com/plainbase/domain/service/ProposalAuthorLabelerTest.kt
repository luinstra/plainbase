package com.plainbase.domain.service

import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.ApiTokenRepository
import com.plainbase.domain.repository.UserRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

/**
 * ProposalAuthorLabeler (P1a C4) — the B2 defense-in-depth property: the builtin-human label arm resolves the
 * snapshot label through the NARROW [UserRepository.displayNameById] (which selects only `display_name`), NEVER
 * [UserRepository.findById] (whose [com.plainbase.domain.repository.UserRow] carries `password_hash`). This is the
 * builtin-human symmetry to the agent-arm `agentLabelById`-only assertion in ProposalAuthzRouteTest's check-first
 * test; together they pin that no credential-bearing row is ever loaded just to read a display label.
 */
class ProposalAuthorLabelerTest : FunSpec({

    test("builtin-human label resolves via the narrow displayNameById, never findById (no credential-hash load)") {
        val tokens = mockk<ApiTokenRepository>()
        val users = mockk<UserRepository>()
        every { users.displayNameById("u1") } returns "Alice"

        val author = ProposalAuthorLabeler(tokens = tokens, users = users)
            .resolve(Principal.Human(issuer = "builtin", externalId = "u1"))

        author shouldBe ProposalAuthor(issuer = "builtin", externalId = "u1", label = "Alice")
        verify(exactly = 1) { users.displayNameById("u1") }
        verify(exactly = 0) { users.findById(any()) } // the full row (with password_hash) is never loaded
        verify { tokens wasNot Called } // a human never touches the token repo
    }

    test("builtin-human with no display name falls back to externalId") {
        val users = mockk<UserRepository>()
        every { users.displayNameById("u2") } returns null

        ProposalAuthorLabeler(tokens = mockk(), users = users)
            .resolve(Principal.Human(issuer = "builtin", externalId = "u2"))
            .label shouldBe "u2"
    }

    test("proxy-human (non-builtin issuer) labels with externalId and touches no repository") {
        val tokens = mockk<ApiTokenRepository>()
        val users = mockk<UserRepository>()

        ProposalAuthorLabeler(tokens = tokens, users = users)
            .resolve(Principal.Human(issuer = "proxy", externalId = "ext@example.com"))
            .label shouldBe "ext@example.com"

        verify { users wasNot Called }
        verify { tokens wasNot Called }
    }
})
