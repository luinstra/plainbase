package com.plainbase.frameworks.ktor.routes

import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.frontendStaticRoutes(ctx: RouteContext) {
    FrontendBundle.files.filter { it != FrontendBundle.SHELL }.forEach { name ->
        // The rest of the bundle stays PUBLIC pre-login: the login view cannot render without its
        // own logo and favicon, so gating these would lock the operator out of the way back in.
        get("/$name") { call.respondBundleFile(name) }
    }
    // `/index.html` is a SECOND app entry point serving the identical shell bytes, so it goes through
    // the SAME cached, guarded path `spaShellRoutes` uses rather than the bundle-file path:
    //
    //  - A3's bare arm, because an entry point must REFUSE (421) an insecure-transport credential
    //    rather than downgrade it to an anonymous shell. Omitting the guard here splits A3.
    //  - `respondSpaShell()`, which already emits `text/html; charset=UTF-8` (the whole header
    //    mechanism: `shellSecurityHeadersPlugin` stamps the CSP trio off the CONTENT TYPE, never off
    //    a path) from `lazy`-cached bytes, and stamps no nosniff of its own because the plugin owns
    //    that arm. Serving it via `respondBundleFile` instead would re-read the classpath resource per
    //    request AND serve `application/octet-stream`, because `ASSET_CONTENT_TYPES` has no `html` key:
    //    the plugin's `text/html` gate would never fire, so the shell would ship with NO CSP and a type
    //    the browser refuses to render. That is the cost, not a duplicate header.
    get("/${FrontendBundle.SHELL}") shell@{
        if (ctx.principalOrRefuseToShell(call) is ExtractedPrincipal.Refused) return@shell
        call.respondSpaShell()
    }
    FrontendBundle.directories.forEach { dir ->
        get("/$dir/{file}") {
            val file = requireNotNull(call.parameters["file"]) {
                "the {file} route matched without a file segment"
            }
            if (!FrontendBundle.FILE_NAME.matches(file)) {
                call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "not bundled")
            } else {
                call.respondBundleFile("$dir/$file")
            }
        }
    }
}

/**
 * The embedded frontend bundle's top-level ownership authority. Every classpath entry is a
 * direct file, a flat directory, or owned by another route. A bundle entry with no owner is a 404.
 */
internal object FrontendBundle {
    /** Top-level bundle files served here. [SHELL] is the HTML shell. */
    val files = listOf(
        "apple-touch-icon.png",
        "favicon.svg",
        "index.html",
        "plainbase-logo-dark.svg",
        "plainbase-logo.svg",
    )

    /** Top-level bundle directories, each exposed through one flat file parameter. */
    val directories = listOf("fonts")

    /** Top-level entries owned by another route, keyed by entry name. */
    val ownedElsewhere = mapOf(
        "assets" to "assetRoute: get(\"/assets/{path...}\")",
    )

    const val SHELL = "index.html"

    /** A single safe bundle path segment. */
    val FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
}
