package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.ktor.dto.ApplyResultResponse
import com.plainbase.frameworks.ktor.dto.AuditEntryResponse
import com.plainbase.frameworks.ktor.dto.AuditListResponse
import com.plainbase.frameworks.ktor.dto.BodyTooLargeBody
import com.plainbase.frameworks.ktor.dto.BodyTooLargeEnvelope
import com.plainbase.frameworks.ktor.dto.ChangeDetail
import com.plainbase.frameworks.ktor.dto.ChangeSummary
import com.plainbase.frameworks.ktor.dto.CitationDto
import com.plainbase.frameworks.ktor.dto.CommitDto
import com.plainbase.frameworks.ktor.dto.ConflictedResponse
import com.plainbase.frameworks.ktor.dto.CreatePageRequest
import com.plainbase.frameworks.ktor.dto.CreatedButUnindexedResponse
import com.plainbase.frameworks.ktor.dto.CreatedResponse
import com.plainbase.frameworks.ktor.dto.CreatedTokenResponse
import com.plainbase.frameworks.ktor.dto.DegradedToProposalResponse
import com.plainbase.frameworks.ktor.dto.DiffResponse
import com.plainbase.frameworks.ktor.dto.ErrorBody
import com.plainbase.frameworks.ktor.dto.ErrorEnvelope
import com.plainbase.frameworks.ktor.dto.HeadingDto
import com.plainbase.frameworks.ktor.dto.HighlightDto
import com.plainbase.frameworks.ktor.dto.HistoryResponse
import com.plainbase.frameworks.ktor.dto.ListChangesResponse
import com.plainbase.frameworks.ktor.dto.PageExistsBody
import com.plainbase.frameworks.ktor.dto.PageExistsEnvelope
import com.plainbase.frameworks.ktor.dto.PageHtmlResponse
import com.plainbase.frameworks.ktor.dto.PageResponse
import com.plainbase.frameworks.ktor.dto.PreviewResponse
import com.plainbase.frameworks.ktor.dto.RebasedResponse
import com.plainbase.frameworks.ktor.dto.RejectChangeRequest
import com.plainbase.frameworks.ktor.dto.RestJson
import com.plainbase.frameworks.ktor.dto.RoleListResponse
import com.plainbase.frameworks.ktor.dto.RoleResponse
import com.plainbase.frameworks.ktor.dto.RootTreeDto
import com.plainbase.frameworks.ktor.dto.SearchHitDto
import com.plainbase.frameworks.ktor.dto.SearchResponse
import com.plainbase.frameworks.ktor.dto.SessionResponse
import com.plainbase.frameworks.ktor.dto.TokenListResponse
import com.plainbase.frameworks.ktor.dto.TokenMetaResponse
import com.plainbase.frameworks.ktor.dto.TreeNodeDto
import com.plainbase.frameworks.ktor.dto.TreeResponse
import com.plainbase.frameworks.ktor.dto.UnsupportedEditBody
import com.plainbase.frameworks.ktor.dto.UnsupportedEditEnvelope
import com.plainbase.frameworks.ktor.dto.UserListResponse
import com.plainbase.frameworks.ktor.dto.UserResponse
import com.plainbase.frameworks.ktor.dto.WriteConflictBody
import com.plainbase.frameworks.ktor.dto.WriteConflictEnvelope
import com.plainbase.frameworks.ktor.dto.WriteWarning
import com.plainbase.frameworks.ktor.dto.WrittenButUnindexedResponse
import com.plainbase.frameworks.ktor.dto.WrittenResponse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.nio.file.Path

/**
 * The server half of the shared wire golden: `frontend/src/api/__fixtures__/wire-golden.json` is
 * asserted from BOTH sides (here via [RestJson] encode + parsed-tree equality — the RestGolden
 * comparison policy — and in `frontend/src/__tests__/wire-golden.test.ts` via typed-literal twins).
 * This pins server-vs-FRONTEND transcription SYNC, a seam no ForeverApiGoldenSuite golden covers
 * (those pin server-vs-contract); evolving a non-frozen shape stays legal — it now requires
 * touching the fixture and `types.ts` together. The two REQUEST entries assert the client→server
 * direction: an omitted optional decodes to its documented default.
 */
