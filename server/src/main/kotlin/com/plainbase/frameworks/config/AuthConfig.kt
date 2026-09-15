package com.plainbase.frameworks.config

import com.plainbase.domain.root.RootName

/** How requests authenticate. */
enum class AuthMode {
    OFF,
    BUILTIN,
    PROXY,
    ;

    companion object {
        /** Parses an auth mode case-insensitively; absent and blank values use [OFF]. */
        fun parse(raw: String?): AuthMode {
            val token = raw?.trim()
            if (token.isNullOrEmpty()) return OFF
            return entries.firstOrNull { it.name.equals(token, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Unknown auth.mode '$token' - legal values: ${entries.joinToString(", ") { it.name.lowercase() }}",
                )
        }
    }
}

/** Restart-only authentication configuration. */
data class AuthConfig(
    val mode: AuthMode = AuthMode.OFF,
    val trustedProxyCidrs: List<String> = emptyList(),
    val insecureHttp: Boolean = false,
    val agentDirectCommitGlobs: List<String> = emptyList(),
    val agentDirectCommitGlobsByRoot: Map<RootName, List<String>> = emptyMap(),
    val proxySecret: String? = null,
    val proxyIdentityHeader: String = PlainbaseConfig.DEFAULT_PROXY_IDENTITY_HEADER,
    val mcpAllowedHosts: List<String> = emptyList(),
    val mcpAllowedOrigins: List<String> = emptyList(),
)
