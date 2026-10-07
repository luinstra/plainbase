package com.plainbase.frameworks.ktor.routes

/**
 * Shared route/composer seam for non-null typed creation.
 * Title, slug and type reject ISO controls, U+FFFE/U+FFFF, U+2028/U+2029 and unpaired surrogates.
 * Format characters, including joiners and bidi characters, are deliberately not newly prohibited.
 * U+2028/U+2029 rejection is producer interoperability policy, not a YAML 1.2 validity rule.
 * Body Unicode is unrestricted except for unpaired surrogates, which UTF-8 encoding would replace.
 * Null type bypasses this seam entirely, preserving legacy behavior.
 */
internal fun invalidTypedCreateField(title: String, slug: String?, type: String, body: String?): String? = when {
    type.isBlank() -> "type"
    title.hasInvalidHeaderUnicode() -> "title"
    slug?.hasInvalidHeaderUnicode() == true -> "slug"
    type.hasInvalidHeaderUnicode() -> "type"
    body?.hasUnpairedSurrogate() == true -> "body"
    else -> null
}

private fun String.hasInvalidHeaderUnicode(): Boolean =
    any { it.isISOControl() || it == '\uFFFE' || it == '\uFFFF' || it == '\u2028' || it == '\u2029' } || hasUnpairedSurrogate()

private fun String.hasUnpairedSurrogate(): Boolean =
    indices.any { index ->
        when {
            this[index].isHighSurrogate() -> getOrNull(index + 1)?.isLowSurrogate() != true
            this[index].isLowSurrogate() -> getOrNull(index - 1)?.isHighSurrogate() != true
            else -> false
        }
    }
