package com.plainbase.frameworks.ktor

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The canonical-address security primitive (the loopback/CIDR/proto verdicts the bind guard, secure-context,
 * and A4b spoof check share). Table-driven — the table IS the spec; widen the table (not just the code) if a
 * review finds a missed form (the differential-oracle discipline).
 */
class AddressParsingTest : FunSpec({

    context("isLoopbackAddress") {
        listOf(
            "127.0.0.1" to true,
            "127.0.0.2" to true, // the whole 127.0.0.0/8
            "127.255.255.255" to true,
            "::1" to true,
            "::ffff:127.0.0.1" to true, // IPv4-mapped loopback — the classic bypass
            "localhost" to true,
            "ip6-localhost" to true,
            "127.0.0.1:8080" to true, // host:port form normalized first
            "[::1]:8080" to true,
            "10.0.0.5" to false,
            "203.0.113.7" to false,
            "2001:db8::1" to false,
            "0.0.0.0" to false, // never a legitimate remote
            "::" to false,
            "example.com" to false, // an arbitrary hostname remote → non-loopback (fail-closed, no DNS)
            "localhost.example.com" to false, // only the EXACT literal `localhost` is loopback — never a name containing it
            "localhost.localdomain" to false, // a name that classically resolves to 127.0.0.1 — still non-loopback (no DNS)
            "[::1" to false, // unterminated bracket → unparseable → non-loopback (fail-closed)
            "[::1]junk" to false, // garbage suffix after `]` → unparseable → non-loopback (fail-closed)
            "::1%lo0" to true, // a zone id is stripped before classification → same as `::1` (loopback)
            "fe80::1%eth0" to false, // link-local + zone → non-loopback, same as its zoneless form
            "%eth0" to false, // a bare `%zone` with no literal → unparseable → non-loopback (fail-closed)
            "garbage%eth0" to false, // a `%`-bearing garbage string → non-loopback
            "" to false,
        ).forEach { (input, expected) ->
            test("'$input' loopback == $expected") {
                RemoteAddress.isLoopbackAddress(input) shouldBe expected
            }
        }
    }

    context("isNonLoopbackBind") {
        listOf(
            "0.0.0.0" to true, // wildcard — the guard's primary target (the default host)
            "::" to true,
            "[::]" to true,
            "10.0.0.5" to true,
            "203.0.113.7" to true,
            "2001:db8::1" to true,
            "127.0.0.1" to false,
            "::1" to false,
            "[::1]" to false, // bracketed loopback literal
            "::ffff:127.0.0.1" to false,
            "localhost" to false,
            "ip6-localhost" to false,
            // A non-literal bind hostname is NEVER DNS-resolved (TOCTOU / DNS-rebinding bypass): even a name that
            // would resolve to 127.0.0.1 classifies as non-loopback, because embeddedServer binds the NAME, not
            // the one resolved result we happened to read (BLOCKING-2 fix).
            "example.com" to true,
            "localhost.localdomain" to true, // resolves to 127.0.0.1 in many setups — still non-loopback (no DNS)
            "[::1" to true, // unterminated bracket → unparseable → fail-closed exposed
            "[::1]junk" to true, // garbage suffix after `]` → unparseable → fail-closed exposed
            "" to true, // empty bind host → fail-closed (treat as exposed)
        ).forEach { (input, expected) ->
            test("'$input' nonLoopbackBind == $expected") {
                RemoteAddress.isNonLoopbackBind(input) shouldBe expected
            }
        }
    }

    context("isInAnyCidr") {
        test("an IPv4 remote inside a /24 matches; outside does not") {
            RemoteAddress.isInAnyCidr("192.168.1.50", listOf("192.168.1.0/24")) shouldBe true
            RemoteAddress.isInAnyCidr("192.168.2.50", listOf("192.168.1.0/24")) shouldBe false
        }
        test("an IPv6 remote inside a /64 matches; outside does not") {
            RemoteAddress.isInAnyCidr("2001:db8:0:1::5", listOf("2001:db8:0:1::/64")) shouldBe true
            RemoteAddress.isInAnyCidr("2001:db8:0:2::5", listOf("2001:db8:0:1::/64")) shouldBe false
        }
        test("families never cross: an IPv4 remote is never inside an IPv6 CIDR") {
            RemoteAddress.isInAnyCidr("10.0.0.5", listOf("2001:db8::/32")) shouldBe false
        }
        test("any-of: a remote inside one of several CIDRs matches") {
            RemoteAddress.isInAnyCidr("172.16.0.9", listOf("10.0.0.0/8", "172.16.0.0/12")) shouldBe true
        }
        test("a malformed CIDR contributes no match (fail-closed)") {
            RemoteAddress.isInAnyCidr("10.0.0.5", listOf("not-a-cidr", "10.0.0.0")) shouldBe false
        }
        test("an empty allowlist never matches") {
            RemoteAddress.isInAnyCidr("10.0.0.5", emptyList()) shouldBe false
        }
        test("a /32 host match is exact") {
            RemoteAddress.isInAnyCidr("10.1.2.3", listOf("10.1.2.3/32")) shouldBe true
            RemoteAddress.isInAnyCidr("10.1.2.4", listOf("10.1.2.3/32")) shouldBe false
        }
        test("a remote bearing a %zone classifies the same as its zoneless form (the zone is stripped)") {
            RemoteAddress.isInAnyCidr("fe80::1%eth0", listOf("fe80::/16")) shouldBe true
            RemoteAddress.isInAnyCidr("fe80::1%eth0", listOf("2001:db8::/32")) shouldBe false
        }
    }

    context("isParseableCidr (config-load fail-fast, A1-amber)") {
        listOf(
            "10.0.0.0/8" to true,
            "192.168.0.0/16" to true,
            "10.1.2.3/32" to true, // a /32 host CIDR
            "2001:db8::/32" to true,
            "::/0" to true, // prefix 0 is in range
            "2001:db8::/128" to true,
            "10.0.0.0" to false, // a bare address with no /prefix is NOT a CIDR
            "not-a-cidr" to false,
            "10.0.0.0/33" to false, // out-of-range IPv4 prefix
            "10.0/8" to false, // legacy abbreviated IPv4 — getByName would expand to 10.0.0.0, masking a typo
            "192.168.1/24" to false, // a dropped octet must fail fast, not silently expand
            "10/8" to false, // single component
            "010.0.0.0/8" to false, // leading-zero octet (octal ambiguity)
            "10.0.0.256/8" to false, // octet out of range
            "2001:db8::/129" to false, // out-of-range IPv6 prefix
            "10.0.0.0/-1" to false, // negative prefix
            "10.0.0.0/abc" to false, // non-numeric prefix
            "" to false,
        ).forEach { (input, expected) ->
            test("'$input' parseableCidr == $expected") {
                RemoteAddress.isParseableCidr(input) shouldBe expected
            }
        }
    }

    context("forwardedProtoIsHttps (multi-value, fail-closed)") {
        listOf(
            listOf("https") to true,
            listOf("https", "https") to true,
            listOf("HTTPS") to true, // case-insensitive
            listOf("https,https") to true, // comma-joined single header
            emptyList<String>() to false, // absent → not secure
            listOf("http") to false,
            listOf("https", "http") to false, // any non-https value loses
            listOf("https,http") to false, // mixed comma-list — a spoofer appends http
            listOf("http,https") to false,
            listOf("") to false,
            listOf("https,") to false, // trailing empty token — a spoofer appends a blank (must NOT be filtered away)
            listOf("https", "") to false, // a blank second header value loses
            listOf(",https") to false, // leading empty token
            listOf("https,,https") to false, // an interior empty token loses
        ).forEach { (input, expected) ->
            test("$input → https == $expected") {
                RemoteAddress.forwardedProtoIsHttps(input) shouldBe expected
            }
        }
    }

    test("a spoofed X-Forwarded-For is never consulted: there is no XFF input to flip a verdict") {
        // The helper exposes no X-Forwarded-For path at all (§0.10). A non-loopback socket remote stays
        // non-loopback regardless of any header a client could forge — encoded here as: the loopback verdict
        // is a pure function of the SOCKET remote, and a routable remote is never loopback.
        RemoteAddress.isLoopbackAddress("203.0.113.7") shouldBe false
    }
})
