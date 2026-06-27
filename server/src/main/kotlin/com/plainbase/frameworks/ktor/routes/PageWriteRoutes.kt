package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.content.Nfc
import com.plainbase.domain.content.PercentCoding
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.service.AssetWriteOutcome
import com.plainbase.domain.service.CitationFactory
import com.plainbase.domain.service.SaveRequest
import com.plainbase.domain.service.SaveResult
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.dto.AssetUploadResponse
import com.plainbase.frameworks.ktor.dto.DegradedToProposalResponse
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import com.plainbase.frameworks.ktor.dto.PageExistsBody
import com.plainbase.frameworks.ktor.dto.PageExistsEnvelope
import com.plainbase.frameworks.ktor.dto.ProposalStatusWire
import com.plainbase.frameworks.ktor.dto.RestJson
import com.plainbase.frameworks.ktor.dto.UnsupportedEditBody
import com.plainbase.frameworks.ktor.dto.UnsupportedEditEnvelope
import com.plainbase.frameworks.ktor.dto.WriteWire
import com.plainbase.frameworks.ktor.dto.toWire
import com.plainbase.frameworks.ktor.dto.unsupportedEditCode
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * PB-WRITE-1 (chunk W3a, FROZEN): `PUT /api/v1/pages/{id}` — the one content-mutating save route.
 * A thin wire mapping over W1's [com.plainbase.domain.service.WritePipeline.write]; W1 owns the
 * correctness (disk-authoritative CAS, the rename guard, the write-ahead dirty mark), W3a pins the
 * outcome→wire mapping forever.
 *
 * The request is RAW: the body is the EXACT document bytes (`Content-Type: text/markdown`, written
 * VERBATIM — BOM and leniently-accepted invalid UTF-8 included), and `base_hash` rides the
 * `If-Match` header as an RFC 7232 STRONG entity-tag `"sha256:<64 lowercase hex>"`. RAW makes a
 * `GET`'s `ETag` the next `PUT`'s `If-Match` by construction — JSON would have round-tripped the
 * document through a string and made a BOM/invalid-UTF-8 file un-`PUT`-able forever.
 *
 * Every rejection is explicit and golden-pinnable — never a Ktor-default 500. The order matters:
 * id-shape → media type → `If-Match` shape → body-cap (streamed) → the guarded facade `save`. The facade owns
 * the AUTHORIZATION order from there: the audited EDIT check FIRST (so a denied PUT writes a denied-EDIT audit
 * row, never swallowed by an unaudited read), THEN the snapshot index lookup + the id-tamper check + pipeline.
 */
