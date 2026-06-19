package com.plainbase.frameworks.ktor.routes

import com.plainbase.frameworks.ktor.RestServices
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
 */
fun Route.assetRoute(services: RestServices) {
    get("/assets/{path...}") {
        val raw = call.rawPathAfter("/assets/")
            ?: return@get call.respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_PATH, "Expected an asset path: /assets/{path}")
        val path = decodedTreePath(raw)
            ?: return@get call.respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_PATH, "Not a valid asset path: '$raw'")
        if (path in services.indexBuilder.current.assets) {
            val bytes = services.contentStore.read(path)
                ?: return@get call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "No such asset: ${path.value}")
            call.response.header(X_CONTENT_TYPE_OPTIONS, "nosniff")
            return@get call.respondBytes(bytes, assetContentType(path.name))
        }
        val bundled = staticResourceBytes(path.value)
            ?: return@get call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "No such asset: ${path.value}")
        call.response.header(X_CONTENT_TYPE_OPTIONS, "nosniff")
        call.respondBytes(bundled, assetContentType(path.name))
    }
}

// The MIME-sniff defense header (not a named constant in Ktor 3.5's HttpHeaders).
private const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
