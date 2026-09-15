package com.plainbase.frameworks.ktor

import java.net.InetAddress

/**
 * Canonical address parsing for the ADR-0008 security predicates (the bind guard, the secure-context test,
 * and A4b's spoof check all consume THESE — never a hand-rolled string compare). Pure string/[InetAddress]
 * logic: no socket, no coroutines, and **no DNS anywhere** — not for a per-request remote (a blocking lookup
 * on an attacker-controlled host is a latency + spoof surface) and not for the bind host (resolving a name
 * then trusting one result is a TOCTOU/DNS-rebinding bypass: `embeddedServer` binds the original NAME, not the
 * resolved address). Loopback is decided from numeric literals + the case-insensitive, unbracketed
 * `localhost`/`ip6-localhost` aliases. Every classification is **fail-closed**: an unparseable or hostname remote/bind is treated
 * as non-loopback / not-in-CIDR.
 *
 * Source identity is ALWAYS the socket remote address — `X-Forwarded-For` is never an input here (§0.10).
 */
object RemoteAddress {
    private const val BITS_PER_BYTE = 8
    private const val IPV4_OCTET_COUNT = 4
    private const val MAX_IPV4_OCTET_DIGITS = 3
    private const val MAX_IPV4_OCTET = 255

    /**
     * Is [remoteHost] a loopback address? Handles IPv4 `127.0.0.0/8`, IPv6 `::1`, the IPv4-mapped form
     * `::ffff:127.0.0.1` (the classic bypass a naive `== "127.0.0.1"` misses), and the case-insensitive,
     * unbracketed aliases `localhost`/`ip6-localhost`. A `host:port` form is normalized first. A name other than those literals is NEVER
     * resolved and classifies as non-loopback (fail-closed). The wildcard `0.0.0.0`/`::` is never a legitimate
     * remote, so it is non-loopback.
     */
    fun isLoopbackAddress(remoteHost: String): Boolean {
        val host = stripPort(remoteHost)?.lowercase() ?: return false
        if (host.isEmpty()) return false
        if (host == "localhost" || host == "ip6-localhost") return true
        val inet = parseNumericLiteral(host) ?: return false
        return inet.isLoopbackAddress
    }

    /**
     * The bind-guard test: is the configured bind [host] a non-loopback or wildcard interface (so a credential
     * would be exposed off-box)? `0.0.0.0`/`::` (bind every interface) and any routable IP / non-localhost
     * NAME → true; loopback literals + case-insensitive, unbracketed `localhost`/`ip6-localhost` aliases → false.
     *
     * A non-literal hostname is NEVER DNS-resolved (the resolve-then-trust-one-result path was a TOCTOU /
     * DNS-rebinding bypass: `embeddedServer` binds the original name, which could resolve to a non-loopback
     * interface even when one resolved result was `127.0.0.1`). Anything that is not a known-loopback literal
     * is therefore treated as non-loopback (fail-closed: refuse to assume a name is safe).
     */
    fun isNonLoopbackBind(host: String): Boolean = !isLoopbackBindLiteral(host)

    /** True only for the literals proven loopback WITHOUT DNS: numeric loopback addresses + `localhost`/`ip6-localhost`. */
    private fun isLoopbackBindLiteral(host: String): Boolean {
        val normalized = stripPort(host)?.lowercase() ?: return false
        if (normalized.isEmpty()) return false
        if (normalized == "localhost" || normalized == "ip6-localhost") return true
        val literal = parseNumericLiteral(normalized) ?: return false
        return literal.isLoopbackAddress
    }

    /**
     * Is [remoteHost] inside any of [cidrs] (`a.b.c.d/n` or IPv6 `…/n`)? Pure prefix math, no DNS. A remote or
     * a CIDR that does not parse as a numeric literal contributes no match (fail-closed). Mixed families never
     * match (an IPv4 remote is never inside an IPv6 CIDR and vice-versa).
     */
    fun isInAnyCidr(remoteHost: String, cidrs: List<String>): Boolean {
        val stripped = stripPort(remoteHost) ?: return false
        val remote = parseNumericLiteral(stripped) ?: return false
        val remoteBytes = remote.address
        return cidrs.any { cidr -> matchesCidr(remoteBytes, cidr) }
    }

    /**
     * Resolves a single secure/not verdict over the `X-Forwarded-Proto` values a request presents (which may
     * be empty, repeated headers, or one comma-joined header). **Fail-closed:** `https` ONLY if at least one
     * token is present AND every token equals `https` — any non-https token (a mixed/duplicate proto) OR any
     * BLANK token (`https,` / `["https",""]`, which a spoofer could append) loses. Never "the last value wins".
     */
    fun forwardedProtoIsHttps(forwardedProtoValues: List<String>): Boolean {
        val tokens = forwardedProtoValues.flatMap { it.split(',') }.map { it.trim() }
        return tokens.isNotEmpty() && tokens.all { it.equals("https", ignoreCase = true) }
    }

