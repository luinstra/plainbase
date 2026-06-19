package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.WriteOutcome
import com.plainbase.domain.page.PageId
import com.plainbase.domain.render.HeadingSlugger
import com.plainbase.domain.service.CreateIntent
import com.plainbase.frameworks.ktor.RestServices
import com.plainbase.frameworks.ktor.dto.CreatePageRequest
import com.plainbase.frameworks.ktor.dto.CreatedButUnindexedResponse
import com.plainbase.frameworks.ktor.dto.CreatedResponse
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import com.plainbase.frameworks.ktor.dto.PageExistsBody
import com.plainbase.frameworks.ktor.dto.PageExistsEnvelope
import com.plainbase.frameworks.ktor.dto.RestJson
import com.plainbase.frameworks.ktor.dto.WriteWarning
import com.plainbase.frameworks.ktor.dto.WriteWarningCode
import com.plainbase.frameworks.ktor.dto.WriteWire
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.SerializationException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * PB-WRITE-1 (chunk W2): `POST /api/v1/pages` — new-page creation. A JSON request (`folder`, `title`,
 * optional `slug`/`body`); the SERVER mints the id, derives the on-disk path + filename via the frozen
 * §A4/PB-SLUG-1 machinery (the client never derives a path), composes a YAML-safe frontmatter+body
 * buffer, and writes it VERBATIM through [com.plainbase.domain.service.WritePipeline.create] — the same
 * serialized monitor every edit and watcher rebuild share. A collision is a race-safe pipeline outcome
 * (the filesystem's own exclusive create), never a route pre-check.
 *
 * This route owns its OWN status `when`: a create returns **201** (a new resource), not the PUT's 200,
 * so it does NOT reuse the frozen `WriteDtos.toWire` (which hard-codes `Written → 200` and carries the
 * edit-only retry-idempotency shim). The clean-create 201 carries `CreatedResponse` — PB-WRITE-1's
 * `WrittenResponse` shape PLUS the minted `id` + the server-authoritative canonical `url` (W6,
 * owner+debate-approved additive revision: 201 identifies the created resource so the client navigates
 * to the server's url, never a client-derived slug). The create-specific failure codes (`page_exists`,
 * `invalid_create_request`) are append-only additions to the frozen `ErrorCodes`.
 */
fun Route.pageCreateRoutes(services: RestServices) {
    route("/api/v1/pages") {
        post {
            // (1) Media type — the create request is JSON (NOT raw), so require application/json BEFORE
            // reading the body (mirrors PUT's text/markdown guard). `withoutParameters()` ignores the
            // charset param, so `application/json; charset=utf-8` matches; a missing/other type is 415.
            if (call.request.contentType().withoutParameters() != ContentType.Application.Json) {
                return@post call.respondError(
                    HttpStatusCode.UnsupportedMediaType,
                    ErrorCodes.UNSUPPORTED_MEDIA_TYPE,
                    "POST requires Content-Type: application/json",
                )
            }

            // (2) Body cap — stream-count to limit+1 (Content-Length advisory only), the SAME PB-WRITE-1
            // 413 the PUT enforces, so a giant create JSON can never be fully buffered.
            val rawBody = call.receiveBodyCapped(services.maxWriteBodyBytes)
                ?: return@post call.respondBodyTooLarge(services.maxWriteBodyBytes)

            val request = parseCreateRequest(rawBody)
                ?: return@post call.respondError(
                    HttpStatusCode.BadRequest,
                    ErrorCodes.INVALID_CREATE_REQUEST,
                    "Request body must be JSON: {folder?, title, slug?, body?}",
                )

            if (request.title.isBlank()) {
                return@post call.respondError(
                    HttpStatusCode.BadRequest,
                    ErrorCodes.INVALID_CREATE_REQUEST,
                    "title is required and must be non-blank",
                )
            }

            // (P3) title/slug are single-line metadata: a control char (newline/CR/tab/…) in either
            // could inject a `---` delimiter line into the composed frontmatter (or otherwise corrupt
            // the block), so REJECT them outright rather than emit ambiguous YAML.
            controlCharField(request.title, request.slug)?.let { field ->
                return@post call.respondError(
                    HttpStatusCode.BadRequest,
                    ErrorCodes.INVALID_CREATE_REQUEST,
                    "$field must not contain control characters (newline, CR, tab, …)",
                )
            }

            // A control char in a client-supplied folder is bad CREATE input — reject it at the route.
            // (TreePath's shared scan/read gate only rejects NUL, so a `\t`/`\n` in an EXISTING on-disk
            // name still indexes; create input is held to the stricter single-line standard here.)
            if (request.folder.any { it.isISOControl() }) {
                return@post call.respondError(
                    HttpStatusCode.BadRequest,
                    ErrorCodes.INVALID_CREATE_REQUEST,
                    "folder must not contain control characters (NUL, newline, CR, tab, …)",
                )
            }

            // The folder is the content-relative parent; "" (or omitted) is the content root. A folder
            // that fails TreePath validation (traversal/absolute/empty segment) is the client's 400.
            val folderPath = if (request.folder.isEmpty()) {
                null
            } else {
                TreePath.of(request.folder)
                    ?: return@post call.respondError(
                        HttpStatusCode.BadRequest,
                        ErrorCodes.INVALID_CREATE_REQUEST,
                        "folder is not a valid content-relative path: '${request.folder}'",
                    )
            }

            // Server-owned path: the filename is the §A4-slugified slug intent (else the title), `.md`.
            val filename = HeadingSlugger.slugify(request.slug ?: request.title, HeadingSlugger.PAGE_FALLBACK) + ".md"
            val path = TreePath.childOf(folderPath, filename)

            // NOTE: the canonical-URL/slug-collision check (page/folder/alias) is NOT a route pre-check —
            // it lives in WritePipeline.create UNDER the create monitor (race-safe against a concurrent
            // URL-colliding create), surfaced here as WriteOutcome.SlugConflict → 409 `slug_conflict`.
            val id = services.idProvider.next()
            val bytes = composeDocument(id.value, request.title, request.slug, request.body)

            // (P2) Composed-document cap — the server ADDS frontmatter, so a request just under the cap can
            // compose a document OVER it (unlike PUT, where the capped body is exactly what lands). Enforce
            // the SAME 413 body_too_large on the composed bytes before writing.
            if (bytes.size > services.maxWriteBodyBytes) {
                return@post call.respondBodyTooLarge(services.maxWriteBodyBytes)
            }

            val outcome = services.writePipeline.create(CreateIntent(pageId = id, path = path, bytes = bytes))
            call.respondCreateOutcome(outcome, id, services)
        }
    }
}

/**
 * Decodes the already-cap-checked [body] bytes as [CreatePageRequest] through the scoped [RestJson]
 * (independent of the negotiation policy, matching how the write routes own their parsing). Null on a
 * malformed request → the route's 400 `invalid_create_request`, never a Ktor-default 500.
 *
 * Two malformed cases, both null: (1) the bytes are not valid UTF-8 — unlike PUT's RAW transport (which
 * accepts invalid bytes verbatim as faithful content), the create request is JSON, which is defined over
 * valid Unicode, so bad UTF-8 is a malformed request, NOT content to preserve. A STRICT (reporting)
 * decode rejects it rather than leniently substituting U+FFFD and creating a corrupted page. (2) the
 * decoded text is not structurally-valid JSON for the DTO ([SerializationException]).
 */
private fun parseCreateRequest(body: ByteArray): CreatePageRequest? {
    val text = strictUtf8Decode(body) ?: return null
    return try {
        RestJson.decodeFromString(CreatePageRequest.serializer(), text)
    } catch (_: SerializationException) {
        null
    }
}

/** Strict UTF-8 decode (the [com.plainbase.domain.content.PercentCoding] idiom): null on any malformed/unmappable input. */
private fun strictUtf8Decode(bytes: ByteArray): String? {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }
}

