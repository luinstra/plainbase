package com.plainbase.frameworks.objectstore

/**
 * The ListObjectsV2 five-element extractor (plan Q7, theme 4): reads exactly `<Contents>`
 * boundaries and, inside them, `<Key>` + `<ETag>`, plus the top-level `<IsTruncated>` and
 * `<NextContinuationToken>`. Deliberately NOT an XML parser - requests always send
 * `encoding-type=url`, so `<Key>` (the one externally-influenced field) arrives
 * percent/form-encoded ASCII, and every other target is provider-generated. Keys are returned
 * RAW (still URL-encoded); decoding is the consumer's job, behind the recorded live captures
 * (the C4 percent-decode + `TreePath.require` funnel, R8).
 *
 * Fail-closed, never fail-wrong: anything outside the frozen shape (markup that could make a
 * real parser read DIFFERENT text - CDATA, comments, processing instructions past the XML
 * declaration, attributes on a target element, a missing/duplicated target) throws
 * [ObjectStoreException] rather than extracting a guess. Guarded by golden tests plus a
 * differential fuzz against the JDK DOM parser as a test-only oracle (accept implies
 * oracle-agrees; refusing what the oracle can read is always safe).
 */
object ListResponseParser {

    data class Listing(val contents: List<Entry>, val isTruncated: Boolean, val nextContinuationToken: String?)

    /** One `<Contents>` block: the RAW (still URL-encoded) key and the opaque ETag as returned. */
    data class Entry(val key: String, val etag: String)

    fun parse(xml: String): Listing {
        SUSPECT_MARKUP.forEach { marker ->
            if (marker in xml) refuse("unsupported markup '$marker' in a ListObjectsV2 response")
        }
        // The XML declaration is the ONE allowed `<?`: strip a WELL-FORMED leading `<?xml ... ?>` only,
        // then any remaining `<?` refuses. The `[^<]*` bound is load-bearing - a declaration can never
        // contain `<`, so it stops an unterminated `<?xml` from consuming up to a LATER PI's `?>` and
        // smuggling the malformed remainder through the gate (which a plain `indexOf("?>")` would).
        val body = XML_DECLARATION.find(xml)?.let { xml.substring(it.range.last + 1) } ?: xml
        if ("<?" in body) refuse("processing instruction past the XML declaration")

        val contents = buildList {
            var from = 0
            while (true) {
                val open = body.indexOf("<Contents", from).takeIf { it >= 0 } ?: break
                // An attributed/self-closing form would otherwise be skipped WHOLE, silently dropping keys.
                if (!body.startsWith("<Contents>", open)) refuse("unsupported form of <Contents>")
                val close = body.indexOf("</Contents>", open).takeIf { it >= 0 }
                    ?: refuse("unclosed <Contents> block")
                val block = body.substring(open + "<Contents>".length, close)
                if ("<Contents" in block) refuse("nested <Contents> block")
                add(Entry(key = requiredText(block, "Key"), etag = requiredText(block, "ETag")))
                from = close + "</Contents>".length
            }
        }

        // The two top-level fields are read OUTSIDE the <Contents> blocks, so a hostile key can
        // never masquerade as pagination state.
        val topLevel = body.split("<Contents>").mapIndexed { index, part ->
            if (index == 0) part else part.substringAfter("</Contents>", missingDelimiterValue = "")
        }.joinToString("")
        val truncated = when (val value = requiredText(topLevel, "IsTruncated")) {
            "true" -> true
            "false" -> false
            else -> refuse("unexpected <IsTruncated> value '$value'")
        }
        val token = optionalText(topLevel, "NextContinuationToken")
        // Fail-closed pagination: a truncated page MUST carry a nonblank continuation token. Accepting
        // `IsTruncated=true` with a missing/blank token makes every paging loop (the s3-smoke LIST, C4's
        // hydration) refetch page 1 forever on a malformed or hostile response — refuse instead of looping.
        if (truncated && token.isNullOrBlank()) refuse("truncated page carries no continuation token")
        return Listing(contents, truncated, token)
    }

    private fun requiredText(scope: String, tag: String): String =
        optionalText(scope, tag) ?: refuse("missing <$tag> element")

    /** The single `<tag>text</tag>` inside [scope]: null when absent, refusal on ambiguity. */
    private fun optionalText(scope: String, tag: String): String? {
        // Anchor on the FIRST `<tag` occurrence: an attributed or self-closing form is a shape we
        // do not read, and it must refuse even when a plain form follows it - never skip.
        val open = scope.indexOf("<$tag").takeIf { it >= 0 } ?: return null
        if (!scope.startsWith("<$tag>", open)) refuse("unsupported form of <$tag>")
        val start = open + tag.length + 2
        val close = scope.indexOf("</$tag>", start).takeIf { it >= 0 } ?: refuse("unclosed <$tag> element")
        if (scope.indexOf("<$tag", close + 1) >= 0) refuse("duplicate <$tag> element")
        val text = scope.substring(start, close)
        if ('<' in text) refuse("markup inside <$tag> text")
        return unescape(text, tag)
    }

    /** The five predefined XML entities plus numeric character references - all a text node can carry. */
    private fun unescape(text: String, tag: String): String {
        if ('&' !in text) return text
        return buildString(text.length) {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c != '&') {
                    append(c)
                    i++
                    continue
                }
                val end = text.indexOf(';', i).takeIf { it > i } ?: refuse("bare '&' inside <$tag> text")
                when (val entity = text.substring(i + 1, end)) {
                    "amp" -> append('&')
                    "lt" -> append('<')
                    "gt" -> append('>')
                    "quot" -> append('"')
                    "apos" -> append('\'')
                    else -> {
                        val codePoint = when {
                            entity.startsWith("#x") || entity.startsWith("#X") -> entity.drop(2).toIntOrNull(16)
                            entity.startsWith("#") -> entity.drop(1).toIntOrNull()
                            else -> null
                        } ?: refuse("unknown entity '&$entity;' inside <$tag> text")
                        // A well-formed XML parser (the oracle) rejects a reference to any codepoint
                        // outside XML 1.0's Char production - not just surrogates but the illegal
                        // control range - so accepting one here would break accept-implies-oracle-agrees.
                        if (!isXmlChar(codePoint)) refuse("character reference '&$entity;' is not a legal XML character")
                        appendCodePoint(codePoint)
                    }
                }
                i = end + 1
            }
        }
    }

    /** XML 1.0 `Char` production: tab / LF / CR, then the three legal scalar ranges. */
    private fun isXmlChar(codePoint: Int): Boolean = codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD ||
        codePoint in 0x20..0xD7FF || codePoint in 0xE000..0xFFFD || codePoint in 0x10000..0x10FFFF

    private fun refuse(reason: String): Nothing = throw ObjectStoreException("ListObjectsV2 response refused: $reason")

    /** A well-formed leading XML declaration: `<?xml`, no `<` until the first `?>` that closes it. */
    private val XML_DECLARATION = Regex("^<\\?xml[^<]*\\?>")

    /** Markup a DOM parser reads differently than a literal scan - refused outright, never guessed at. */
    private val SUSPECT_MARKUP = listOf("<![CDATA[", "<!--", "<!DOCTYPE", "<!")
}