    /** Normalizes outer whitespace, validates an optional port, and unwraps bracketed IPv6 literals. */
    private fun stripPort(hostPort: String): String? {
        val host = hostPort.trim()
        if (!host.startsWith("[")) {
            if (host.count { it == ':' } != 1) return host
            val port = host.substringAfter(':')
            return port.takeIf(::isValidPort)?.let { host.substringBefore(':') }
        }
        val close = host.indexOf(']').takeIf { it >= 0 } ?: return null
        val content = host.substring(1, close)
        if (':' !in content.substringBefore('%')) return null
        val suffix = host.substring(close + 1)
        if (suffix.isNotEmpty() && !(suffix.startsWith(':') && isValidPort(suffix.drop(1)))) return null
        return content
    }

    /** Parses a strict numeric literal without invoking hostname resolution. */
    private fun parseNumericLiteral(host: String): InetAddress? {
        if (host.isEmpty()) return null
        val literal = host.substringBefore('%')
        if (literal.isEmpty()) return null
        if ('%' in host && ':' !in literal) return null
        if (!literal.all { it.isAsciiLiteralCharacter() }) return null
        if (':' !in literal && !isStrictIpv4(literal)) return null
        if (':' in literal && '.' in literal && !isStrictIpv4(literal.substringAfterLast(':'))) return null
        return try {
            InetAddress.ofLiteral(literal)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun isValidPort(port: String): Boolean =
        port.isNotEmpty() && port.all { it in '0'..'9' } && port.toIntOrNull()?.let { it in 0..65535 } == true

    private fun Char.isAsciiLiteralCharacter(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F' || this == '.' || this == ':'

    /**
     * Does [cidr] parse as a well-formed CIDR (`a.b.c.d/n` or IPv6 `…/n`)? The config layer (A1-amber) requires
     * every `trustedProxyCidrs` entry pass this at LOAD — a present-but-malformed CIDR fails fast rather than
     * silently contributing no match (which would defeat the fail-closed bind guard). The SAME split + numeric-
     * literal parse + prefix-bounds logic [matchesCidr] uses (one source of truth), minus the remote: a MISSING
     * `/prefix` (a bare address) and an OUT-OF-RANGE prefix (`/33`, `/129`, negative/non-numeric) both reject.
     */
    fun isParseableCidr(cidr: String): Boolean = parseCidr(cidr) != null

    /** A strict dotted quad: exactly four ASCII `0..255` octets, no abbreviation or leading-zero ambiguity. */
    private fun isStrictIpv4(s: String): Boolean {
        val octets = s.split('.')
        return octets.size == IPV4_OCTET_COUNT &&
            octets.all { octet ->
                octet.length in 1..MAX_IPV4_OCTET_DIGITS &&
                    (octet.length == 1 || octet[0] != '0') &&
                    octet.all { it in '0'..'9' } &&
                    octet.toIntOrNull()?.let { it in 0..MAX_IPV4_OCTET } == true
            }
    }

    private fun matchesCidr(remoteBytes: ByteArray, cidr: String): Boolean {
        val parsed = parseCidr(cidr)
        return parsed?.let { it.network.size == remoteBytes.size && sharesPrefix(remoteBytes, it.network, it.prefix) } == true
    }

    private data class ParsedCidr(val network: ByteArray, val prefix: Int)

    private fun parseCidr(cidr: String): ParsedCidr? {
        val slash = cidr.indexOf('/').takeIf { it >= 0 }
        val networkPart = slash?.let { cidr.substring(0, it).trim() }
        val network = networkPart?.let(::parseNumericLiteral)
        val prefix = slash?.let { cidr.substring(it + 1).trim().toIntOrNull() }
        return when {
            network != null && prefix != null && prefix in 0..(network.address.size * BITS_PER_BYTE) ->
                ParsedCidr(network.address, prefix)
            else -> null
        }
    }

    private fun sharesPrefix(a: ByteArray, b: ByteArray, prefixBits: Int): Boolean {
        val fullBytes = prefixBits / BITS_PER_BYTE
        for (i in 0 until fullBytes) {
            if (a[i] != b[i]) return false
        }
        val remainder = prefixBits % BITS_PER_BYTE
        if (remainder == 0) return true
        val mask = (MAX_IPV4_OCTET shl (BITS_PER_BYTE - remainder)) and MAX_IPV4_OCTET
        return (a[fullBytes].toInt() and mask) == (b[fullBytes].toInt() and mask)
    }
}
