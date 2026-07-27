package com.plainbase.frameworks.ktor.dto

import com.plainbase.domain.page.Citation
import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.FrontmatterValue
import com.plainbase.domain.page.Heading
import com.plainbase.domain.service.PageHtmlPayload
import com.plainbase.domain.service.PagePayload
import com.plainbase.domain.service.TreeNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The serializer for the PB-REST-1 response DTOs in this file — and ONLY these (§A4): the frozen
 * Phase-1 shapes guarantee present-`null` for nullable fields (`url`, `title`, `commit`,
 * `heading_id`), so `explicitNulls = true` here. Deliberately NOT the app-wide ContentNegotiation
 * policy — responses built from these DTOs bypass content negotiation and encode through this
 * instance, so the guarantee is scoped to the documented shapes.
 */
val RestJson: Json = Json {
    explicitNulls = true
    // PB-SEARCH-1 §A2 (phase-2 plan line 97): "encodeDefaults likewise" — a frozen field may never
    // vanish from the wire because some future DTO revision gives it a default value. No frozen DTO
    // carries a default today, so this changes nothing now (the golden corpora in the build prove it).
    encodeDefaults = true
}

/** The frozen error-code vocabulary (§A4, append-only). */
object ErrorCodes {
    /** 404: a canonical-shape-valid id (any version) absent from the index, or an unknown by-path. */
    const val PAGE_NOT_FOUND: String = "page_not_found"

    /**
     * 410: the page's binding was RETIRED (a C0 tombstone). The id was deleted from its root - an honest answer,
     * distinct from [PAGE_NOT_FOUND], which says the id was never ours at all. It is NOT permanent: the same
     * (root, path) may reclaim the id, so the route sends `Cache-Control: no-store`. An agent holding a citation
     * can still tell "this document was deleted" from "you made this up".
     */
    const val PAGE_RETIRED: String = "page_retired"

    /** 400: an id failing the §A4 canonical-shape regex (the regex decides, never JDK leniency). */
    const val INVALID_PAGE_ID: String = "invalid_page_id"

    /** 404: an unknown asset or browse path, or an unknown `/api/...` endpoint (never the SPA shell). */
    const val NOT_FOUND: String = "not_found"

    /** 400: a traversal attempt or malformed percent-encoding in a path. */
    const val INVALID_PATH: String = "invalid_path"

    /** 500: an uncaught server error (appended to the vocabulary; codes are append-only). */
    const val INTERNAL_ERROR: String = "internal_error"

    /** 400: any PB-SEARCH-1 §A1 grammar violation (missing/blank/oversized `q`, bad `limit`/`offset`); message names the rule. */
    const val INVALID_QUERY: String = "invalid_query"

    /**
     * 503: the configured search engine cannot be reached at request time. Registered now but
     * emitted by NO Phase-2 code path — the embedded engine is in-process and can never be
     * unreachable; reserved per §A5 so a future out-of-process engine appends no new vocabulary.
     */
    const val SEARCH_UNAVAILABLE: String = "search_unavailable"

    /**
     * 409: POST /api/v1/admin/reindex while a reindex is already running (admin surface; the
     * response body shape itself is NOT frozen, like RescanResponse). Already reserved in the
     * frozen §A5 vocabulary; appended here as a constant (codes are append-only).
     */
    const val REINDEX_IN_FLIGHT: String = "reindex_in_flight"

    // ---- PB-WRITE-1 (W3a): the write/save vocabulary (append-only; froze when W3a landed) --------

    /** 409: a save's base_hash no longer matches the on-disk bytes — the conflict envelope carries reason + current_*. */
    const val CONFLICT: String = "conflict"

    /** 422: a PUT body would change the frontmatter id (vs path-param, or vs the file's current id) — IDs are immutable. */
    const val ID_CHANGE_UNSUPPORTED: String = "id_change_unsupported"

    /** 422: a PUT body would change the slug — a re-slug is a move (deferred §H), never a save side effect. */
    const val SLUG_CHANGE_UNSUPPORTED: String = "slug_change_unsupported"

    /** 422: a PUT body would change redirect_from — an alias change is a move (deferred §H), never a save side effect. */
    const val REDIRECT_FROM_CHANGE_UNSUPPORTED: String = "redirect_from_change_unsupported"

