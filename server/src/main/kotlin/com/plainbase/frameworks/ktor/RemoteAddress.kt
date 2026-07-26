package com.plainbase.frameworks.ktor

import java.net.InetAddress

/**
 * Canonical address parsing for the ADR-0008 security predicates (the bind guard, the secure-context test,
 * and A4b's spoof check all consume THESE — never a hand-rolled string compare). Pure string/[InetAddress]
 * logic: no socket, no coroutines, and **no DNS anywhere** — not for a per-request remote (a blocking lookup
 * on an attacker-controlled host is a latency + spoof surface) and not for the bind host (resolving a name
 * then trusting one result is a TOCTOU/DNS-rebinding bypass: `embeddedServer` binds the original NAME, not the
 * resolved address). Loopback is decided from numeric literals + the exact `localhost`/`ip6-localhost`
 * literals ONLY. Every classification is **fail-closed**: an unparseable or hostname remote/bind is treated
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
     * `::ffff:127.0.0.1` (the classic bypass a naive `== "127.0.0.1"` misses), and the literals `localhost`/
     * `ip6-localhost`. A `host:port` form is normalized first. A name other than those literals is NEVER
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
     * NAME → true; loopback literals + the exact `localhost`/`ip6-localhost` literals → false.
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

    /**
     * Drops a trailing `:port`, unwrapping a bracketed IPv6 literal (`[::1]:8080` → `::1`). Leaves a bare IPv6
     * literal untouched. **Fail-closed on malformed brackets:** a `[` with no closing `]`, or a non-empty,
     * non-`:port` suffix after `]` (`[::1` / `[::1]junk`), returns null (→ unparseable → non-loopback).
     */
    private fun stripPort(hostPort: String): String? {
        val host = hostPort.trim()
        val close = host.indexOf(']')
        return when {
            !host.startsWith("[") && host.count { it == ':' } == 1 -> host.substringBefore(':')
            !host.startsWith("[") -> host
            close < 0 -> null
            host.substring(close + 1).let { it.isNotEmpty() && !(it.startsWith(':') && it.drop(1).all(Char::isDigit)) } -> null
            else -> host.substring(1, close)
        }
    }

    /**
     * Parses a NUMERIC IP literal (v4 or v6) without touching DNS. `InetAddress.getByName` on a numeric literal
     * parses rather than resolves; a hostname would resolve, so we pre-screen to digits/`.`/`:`/hex and return
     * null for anything that is not a bare literal (so a hostname never sneaks a DNS lookup in).
     */
    private fun parseNumericLiteral(host: String): InetAddress? {
        if (host.isEmpty()) return null
        // Drop a trailing `%zone` (e.g. `fe80::1%eth0`): a zone id is meaningless for a CIDR/loopback verdict and is
        // attacker-controllable on a remote, so it never reaches getByName (and `%` leaves the numeric screen).
        val literal = host.substringBefore('%')
        if (literal.isEmpty()) return null
        val looksNumeric = literal.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' || it in 'A'..'F' }
        if (!looksNumeric) return null
        if ('.' !in literal && ':' !in literal) return null // a bare hex word like "abc" is a hostname, not a literal
        return runCatching { InetAddress.getByName(literal) }.getOrNull()
    }

    /**
     * Does [cidr] parse as a well-formed CIDR (`a.b.c.d/n` or IPv6 `…/n`)? The config layer (A1-amber) requires
     * every `trustedProxyCidrs` entry pass this at LOAD — a present-but-malformed CIDR fails fast rather than
     * silently contributing no match (which would defeat the fail-closed bind guard). The SAME split + numeric-
     * literal parse + prefix-bounds logic [matchesCidr] uses (one source of truth), minus the remote: a MISSING
     * `/prefix` (a bare address) and an OUT-OF-RANGE prefix (`/33`, `/129`, negative/non-numeric) both reject.
     */
    fun isParseableCidr(cidr: String): Boolean = parseCidr(cidr, requireStrictIpv4 = true) != null

    /** A strict dotted quad: exactly four `0..255` octets, no abbreviation, no leading-zero (octal) ambiguity. */
    private fun isStrictIpv4(s: String): Boolean {
        val octets = s.split('.')
        return octets.size == IPV4_OCTET_COUNT &&
            octets.all { octet ->
                octet.length in 1..MAX_IPV4_OCTET_DIGITS &&
                    (octet.length == 1 || octet[0] != '0') &&
                    octet.toIntOrNull()?.let { it in 0..MAX_IPV4_OCTET } == true
            }
    }

    private fun matchesCidr(remoteBytes: ByteArray, cidr: String): Boolean {
        val parsed = parseCidr(cidr, requireStrictIpv4 = false)
        return parsed?.let { it.network.size == remoteBytes.size && sharesPrefix(remoteBytes, it.network, it.prefix) } == true
    }

    private data class ParsedCidr(val network: ByteArray, val prefix: Int)

    private fun parseCidr(cidr: String, requireStrictIpv4: Boolean): ParsedCidr? {
        val slash = cidr.indexOf('/').takeIf { it >= 0 }
        val networkPart = slash?.let { cidr.substring(0, it).trim() }
        val strictEnough = networkPart?.let { ':' in it || !requireStrictIpv4 || isStrictIpv4(it) } == true
        val network = networkPart?.takeIf { strictEnough }?.let(::parseNumericLiteral)
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