class WireTypeGoldenTest : FunSpec({

    val fixture = Json.parseToJsonElement(Files.readString(wireGoldenPath())).jsonObject

    val responses: Map<String, JsonElement> = mapOf(
        "treeResponse" to encoded(
            TreeResponse.serializer(),
            TreeResponse(
                roots = listOf(
                    RootTreeDto(
                        root = "main",
                        available = true,
                        editable = true,
                        tree = TreeNodeDto.Folder(
                            name = "",
                            title = null,
                            description = null,
                            path = "",
                            url = "/docs/main",
                            pageCount = 0,
                            children = listOf(
                                TreeNodeDto.Folder(
                                    name = "guides",
                                    title = "Guides",
                                    description = "How-to guides",
                                    path = "guides",
                                    url = "/docs/main/guides",
                                    pageCount = 1,
                                    children = listOf(
                                        TreeNodeDto.Page(
                                            id = PAGE_1,
                                            title = "Deploy Guide",
                                            slug = "deploy-guide",
                                            path = "guides/deploy-guide.md",
                                            url = "/docs/main/guides/deploy-guide",
                                            status = "published",
                                            updated = "2026-06-01",
                                        ),
                                    ),
                                ),
                                TreeNodeDto.Folder(
                                    name = "attic",
                                    title = null,
                                    description = null,
                                    path = "attic",
                                    url = null,
                                    pageCount = 0,
                                    children = emptyList(),
                                ),
                                TreeNodeDto.Page(
                                    id = PAGE_2,
                                    title = "Shadowed",
                                    slug = "shadowed",
                                    path = "shadowed.md",
                                    url = null,
                                    status = "published",
                                    updated = null,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
        // Null-coverage policy (both twins agree): every nullable field is null in at least one entry, so
        // a frontend de-nullification can't ship silently — here `url` (the collision-loser branch); the
        // non-null page url stays pinned by treeResponse/createdResponse.
        "pageResponse" to encoded(
            PageResponse.serializer(),
            PageResponse(
                id = PAGE_1,
                root = "main",
                path = "guides/deploy-guide.md",
                slug = "deploy-guide",
                url = null,
                title = "Deploy Guide",
                markdown = "# Deploy Guide\n\nShip it.\n",
                frontmatter = buildJsonObject {
                    put("title", "Deploy Guide")
                    putJsonArray("tags") {
                        add("ops")
                        add("deploy")
                    }
                },
                contentHash = HASH_A,
                idMaterialized = true,
                commit = null,
                citation = CitationDto(
                    pageId = PAGE_1,
                    headingId = null,
                    path = "guides/deploy-guide.md",
                    contentHash = HASH_A,
                    commit = null,
                    uri = "plainbase://main/$PAGE_1@$HASH_A",
                ),
            ),
        ),
        "pageHtmlResponse" to encoded(
            PageHtmlResponse.serializer(),
            PageHtmlResponse(
                id = PAGE_2,
                root = "main",
                path = "shadowed.md",
                slug = "shadowed",
                url = null,
                title = "Shadowed",
                html = "<h1 id=\"shadowed\">Shadowed</h1>",
                contentHash = HASH_B,
                // Top-level commit null (off Git); the citation keeps the non-null CitationDto.commit pin.
                commit = null,
                headings = listOf(HeadingDto(id = "shadowed", level = 1, text = "Shadowed")),
                citation = CitationDto(
                    pageId = PAGE_2,
                    headingId = "shadowed",
                    path = "shadowed.md",
                    contentHash = HASH_B,
                    commit = COMMIT_1,
                    uri = "plainbase://main/$PAGE_2@$HASH_B",
                ),
            ),
        ),
        "errorEnvelope" to encoded(
            ErrorEnvelope.serializer(),
            ErrorEnvelope(ErrorBody(code = "page_not_found", message = "No page with that id")),
        ),
        "searchResponse" to encoded(
            SearchResponse.serializer(),
            SearchResponse(
                query = "deploy",
                engine = "fts5",
                limit = 20,
                offset = 0,
                total = 1,
                hits = listOf(
                    SearchHitDto(
                        pageId = PAGE_2, root = "main", path = "shadowed.md", url = null, title = "Shadowed",
                        headingId = null, headingText = null, headingPath = emptyList(),
                        snippet = "Deploy targets are listed here.",
                        highlights = listOf(HighlightDto(start = 0, end = 6)),
                        score = 1.25,
                        citation = CitationDto(
                            pageId = PAGE_2,
                            headingId = null,
                            path = "shadowed.md",
                            contentHash = HASH_B,
                            commit = null,
                            uri = "plainbase://main/$PAGE_2@$HASH_B",
                        ),
                    ),
                ),
            ),
        ),
        "writtenResponse" to encoded(
            WrittenResponse.serializer(),
            WrittenResponse(contentHash = HASH_C, commit = null),
        ),
        "writtenButUnindexedResponse" to encoded(
            WrittenButUnindexedResponse.serializer(),
            WrittenButUnindexedResponse(contentHash = HASH_C, commit = null, warning = REINDEX_WARNING),
        ),
        "degradedToProposalResponse" to encoded(
            DegradedToProposalResponse.serializer(),
            DegradedToProposalResponse(
                proposalId = PROPOSAL_1,
                status = "PENDING",
                unifiedDiff = "--- a/guides/deploy-guide.md\n+++ b/guides/deploy-guide.md\n",
            ),
        ),
        "writeConflictEnvelope" to encoded(
            WriteConflictEnvelope.serializer(),
            WriteConflictEnvelope(
                WriteConflictBody(
                    code = "conflict",
                    reason = "content_changed",
                    message = "The page changed on disk since you loaded it.",
                    currentContent = "# Deploy Guide\n\nEdited elsewhere.\n",
                    currentHash = HASH_D,
                    currentPath = "guides/deploy-guide.md",
                ),
            ),
        ),
        "writeConflictEnvelopeDeleted" to encoded(
            WriteConflictEnvelope.serializer(),
            WriteConflictEnvelope(
                WriteConflictBody(
                    code = "conflict",
                    reason = "page_deleted",
                    message = "The page no longer exists on disk.",
                    currentContent = null,
                    currentHash = null,
                    currentPath = null,
                ),
            ),
        ),
        "unsupportedEditEnvelope" to encoded(
            UnsupportedEditEnvelope.serializer(),
            UnsupportedEditEnvelope(
                UnsupportedEditBody(
                    code = "slug_change_unsupported",
                    field = "slug",
                    message = "Changing slug is a move, not a save (deferred).",
                ),
            ),
        ),
        "bodyTooLargeEnvelope" to encoded(
            BodyTooLargeEnvelope.serializer(),
            BodyTooLargeEnvelope(
                BodyTooLargeBody(
                    code = "body_too_large",
                    message = "Request body exceeds the 1048576-byte limit",
                    maxBytes = 1_048_576,
                ),
            ),
        ),
        "pageExistsEnvelope" to encoded(
            PageExistsEnvelope.serializer(),
            PageExistsEnvelope(
                PageExistsBody(
                    code = "page_exists",
                    message = "A page already exists at guides/deploy-guide.md",
                    path = "guides/deploy-guide.md",
                ),
            ),
        ),
        "createdResponse" to encoded(
            CreatedResponse.serializer(),
            CreatedResponse(id = PAGE_1, url = "/docs/main/guides/deploy-guide", contentHash = HASH_A, commit = null),
        ),
        "createdButUnindexedResponse" to encoded(
            CreatedButUnindexedResponse.serializer(),
            CreatedButUnindexedResponse(id = PAGE_1, url = null, contentHash = HASH_A, commit = null, warning = REINDEX_WARNING),
        ),
        "previewResponse" to encoded(
            PreviewResponse.serializer(),
            PreviewResponse(
                html = "<h1 id=\"deploy-guide\">Deploy Guide</h1>",
                headings = listOf(HeadingDto(id = "deploy-guide", level = 1, text = "Deploy Guide")),
            ),
        ),
        "historyResponse" to encoded(
            HistoryResponse.serializer(),
            HistoryResponse(
                gitEnabled = true,
                commits = listOf(
                    CommitDto(
                        sha = COMMIT_1,
                        authorName = "Ada Lovelace",
                        authorEmail = "ada@example.com",
                        authorTime = "2026-06-01T12:00:00Z",
                        committerName = "Ada Lovelace",
                        committerEmail = "ada@example.com",
                        committerTime = "2026-06-01T12:00:00Z",
                        message = "docs: update deploy guide",
                    ),
                ),
            ),
        ),
        "diffResponse" to encoded(
            DiffResponse.serializer(),
            DiffResponse(
                gitEnabled = true,
                from = COMMIT_1,
                to = COMMIT_2,
                path = "guides/deploy-guide.md",
                unifiedDiff = "--- a/guides/deploy-guide.md\n+++ b/guides/deploy-guide.md\n",
            ),
        ),
        "sessionResponse" to encoded(
            SessionResponse.serializer(),
            SessionResponse(authenticated = false, username = null, csrfToken = null, authMode = "builtin"),
        ),
        "tokenListResponse" to encoded(
            TokenListResponse.serializer(),
            TokenListResponse(
                tokens = listOf(
                    TokenMetaResponse(
                        id = TOKEN_ID,
                        label = "ci-bot",
                        mode = "propose",
                        createdAt = "2026-06-01T12:00:00Z",
                        lastUsedAt = null,
                        expiresAt = null,
                        revokedAt = null,
                    ),
                ),
            ),
        ),
        "createdTokenResponse" to encoded(
            CreatedTokenResponse.serializer(),
            CreatedTokenResponse(id = TOKEN_ID, plaintext = "pb_c2VjcmV0LXRva2Vu"),
        ),
        "auditListResponse" to encoded(
            AuditListResponse.serializer(),
            AuditListResponse(
                entries = listOf(
                    AuditEntryResponse(
                        id = "01970000-0000-7000-8000-0000000000c1",
                        ts = "2026-06-01T12:00:00Z",
                        principalKind = "human",
                        issuer = null,
                        externalId = null,
                        action = "page.write",
                        resource = "guides/deploy-guide.md",
                        decision = "allow",
                    ),
                ),
            ),
        ),
        "roleListResponse" to encoded(
            RoleListResponse.serializer(),
            RoleListResponse(
                roles = listOf(
                    RoleResponse(issuer = "proxy", externalId = "ada@example.com", role = "admin", createdAt = "2026-06-01T12:00:00Z"),
                ),
            ),
        ),
        "userListResponse" to encoded(
            UserListResponse.serializer(),
            UserListResponse(
                users = listOf(
                    UserResponse(id = "01970000-0000-7000-8000-0000000000d1", username = "ada", displayName = null, disabled = false),
                ),
            ),
        ),
        "listChangesResponse" to encoded(
            ListChangesResponse.serializer(),
            ListChangesResponse(
                proposals = listOf(
                    ChangeSummary(
                        id = "01970000-0000-7000-8000-0000000000a2", operation = "create", status = "PENDING",
                        root = "main", targetPath = "guides/rollback.md", pageId = null, baseDrifted = false,
                        authorLabel = "ci-bot", createdAt = "2026-06-01T12:00:00Z", rationale = "Add a rollback guide.",
                    ),
                ),
            ),
        ),
        "changeDetail" to encoded(
            ChangeDetail.serializer(),
            ChangeDetail(
                id = "01970000-0000-7000-8000-0000000000a3", operation = "create", status = "PENDING",
                root = "main", targetPath = "guides/rollback.md", pageId = null,
                baseHash = null, baseDrifted = false,
                authorLabel = "ci-bot", authorIssuer = "plainbase", authorExternalId = TOKEN_ID,
                createdAt = "2026-06-01T12:00:00Z", rationale = "Add a rollback guide.",
                unifiedDiff = "--- /dev/null\n+++ b/guides/rollback.md\n",
                approverIssuer = null, approverExternalId = null, decisionComment = null,
                decidedAt = null, appliedCommit = null, statusReason = null,
            ),
        ),
        "applyResultResponse" to encoded(
            ApplyResultResponse.serializer(),
            ApplyResultResponse(newHash = HASH_D, commitSha = null, appliedAt = "2026-06-02T09:00:00Z", warnings = null),
        ),
        "conflictedResponse" to encoded(
            ConflictedResponse.serializer(),
            ConflictedResponse(currentHash = null, currentPath = null),
        ),
        "rebasedResponse" to encoded(
            RebasedResponse.serializer(),
            RebasedResponse(
                newBaseHash = HASH_D,
                unifiedDiff = "--- a/guides/deploy-guide.md\n+++ b/guides/deploy-guide.md\n",
            ),
        ),
    )

    responses.forEach { (key, element) ->
        test("$key matches the shared wire golden") {
            element shouldBe fixture.getValue(key)
        }
    }

    test("createPageRequest carries the client's declared root (multi-root C4) and defaults its other optionals") {
        RestJson.decodeFromString(CreatePageRequest.serializer(), fixture.getValue("createPageRequest").toString()) shouldBe
            CreatePageRequest(root = "extra", folder = "", title = "Deploy Guide", slug = null, body = null)
    }

    test("an omitted root does NOT decode - a create must SAY where the bytes land, and never be read as 'main'") {
        // The field has no default, so an omitted root is a decode failure, which the route answers 400
        // `invalid_create_request`. A default would have made forgetting it a silent relocation into main - and an
        // authorization decision (main's editable bit, main's globs) reachable by leaving a field out.
        shouldThrow<SerializationException> {
            RestJson.decodeFromString(CreatePageRequest.serializer(), """{"title":"Deploy Guide"}""")
        }
    }

    test("rejectChangeRequest decodes with the omitted comment null") {
        RestJson.decodeFromString(RejectChangeRequest.serializer(), fixture.getValue("rejectChangeRequest").toString()) shouldBe
            RejectChangeRequest(comment = null)
    }

    test("the fixture's key set equals the covered-entry set — 31 entries, none silently skipped") {
        fixture.keys shouldBe responses.keys + setOf("createPageRequest", "rejectChangeRequest")
        fixture.keys.size shouldBe 31
    }
})

/** Encodes through the scoped [RestJson] and re-parses — the RestGolden parsed-tree comparison policy. */
private fun <T> encoded(serializer: KSerializer<T>, value: T): JsonElement =
    Json.parseToJsonElement(RestJson.encodeToString(serializer, value))

/** The Fixtures walk-up idiom: locate the committed shared fixture from `user.dir`, CWD-independent. */
private fun wireGoldenPath(): Path {
    val relative = Path.of("frontend", "src", "api", "__fixtures__", "wire-golden.json")
    var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    while (dir != null) {
        val candidate = dir.resolve(relative)
        if (Files.isRegularFile(candidate)) return candidate
        dir = dir.parent
    }
    error("Could not locate $relative from ${System.getProperty("user.dir")}")
}

private const val PAGE_1 = "01970000-0000-7000-8000-000000000001"
private const val PAGE_2 = "01970000-0000-7000-8000-000000000002"
private const val PROPOSAL_1 = "01970000-0000-7000-8000-0000000000a1"
private const val TOKEN_ID = "01970000-0000-7000-8000-0000000000b1"
private const val COMMIT_1 = "1111111111111111111111111111111111111111"
private const val COMMIT_2 = "2222222222222222222222222222222222222222"
private val HASH_A = "sha256:" + "a".repeat(64)
private val HASH_B = "sha256:" + "b".repeat(64)
private val HASH_C = "sha256:" + "c".repeat(64)
private val HASH_D = "sha256:" + "d".repeat(64)

private val REINDEX_WARNING =
    WriteWarning(code = "reindex_deferred", message = "Saved to disk; search/history update deferred to next startup reconciliation.")
