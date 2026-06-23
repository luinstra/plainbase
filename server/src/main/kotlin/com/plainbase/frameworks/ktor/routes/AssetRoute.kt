package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.service.AccessDenied
import com.plainbase.domain.service.AssetReadOutcome
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /assets/{path}` (§A4): serves any non-`.md`, non-ignored file from the content tree.
 *
 * Security path (frozen, chunk 1.5 rules): the RAW url tail → `PercentCoding.decodeOnce` (the one
 * decoder; `%2F`, malformed escapes, invalid UTF-8 all rejected) → `TreePath` (NFC; traversal and
 * absoluteness structurally impossible) → snapshot membership (`PageIndex.assets`, which excludes
 * `.md` pages and everything the scan ignored) → the membership-gated `ContentStore.read` (raw-name
 * read-back + NOFOLLOW containment). There is no direct filesystem access here.
 *
 * A miss against the content tree falls back to the embedded SPA bundle under `static/assets/`
 * (the Vite build emits its js/css there): the content tree wins any name it actually uses, and
 * the [TreePath] validation above already guarantees the resource lookup cannot escape `static/`.
 *
 * **Deliberate asymmetry with the page endpoints:** pages serve ENTIRELY from the published index
 * snapshot (coherent markdown/html/hash, see `PageService`), but assets stay LIVE disk reads —
 * disk is the source of truth for binaries, and a just-deleted asset answering 404 is correct,
 * not a torn read. There is nothing per-asset to keep coherent with the snapshot.
 *
 * **Serving safety (W3b — two orthogonal layers):**
 *  - **MIME-sniff XSS — handled here now.** W3b's upload route lets any writer plant a file whose
 *    bytes don't match its extension (e.g. HTML named `logo.png`, served `image/png`). Every asset
 *    response carries `X-Content-Type-Options: nosniff` so a sniffing browser can't reinterpret it as
 *    HTML. A pure win — no UX cost, no dependency, trust-model-independent — so it lands now.
 *  - **Scriptable SVG/HTML on direct navigation — STAYS the auth-phase deferral.** An uploaded
 *    `diagram.svg` is served `image/svg+xml` and is scriptable when navigated to directly. The real
 *    mitigations (`Content-Disposition: attachment` to force-download, or `Content-Security-Policy:
 *    sandbox`) carry a UX / trust-model dimension — forcing attachment breaks legitimate inline
 *    `<img src=…foo.svg>` — so the CSP/sandbox decision belongs to the auth phase, alongside the rest
 *    of the trust model. `nosniff` is the orthogonal first layer, not a substitute for it.
 *
 * A3 (§WI-5, the SPLIT): the read GATE fires on the content-tree branch BEFORE the membership test, so an
 * anonymous request to a content asset gets 401/403 — never a 404 that distinguishes "exists but unauth" from
 * "absent" (no existence leak). The order is: decode → `read.assetRead` (which checkRead-gates, throws→401/403,
 * THEN tests content-tree membership).
 *
 * **Public bundled-SPA fallback on a denial (B1):** when `assetRead` throws [AccessDenied] (an anonymous request
 * under enforced auth), the route serves the path IF it names a real embedded bundle file — the SPA's own js/css
 * is PUBLIC in every mode so the shell (incl. the login page) loads for an anonymous user. A bundled file is NOT a
 * content-tree asset, so serving it leaks nothing about the tree; anything that is not a real bundle re-raises the
 * denial → 401 (an absent non-bundle and a real content asset stay 401 alike — no existence leak). A content asset
 * whose name COLLIDES with a bundled name serves the PUBLIC bundled bytes to an anonymous caller, never the private
 * content bytes (the gate already denied the content read); an AUTHORIZED caller gets `Found` and never reaches the
 * catch, so the content-wins shadow law is unchanged for them.
 *
 * The outcome is a 3-way [com.plainbase.domain.service.AssetReadOutcome]:
 *  - `NotContentAsset` — not in the content tree → fall through to the PUBLIC bundled-static fallback (the SPA's
 *    own js/css), then 404 if that misses too.
 *  - `Found` — an indexed asset's current on-disk bytes → serve.
 *  - `IndexedButMissing` — an indexed asset whose file vanished → **404**, NEVER the bundled fallback. Conflating
 *    this with `NotContentAsset` would let a vanished upload unmask a bundled-static name it shadowed (e.g. an
 *    uploaded `favicon.ico` deleted on disk would serve the BUNDLED one — disk is source of truth, so it's a 404).
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
            // The read gate fires inside assetRead BEFORE the membership test (no existence leak). ONLY a
            // NotContentAsset may fall through to the public bundled-static fallback; an IndexedButMissing (the
            // indexed asset's file vanished) is a 404, never the fallback (disk is source of truth — no unmasking
            // of a shadowed bundled name).
            val outcome = try {
                ctx.read.assetRead(principal, path)
            } catch (denied: AccessDenied) {
                // PUBLIC bundled-SPA fallback: a real embedded static asset (the shell's own js/css) is PUBLIC in
                // every mode — the app shell (incl. the login page) must load for an anonymous user under enforced
                // auth. A bundled file here is NOT a content-tree asset (the gate already proved the principal may
                // not read the content tree), so serving it leaks nothing about the content tree. Anything that is
                // NOT a real bundled file re-raises the denial → 401, preserving the existence non-leak (an absent
                // non-bundle AND a real content asset both stay 401, never 404, never unmasking membership).
                val bundled = staticResourceBytes(path.value) ?: throw denied
                call.response.header(X_CONTENT_TYPE_OPTIONS, "nosniff")
                call.respondBytes(bundled, assetContentType(path.name))
                return@guarded
            }
            when (outcome) {
                AssetReadOutcome.NotContentAsset -> {
                    val bundled = staticResourceBytes(path.value)
                        ?: return@guarded call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "No such asset: ${path.value}")
                    call.response.header(X_CONTENT_TYPE_OPTIONS, "nosniff")
                    call.respondBytes(bundled, assetContentType(path.name))
                }
                AssetReadOutcome.IndexedButMissing ->
                    call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "No such asset: ${path.value}")
                is AssetReadOutcome.Found -> {
                    call.response.header(X_CONTENT_TYPE_OPTIONS, "nosniff")
                    call.respondBytes(outcome.bytes, assetContentType(path.name))
                }
            }
        }
    }
}

// The MIME-sniff defense header (not a named constant in Ktor 3.5's HttpHeaders).
private const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
