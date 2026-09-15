package com.plainbase.frameworks.config

import com.plainbase.frameworks.net.RemoteAddress

/** Pure bind, cookie, and MCP transport values shared by configuration and HTTP assembly. */
internal object TransportSecurityPolicy {
    fun derive(config: PlainbaseConfig): TransportSecurityValues {
        val nonLoopbackBind = RemoteAddress.isNonLoopbackBind(config.host)
        val bindRefusal = when {
            // Proxy completeness must precede loopback and insecure admission.
            config.auth.mode == AuthMode.PROXY &&
                (config.auth.trustedProxyCidrs.isEmpty() || config.auth.proxySecret.isNullOrBlank()) ->
                INCOMPLETE_PROXY_REFUSAL
            !nonLoopbackBind -> null
            config.auth.trustedProxyCidrs.isNotEmpty() -> null
            config.auth.insecureHttp -> null
            else ->
                "binds ${config.host} with auth.mode=${config.auth.mode.name.lowercase()} but no TLS/trusted-proxy and no insecure " +
                    "override. " +
                    "Remedies: (1) front with a TLS proxy and set PLAINBASE_TRUSTED_PROXY CIDRs; " +
                    "(2) bind loopback (PLAINBASE_HOST=127.0.0.1) behind the proxy; " +
                    "(3) set PLAINBASE_INSECURE_HTTP=1 to knowingly serve plaintext."
        }
        val effectiveMcpHosts = config.auth.mcpAllowedHosts.ifEmpty {
            (listOf(config.host) + MCP_LOOPBACK_HOSTS).distinct()
        }
        val effectiveMcpOrigins = config.auth.mcpAllowedOrigins.ifEmpty {
            (
                listOf(
                    "http://${config.host}:${config.port}",
                    "https://${config.host}:${config.port}",
                ) + MCP_LOOPBACK_HOSTS.map { "http://$it:${config.port}" }
            ).distinct()
        }
        return TransportSecurityValues(
            bindRefusal = bindRefusal,
            nonLoopbackBind = nonLoopbackBind,
            // The insecure bind override never relaxes cookie security.
            secureCookie = nonLoopbackBind || config.auth.trustedProxyCidrs.isNotEmpty(),
            effectiveMcpHosts = effectiveMcpHosts,
            effectiveMcpOrigins = effectiveMcpOrigins,
        )
    }

    private val MCP_LOOPBACK_HOSTS: List<String> = listOf("127.0.0.1", "localhost")

    private const val INCOMPLETE_PROXY_REFUSAL =
        "auth.mode=proxy requires both a trusted-proxy allowlist and a shared secret. " +
            "Remedies: set PLAINBASE_TRUSTED_PROXY to the proxy's /32; " +
            "set PLAINBASE_PROXY_SECRET to a shared value the proxy stamps."
}

internal data class TransportSecurityValues(
    val bindRefusal: String?,
    val nonLoopbackBind: Boolean,
    val secureCookie: Boolean,
    val effectiveMcpHosts: List<String>,
    val effectiveMcpOrigins: List<String>,
)
