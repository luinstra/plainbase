package com.plainbase.domain.service

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.model.LinkOutcome
import com.plainbase.domain.model.LinkOutcome.BrokenReason
import com.plainbase.frameworks.filesystem.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.checkAll

/**
 * PB-LINK-1 scheme allowlist pinned BEYOND the golden rows (acceptance criterion 2): the allowlist is
 * exactly {http, https, mailto, protocol-relative}; any OTHER RFC-3986 scheme is `blocked_scheme`.
 * Property test over generated scheme strings — safer-by-default: unknown schemes are blocked, never
 * passed through.
 */
class SchemeAllowlistPropertyTest : FunSpec({

    val source = TreePath.require("guides/deploy-guide.md")
    val resolver = LinkResolver(FixtureIndexStub(Fixtures.demoDocs))

    val allowed = setOf("http", "https", "mailto")

    // Valid RFC-3986 scheme grammar: ALPHA *( ALPHA / DIGIT / "+" / "-" / "." ).
    val schemes: Arb<String> = Arb.stringPattern("[A-Za-z][A-Za-z0-9+.-]{0,6}")

    test("any scheme not on the allowlist resolves to blocked_scheme") {
        checkAll(schemes.filter { it.lowercase() !in allowed }) { scheme ->
            val outcome = resolver.resolve(source, "$scheme:some/target")
            check(outcome is LinkOutcome.Broken) { "expected broken for scheme '$scheme', got $outcome" }
            outcome.reason shouldBe BrokenReason.BLOCKED_SCHEME
        }
    }

    test("allowed schemes always pass through as external, in any letter casing") {
        val casings: Arb<String> = arbitrary { rs ->
            val scheme = allowed.toList()[rs.random.nextInt(allowed.size)]
            scheme.map { if (rs.random.nextBoolean()) it.uppercaseChar() else it }.joinToString("")
        }
        checkAll(casings) { cased ->
            val outcome = resolver.resolve(source, "$cased:some/target")
            check(outcome is LinkOutcome.Resolved.External) { "expected external for '$cased', got $outcome" }
        }
    }
})