fun Route.pageWriteRoutes(ctx: RouteContext) {
    route("/api/v1/pages") {
        put("/{id}") {
            val principal = ctx.mutatingPrincipalOrRefuse(call) ?: return@put
            call.guarded {
                val id = call.pageId() ?: return@guarded

                // (2) Media type — RAW body must be text/markdown.
                if (call.request.contentType().withoutParameters() != ContentType.parse("text/markdown")) {
                    return@guarded call.respondError(
                        HttpStatusCode.UnsupportedMediaType,
                        ErrorCodes.UNSUPPORTED_MEDIA_TYPE,
                        "PUT requires Content-Type: text/markdown (the raw document bytes)",
                    )
                }

                // (3) If-Match base_hash — must be a present, double-quoted, strong `"sha256:<64-hex>"`.
                val baseHash = call.parseIfMatchBaseHash()
                    ?: return@guarded call.respondError(
                        HttpStatusCode.BadRequest,
                        ErrorCodes.INVALID_BASE_HASH,
                        "If-Match must be a strong entity-tag \"sha256:<64 lowercase hex>\" (the base_hash you last saw)",
                    )

                // (4) Stream the body counting bytes to limit+1; over the cap aborts BEFORE buffering it all.
                val bytes = call.receiveBodyCapped(ctx.maxWriteBodyBytes)
                    ?: return@guarded call.respondBodyTooLarge(ctx.maxWriteBodyBytes)

                // (5+6+7) The guarded facade owns the WHOLE write decision and EXACTLY-ONCE audit. For a Human/Anonymous
                // (and the proposal-apply caller) the EDIT check (audited, mint-or-throw) fires FIRST — BEFORE any read —
                // so a denied PUT writes a denied-EDIT audit row instead of being swallowed by an unaudited read-check.
                // The P5 agent DIRECT_PUT path deliberately RELAXES that strict ordering (an AGENT-ONLY, non-auditing
                // agentModeFor + in-memory snapshot lookup runs before the audited check on the chosen direct/degrade
                // branch); it still audits EXACTLY once and a deny still throws with no content returned (see
                // GuardedMutatingFacade.save). After the grant, the facade resolves the page from the snapshot (R1: id
                // absent → 404 PageNotFound — the route never invents a path) and runs the PB-WRITE-1 id-tamper check (a
                // submitted `id:` denoting a different identity is a rename → 422 IdMismatch, before the pipeline runs). A
                // Written outcome renders through the frozen wire mapping, applying the retry-idempotency shim (stale
                // base_hash but on-disk == submitted → 200 no-op).
                val submittedHash = CITATIONS.contentHash(bytes)
                when (val result = ctx.mutate.save(principal, SaveRequest(id, baseHash, bytes))) {
                    SaveResult.PageNotFound ->
                        call.respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page with id ${id.value}")
                    SaveResult.IdMismatch -> call.respondUnsupportedEdit("id")
                    is SaveResult.Written -> call.respondWriteWire(result.outcome.toWire(submittedHash))
                    // P5: an agent COMMIT write outside agentDirectCommit.globs degraded to a proposal — 202 with a
                    // NEW shape, never a field on the frozen WrittenResponse.
                    is SaveResult.DegradedToProposal ->
                        call.respondRest(
                            DegradedToProposalResponse.serializer(),
                            DegradedToProposalResponse(
                                proposalId = result.proposalId.value,
                                status = ProposalStatusWire.PENDING,
                                unifiedDiff = result.unifiedDiff,
                            ),
                            HttpStatusCode.Accepted,
                        )
                    // P5: the degrade's proposeEdit hit a stale base_hash / missing target — the existing propose
                    // vocabulary, now also a PUT-path outcome.
                    SaveResult.DegradeStaleBase ->
                        call.respondError(
                            HttpStatusCode.BadRequest,
                            ErrorCodes.STALE_BASE,
                            "The base you proposed against is no longer current; re-read the page and re-propose.",
                        )
                }
            }
        }

        // W3b (NON-FROZEN): `POST /api/v1/pages/{id}/assets` — uploads a raw binary into the page's OWN
        // folder. NOT a page (no frontmatter, no minted id, no WritePipeline) — it maps CreateResult
        // directly, never the page-shaped toWire/WriteOutcome. The write goes through the new fail-closed,
        // never-creates-a-dir `ContentStore.writeAssetExclusive` (design call 2), which reuses W2's
        // containment guards as ONE source of truth.
        post("/{id}/assets") {
            val principal = ctx.mutatingPrincipalOrRefuse(call) ?: return@post
            call.guarded {
                // (1) Page id — the shared §A4 canonical gate (400 invalid_page_id on a bad shape).
                val id = call.pageId() ?: return@guarded

                // (2) Filename — the strict decode→NFC→cap→reject pipeline; the SOLE single-segment validator.
                val filename = call.assetFilename()
                    ?: return@guarded call.respondError(
                        HttpStatusCode.BadRequest,
                        ErrorCodes.INVALID_ASSET_REQUEST,
                        "filename must be a single valid, non-`.md`, non-reserved name (see ?filename=)",
                    )

                // (3) Body cap — the SEPARATE, larger asset cap (assets are binaries); streamed, 413 on over.
                val bytes = call.receiveBodyCapped(ctx.maxAssetBytes)
                    ?: return@guarded call.respondBodyTooLarge(ctx.maxAssetBytes)

                // (4) The guarded facade owns the EDIT check + the WHOLE asset write: it resolves the page folder
                // from the snapshot, the stale-page recheck, the no-clobber writeAssetExclusive(grant, …), and the
                // post-write rebuild (the ungated internal rebuild — part of the write). It returns an
                // AssetWriteOutcome the route maps to status; no raw mutator/snapshot access lives here.
                when (val outcome = ctx.mutate.writeAsset(principal, id, filename, bytes, CITATIONS::contentHash)) {
                    is AssetWriteOutcome.Created ->
                        call.respondRest(
                            AssetUploadResponse.serializer(),
                            AssetUploadResponse(
                                url = outcome.url,
                                path = outcome.path.value,
                                contentHash = outcome.contentHash,
                            ),
                            HttpStatusCode.Created,
                        )
                    is AssetWriteOutcome.WrittenButUnindexed -> {
                        logger.error { "asset written but rebuild failed for '${outcome.path.value}'; bytes are durable" }
                        call.respondError(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorCodes.CONTENT_UNREADABLE,
                            "The asset was written to disk but the index update failed; it will become reachable after the next rebuild.",
                        )
                    }
                    is AssetWriteOutcome.Exists ->
                        // The existing file wins (no clobber; the retry's bytes were NOT written). The orphan
                        // self-heal rebuild, if any, already ran inside the facade.
                        call.respondAssetExists(outcome.path)
                    AssetWriteOutcome.PageMissing ->
                        // Unknown id, or the page's .md / folder vanished on disk — do NOT recreate it.
                        call.respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page with id ${id.value}")
                    is AssetWriteOutcome.Rejected -> {
                        logger.warn { "Refusing asset upload for page ${id.value}: ${outcome.reason}" }
                        call.respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_PATH, "The asset location cannot hold content")
                    }
                    AssetWriteOutcome.Unreadable ->
                        call.respondError(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorCodes.CONTENT_UNREADABLE,
                            "The asset could not be written to disk; nothing was written. Retry.",
                        )
                }
            }
        }
    }
}

