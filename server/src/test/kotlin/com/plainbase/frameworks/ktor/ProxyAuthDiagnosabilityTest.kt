package com.plainbase.frameworks.ktor

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.plainbase.domain.principal.Principal
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory

/**
 * A4b WI-6: operator-log diagnosability (the council's sonnet hole). The three not-authenticated reasons — a CIDR
 * miss, a proto mismatch, and a secret mismatch — each produce a DISTINCT operator log line, while the CLIENT-visible
 * verdict carries no oracle (a CIDR/proto miss is the same `InsecureTransportRefused`; a secret miss is the same
 * `Resolved(Anonymous)`). NO secret or identity VALUE ever appears in a log line.
 */
class ProxyAuthDiagnosabilityTest : FunSpec({

    val secret = "super-secret-value"
    val identity = "secret-subject-handle"

    fun captureDecision(
        remoteHost: String,
        forwardedProto: List<String>,
        presentedSecrets: List<String>,
    ): Pair<PrincipalExtraction, List<String>> {
        val root = LoggerFactory.getLogger(ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        return try {
            val result = decidePrincipalExtraction(
                bearer = null,
                cookie = null,
                remoteHost = remoteHost,
                forwardedProtoValues = forwardedProto,
                trustedProxyCidrs = listOf("10.0.0.0/8"),
                authenticateBearer = { Principal.Anonymous },
                authenticateCookie = { null },
                builtinAuthEnabled = false,
                proxyIdentityValues = listOf(identity),
                presentedProxySecrets = presentedSecrets,
                configuredProxySecret = secret,
                proxyAuthEnabled = true,
            )
            result to appender.list.map { it.formattedMessage }
        } finally {
            root.detachAppender(appender)
        }
    }

    test("CIDR miss vs proto mismatch vs secret mismatch are three DISTINCT log lines, identical client response") {
        val (cidrResult, cidrLogs) = captureDecision("203.0.113.9", listOf("https"), listOf(secret))
        val (protoResult, protoLogs) = captureDecision("10.1.2.3", emptyList(), listOf(secret))
        val (secretResult, secretLogs) = captureDecision("10.1.2.3", listOf("https"), listOf("wrong"))

        // CIDR miss + proto mismatch share the SAME client verdict (no oracle); secret miss shares Anonymous.
        cidrResult.shouldBe(PrincipalExtraction.InsecureTransportRefused)
        protoResult.shouldBe(PrincipalExtraction.InsecureTransportRefused)
        (secretResult as PrincipalExtraction.Resolved).principal shouldBe Principal.Anonymous

        cidrLogs.single { it.contains("CIDR miss") } shouldContain "CIDR miss"
        protoLogs.single { it.contains("proto mismatch") } shouldContain "proto mismatch"
        secretLogs.single { it.contains("secret mismatch") } shouldContain "secret mismatch"

        // The three categories are mutually exclusive in their own capture (one distinct line each).
        cidrLogs.none { it.contains("proto mismatch") || it.contains("secret mismatch") } shouldBe true
        protoLogs.none { it.contains("CIDR miss") || it.contains("secret mismatch") } shouldBe true
        secretLogs.none { it.contains("CIDR miss") || it.contains("proto mismatch") } shouldBe true
    }

    test("no log line carries the secret value or the identity value") {
        val captures = listOf(
            captureDecision("203.0.113.9", listOf("https"), listOf(secret)),
            captureDecision("10.1.2.3", emptyList(), listOf(secret)),
            captureDecision("10.1.2.3", listOf("https"), listOf("wrong")),
        )
        val allLogs = captures.flatMap { it.second }
        allLogs.filter { it.contains(secret) || it.contains(identity) }.shouldBeEmpty()
    }
})
