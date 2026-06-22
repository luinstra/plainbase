package com.plainbase.frameworks.ktor.routes

import com.plainbase.domain.service.SearchService
import com.plainbase.frameworks.ktor.RouteContext
import com.plainbase.frameworks.ktor.dto.ErrorCodes
import com.plainbase.frameworks.ktor.dto.SearchResponse
import com.plainbase.frameworks.ktor.dto.toDto
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLDecodeException
import io.ktor.http.decodeURLQueryComponent
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `GET /api/v1/search?q={text}&limit={n}&offset={n}` — PB-SEARCH-1 (§A1/§A2, frozen).
 *
 * `q` is decoded by standard HTTP query-string decoding (`+` is a space — this is a query
 * parameter, NOT a path segment, so PB-LINK-1's decode-once rules do not apply). The decode runs
 * HERE, defensively, over the still-encoded `rawQueryParameters` — this handler never touches
 * Ktor's `queryParameters`, whose lazy decode throws `URLDecodeException` on a malformed escape
 * (`?q=%`, `?q=100%` — ktor#2559). In current Ktor that throw actually happens EARLIER (routing
 * eagerly merges query+path parameters once a route matches), where the `StatusPages` mapping in
 * `KtorServer` answers the same frozen 400; the defensive decode here keeps this handler correct
 * on its own terms, whichever layer Ktor decodes in. Both answer with [malformedQueryMessage].
 *
 * Grammar validation lives in [SearchService] (domain); this route only decodes and maps a
 * violation to the frozen envelope. Unknown parameters are ignored (§A1 additive evolution).
 */
fun Route.searchRoute(ctx: RouteContext) {
    get("/api/v1/search") {
        val principal = ctx.principalOrRefuse(call) ?: return@get
        call.guarded {
            val raw = call.request.rawQueryParameters
            val decoded = HashMap<String, String?>()
            // §A1 parameter NAMES are matched in raw form (deliberate): no real client percent-encodes
            // ASCII names, and decoding names would re-open the throwing decode this handler avoids.
            for (name in listOf("q", "limit", "offset")) {
                decoded[name] = try {
                    raw[name]?.decodeURLQueryComponent(plusIsSpace = true)
                } catch (cause: URLDecodeException) {
                    return@guarded call.respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_QUERY, malformedQueryMessage(raw))
                }
            }
            // Blocking JDBC must never park a CIO event-loop thread — the SearchDb contract makes this
            // route own the hop to Dispatchers.IO before the engine query runs (§B5). The read gate
            // (checkRead, inside the facade) fires off the event loop on Dispatchers.IO with the rest.
            val outcome = withContext(Dispatchers.IO) {
                ctx.read.search(principal, q = decoded["q"], limit = decoded["limit"], offset = decoded["offset"])
            }
            when (outcome) {
                is SearchService.Outcome.InvalidQuery ->
                    call.respondError(HttpStatusCode.BadRequest, ErrorCodes.INVALID_QUERY, outcome.message)
                is SearchService.Outcome.Results ->
                    call.respondRest(SearchResponse.serializer(), outcome.payload.toDto())
            }
        }
    }
}