/** The on-disk name of a folder's metadata sidecar (mirrors `LocalContentStore.FOLDER_META_NAME`). */
private const val FOLDER_META_NAME = "_folder.yaml"

/** The common FS NAME_MAX: an asset filename's NFC UTF-8 byte length must not exceed this. */
private const val ASSET_FILENAME_MAX_BYTES = 255

/** Windows reserved device names (case-insensitive, with or without an extension) — cross-platform safety. */
private val WINDOWS_RESERVED_NAMES: Set<String> =
    (listOf("CON", "PRN", "AUX", "NUL") + (1..9).map { "COM$it" } + (1..9).map { "LPT$it" }).toSet()

/** Printable chars that are legal on POSIX but illegal in a Windows filename (→ InvalidPathException) — reject for portability. */
private const val WINDOWS_INVALID_CHARS = "<>:\"|?*"

/**
 * Decodes + validates the `?filename=` for an asset upload into a single NFC path segment, or null →
 * 400 `invalid_asset_request`. The ORDER is load-bearing (the debate's hardening item #1):
 *
 *  1. Read the RAW (still-encoded) value from `rawQueryParameters`, apply the query-component space
 *     convention (`+`→space — application/x-www-form-urlencoded, what `URLSearchParams` emits) to the RAW
 *     value, then decode it EXACTLY ONCE with the project's strict [PercentCoding.decodeOnce] — NOT Ktor's
 *     lenient decoded `queryParameters` (the forbidden second decoder, which substitutes U+FFFD instead of
 *     rejecting). The `+`→space runs BEFORE decodeOnce: a literal `+` the client wants is sent as `%2B`
 *     (no bare `+`, so the replace is a no-op) and decodeOnce restores it; a bare `+` correctly becomes a
 *     space. This single strict decode subsumes charset-smuggling: an alternate/over-encoded form decodes
 *     to the same bytes or is rejected.
 *  2. [Nfc.normalize] — the project's single NFC call site for path data, the SAME normalization
 *     `TreePath.childOf` applies, so validation runs on the FINAL form (normalize-before-validate closes
 *     the traversal-bypass class).
 *  3. Cap the NFC UTF-8 BYTE length at [ASSET_FILENAME_MAX_BYTES] (not the char count — precomposed chars
 *     can smuggle a >255-byte name past a naive char check).
 *  4. Reject on the normalized form (this is the SOLE segment validator — step (5)'s `childOf` is total
 *     here): blank; `/` or `\` (an asset is ONE segment, never a client-chosen subtree); any ISO control
 *     char; bidi/directional-override controls; the Windows-illegal printables `< > : " | ? *` and a
 *     trailing dot/space (all legal on POSIX but they break or silently rename on a Windows mirror — a
 *     filesystem-native tree may sync there); `.` / `..`; Windows reserved names; AND — so the route's
 *     own post-write `rebuild()` can NEVER index the upload as a PAGE — `.md` (case-insensitive) and every
 *     scan-skipped name ([isAssetSkippedName], mirroring `LocalContentStore.isScanSkippedName`).
 */