    /** 503: the on-disk file could not be read at CAS time (locked/permission/transient FS) — retryable, nothing written. */
    const val CONTENT_UNREADABLE: String = "content_unreadable"

    /** 400: the base_hash (If-Match) header is missing, malformed, or not sha256:+64-hex. */
    const val INVALID_BASE_HASH: String = "invalid_base_hash"

    /** 413: a request body exceeding the configured PB-WRITE-1 max body size (the body carries the authoritative max_bytes). */
    const val BODY_TOO_LARGE: String = "body_too_large"

    /** 415: a PUT without the accepted text/markdown media type. */
    const val UNSUPPORTED_MEDIA_TYPE: String = "unsupported_media_type"

    // ---- PB-WRITE-1: the new-page-creation vocabulary (append-only) -----------------------------

    /** 409: POST /api/v1/pages targets a path that already exists on disk — nothing written; the body carries the path. */
    const val PAGE_EXISTS: String = "page_exists"

    /** 400: a POST /api/v1/pages request is malformed — missing/blank title, an invalid folder, or unparseable JSON. */
    const val INVALID_CREATE_REQUEST: String = "invalid_create_request"

    /** 409: a POST /api/v1/pages would claim a canonical URL/slug another page already owns — nothing written; the body carries the URL. */
    const val SLUG_CONFLICT: String = "slug_conflict"

    // ---- W3b: the asset-upload vocabulary (append-only) ----------------------------------------------

    /** 400: a POST …/assets request is malformed — a missing/blank/invalid filename, or a control-char filename. */
    const val INVALID_ASSET_REQUEST: String = "invalid_asset_request"

    // ---- multi-root C4: the per-root vocabulary (append-only) ----------------------------------------

    /**
     * 503 (+ `Retry-After`): the target root is not SERVING — its disk vanished, its watcher died, it was already
     * gone at boot, or its name has been removed from `roots {}` while its rows remain.
     *
     * Deliberately NOT a 404: a 404 tells an agent the page is GONE and it should drop its citations, when the truth
     * is that a disk is unmounted and the content is coming back. NOTHING was written on a write that answers this.
     * Recovery is an operator action (restore the root, restart the server), which is why the retry window is long.
     */
    const val ROOT_UNAVAILABLE: String = "root_unavailable"

    /**
     * 503 (+ a SHORT `Retry-After`): the durable index still binds this page and the store cannot produce its bytes.
     * The page is in LIMBO - neither present nor proven deleted (C1).
     *
     * **Deliberately NOT `root_unavailable`, and the difference is the operator's next hour.** The root may be
     * perfectly healthy: its other pages are serving normally, the disk is mounted, and there is nothing to restart -
     * what is in doubt is ONE page (a failed submount, a half-finished restore, a decoy tree at the mount point).
     * Reporting it as a downed root would send an operator to remount a volume that is already there.
     *
     * **Deliberately NOT a 404**, for the reason the whole absence-authority redesign exists: a 404 tells an agent
     * the page is GONE and it should drop its citations, and the page is not gone - we simply did not see it. It
     * self-heals with no operator action the moment the page is witnessed again, which is why the retry window is
     * short.
     */
    const val ABSENCE_UNVERIFIED: String = "absence_unverified"

    /** 403: the target root is declared `editable = false` — page-mutation writes are refused there in EVERY auth mode. */
    const val ROOT_NOT_EDITABLE: String = "root_not_editable"

    /** 400: a request named a root that is not a legal slug, or names no configured root. */
    const val INVALID_ROOT: String = "invalid_root"

    /**
     * 409 (REST) / 300 (permalink): a bare page id is held by more than one root and the caller named none, so the
     * server cannot pick one. The body carries the candidate roots + their per-root URLs; the caller retries with a
     * `root`. FAKE-only under `UNIQUE(id)` (no real row produces it until C5), but the contract ships in C4.
     */
    const val AMBIGUOUS_PAGE_ID: String = "ambiguous_page_id"

    // ---- A3: the authorization vocabulary (append-only) ----------------------------------------------

    /** 401: no (or anonymous) credential on a gated route under auth-on — the client must authenticate. */
    const val UNAUTHORIZED: String = "unauthorized"

