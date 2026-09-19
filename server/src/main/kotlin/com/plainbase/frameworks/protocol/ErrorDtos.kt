package com.plainbase.frameworks.protocol

import kotlinx.serialization.Serializable

/** The frozen error-code vocabulary; numeric prefixes below are REST mapping notes (§A4, append-only). */
object ErrorCodes {
    /** 503: admission is closed while the server drains its admitted call jobs. */
    const val SERVER_SHUTTING_DOWN: String = "server_shutting_down"

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

    /** 400: the request body ended before its declared Content-Length. */
    const val INVALID_REQUEST_BODY: String = "invalid_request_body"

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
