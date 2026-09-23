package com.plainbase.frameworks.ktor.routes

import io.ktor.http.HeaderValue
import io.ktor.http.HttpHeaders
import io.ktor.http.parseHeaderValue
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes

internal enum class PageRepresentation(
    val type: String,
    val subtype: String,
    val parameters: Map<String, String>,
) {
    JSON("application", "json", emptyMap()),
    HTML("text", "html", mapOf("charset" to "utf-8")),
    MARKDOWN("text", "markdown", mapOf("charset" to "utf-8")),
}

/** Adds the protocol variance marker before the route performs principal extraction. */
internal fun ApplicationCall.appendAcceptVary() {
    response.header(HttpHeaders.Vary, HttpHeaders.Accept)
}

/** Selects Markdown only when its explicit offer beats the route's historical representation. */
internal fun ApplicationCall.selectPageRepresentation(default: PageRepresentation): PageRepresentation =
    selectPageRepresentation(request.headers.getAll(HttpHeaders.Accept).orEmpty(), default)

internal fun selectPageRepresentation(
    acceptHeaders: List<String>,
    default: PageRepresentation,
): PageRepresentation {
    val ranges = acceptHeaders.flatMap { header ->
        runCatching { parseHeaderValue(header) }.getOrDefault(emptyList())
    }.mapIndexedNotNull { index, value -> parseAcceptRange(value, index) }

    // A wildcard may describe the default offer, but it is never an opt-in Markdown request.
    val markdownQuality = bestQuality(
        ranges.filter { it.type == "text" && it.subtype == "markdown" },
        PageRepresentation.MARKDOWN,
    )
    if (markdownQuality == null || markdownQuality <= 0.0) return default

    val defaultQuality = bestQuality(ranges, default)
    return if (markdownQuality > (defaultQuality ?: 0.0)) PageRepresentation.MARKDOWN else default
}

/** Selected Markdown is deliberately uncacheable until a representation-specific validator exists. */
internal fun ApplicationCall.markdownCacheHeaders() {
    response.header(HttpHeaders.CacheControl, "no-store")
}

/** Sends the indexed source directly, avoiding JSON serialization and the global JSON converter. */
internal suspend fun ApplicationCall.respondMarkdown(markdown: String) {
    markdownCacheHeaders()
    response.header(X_CONTENT_TYPE_OPTIONS, "nosniff")
    respondBytes(markdown.toByteArray(Charsets.UTF_8), MARKDOWN_CONTENT_TYPE)
}

private val MARKDOWN_CONTENT_TYPE = io.ktor.http.ContentType("text", "markdown").withCharset(Charsets.UTF_8)

private data class AcceptRange(
    val type: String,
    val subtype: String,
    val parameters: Map<String, String>,
    val quality: Double,
    val order: Int,
)

private fun parseAcceptRange(value: HeaderValue, order: Int): AcceptRange? {
    val mediaType = value.value.trim().split('/', limit = 2)
    if (mediaType.size != 2) return null
    val type = mediaType[0].trim().lowercase()
    val subtype = mediaType[1].trim().lowercase()
    if (type.isEmpty() || subtype.isEmpty()) return null

    val qParameters = value.params.filter { it.name.equals("q", ignoreCase = true) }
    if (qParameters.size > 1) return null
    val quality = when (val qParameter = qParameters.singleOrNull()) {
        null -> if (qParameters.isEmpty()) 1.0 else return null
        else -> qParameter.value.parseQuality() ?: return null
    }

    val mediaParameters = value.params.filterNot { it.name.equals("q", ignoreCase = true) }
    if (mediaParameters.any { !it.name.equals("charset", ignoreCase = true) } ||
        mediaParameters.count { it.name.equals("charset", ignoreCase = true) } > 1
    ) {
        return null
    }

    return AcceptRange(
        type = type,
        subtype = subtype,
        parameters = mediaParameters.associate { it.name.lowercase() to it.value.lowercase() },
        quality = quality,
        order = order,
    )
}

private fun String.parseQuality(): Double? {
    if (!matches(Regex("(?:0(?:\\.[0-9]{0,3})?|1(?:\\.0{0,3})?)"))) return null
    return toDoubleOrNull()
}

private fun bestQuality(ranges: List<AcceptRange>, offered: PageRepresentation): Double? =
    ranges.asSequence()
        .filter { it.matches(offered) }
        .sortedWith(compareByDescending<AcceptRange> { it.specificity() }.thenBy { it.order })
        .firstOrNull()
        ?.quality

private fun AcceptRange.matches(offered: PageRepresentation): Boolean {
    val typeMatches = type == "*" || type == offered.type
    val subtypeMatches = subtype == "*" || subtype == offered.subtype
    if (!typeMatches || !subtypeMatches) return false
    return parameters.all { (name, value) -> offered.parameters[name] == value }
}

private fun AcceptRange.specificity(): Int = when {
    type == "*" && subtype == "*" -> 0
    subtype == "*" -> 1
    else -> 2 + parameters.size
}
