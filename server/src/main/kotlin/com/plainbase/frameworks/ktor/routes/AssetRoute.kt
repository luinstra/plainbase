package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.service.AssetReadOutcome
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /assets/{path}` (§A4): serves any non-`.md`, non-ignored file from the content tree, plus the
 * SPA's own embedded bundle under `static/assets/`.
 *
 * Security path (frozen, chunk 1.5 rules): the RAW url tail → `PercentCoding.decodeOnce` (the one
 * decoder; `%2F`, malformed escapes, invalid UTF-8 all rejected) → `TreePath` (NFC; traversal and
 * absoluteness structurally impossible) → snapshot membership (`PageIndex.assets`, which excludes
 * `.md` pages and everything the scan ignored) → the membership-gated `ContentStore.read` (raw-name
 * read-back + NOFOLLOW containment). There is no direct filesystem access here.
 *
 * **Bundle-wins integrity (C1a item 1) — inverts the shadow law for the `static/assets/` namespace ONLY.** Before
 * the content-tree read, a request whose [TreePath] names a REAL embedded bundle file (the Vite hashed
 * js/css under `static/assets/`, `frontend/dist/index.html`'s `<script>`/`<link>` targets) serves the
 * EMBEDDED bundle bytes and returns. A writer-planted content asset that collides with a bundle name can
 * therefore never be served in a `<script src>`/`<link href>` slot the shell trusts (the bundle-shadow
 * stored-XSS class). The bundle is PUBLIC in every auth mode — the shell + login page load anonymously
 * (the check runs regardless of principal, before `assetRead`) — and is NEVER sandboxed: its js must
 * execute. Filesystem-native: a file can arrive at `<root>/index-<hash>.js` via git/editor/upload, so
 * the fix lives at the SERVING layer, correct regardless of how the file arrived. The shadow is given up
 * for the SPA's own immutable build output ONLY (the owner ruling): there is no legitimate reason to
 * override it, and the collision is the exact attack vector. The bundle namespace is the `static/assets/`
 * subtree alone — the favicon/logos/fonts at `static/` root are served by `staticResources("/", "static")`
 * (`KtorServer`), so a user's own `assets/foo.svg` or root `favicon.ico` upload is NOT shadowed.
 *
 * **Per-asset sandbox (C1a item 2) — served-MIME inert-allowlist.** A content-tree `Found` response is
 * sandboxed (`Content-Security-Policy: sandbox; script-src 'none'`) iff its resolved [assetContentType]
 * is NOT a known-inert type ([assetNeedsSandbox]) — so an uploaded `diagram.svg`/`.pdf` cannot script on
 * direct navigation, while a `.png`/`.css`/`.woff2`/`.json`/`.txt` is served unrestricted. An uploaded
 * `evil.html` is already inert at HEAD: `.html` is not in the map → `application/octet-stream` + nosniff
 * → a browser downloads it, never executes (octet-stream is inert here → no sandbox, correctly). The
 * bundle-wins and root-static serves are TRUSTED build output and are CORRECTLY never sandboxed — the
 * sandbox CSP lives ONLY in the content-tree `Found` branch. A future "add CSP to all static assets"
 * refactor must NOT sandbox these (it would break the SPA's own js and its own SVG icons); the shell-CSP
 * plugin is text/html-gated, so a root-served `favicon.svg` (`image/svg+xml`) never receives it either.
 *
 * Every asset response carries `X-Content-Type-Options: nosniff` (the orthogonal MIME-sniff layer — a
 * sniffing browser can't reinterpret a mislabeled upload as HTML).
 *
 * **Deliberate asymmetry with the page endpoints:** pages serve ENTIRELY from the published index
 * snapshot (coherent markdown/html/hash, see `PageService`), but content assets stay LIVE disk reads —
 * disk is the source of truth for binaries, and a just-deleted asset answering 404 is correct, not a
 * torn read. There is nothing per-asset to keep coherent with the snapshot.
 *
 * A3 (§WI-5, the SPLIT): the read GATE fires inside `assetRead` BEFORE the membership test, so an
 * anonymous request to a content asset gets 401/403 (`AccessDenied` → [guarded]) — never a 404 that
 * distinguishes "exists but unauth" from "absent" (no existence leak). A bundle name returns at the top
 * check, before the gate runs, so the public shell still loads anonymously.
 *
 * The content-tree outcome is a 3-way [com.plainbase.domain.service.AssetReadOutcome]:
 *  - `NotContentAsset` — not in the content tree (and not a bundle name, which returned above) → 404.
 *  - `Found` — an indexed asset's current on-disk bytes → serve (sandboxed unless inert).
 *  - `IndexedButMissing` — an indexed asset whose file vanished → **404** (disk is source of truth). A
 *    bundle name never reaches here (it returned above), so a vanished upload at a bundle path serves the
 *    EMBEDDED bundle, not a 404 — the bundle-wins check precedes `assetRead`.
 */
fun Route.assetRoute(ctx: RouteContext) {
    get("/assets/{path...}") {
        val principal = ctx.principalOrRefuse(call) ?: return@get
        call.guarded {
            val raw = call.rawPathAfter("/assets/")
                ?: return@guarded call.respondError(
                    HttpStatusCode.BadRequest,
                    ErrorCodes.INVALID_PATH,
                    "Expected an asset path: /assets/{path}",
                )
            val path = decodedTreePath(raw)
                ?: return@guarded call.respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_PATH, "Not a valid asset path: '$raw'")
            // Bundle-wins (item 1): the SPA's OWN trusted static asset (Vite js/css/etc.). PUBLIC in every
            // mode (the shell + login page must load anonymously), NEVER sandboxed (its js must execute),
            // and ALWAYS the embedded bytes — a content asset shadowing a bundle name can never be served.
            val bundled = staticResourceBytes(path.value)
            if (bundled != null) {
                call.response.header(X_CONTENT_TYPE_OPTIONS, "nosniff")
                call.respondBytes(bundled, assetContentType(path.name))
                return@guarded
            }
            when (val outcome = ctx.read.assetRead(principal, path)) {
                AssetReadOutcome.NotContentAsset, AssetReadOutcome.IndexedButMissing ->
                    call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "No such asset: ${path.value}")
                is AssetReadOutcome.Found -> {
                    val contentType = assetContentType(path.name)
                    if (assetNeedsSandbox(contentType)) {
                        call.response.header(CONTENT_SECURITY_POLICY, "sandbox; script-src 'none'")
                    }
                    call.response.header(X_CONTENT_TYPE_OPTIONS, "nosniff")
                    call.respondBytes(outcome.bytes, contentType)
                }
            }
        }
    }
}
