package com.plainbase.frameworks.ktor.routes

import com.plainbase.frameworks.ktor.dto.ErrorCodes
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.path
import io.ktor.server.routing.Route
import io.ktor.server.routing.route

/**
 * The `/api/{...}` catch-all: any request under `/api/` that no real endpoint claims answers
 * **404 `not_found` in the frozen envelope** — never the SPA shell. Without it, the root static
 * fallback's `default(index.html)` swallowed every API typo (`/api/v1/page/{id}`), trailing slash
 * (`/api/v1/pages/{id}/`), and misspelled sub-resource (`.../htlm`) as a 200 `text/html` page —
 * agent-hostile in exactly the surface built for agents.
 *
 * Ktor resolves by specificity, so every real API route (constant segments) outranks this tailcard;
 * the bare `handle` covers ALL methods, so a HEAD/POST against an unknown API path gets the same
 * honest 404 instead of the shell.
 */
fun Route.apiFallbackRoute() {
    route("/api/{...}") {
        handle {
            call.respondError(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, "No such API endpoint: ${call.request.path()}")
        }
    }
}
