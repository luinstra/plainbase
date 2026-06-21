package com.plainbase.frameworks.ktor

import com.plainbase.domain.principal.Principal
import io.ktor.server.application.ApplicationCall

/**
 * The seam later chunks plug identity sources into. A1 establishes it returning [Principal.Anonymous] — no
 * source is registered yet (A2 adds the `pb_` bearer source, A4a/A4b the cookie/proxy-header sources). It is a
 * plain helper, deliberately NOT installed as a global interceptor: A1 changes the behavior of NO existing
 * route (§C "no behavior change"); A2/A4a call this from their own extraction points.
 *
 * SECURE-CONTEXT CONTRACT (ADR-0008, wired by A2/A4a, NOT A1): at the point a source EXTRACTS a credential
 * (a session cookie or an `Authorization: Bearer pb_…`), it MUST first evaluate [isSecureContext] over the
 * socket remote address + `X-Forwarded-Proto` + the configured trusted-proxy CIDRs, and refuse the request if
 * it is false — presenting a credential over plain non-loopback HTTP LEAKS it. A1 ships the predicate; this
 * seam documents where the check fires; no credential source exists yet, so nothing is enforced here.
 */
@Suppress("UnusedReceiverParameter")
fun ApplicationCall.extractPrincipal(): Principal = Principal.Anonymous