/**
 * Returns the name of the first of [title]/[slug] that contains an ISO control character (newline, CR,
 * tab, etc. — `Char.isISOControl`), or null when both are control-char-free. Titles/slugs are
 * single-line by nature; a control char could inject a frontmatter delimiter into the composed block.
 */
private fun controlCharField(title: String, slug: String?): String? = when {
    title.any { it.isISOControl() } -> "title"
    slug?.any { it.isISOControl() } == true -> "slug"
    else -> null
}

/** 409 `slug_conflict` — the create's canonical URL is already owned by a published page; nothing written. */
private suspend fun ApplicationCall.respondSlugConflict(urlPath: String) {
    respondText(
        RestJson.encodeToString(
            PageExistsEnvelope.serializer(),
            PageExistsEnvelope(
                PageExistsBody(
                    code = ErrorCodes.SLUG_CONFLICT,
                    message = "Another page already owns the canonical URL /docs/$urlPath",
                    path = urlPath,
                ),
            ),
        ),
        ContentType.Application.Json,
        HttpStatusCode.Conflict,
    )
}

/**
 * Composes the minimal frontmatter block (the minted [id], [title], optional [slug]) + [body], written
 * VERBATIM. The `id:` line is plain ASCII (the patcher's shape); `title`/`slug` are emitted as
 * YAML double-quoted scalars (quote-always) with `\` and `"` escaped, so a value bearing `:`/`[`/`>`/
 * `@`/`|`/`&`/`*`/`!`/quotes/backslashes/unicode composes to VALID YAML the reader reads back EXACTLY
 * (the inverse of ADR-0001: the writer must never PRODUCE ambiguous YAML).
 */
