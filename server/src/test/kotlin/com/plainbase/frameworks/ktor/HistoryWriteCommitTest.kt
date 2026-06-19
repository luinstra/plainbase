package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.domain.service.CitationFactory
import com.plainbase.frameworks.filesystem.Fixtures
import com.plainbase.frameworks.git.GitExecutor
import com.plainbase.frameworks.git.headCommits
import com.plainbase.frameworks.git.openOracle
import com.plainbase.frameworks.git.providerOver
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * W5 F4 — in Git mode a SAVE response (`PUT` edit AND `POST` create) reports the REAL commit SHA the
 * save produced, equal to the new HEAD commit, NOT `commit:null`. The off-Git path is covered
 * byte-for-byte by [WriteRouteTest] (`commit` is `JsonNull`) and the frozen [WriteGoldenTest]; this is
 * the additive Git-mode complement (NOT a forever-golden). The SHA is asserted to be SHA-shaped and to
 * equal HEAD via the JGit oracle, never pinned (git's commit encoding owns the value — the §A6
 * normalization convention applied to a write response). Real git over the harness's own temp root,
 * never mocked; the same provider drives BOTH the recorded commit and the read-side citations.
 */
class HistoryWriteCommitTest : FunSpec({

    val sha = Regex("^[0-9a-f]{40}([0-9a-f]{24})?$")
    val citations = CitationFactory()
    val deployGuideId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
    val seed: (IdMapRepository) -> Unit = { idMap ->
        idMap.bind(TreePath.require("guides/deploy-guide.md"), PageId.require(deployGuideId), materialized = false)
    }

    fun markdown(): ContentType = ContentType.parse("text/markdown")
    fun etag(hash: String) = "\"$hash\""
    suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    /** The current HEAD commit SHA, via the JGit oracle reading what shell-git wrote. */
    fun headSha(root: Path): String = openOracle(root).use { it.headCommits().first().name }

    /** A real Git provider (nested repo, lazy-init on first commit) over the harness's own [root]. */
    fun gitFactory(): (Path) -> HistoryProvider = { root ->
        val home = Files.createTempDirectory("plainbase-write-commit-home")
        providerOver(GitExecutor(workTree = root, home = home), root, home)
    }

    test("a PUT edit in Git mode reports the save's real commit SHA, equal to the new HEAD") {
        writeRestTest(Fixtures.demoDocs, seed, historyFactory = gitFactory()) { harness ->
            val original = harness.diskBytes("guides/deploy-guide.md")
            // Seed HEAD so the PUT advances it: commit the current bytes once through the harness provider.
            harness.history.commit(TreePath.require("guides/deploy-guide.md"), original)

            val edited = original + "\nEdited body.\n".toByteArray()
            val put = client.put("/api/v1/pages/$deployGuideId") {
                header(HttpHeaders.IfMatch, etag(citations.contentHash(original)))
                contentType(markdown())
                setBody(edited)
            }
            put.status shouldBe HttpStatusCode.OK
            val commit = put.json().getValue("commit").jsonPrimitive.content
            commit shouldMatch sha
            commit shouldBe headSha(harness.root)
        }
    }

    test("a POST create in Git mode reports the create's real commit SHA, equal to the new HEAD") {
        writeRestTest(Fixtures.demoDocs, seed, historyFactory = gitFactory()) { harness ->
            val post = client.post("/api/v1/pages") {
                contentType(ContentType.Application.Json)
                setBody("""{"folder":"guides","title":"Git Created"}""")
            }
            post.status shouldBe HttpStatusCode.Created
            val commit = post.json().getValue("commit").jsonPrimitive.content
            commit shouldMatch sha
            commit shouldBe headSha(harness.root)
        }
    }
})
