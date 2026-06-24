package com.plainbase.frameworks.ktor

import com.plainbase.domain.page.PageId
import com.plainbase.domain.principal.Principal
import com.plainbase.domain.repository.AgentMode
import com.plainbase.domain.repository.Role
import com.plainbase.domain.service.AccessDenied
import com.plainbase.domain.service.ApiTokenService
import com.plainbase.domain.service.IdProvider
import com.plainbase.domain.service.PolicyService
import com.plainbase.domain.service.unifiedDiff
import com.plainbase.frameworks.ktor.dto.ChangeDetail
import com.plainbase.frameworks.ktor.dto.ChangeSummary
import com.plainbase.frameworks.ktor.dto.ListChangesResponse
import com.plainbase.frameworks.ktor.dto.ProposeChangeRequest
import com.plainbase.frameworks.ktor.dto.ProposeChangeResponse
import com.plainbase.frameworks.ktor.dto.RejectChangeRequest
import com.plainbase.frameworks.ktor.dto.RestJson
import com.plainbase.frameworks.security.ApiTokenMinter
import com.plainbase.frameworks.security.TokenHasher
import com.plainbase.frameworks.sqldelight.DatabaseFactory
import com.plainbase.frameworks.sqldelight.SqlDelightApiTokenRepository
import com.plainbase.frameworks.sqldelight.SqlDelightAuditRepository
import com.plainbase.frameworks.sqldelight.SqlDelightProposalRepository
import com.plainbase.frameworks.sqldelight.SqlDelightRoleRepository
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * PB-PROPOSE-1 native gate (P1a, §WI-11): the closed-world image would otherwise never compile the proposal DTO
 * serializers, the `checkApprove` choke point, or the `7.sqm` migration. Proves (E3) BOTH the REQUEST decode paths
 * AND the RESPONSE encode paths round-trip through the scoped [RestJson] (manual serialization, NOT
 * content-negotiation — so no reflect-config triple is needed, the AuthDto idiom), the enforced `checkApprove`
 * deny+allow holds natively (hole #4: JVM floor AND native), the `proposals` table is present in the migrated
 * native DB, and the bounded `unifiedDiff` completes on a large input without OOM.
 *
 * @Tag("native") + kotlin.test only.
 */
@Tag("native")
class ProposalDtoNativeTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)
    }

    @Test
    fun `the PB-PROPOSE-1 request DTOs DECODE through RestJson natively (E3)`() {
        val proposeJson = """
            {"operation":"edit","page_id":"0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a","base_hash":"sha256:${"0".repeat(64)}",
             "proposed_content":"# New\n","rationale":"r"}
        """.trimIndent()
        val request = RestJson.decodeFromString(ProposeChangeRequest.serializer(), proposeJson)
        assertEquals("edit", request.operation)
        assertEquals("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a", request.pageId)
        assertEquals("# New\n", request.proposedContent)

        val reject = RestJson.decodeFromString(RejectChangeRequest.serializer(), """{"comment":"no"}""")
        assertEquals("no", reject.comment)
    }

    @Test
    fun `the PB-PROPOSE-1 response DTOs ENCODE+DECODE round-trip through RestJson natively`() {
        val response = ProposeChangeResponse(
            id = "01900000-0000-7000-9000-000000000001",
            status = "PENDING",
            unifiedDiff = "@@ -0,0 +1,1 @@\n+x\n",
        )
        assertRoundTrips(ProposeChangeResponse.serializer(), response)

        val detail = ChangeDetail(
            id = "01900000-0000-7000-9000-000000000001", operation = "edit", status = "PENDING",
            targetPath = "a.md", pageId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a", baseHash = "sha256:${"0".repeat(64)}",
            baseDrifted = false, authorLabel = "ci", authorIssuer = "agent", authorExternalId = "pb_a",
            createdAt = "2023-11-14T22:13:20Z", rationale = "r", unifiedDiff = "@@ -0,0 +1,1 @@\n+x\n",
            approverIssuer = null, approverExternalId = null, decisionComment = null, decidedAt = null, appliedCommit = null,
        )
        assertRoundTrips(ChangeDetail.serializer(), detail)

        val summary = ChangeSummary(
            id = "01900000-0000-7000-9000-000000000001", operation = "edit", status = "PENDING", targetPath = "a.md",
            pageId = null, baseDrifted = false, authorLabel = "ci", createdAt = "2023-11-14T22:13:20Z", rationale = "r",
        )
        val list = ListChangesResponse(proposals = listOf(summary))
        assertRoundTrips(ListChangesResponse.serializer(), list)
    }

    private fun <T> assertRoundTrips(serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        assertEquals(value, RestJson.decodeFromString(serializer, RestJson.encodeToString(serializer, value)))
    }

    @Test
    fun `checkApprove enforced deny+allow holds natively (hole #4)`() {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val db = DatabaseFactory.createDatabase(driver)
            val roles = SqlDelightRoleRepository(db)
            val tokens = SqlDelightApiTokenRepository(db)
            var n = 0
            val ids = IdProvider { PageId.of("0190aaaa-bbbb-7ccc-8ddd-%012d".format(n++))!! }
            val policy = PolicyService(roles, tokens, SqlDelightAuditRepository(db), ids, fixedClock, enforced = true)

            // ADMIN is allowed; an EDITOR + a PROPOSE agent are denied.
            roles.upsert("builtin", "admin", Role.ADMIN, fixedClock.now())
            roles.upsert("builtin", "editor", Role.EDITOR, fixedClock.now())
            policy.checkApprove(Principal.Human("builtin", "admin"), "proposal:p:approve") // no throw
            assertFailsWith<AccessDenied> { policy.checkApprove(Principal.Human("builtin", "editor"), "proposal:p:approve") }

            val agentSvc = ApiTokenService(minter = ApiTokenMinter(), hasher = TokenHasher(), tokens = tokens, clock = fixedClock)
            val agent = Principal.Agent(agentSvc.mint(label = "ci", mode = AgentMode.PROPOSE).id)
            assertFailsWith<AccessDenied> { policy.checkApprove(agent, "proposal:p:approve") }
            assertFailsWith<AccessDenied> { policy.checkApprove(Principal.Anonymous, "proposal:p:approve") }
        }
    }

    @Test
    fun `the proposals table exists in the migrated native DB`() {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            // The repo round-trips a row only if the 7.sqm migration created the proposals table in the image.
            val repo = SqlDelightProposalRepository(DatabaseFactory.createDatabase(driver))
            assertTrue(repo.all().isEmpty())
            // A name SELECT against sqlite_master proves the table by name (the migration ran closed-world).
            val name = driver.executeQuery(
                identifier = null,
                sql = "SELECT name FROM sqlite_master WHERE type='table' AND name='proposals'",
                mapper = { cursor ->
                    cursor.next()
                    app.cash.sqldelight.db.QueryResult.Value(cursor.getString(0))
                },
                parameters = 0,
            ).value
            assertEquals("proposals", name)
        }
    }

    @Test
    fun `unifiedDiff completes on a large input under the image (no OOM, no stall)`() {
        val base = buildString { repeat(20_000) { append("base-").append(it).append('\n') } }.toByteArray()
        val proposed = buildString { repeat(20_000) { append("prop-").append(it).append('\n') } }.toByteArray()
        val diff = unifiedDiff(base, proposed)
        assertTrue(diff.contains("@@ "))
    }
}