private fun composeDocument(id: String, title: String, slug: String?, body: String?): ByteArray =
    buildString {
        append("---\n")
        append("id: ").append(id).append('\n')
        append("title: ").append(yamlDoubleQuoted(title)).append('\n')
        if (slug != null) append("slug: ").append(yamlDoubleQuoted(slug)).append('\n')
        append("---\n\n")
        append(body.orEmpty())
    }.toByteArray(Charsets.UTF_8)

/** A YAML double-quoted scalar: `"` + the value with `\` and `"` backslash-escaped + `"`. */
private fun yamlDoubleQuoted(value: String): String =
    buildString(value.length + 2) {
        append('"')
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> append(c)
            }
        }
        append('"')
    }

/**
 * The route's OWN exhaustive [WriteOutcome] → wire mapping (it does NOT reuse `toWire`). Only four
 * outcomes are reachable from a create — a create has no `base_hash`/prior identity, so `Conflict`/
 * `UnsupportedEdit` are unreachable and `error(...)` if they ever appear.
 *
 * The clean-create 201 carries the minted [id] + the SERVER-AUTHORITATIVE canonical url (W6): after a
 * clean `Written` the pipeline's rebuild has already published the page, so the url is the real
 * `IndexedPage.url` looked up by id ([createUrl]) — the slugified/collision-de-duped form, NEVER the raw
 * on-disk [path]. The `WrittenButUnindexed` twin returns a present-`null` url instead: the page is
 * unpublished, so no reliable canonical url exists until reconciliation and fabricating one from the raw
 * path would diverge (slug override / unicode / collision-de-dup) — the client doesn't navigate there.
 */