    /** 403: an authenticated principal lacks the role for this action (the role×action matrix denied it). */
    const val FORBIDDEN: String = "forbidden"

    /** 421: a credential (bearer OR cookie) was presented over a NON-secure transport — refused before it was honored (A2/A4a). */
    const val TRANSPORT_INSECURE: String = "transport_insecure"

    // ---- A4a: the human-login vocabulary (append-only) -----------------------------------------------

    /** 400: a login/setup/reset/change request body is malformed (missing field, blank, or unparseable JSON). */
    const val INVALID_AUTH_REQUEST: String = "invalid_auth_request"

    /** 401: wrong username/password — OR a disabled user (mapped to the SAME 401, never an oracle). */
    const val INVALID_CREDENTIALS: String = "invalid_credentials"

    /** 403: a cookie-auth state mutation is missing or carries a wrong `X-CSRF-Token` (the §3 synchronizer guard). */
    const val CSRF_FAILED: String = "csrf_failed"

    /** 403: a cookie-auth state mutation's present `Origin`/`Referer` is cross-origin (fail-closed-when-present). */
    const val CROSS_ORIGIN: String = "cross_origin"

    /** 400: a setup/reset token is unknown, already used, or expired (single-use consume failed). */
    const val SETUP_TOKEN_INVALID: String = "setup_token_invalid"

    /** 429: the login rate limiter is throttling this source (per-IP or per-(IP,username)); retry after the backoff. */
    const val RATE_LIMITED: String = "rate_limited"

    /** 409: a create-user request targets a username that already exists. */
    const val USERNAME_EXISTS: String = "username_exists"

    // ---- A4b: the proxy-auth vocabulary (append-only) ------------------------------------------------

    /**
     * 400: in proxy mode, a trusted proxy passed the secret+transport gate but sent a malformed identity header
     * (multi-value/duplicate, blank, control chars, or oversized) — an operator MISCONFIG signal, never a 401. The
     * message names the class of problem, never the offending value.
     */
    const val INVALID_PROXY_IDENTITY: String = "invalid_proxy_identity"

    // ---- PB-PROPOSE-1 (P1a): the proposal vocabulary (append-only) -----------------------------------

    /** 400: an `edit` proposal's claimed `base_hash` no longer matches the live content, OR the target page no longer exists; nothing persisted. */
    const val STALE_BASE: String = "stale_base"

    /** 400: a malformed propose request — missing/blank/contradictory field, empty content, unknown operation, unparseable JSON, malformed UTF-8 envelope, traversal `target_path`, or a client `target_path` disagreeing with the `page_id`-resolved path. */
    const val INVALID_PROPOSE_REQUEST: String = "invalid_propose_request"

    /** 409: a `reject` (or future P1b decision) targeted a proposal no longer `PENDING` (already terminal/in-flight); no state change. */
    const val NOT_PENDING: String = "not_pending"

    // ---- PB-PROPOSE-1 (P1b): the apply/rebase vocabulary (append-only) -------------------------------

    /** 409: an apply hit disk-drift; the proposal is REBASABLE. Body is `ConflictedResponse` (carries `code="conflicted"`). DISTINCT from `not_pending`. */
    const val CONFLICTED: String = "conflicted"

    /** 422: a terminal edit-apply failure (`UnsupportedEdit`/`Unreadable`) OR a rebase whose target page was deleted (Gone). The message is a STABLE, non-leaking string. */
    const val APPLY_FAILED: String = "apply_failed"

    /** 400: a create proposal blob the server could not materialize an id into (FrontmatterPatcher refusal / an agent-supplied id). */
    const val INVALID_CREATE_CONTENT: String = "invalid_create_content"

    /** 409: a rebase of a proposal that is not in CONFLICTED state (already PENDING/terminal). DISTINCT from `not_pending`. */
    const val NOT_CONFLICTED: String = "not_conflicted"
}

/** The uniform error envelope (§A4, frozen): `{"error":{"code":…,"message":…}}`. */
@Serializable
data class ErrorEnvelope(val error: ErrorBody)

@Serializable
data class ErrorBody(val code: String, val message: String)

