package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.Commit
import com.plainbase.domain.history.CommitIdentity
import com.plainbase.domain.history.FileDiff
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.frameworks.git.GitExecutor
import com.plainbase.frameworks.git.providerOver
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * W5 §5 test 1 — in Git mode a page response AND a search hit carry a REAL commit SHA, and the read path
 * issues NO request-time git call. The SHA is NORMALIZED to `{{commit}}` (asserted to match a 40-hex /
 * 64-hex shape first), never pinned — git's commit encoding owns the value (the §A6 score-normalization
 * convention applied to `commit`). Real git over a temp repo, never mocked.
 */
class HistoryCitationTest : FunSpec({

    val sha = Regex("[0-9a-f]{40}([0-9a-f]{24})?$")
    val pageId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
    val pagePath = "notes/page.md"
    val seed: (IdMapRepository) -> Unit = { it.bind(TreePath.require(pagePath), PageId.require(pageId), materialized = false) }

    fun withTree(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("plainbase-history-citation")
        try {
            val file = root.resolve(pagePath)
            Files.createDirectories(file.parent)
            Files.writeString(file, "---\ntitle: Quagga Notes\n---\n\n# Quagga\n\nquagga herd basics.\n")
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun gitProvider(root: Path): HistoryProvider {
        val home = Files.createTempDirectory("plainbase-history-citation-home")
        val exec = GitExecutor(workTree = root, home = home)
        val provider = providerOver(exec, root, home)
        provider.commit(TreePath.require(pagePath), Files.readAllBytes(root.resolve(pagePath)))
        return provider
    }

    /** Asserts the value at [key] is a non-null SHA-shaped string, then placeholder-substitutes it. */
    fun normalizeCommit(obj: JsonObject, key: String): JsonObject {
        val value = obj.getValue(key).jsonPrimitive
        value.content shouldMatch sha
        return JsonObject(obj + (key to JsonPrimitive("{{commit}}")))
    }

    test("GET /pages/{id} carries a normalized non-null commit in Git mode") {
        withTree { root ->
            restTest(root, seed, history = gitProvider(root)) {
                val body = Json.parseToJsonElement(client.get("/api/v1/pages/$pageId").bodyAsText()).jsonObject
                normalizeCommit(body, "commit").getValue("commit") shouldBe JsonPrimitive("{{commit}}")
                normalizeCommit(body.getValue("citation").jsonObject, "commit").getValue("commit") shouldBe JsonPrimitive("{{commit}}")
            }
        }
    }

    test("a search hit carries a normalized non-null commit in Git mode") {
        withTree { root ->
            restTest(root, seed, history = gitProvider(root)) {
                val hits = Json.parseToJsonElement(client.get("/api/v1/search?q=quagga").bodyAsText())
                    .jsonObject.getValue("hits").jsonArray
                (hits.size >= 1) shouldBe true
                hits.forEach { hit ->
                    val citation = hit.jsonObject.getValue("citation").jsonObject
                    normalizeCommit(citation, "commit").getValue("commit") shouldBe JsonPrimitive("{{commit}}")
                }
            }
        }
    }

    test("the read path makes no git call (a spy provider records zero log/diff/lastCommits during GET/search)") {
        withTree { root ->
            val spy = CountingHistoryDecorator(gitProvider(root))
            restTest(root, seed, history = spy) {
                // The rebuild legitimately called lastCommits once; reset, then prove the READ path is git-free.
                spy.reset()
                repeat(3) {
                    client.get("/api/v1/pages/$pageId").status shouldBe HttpStatusCode.OK
                    client.get("/api/v1/pages/$pageId/html").status shouldBe HttpStatusCode.OK
                    client.get("/api/v1/search?q=quagga").status shouldBe HttpStatusCode.OK
                }
                spy.lastCommitsCalls shouldBe 0
                spy.logCalls shouldBe 0
                spy.diffCalls shouldBe 0
            }
        }
    }
})

/** Decorates a real provider, counting the read-surface calls so the citation read path can be proven git-free. */
private class CountingHistoryDecorator(private val delegate: HistoryProvider) : HistoryProvider {
    override val enabled get() = delegate.enabled
    var lastCommitsCalls = 0
    var logCalls = 0
    var diffCalls = 0

    override fun commit(path: TreePath, bytes: ByteArray, author: CommitIdentity?, committer: CommitIdentity?) =
        delegate.commit(path, bytes, author, committer)

    override fun lastCommits(paths: List<TreePath>): Map<TreePath, Commit> {
        lastCommitsCalls += 1
        return delegate.lastCommits(paths)
    }

    override fun log(path: TreePath, limit: Int?): List<Commit> {
        logCalls += 1
        return delegate.log(path, limit)
    }

    override fun diff(from: String, to: String, path: TreePath): FileDiff {
        diffCalls += 1
        return delegate.diff(from, to, path)
    }

    override fun prepare() = delegate.prepare()

    override fun gateCheck() = delegate.gateCheck()

    fun reset() {
        lastCommitsCalls = 0
        logCalls = 0
        diffCalls = 0
    }
}