private suspend fun ApplicationCall.respondCreateOutcome(outcome: WriteOutcome, id: PageId, services: RestServices) {
    when (outcome) {
        is WriteOutcome.Written -> {
            setContentHashETag(outcome.newHash)
            respondWriteWire(
                WriteWire.of(
                    HttpStatusCode.Created,
                    CreatedResponse.serializer(),
                    CreatedResponse(
                        id = id.value,
                        url = createUrl(id, services),
                        contentHash = outcome.newHash,
                        commit = outcome.commit,
                    ),
                ),
            )
        }
        is WriteOutcome.WrittenButUnindexed -> {
            setContentHashETag(outcome.newHash)
            respondWriteWire(
                // The SAME 201 as a clean create (never toWire's hard-coded 200), with the frozen warning body.
                // `url` is present-`null`: the page is unpublished, so there is no reliable canonical url until
                // reconciliation. Fabricating one from the raw on-disk path would diverge from the eventual
                // canonical (a `_folder.yaml` slug override / unicode / collision-de-dup shifts the segment), so
                // the honest answer is null — the client does NOT navigate on this branch, it shows the warning.
                WriteWire.of(
                    HttpStatusCode.Created,
                    CreatedButUnindexedResponse.serializer(),
                    CreatedButUnindexedResponse(
                        id = id.value,
                        url = null,
                        contentHash = outcome.newHash,
                        commit = null,
                        warning = WriteWarning(code = WriteWarningCode.REINDEX_DEFERRED, message = REINDEX_DEFERRED_MESSAGE),
                    ),
                ),
            )
        }
        is WriteOutcome.AlreadyExists ->
            respondText(
                RestJson.encodeToString(
                    PageExistsEnvelope.serializer(),
                    PageExistsEnvelope(
                        PageExistsBody(
                            code = ErrorCodes.PAGE_EXISTS,
                            message = "A page already exists at ${outcome.path.value}",
                            path = outcome.path.value,
                        ),
                    ),
                ),
                ContentType.Application.Json,
                HttpStatusCode.Conflict,
            )
        is WriteOutcome.InvalidLocation ->
            // P1: the requested location can never name content (ignored/excluded segment, or a
            // symlinked / outside-root ancestor). A 4xx, never a 5xx — the reason is diagnostic only.
            respondError(
                HttpStatusCode.BadRequest,
                ErrorCodes.INVALID_CREATE_REQUEST,
                "The requested folder cannot hold content: ${outcome.reason}",
            )
        is WriteOutcome.SlugConflict ->
            // P1/P2: the prospective canonical URL is owned by a different page/folder/live alias —
            // evaluated under the create monitor, so a concurrent URL-colliding create can't both win.
            respondSlugConflict(outcome.urlPath)
        is WriteOutcome.Unreadable ->
            respondError(
                HttpStatusCode.ServiceUnavailable,
                ErrorCodes.CONTENT_UNREADABLE,
                "The page could not be created on disk; nothing was written. Retry.",
            )
        is WriteOutcome.Conflict, is WriteOutcome.UnsupportedEdit ->
            error("create produced an edit-only outcome: ${outcome::class.simpleName}")
    }
}

private suspend fun ApplicationCall.respondWriteWire(wire: WriteWire) {
    respondText(wire.encode(), ContentType.Application.Json, wire.status)
}

/**
 * The clean-create page's SERVER-AUTHORITATIVE addressable url. The published `IndexedPage.url` (looked
 * up by the minted [id]) is the canonical path — the slugified, collision-de-duped form via
 * `PercentCoding.encodePath`, which DIVERGES from the raw on-disk path on slug-override, unicode, and
 * collision-de-dup. A page that LOST the path-space race carries a null canonical `url` (it's reachable
 * only via its permalink), so the fallback is the `/p/{id}` permalink — the one URL that ALWAYS resolves
 * for any published page (the server 302s a winner's permalink → canonical, and serves a loser's
 * permalink directly). We NEVER fabricate a `/docs/<raw path>` url: the raw path diverges from the
 * canonical and points at a route that may not exist (a 404 for a loser). This is called ONLY from the
 * clean `Written` branch, where the rebuild has already published the page; the unindexed branch returns
 * a `null` url instead (the client doesn't navigate there).
 */
internal fun createUrl(id: PageId, services: RestServices): String =
    services.indexBuilder.current.byId[id]?.url ?: "/p/${id.value}"

/** The W3a default warning message for a deferred reindex (R2) — shared text with `WriteDtos`. */
private const val REINDEX_DEFERRED_MESSAGE =
    "Saved to disk; search/history update deferred to next startup reconciliation."
