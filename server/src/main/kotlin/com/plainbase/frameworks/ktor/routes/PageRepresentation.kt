package com.plainbase.frameworks.ktor.routes

internal enum class PageRepresentation(
    val type: String,
    val subtype: String,
    val parameters: Map<String, String>,
) {
    JSON("application", "json", emptyMap()),
    HTML("text", "html", mapOf("charset" to "utf-8")),
    MARKDOWN("text", "markdown", mapOf("charset" to "utf-8")),
}