/** §5.3 citation wire shape (frozen). */
@Serializable
data class CitationDto(
    @SerialName("page_id") val pageId: String,
    @SerialName("heading_id") val headingId: String?,
    val path: String,
    @SerialName("content_hash") val contentHash: String,
    val commit: String?,
    val uri: String,
)

/** `GET /api/v1/pages/{id}` and `by-path/{path}` wire shape (frozen, identical for both). */
@Serializable
data class PageResponse(
    val id: String,
    /** Additive amendment (ADR-0011 D3, multi-root C3): the page's root-name slug. */
    val root: String,
    val path: String,
    val slug: String,
    val url: String?,
    val title: String,
    val markdown: String,
    val frontmatter: JsonObject,
    @SerialName("content_hash") val contentHash: String,
    @SerialName("id_materialized") val idMaterialized: Boolean,
    val commit: String?,
    val citation: CitationDto,
)

/** `GET /api/v1/pages/{id}/html` wire shape (frozen; the `html` markup itself is non-frozen). */
@Serializable
data class PageHtmlResponse(
    val id: String,
    /** Additive amendment (ADR-0011 D3, multi-root C3): the page's root-name slug. */
    val root: String,
    val path: String,
    val slug: String,
    val url: String?,
    val title: String,
    val html: String,
    @SerialName("content_hash") val contentHash: String,
    val commit: String?,
    val headings: List<HeadingDto>,
    val citation: CitationDto,
)

@Serializable
data class HeadingDto(val id: String, val level: Int, val text: String)

/**
 * `GET /api/v1/tree` wire shape (frozen as SHAPE; child ordering is documented-not-frozen): one
 * entry per CONFIGURED root, in registry (D7) order. Reshaped from `{root: <node>}` under the same
 * ADR-0011 D3 amendment as the url values (multi-root C3; the ForeverApiGoldenSuite ledger records
 * it).
 */
@Serializable
data class TreeResponse(val roots: List<RootTreeDto>)

/**
 * One root's tree entry: the validated root-name slug, whether it is currently SERVING, whether it accepts
 * WRITES, whether it is the PRIMARY, and its synthetic root folder node. [tree] is always a folder node, but it is DECLARED as the sealed
 * interface so the polymorphic serializer emits the `type` discriminator on the root exactly like on every child
 * (the pre-C3 TreeResponse rule, unchanged).
 *
 * [available] `false` means the root is configured but not serving: its subtree is EMPTY here (never its stale
 * carried-forward listing) and every read of it answers 503. It is listed rather than omitted so a client can tell
 * "this root is down" from "this root does not exist" - and so the client's known-root set matches the server's,
 * which is what lets it route `/docs/{root}/...` without guessing.
 *
 * [editable] is the root's CONFIGURED write disposition (ADR-0011 `roots.<name>.editable`), and it is on the wire
 * for the same reason [available] is: without it the SPA cannot tell a writable root from a read-only one, so it
 * offers Edit/New on every page of a root whose every write answers 403 `root_not_editable` - and `plainbase root
 * add` defaults an extra root to `editable = false`, which makes that the DEFAULT experience of a CLI-added root,
 * not an exotic one. Config, not authorization: it says what the TOPOLOGY allows, never what this principal may do
 * (the 403 remains the authority, and the client's buffer-preserving 403 path remains the backstop).
 *
 * [primary] flags the reserved primary root (ADR-0011 D1). It is on the wire because the primary is NOT
 * `roots[0]`: D7 order is the operator's config order, so it sits wherever config declared it, and a client
 * deriving it positionally is wrong on any install that declared its primary second. Exactly one entry carries
 * `true`, guaranteed by `RootRegistry`'s construction-time resolution rather than by a search here.
 */
@Serializable
data class RootTreeDto(val root: String, val available: Boolean, val editable: Boolean, val primary: Boolean, val tree: TreeNodeDto)

/** A tree node; the `type` discriminator (`folder`/`page`) comes from the sealed serializer. */
@Serializable
sealed interface TreeNodeDto {

    @Serializable
    @SerialName("folder")
    data class Folder(
        val name: String,
        val title: String?,
        // provisional (Chunk-3 landing): the `_folder.yaml` plaintext summary; null when absent/blank.
        val description: String?,
        val path: String,
        /** Additive amendment (ADR-0003): the folder's `/docs` URL prefix; null for a collision-loser subtree. */
        val url: String?,
        // provisional (Chunk-3 landing): page_count is DIRECT child pages only (not recursive).
        @SerialName("page_count") val pageCount: Int,
        val children: List<TreeNodeDto>,
    ) : TreeNodeDto

