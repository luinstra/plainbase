package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.content.CreateResult
import com.plainbase.domain.content.Nfc
import com.plainbase.domain.content.PercentCoding
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.service.FrontmatterPatcher
import com.plainbase.domain.service.WriteIntent
import com.plainbase.frameworks.ktor.RestServices
import com.plainbase.frameworks.ktor.dto.AssetUploadResponse
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import com.plainbase.frameworks.ktor.dto.PageExistsBody
import com.plainbase.frameworks.ktor.dto.PageExistsEnvelope
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
 * id-shape → media type → `If-Match` shape → body-cap (streamed) → index lookup → route-layer id
 * check → pipeline.
 */
fun Route.pageWriteRoutes(services: RestServices) {
    route("/api/v1/pages") {
        put("/{id}") {
            val id = call.pageId() ?: return@put

            // (2) Media type — RAW body must be text/markdown.
            if (call.request.contentType().withoutParameters() != ContentType.parse("text/markdown")) {
                return@put call.respondError(
                    HttpStatusCode.UnsupportedMediaType,
                    ErrorCodes.UNSUPPORTED_MEDIA_TYPE,
                    "PUT requires Content-Type: text/markdown (the raw document bytes)",
                )
            }

            // (3) If-Match base_hash — must be a present, double-quoted, strong `"sha256:<64-hex>"`.
            val baseHash = call.parseIfMatchBaseHash()
                ?: return@put call.respondError(
                    HttpStatusCode.BadRequest,
                    ErrorCodes.INVALID_BASE_HASH,
                    "If-Match must be a strong entity-tag \"sha256:<64 lowercase hex>\" (the base_hash you last saw)",
                )

            // (4) Stream the body counting bytes to limit+1; over the cap aborts BEFORE buffering it all.
            val bytes = call.receiveBodyCapped(services.maxWriteBodyBytes)
                ?: return@put call.respondBodyTooLarge(services.maxWriteBodyBytes)

            // (5) Path-param id is the identity authority (R1): an id absent from the index is 404 —
            // the route never invents a path.
            val current = services.indexBuilder.current.byId[id]
                ?: return@put call.respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page with id ${id.value}")

            // (6) Route-layer id-tamper check (R1): the submitted buffer's `id:` line must denote the SAME
            // identity as the page's CURRENT on-disk `id:` line. BOTH sides read through the IDENTICAL
            // `PATCHER.readIdValue` — over the submitted bytes and over `current.markdown` (the verbatim
            // lenient decode the index captured; the `id:` line is pure ASCII by the patcher grammar, so the
            // round-trip is faithful) — and the two raw values compare via [sameIdentity] (canonical-UUID when
            // both parse, else byte-identical raw). Reading BOTH sides with the same reader is what keeps the
            // check symmetric: a byte-identical save of a page whose on-disk id is QUOTED (`id: "<uuid>"`)
            // round-trips to 200 because both raw values are the same quoted string, where the old
            // `current.id`-vs-`readIdValue` compare relied on `materialized` and false-422'd. Comparing the
            // file's CURRENT id — never `current.id`, the assigned pageId — is what lets a duplicate/adopted page
            // whose on-disk id legitimately differs from its pageId (non-materialized, still carrying an `id:`
            // line) take a pure-body edit, matching `WritePipeline.classifyEdit` exactly. Adding/changing/removing
            // the honored id is a rename → 422 before the pipeline runs; the path-param `{id}` stays the identity
            // authority (R1).
            val submittedRaw = PATCHER.readIdValue(bytes)
            val honoredRaw = PATCHER.readIdValue(current.markdown.toByteArray())
            if (!sameIdentity(submittedRaw, honoredRaw)) {
                return@put call.respondUnsupportedEdit("id")
            }

            // (7) The pipeline owns the write; render the outcome via the frozen wire mapping, applying
            // the retry-idempotency shim (stale base_hash but on-disk == submitted → 200 no-op).
            val submittedHash = services.citations.contentHash(bytes)
            val outcome = services.writePipeline.write(WriteIntent(id, current.path, baseHash, bytes))
            call.respondWriteWire(outcome.toWire(submittedHash))
        }

        // W3b (NON-FROZEN): `POST /api/v1/pages/{id}/assets` — uploads a raw binary into the page's OWN
        // folder. NOT a page (no frontmatter, no minted id, no WritePipeline) — it maps CreateResult
        // directly, never the page-shaped toWire/WriteOutcome. The write goes through the new fail-closed,
        // never-creates-a-dir `ContentStore.writeAssetExclusive` (design call 2), which reuses W2's
        // containment guards as ONE source of truth.
        post("/{id}/assets") {
            // (1) Page id — the shared §A4 canonical gate (400 invalid_page_id on a bad shape).
            val id = call.pageId() ?: return@post

            // (2) Filename — the strict decode→NFC→cap→reject pipeline; the SOLE single-segment validator.
            val filename = call.assetFilename()
                ?: return@post call.respondError(
                    HttpStatusCode.BadRequest,
                    ErrorCodes.INVALID_ASSET_REQUEST,
                    "filename must be a single valid, non-`.md`, non-reserved name (see ?filename=)",
                )

            // (3) Body cap — the SEPARATE, larger asset cap (assets are binaries); streamed, 413 on over.
            val bytes = call.receiveBodyCapped(services.maxAssetBytes)
                ?: return@post call.respondBodyTooLarge(services.maxAssetBytes)

            // (4) Resolve the page's folder from the published snapshot; an unknown id is 404.
            val page = services.indexBuilder.current.byId[id]
                ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page with id ${id.value}")

            // (4b) Snapshot membership ≠ disk reality: the page's .md may have been externally deleted since
            // the last rebuild while its FOLDER survives (always true for a top-level page, whose parent is the
            // content root that writeAssetExclusive's parent-exists check always passes). Re-check the page file
            // on disk so we don't write an asset — and return 201 — for a page that no longer exists. The
            // residual write-time TOCTOU is best-effort (debate-ratified): the worst case is a folder-relative
            // orphaned asset, never corruption.
            val pageStillOnDisk = try {
                services.contentStore.read(page.path) != null
            } catch (e: Exception) {
                // read() THROWS (file locked/unreadable after the last scan) — a transient FS fault, not a
                // missing page. Surface the asset route's documented retryable failure, not a bare 500.
                logger.warn(e) { "stale-page re-check failed reading '${page.path.value}'; treating as unreadable" }
                return@post call.respondError(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorCodes.CONTENT_UNREADABLE,
                    "The page file could not be read; nothing was written. Retry.",
                )
            }
            if (!pageStillOnDisk) {
                return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page with id ${id.value}")
            }

            // (5) The asset path = the page's folder + the validated segment. UNCONDITIONAL: childOf is
            // non-null and throws only on a bad segment, which step 2 has already excluded (no Elvis).
            val assetPath = TreePath.childOf(page.path.parent, filename)

            // (6) Atomic no-clobber write (NOT a check-then-write): writeAssetExclusive owns containment.
            when (val result = services.contentStore.writeAssetExclusive(assetPath, bytes, services.citations::contentHash)) {
                is CreateResult.Created -> {
                    // Make the asset reachable: a full rebuild puts it in current.assets so the returned URL
                    // serves (AssetRoute reads only from the snapshot). If the rebuild THROWS, the bytes are
                    // durably on disk but not yet indexed — surface 503 written-but-unindexed, not a bare 500.
                    try {
                        services.indexBuilder.rebuild()
                    } catch (e: Exception) {
                        logger.error(e) { "asset written but rebuild failed for '${assetPath.value}'; bytes are durable" }
                        return@post call.respondError(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorCodes.CONTENT_UNREADABLE,
                            "The asset was written to disk but the index update failed; it will become reachable after the next rebuild.",
                        )
                    }
                    call.respondRest(
                        AssetUploadResponse.serializer(),
                        AssetUploadResponse(
                            url = services.indexBuilder.current.assetUrl(assetPath),
                            path = assetPath.value,
                            contentHash = result.newHash,
                        ),
                        HttpStatusCode.Created,
                    )
                }
                is CreateResult.Exists -> {
                    // Self-heal a prior written-but-unindexed orphan: if the bytes are on disk but the
                    // asset is NOT in current.assets (a previous upload's post-write rebuild threw → 503),
                    // a plain 409 would leave it 404-unreachable forever. Best-effort rebuild FIRST so the
                    // orphaned-but-on-disk file becomes reachable on this retry. runCatching: a failing
                    // rebuild here must NOT turn the 409 into a 500. A path already in current.assets is a
                    // genuine duplicate → plain 409, no rebuild.
                    if (result.path !in services.indexBuilder.current.assets) {
                        runCatching { services.indexBuilder.rebuild() }
                    }
                    // Either way the existing file wins (no clobber; the retry's bytes were NOT written).
                    call.respondAssetExists(result.path)
                }
                is CreateResult.ParentMissing ->
                    // The page's folder vanished on disk between index time and upload — do NOT recreate it.
                    call.respondError(HttpStatusCode.NotFound, ErrorCodes.PAGE_NOT_FOUND, "No page with id ${id.value}")
                is CreateResult.Rejected -> {
                    logger.warn { "Refusing asset upload to '${assetPath.value}': ${result.reason}" }
                    call.respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_PATH, "The asset location cannot hold content")
                }
                is CreateResult.Unreadable ->
                    call.respondError(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorCodes.CONTENT_UNREADABLE,
                        "The asset could not be written to disk; nothing was written. Retry.",
                    )
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

/** The single frontmatter id-detection grammar (lenient decode — the id-inspection trap is closed in W3a). */
private val PATCHER = FrontmatterPatcher()

/**
 * Two raw `id:` line values (each from [FrontmatterPatcher.readIdValue], surrounding quotes NOT stripped)
 * denote the SAME identity iff they parse to the same canonical [PageId], OR — when one or both are not a
 * bare UUID (`id: "<uuid>"`, garbage, or absent) — they are the byte-identical raw string. The UUID arm
 * makes the check quote-TOLERANT across forms (`id: <uuid>` == `id: "<uuid>"` only if quoting didn't change
 * the parse — but a quoted value parses to null, so cross-quote equality holds only via the raw arm when the
 * quoting matches); the raw arm keeps a both-null (both-quoted/both-malformed/both-absent) comparison honest
 * instead of collapsing every unparseable id to "equal".
 */
private fun sameIdentity(a: String?, b: String?): Boolean {
    val pa = a?.let(PageId::of)
    val pb = b?.let(PageId::of)
    return if (pa != null && pb != null) pa == pb else a == b
}

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