private fun ApplicationCall.assetFilename(): String? {
    val raw = request.rawQueryParameters["filename"]?.replace('+', ' ') ?: return null
    val decoded = (PercentCoding.decodeOnce(raw) as? PercentCoding.DecodeResult.Success)?.value ?: return null
    val name = Nfc.normalize(decoded)
    if (name.toByteArray(Charsets.UTF_8).size > ASSET_FILENAME_MAX_BYTES) return null
    if (!isValidAssetSegment(name)) return null
    return name
}

/** True iff [name] is a legal, single-segment, indexable-as-an-asset filename (the [assetFilename] gate). */
private fun isValidAssetSegment(name: String): Boolean {
    if (name.isBlank()) return false
    if (name == "." || name == "..") return false
    if (name.any { it == '/' || it == '\\' || it.isISOControl() || it.isBidiControl() || it in WINDOWS_INVALID_CHARS }) return false
    // Windows silently trims a trailing dot or space, so `foo.png.` and `foo.png ` collide with `foo.png` —
    // reject for portability (distinct from the `.`/`..` whole-name reject above).
    if (name.endsWith('.') || name.endsWith(' ')) return false
    // Windows treats the name before the FIRST dot as the device — `CON`, `CON.png`, `CON.foo.png` all map
    // to CON. substringBefore('.') of a dotless name is the whole name, so this subsumes the bare-name check.
    if (name.substringBefore('.').uppercase() in WINDOWS_RESERVED_NAMES) return false
    if (name.endsWith(".md", ignoreCase = true)) return false // would be indexed as a PAGE by rebuild()
    if (isAssetSkippedName(name)) return false
    return true
}

/**
 * Mirrors `LocalContentStore.isScanSkippedName` for a single segment: a name the scan would skip — the
 * `_folder.yaml` sidecar or a dot-prefixed entry (`IgnoreRules`' always-ignored dotfile rule). The
 * `content.ignore` globs are deploy config and not consulted here (a single client filename can only
 * exercise the name-level skips); `writeAssetExclusive`'s `rejectionReason` is the backstop for any
 * residual containment refusal. Reject so the post-write `rebuild()` can never silently drop the upload.
 */
private fun isAssetSkippedName(name: String): Boolean = name.equals(FOLDER_META_NAME, ignoreCase = true) || name.startsWith(".")

/** 409 `page_exists` for an asset name already taken — reuses W2's envelope (a thing exists at path). */
private suspend fun ApplicationCall.respondAssetExists(path: TreePath) {
    respondText(
        RestJson.encodeToString(
            PageExistsEnvelope.serializer(),
            PageExistsEnvelope(
                PageExistsBody(
                    code = ErrorCodes.PAGE_EXISTS,
                    message = "An asset already exists at ${path.value}",
                    path = path.value,
                ),
            ),
        ),
        ContentType.Application.Json,
        HttpStatusCode.Conflict,
    )
}

private val logger = KotlinLogging.logger {}

/** The frozen content-hash for the PUT retry-idempotency shim's submitted-hash (stateless; A3 dropped the bundle). */
private val CITATIONS = CitationFactory()

/** The frozen `If-Match` form: a double-quoted strong entity-tag wrapping `sha256:` + 64 lowercase hex. */
private val IF_MATCH_BASE_HASH = Regex("\"(sha256:[0-9a-f]{64})\"")

/**
 * Parses the `If-Match` header to the bare `sha256:<64-hex>` base_hash, or null when it is missing,
 * unquoted, weak (`W/"…"`), or not the frozen shape. Only the SHAPE is validated here: a shape-valid
 * hash that simply doesn't match disk is the 409 drift path, not a 400.
 */
private fun ApplicationCall.parseIfMatchBaseHash(): String? =
    request.headers[HttpHeaders.IfMatch]?.let { IF_MATCH_BASE_HASH.matchEntire(it)?.groupValues?.get(1) }

private suspend fun ApplicationCall.respondUnsupportedEdit(field: String) {
    respondText(
        RestJson.encodeToString(
            UnsupportedEditEnvelope.serializer(),
            UnsupportedEditEnvelope(
                UnsupportedEditBody(
                    code = unsupportedEditCode(field),
                    field = field,
                    message = "The submitted body's frontmatter id does not match the page id — identity is immutable.",
                ),
            ),
        ),
        ContentType.Application.Json,
        HttpStatusCode.UnprocessableEntity,
    )
}

private suspend fun ApplicationCall.respondWriteWire(wire: WriteWire) {
    respondText(wire.encode(), ContentType.Application.Json, wire.status)
}