    @Serializable
    @SerialName("page")
    data class Page(
        val id: String,
        val title: String,
        val slug: String,
        val path: String,
        val url: String?,
        val status: String,
        // editorial author-declared date, validated YYYY-MM-DD; provisional — Phase-3 Git may add a
        // distinct last_modified (never a repoint of updated).
        val updated: String?,
    ) : TreeNodeDto
}

/** `POST /api/v1/admin/rescan` response — a §C4 convenience, NOT a frozen PB-REST-1 shape. */
@Serializable
data class RescanResponse(val status: String, val pages: Int)

/**
 * `POST /api/v1/admin/reindex` response — a convenience like [RescanResponse], NOT a frozen PB-*
 * shape (§A5 says the reindex body is not frozen). Parallels [RescanResponse]: `status` ("ok") and
 * `pages` (the count of pages rebuilt into the search engine). No forever golden pins it.
 */
@Serializable
data class ReindexResponse(val status: String, val pages: Int)

// ---- domain -> DTO mapping (the only place domain types meet the wire shapes) -----------------

fun PagePayload.toDto(): PageResponse = PageResponse(
    id = page.id.value,
    root = page.root.value,
    path = page.path.value,
    slug = page.slug,
    url = page.url,
    title = page.title,
    markdown = page.markdown,
    frontmatter = page.frontmatter.toJsonObject(idMaterialized = page.materialized),
    contentHash = page.contentHash,
    idMaterialized = page.materialized,
    commit = page.commit, // the page's snapshot-resident last commit; null off Git
    citation = citation.toDto(),
)

fun PageHtmlPayload.toDto(): PageHtmlResponse = PageHtmlResponse(
    id = page.id.value,
    root = page.root.value,
    path = page.path.value,
    slug = page.slug,
    url = page.url,
    title = page.title,
    html = page.html,
    contentHash = page.contentHash,
    commit = page.commit,
    headings = page.headings.map { it.toDto() },
    citation = citation.toDto(),
)

fun Citation.toDto(): CitationDto = CitationDto(
    pageId = pageId.value,
    headingId = headingId,
    path = path.value,
    contentHash = contentHash,
    commit = commit,
    uri = uri,
)

fun Heading.toDto(): HeadingDto = HeadingDto(id = id, level = level, text = text)

fun TreeNode.Folder.toDto(): TreeNodeDto.Folder = TreeNodeDto.Folder(
    name = name,
    title = title,
    description = description,
    // The synthetic root's domain path is null; the frozen wire shape spells it "" (§A4 example).
    path = path?.value ?: "",
    url = url,
    pageCount = pageCount,
    children = children.map { child ->
        when (child) {
            is TreeNode.Folder -> child.toDto()
            is TreeNode.Page -> child.toDto()
        }
    },
)

fun TreeNode.Page.toDto(): TreeNodeDto.Page = TreeNodeDto.Page(
    id = id.value,
    title = title,
    slug = slug,
    path = path.value,
    url = url,
    status = status,
    updated = updated,
)

/**
 * The §C2 frontmatter subset as the wire `frontmatter` object: scalars and arrays-of-strings.
 *
 * The `id` key is OMITTED unless [idMaterialized] — the frozen §A4 sentence (plan line 353):
 * "`id` appears here only when materialized." An in-file id the indexer REJECTED (someone else's
 * claim, shape-invalid) must not echo through the structured view as if it were the page's
 * identity; the verbatim block stays fully visible in `markdown`, so raw truth is preserved.
 * Omission is the conservative direction — relaxable later, while a garbage echo, once relied
 * on, isn't retractable.
 */
fun Frontmatter.toJsonObject(idMaterialized: Boolean): JsonObject = buildJsonObject {
    for ((key, value) in values) {
        if (key == "id" && !idMaterialized) continue
        when (value) {
            is FrontmatterValue.Scalar -> put(key, value.value)
            is FrontmatterValue.StringList -> put(key, JsonArray(value.values.map(::JsonPrimitive)))
        }
    }
}
