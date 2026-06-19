package com.plainbase.frameworks.ktor

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.history.FileDiff
import com.plainbase.domain.history.HistoryProvider
import com.plainbase.domain.page.PageId
import com.plainbase.domain.repository.IdMapRepository
import com.plainbase.frameworks.filesystem.Fixtures
import com.plainbase.frameworks.git.GitCommandException
import com.plainbase.frameworks.git.GitExecutor
import com.plainbase.frameworks.git.providerOver
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * W5 §5 tests 3 / 4 — the `/history` and `/diff` read routes. Git mode is exercised over a REAL temp repo
 * (never mocked git): a [com.plainbase.frameworks.git.GitCliHistoryProvider] over the same content tree
 * the harness indexes, with the page committed via the provider. Off-Git uses the default NoOp harness,
 * which must answer empty-200 with `git_enabled:false` — distinct from "Git on, no commits yet".
 */
class HistoryRoutesTest : FunSpec({

    val pageId = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a"
    val pagePath = "notes/page.md"
    val seed: (IdMapRepository) -> Unit = { it.bind(TreePath.require(pagePath), PageId.require(pageId), materialized = false) }

    /** A temp content tree with one page file, cleaned up after [block]. */
    fun withTree(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("plainbase-history-routes")
        try {
            val file = root.resolve(pagePath)
            Files.createDirectories(file.parent)
            Files.writeString(file, "---\ntitle: Page\n---\n\n# Page\n\nbody.\n")
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("GET /history returns commits newest-first with git_enabled true in git mode") {
        withTree { root ->
            val home = Files.createTempDirectory("plainbase-history-home")
            val exec = GitExecutor(workTree = root, home = home)
            val provider = providerOver(exec, root, home)
            // Two versions of the page → two commits, newest first.
            provider.commit(TreePath.require(pagePath), "v1\n".toByteArray())
            provider.commit(TreePath.require(pagePath), "v2\n".toByteArray())
            val newest = exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()

            restTest(root, seed, history = provider) {
                val body = Json.parseToJsonElement(client.get("/api/v1/pages/$pageId/history").bodyAsText()).jsonObject
                body.getValue("git_enabled").jsonPrimitive.content shouldBe "true"
                val commits = body.getValue("commits").jsonArray
                commits.size shouldBe 2
                commits.first().jsonObject.getValue("sha").jsonPrimitive.content shouldBe newest
            }
            home.toFile().deleteRecursively()
        }
    }

    test("GET /history bounds the read with a default max-count (never an unbounded list)") {
        // W5 revision BLOCKING: a page with very deep history must not return an unbounded list. The route
        // passes a default cap to `log(path, limit)`. A delegating provider records the limit it was called
        // with (and still delegates to the real bounded log, so the response is genuinely capped). Proving
        // the limit is PASSED (not its exact value) is the contract; committing 100+ versions would be slow.
        withTree { root ->
            val home = Files.createTempDirectory("plainbase-history-home")
            val exec = GitExecutor(workTree = root, home = home)
            val provider = providerOver(exec, root, home)
            provider.commit(TreePath.require(pagePath), "v1\n".toByteArray())
            provider.commit(TreePath.require(pagePath), "v2\n".toByteArray())
            var recordedLimit: Int? = -1 // -1 ⇒ log was never called
            val recordingLimit = object : HistoryProvider by provider {
                override fun log(path: TreePath, limit: Int?): List<com.plainbase.domain.history.Commit> {
                    recordedLimit = limit
                    return provider.log(path, limit)
                }
            }
            restTest(root, seed, history = recordingLimit) {
                val body = Json.parseToJsonElement(client.get("/api/v1/pages/$pageId/history").bodyAsText()).jsonObject
                body.getValue("commits").jsonArray.size shouldBe 2 // newest-first, both under the cap
            }
            // The route passed a positive, bounded default cap — NOT null (unbounded).
            (recordedLimit != null && recordedLimit!! > 0) shouldBe true
            home.toFile().deleteRecursively()
        }
    }

    test("GET /history is empty-200 with git_enabled false when git is off") {
        withTree { root ->
            restTest(root, seed) {
                val response = client.get("/api/v1/pages/$pageId/history")
                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body.getValue("git_enabled").jsonPrimitive.content shouldBe "false"
                body.getValue("commits").jsonArray.size shouldBe 0
            }
        }
    }

    test("GET /history with git on but no commits is empty-200 with git_enabled true (the disambiguation)") {
        withTree { root ->
            val home = Files.createTempDirectory("plainbase-history-home")
            val exec = GitExecutor(workTree = root, home = home)
            exec.run(listOf("init")).ok shouldBe true // a real repo, but the page is never committed
            val provider = providerOver(exec, root, home)
            restTest(root, seed, history = provider) {
                val body = Json.parseToJsonElement(client.get("/api/v1/pages/$pageId/history").bodyAsText()).jsonObject
                body.getValue("git_enabled").jsonPrimitive.content shouldBe "true"
                body.getValue("commits").jsonArray.size shouldBe 0
            }
            home.toFile().deleteRecursively()
        }
    }

    test("GET /history 404s for an unknown page id (page existence, distinct from the git flag)") {
        restTest(Fixtures.demoDocs) {
            client.get("/api/v1/pages/00000000-0000-0000-0000-000000000000/history").status shouldBe HttpStatusCode.NotFound
        }
    }

    test("GET /history 400s for a non-canonical page id") {
        restTest(Fixtures.demoDocs) {
            client.get("/api/v1/pages/not-a-uuid/history").status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("GET /diff returns a unified diff with git_enabled true between two shas") {
        withTree { root ->
            val home = Files.createTempDirectory("plainbase-history-home")
            val exec = GitExecutor(workTree = root, home = home)
            val provider = providerOver(exec, root, home)
            provider.commit(TreePath.require(pagePath), "first line\n".toByteArray())
            provider.commit(TreePath.require(pagePath), "second line\n".toByteArray())
            val from = exec.run(listOf("rev-parse", "HEAD~1")).stdoutText.trim()
            val to = exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()

            restTest(root, seed, history = provider) {
                val response = client.get("/api/v1/pages/$pageId/diff?from=$from&to=$to")
                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body.getValue("git_enabled").jsonPrimitive.content shouldBe "true"
                val diff = body.getValue("unified_diff").jsonPrimitive.content
                diff shouldContain "-first line"
                diff shouldContain "+second line"
            }
            home.toFile().deleteRecursively()
        }
    }

    test("GET /diff is empty-200 with git_enabled false when git is off") {
        withTree { root ->
            restTest(root, seed) {
                val from = "a".repeat(40)
                val to = "b".repeat(40)
                val response = client.get("/api/v1/pages/$pageId/diff?from=$from&to=$to")
                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body.getValue("git_enabled").jsonPrimitive.content shouldBe "false"
                body.getValue("unified_diff").jsonPrimitive.content shouldBe ""
            }
        }
    }

    test("GET /diff 404s for an unknown sha (NOT_FOUND, never a 500)") {
        withTree { root ->
            val home = Files.createTempDirectory("plainbase-history-home")
            val exec = GitExecutor(workTree = root, home = home)
            val provider = providerOver(exec, root, home)
            provider.commit(TreePath.require(pagePath), "content\n".toByteArray())
            val real = exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()

            restTest(root, seed, history = provider) {
                val response = client.get("/api/v1/pages/$pageId/diff?from=deadbeefdeadbeefdeadbeefdeadbeefdeadbeef&to=$real")
                response.status shouldBe HttpStatusCode.NotFound
            }
            home.toFile().deleteRecursively()
        }
    }

    test("GET /diff 400s for a malformed (non-hex) sha") {
        withTree { root ->
            restTest(root, seed) {
                client.get("/api/v1/pages/$pageId/diff?from=zzz&to=HEAD").status shouldBe HttpStatusCode.BadRequest
            }
        }
    }

    // W5 P2: an OPERATIONAL diff failure (timeout, corrupt repo, unsupported flag) is NOT an unresolvable
    // ref — the route must surface it as 500, never collapse it to the 404 that hides it as "no diff".
    // A provider whose diff throws a plain GitCommandException (delegating reads so rebuild still works)
    // proves the route only 404s the narrow UnknownRevisionException.
    test("GET /diff surfaces an operational failure as 500, not 404") {
        withTree { root ->
            val home = Files.createTempDirectory("plainbase-history-home")
            val exec = GitExecutor(workTree = root, home = home)
            val real = providerOver(exec, root, home)
            real.commit(TreePath.require(pagePath), "content\n".toByteArray())
            val sha = exec.run(listOf("rev-parse", "HEAD")).stdoutText.trim()
            // Delegate everything (so the harness rebuild's lastCommits works) except diff, which throws an
            // OPERATIONAL GitCommandException — NOT the UnknownRevisionException the route maps to 404.
            val operationalDiff = object : HistoryProvider by real {
                override fun diff(from: String, to: String, path: TreePath): FileDiff =
                    throw GitCommandException("diff $from..$to", 128, "fatal: unable to read tree object")
            }
            restTest(root, seed, history = operationalDiff) {
                val response = client.get("/api/v1/pages/$pageId/diff?from=$sha&to=$sha")
                response.status shouldBe HttpStatusCode.InternalServerError
            }
            home.toFile().deleteRecursively()
        }
    }
})
