package com.plainbase.frameworks.ktor

/**
 * ADR-0008 per-request secure-context test. True when the request is over a transport trustworthy enough to
 * carry a credential: **loopback**, OR `X-Forwarded-Proto: https` from an **allowlisted proxy source**. The
 * credential-CONDITIONAL triggering (fire only when a cookie/bearer is actually present) lives at the
 * extraction point that A2/A4a wire — this predicate is credential-AGNOSTIC: it answers only "is the transport
 * secure?". A1 builds + unit-tests it; it is wired to nothing yet.
 *
 * Source identity is the SOCKET remote address, never a client header (§0.10). Built ON [RemoteAddress] so it
 * shares the canonical loopback / CIDR / multi-value-`X-Forwarded-Proto` rules (WI 3), never re-implementing them.
 *
 * Fail-closed: a non-loopback request with `https` from a NON-allowlisted source is false (the spoof case); an
 * allowlisted source presenting `http` (or a mixed/duplicate proto with any non-https token) is false.
 */
fun isSecureContext(
    remoteHost: String,
    forwardedProtoValues: List<String>,
    trustedProxyCidrs: List<String>,
): Boolean {
    if (RemoteAddress.isLoopbackAddress(remoteHost)) return true
    val proxyIsAllowlisted = RemoteAddress.isInAnyCidr(remoteHost, trustedProxyCidrs)
    return proxyIsAllowlisted && RemoteAddress.forwardedProtoIsHttps(forwardedProtoValues)
}
