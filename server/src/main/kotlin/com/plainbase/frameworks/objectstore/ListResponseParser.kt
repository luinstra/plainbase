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
        // A leading U+FEFF (BOM) is NOT tolerated (P2): the oracle parses a StringReader of already-decoded
        // chars, so a literal U+FEFF is "content before the root" it REJECTS - and real S3/R2 responses never
        // carry one. Left unstripped, a BOM-prefixed body refuses at the char-data-outside-root check below
        // (fail-closed, accept-implies-oracle-agrees).
        val input = xml
        SUSPECT_MARKUP.forEach { marker ->
            if (marker in input) refuse("unsupported markup '$marker' in a ListObjectsV2 response")
        }
        // The XML declaration is the ONE allowed `<?`: strip a leading declaration that matches XML's real
        // `XMLDecl` grammar (version required + quoted, then optional encoding then standalone, in that order),
        // then any remaining `<?` refuses. A `<?xml` prefix that does NOT match the grammar (unquoted version,
        // wrong order, bad version number, ...) is a MALFORMED declaration the DOM oracle rejects, so refuse it
        // rather than strip it (the old loose `<?xml[^<]*?>` accepted-and-stripped those). No component matches
        // `<`, so an unterminated `<?xml` still cannot smuggle a later PI's `?>` through the gate.
        val declaration = XML_DECLARATION.find(input)
        val body = when {
            declaration != null -> input.substring(declaration.range.last + 1)
            input.startsWith("<?xml") -> refuse("malformed XML declaration")
            else -> input
        }
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
            else -> refuse("unexpected <IsTruncated> value '${cap(value)}'")
        }
        val token = optionalText(topLevel, "NextContinuationToken")
        // Fail-closed pagination: a truncated page MUST carry a nonblank continuation token. Accepting
        // `IsTruncated=true` with a missing/blank token makes every paging loop (the s3-smoke LIST, C4's
        // hydration) refetch page 1 forever on a malformed or hostile response - refuse instead of looping.
        if (truncated && token.isNullOrBlank()) refuse("truncated page carries no continuation token")
        // Fail-closed well-formedness (accept-implies-oracle-agrees): the extraction above trusts the FIRST
        // `</Contents>` as a block's close and reads only the <Key>/<ETag> substrings, so an unbalanced or
        // mis-nested sibling a real DOM parser rejects (a `<Bar>` with no close, a stray `</Bar>`, crossed
        // nesting) would otherwise be silently ignored and a bogus Entry returned. Checked LAST so the
        // specific shape refusals above keep their exact messages; balanced ignorable siblings
        // (<LastModified>, <Size>, <Owner>...</Owner>, ...) still pass untouched.
        requireWellFormed(body)
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
        return unescape(text, "<$tag> text")
    }

    /**
     * The five predefined XML entities plus numeric character references - all a text node can carry.
     * [context] is a pre-formatted, already-capped location for refusal messages (e.g. `<Key> text` or
     * `attribute 'x' value`), so a hostile-but-valid long element/attribute name can't inflate the log.
     */
    private fun unescape(rawText: String, context: String): String {
        // XML 1.0 §2.11 line-ending normalization (P3): a LITERAL CRLF or lone CR in the source is normalized
        // to LF (a `&#xD;` REFERENCE is not - it decodes to a real CR below), matching the DOM oracle's
        // textContent, so an accepted Key/ETag carrying a raw CR agrees with the oracle byte-for-byte.
        val text = rawText.replace("\r\n", "\n").replace("\r", "\n")
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
                val end = text.indexOf(';', i).takeIf { it > i } ?: refuse("bare '&' inside $context")
                when (val entity = text.substring(i + 1, end)) {
                    "amp" -> append('&')
                    "lt" -> append('<')
                    "gt" -> append('>')
                    "quot" -> append('"')
                    "apos" -> append('\'')
                    else -> {
                        val codePoint = parseCharRef(entity) ?: refuse("unknown entity '&${cap(entity)};' inside $context")
                        // A well-formed XML parser (the oracle) rejects a reference to any codepoint
                        // outside XML 1.0's Char production - not just surrogates but the illegal
                        // control range - so accepting one here would break accept-implies-oracle-agrees.
                        if (!isXmlChar(codePoint)) refuse("character reference '&${cap(entity)};' is not a legal XML character")
                        appendCodePoint(codePoint)
                    }
                }
                i = end + 1
            }
        }
    }

    /**
     * XML `CharRef`, STRICT (P1): decimal `#[0-9]+` or hex `#x[0-9a-fA-F]+` - ASCII digits only, LOWERCASE `x`
     * only, no leading sign, non-empty, no trailing junk. Returns the code point, or null for any other shape
     * (which the caller refuses). [Char.digit]-style parsers like `toIntOrNull(16)` accept a `+`/`-` sign and
     * Unicode digits (`&#٦٥;`, `&#x４１;`) the DOM oracle REJECTS, so the lexeme is validated BEFORE conversion;
     * `toIntOrNull` is used ONLY to convert an already-ASCII-validated string (so an out-of-range `&#99999999999;`
     * still returns null and is refused).
     */
    private fun parseCharRef(entity: String): Int? {
        val (digits, radix) = when {
            entity.startsWith("#x") -> entity.substring(2) to 16
            entity.startsWith("#") -> entity.substring(1) to 10
            else -> return null
        }
        val asciiValid = digits.isNotEmpty() && digits.all {
            it in '0'..'9' || (radix == 16 && (it in 'a'..'f' || it in 'A'..'F'))
        }
        return if (asciiValid) digits.toIntOrNull(radix) else null
    }

    /** XML 1.0 `Char` production: tab / LF / CR, then the three legal scalar ranges. */
    private fun isXmlChar(codePoint: Int): Boolean = codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD ||
        codePoint in 0x20..0xD7FF || codePoint in 0xE000..0xFFFD || codePoint in 0x10000..0x10FFFF

    /**
     * Every RAW code point in [text] must be a legal XML 1.0 `Char` (the last CharData rule): [unescape] only
     * validates code points that arrive via `&#...;` refs, so a LITERAL illegal char (a raw control byte, or a
     * LONE surrogate) would otherwise pass unchecked while the DOM oracle rejects the document. Iterates by code
     * point, so a valid surrogate PAIR reads as its supplementary code point (accepted) but a lone surrogate
     * reads as the 0xD800-0xDFFF value [isXmlChar] rejects. Applies to element char data AND attribute values.
     */
    private fun requireLegalXmlChars(text: String, context: String) {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (!isXmlChar(cp)) refuse("illegal XML character U+${cp.toString(16).uppercase().padStart(4, '0')} in $context")
            i += Character.charCount(cp)
        }
    }

    /**
     * A COMPLETE well-formedness check for the reduced grammar (R3-2): after this, an ACCEPT implies the
     * JDK DOM parser can parse the whole document. SUSPECT_MARKUP + the `<?` gate already refused comments /
     * CDATA / PIs / DOCTYPE, so what remains is elements, text and references, and these finite rules decide
     * well-formedness: exactly ONE root element; proper nesting; every start/end tag matching XML's STag/
     * ETag/EmptyElemTag; XML `Name` on every tag AND attribute name; whitespace-separated `Name="value"`
     * attributes with no duplicates; and every TEXT region (not just <Key>/<ETag>) obeying XML CharData -
     * no raw `<`, no bare `&`, no literal `]]>`, only legal XML `Char`s (raw and referenced) and valid refs.
     * Refusing here is always safe; it is the accept side of the differential fuzz.
     */
    private fun requireWellFormed(body: String) {
        val open = ArrayDeque<String>()
        var roots = 0 // XML requires exactly one root element
        var i = 0
        while (i < body.length) {
            val lt = body.indexOf('<', i).takeIf { it >= 0 } ?: run {
                requireValidText(body.substring(i), open.lastOrNull()) // trailing text
                break
            }
            requireValidText(body.substring(i, lt), open.lastOrNull()) // text before this tag
            if (lt + 1 >= body.length) refuse("unterminated '<' in the response")
            if (body[lt + 1] == '/') {
                val gt = body.indexOf('>', lt).takeIf { it >= 0 } ?: refuse("unterminated end tag")
                val name = endTagName(body.substring(lt + 2, gt))
                val top = open.removeLastOrNull() ?: refuse("end tag </${cap(name)}> has no matching start tag")
                if (top != name) refuse("mis-nested tags: </${cap(name)}> closes <${cap(top)}>")
                i = gt + 1
            } else {
                val tag = scanStartTag(body, lt)
                if (open.isEmpty()) roots++ // a new top-level element (self-closing counts as its own root)
                if (!tag.selfClosing) open.addLast(tag.name)
                i = tag.end + 1
            }
        }
        if (open.isNotEmpty()) refuse("unclosed element <${cap(open.last())}>")
        if (roots != 1) refuse("a ListObjectsV2 response must have exactly one root element (found $roots)")
    }

    /**
     * A text region between tags. OUTSIDE any element (before/after the root) only whitespace is legal XML
     * Misc - char data there is malformed. INSIDE an element, text obeys the same rule <Key>/<ETag> text does.
     * XML's CharData production forbids exactly three things: `<`, `&` and the literal `]]>` (a CDATA-section
     * terminator only - and CDATA is already refused up front). Raw `<` can never appear here (the scan stops
     * at the next `<`); [unescape] handles `&` (validating by throwing, decoded value discarded); and the `]]>`
     * check below closes CharData completely. Attribute values legally MAY contain `]]>`, so that check is
     * deliberately NOT in [requireWellFormedAttributes] - only element character data.
     */
    private fun requireValidText(text: String, enclosing: String?) {
        if (text.isEmpty()) return
        if (enclosing == null) {
            if (text.any { !isXmlSpace(it) }) refuse("character data outside the root element: '${cap(text)}'")
        } else {
            if ("]]>" in text) refuse("literal ']]>' in <${cap(enclosing)}> character data")
            requireLegalXmlChars(text, "<${cap(enclosing)}> character data")
            unescape(text, "<${cap(enclosing)}> character data")
        }
    }

    /**
     * The name of an end tag from its `</` .. `>` content. XML's ETag production is `'</' Name S? '>'`:
     * the name starts IMMEDIATELY after `</` (no leading whitespace), optional whitespace only AFTER it,
     * and no attributes. A `.trim()` here would wrongly accept `</ Bar>` the DOM oracle rejects (R2-3).
     */
    private fun endTagName(content: String): String {
        val name = content.takeWhile { !isXmlSpace(it) }
        if (name.isEmpty() || content.drop(name.length).any { !isXmlSpace(it) }) refuse("malformed end tag </${cap(content)}>")
        return name
    }

    private class StartTag(val name: String, val selfClosing: Boolean, val end: Int)

    /**
     * Scans a start tag at [lt], validating it against XML's `STag`/`EmptyElemTag` production (R2-3/R3-2):
     * a valid XML `Name`, then whitespace-separated `Name="value"`/`Name='value'` attributes only, with a
     * self-close `/` immediately before `>`. So `<1Bar>`, `<Bar/ >`, `<Bar attr=1>`, `<Bar attr>` and
     * `<a="1"b="2">` all refuse, while a real root `<ListBucketResult xmlns="...">` passes.
     */
    private fun scanStartTag(body: String, lt: Int): StartTag {
        val gt = endOfStartTag(body, lt)
        val content = body.substring(lt + 1, gt)
        val selfClosing = content.endsWith('/') // a quoted value ending in '/' is still followed by its close quote
        val inner = if (selfClosing) content.dropLast(1) else content
        val name = inner.takeWhile { !isXmlSpace(it) && it != '/' && it != '=' }
        requireXmlName(name, "start tag <${cap(content)}>")
        requireWellFormedAttributes(inner.substring(name.length), content)
        return StartTag(name, selfClosing, gt)
    }

    /** Every attribute: whitespace-separated `Name="value"`/`Name='value'`, a valid XML `Name`, no duplicate names. */
    private fun requireWellFormedAttributes(rest: String, content: String) {
        val seen = mutableSetOf<String>()
        var i = 0
        while (i < rest.length) {
            if (!isXmlSpace(rest[i])) refuse("malformed start tag <${cap(content)}>") // e.g. a stray '/' or unseparated attr
            while (i < rest.length && isXmlSpace(rest[i])) i++
            if (i >= rest.length) return
            val nameStart = i
            while (i < rest.length && !isXmlSpace(rest[i]) && rest[i] != '=') i++
            val attrName = rest.substring(nameStart, i)
            requireXmlName(attrName, "attribute of <${cap(content)}>")
            if (!seen.add(attrName)) refuse("duplicate attribute '${cap(attrName)}' in <${cap(content)}>")
            while (i < rest.length && isXmlSpace(rest[i])) i++
            if (i >= rest.length || rest[i] != '=') refuse("attribute without a value in <${cap(content)}>")
            i++
            while (i < rest.length && isXmlSpace(rest[i])) i++
            val quote = rest.getOrNull(i)
            if (quote != '"' && quote != '\'') refuse("unquoted attribute value in <${cap(content)}>")
            val close = rest.indexOf(quote, i + 1).takeIf { it >= 0 } ?: refuse("unterminated attribute value in <${cap(content)}>")
            // An attribute value obeys the same entity + legal-Char rules as text (but MAY contain `]]>`).
            val value = rest.substring(i + 1, close)
            requireLegalXmlChars(value, "attribute '${cap(attrName)}' value")
            unescape(value, "attribute '${cap(attrName)}' value")
            i = close + 1
        }
    }

    /**
     * XML `Name`, restricted to the ASCII subset (B-C1): `NameStartChar` = `:` `A-Z` `_` `a-z`, `NameChar`
     * adds `-` `.` `0-9`. Deliberately ASCII-ONLY, not Kotlin's Unicode-category [Char.isLetter]/[Char.isDigit]:
     * whether a given non-ASCII code point is a legal `NameStartChar` depends on the JDK parser's (unpinned) XML
     * edition, so an ASCII restriction sidesteps the edition question - it can only ever OVER-refuse a non-ASCII
     * name, which the one-directional accept-implies-oracle-agrees invariant ALLOWS and which real S3/R2
     * responses never contain. Refuses a digit/punct-leading tag or attribute name too (`<1Bar>`, `1attr=`).
     */
    private fun requireXmlName(name: String, context: String) {
        if (name.isEmpty() || !isNameStart(name[0]) || name.any { !isNameChar(it) }) {
            refuse("malformed XML name '${cap(name)}' in $context")
        }
    }

    private fun isNameStart(c: Char): Boolean = c == ':' || c in 'A'..'Z' || c == '_' || c in 'a'..'z'

    private fun isNameChar(c: Char): Boolean = isNameStart(c) || c == '-' || c == '.' || c in '0'..'9'

    /**
     * XML's `S` production is EXACTLY space / tab / LF / CR - NOT Kotlin's [Char.isWhitespace] (which also
     * matches Unicode Zs/Zl: NBSP U+00A0, U+2007, U+202F, the U+2000-200A run, ...). Using isWhitespace at an
     * `S` position would ACCEPT e.g. an NBSP attribute separator or end-tag/prolog space the DOM oracle rejects,
     * breaking accept-implies-oracle-agrees. (Unlike the deliberately-conservative Name check, this one is EXACT.)
     */
    private fun isXmlSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    /** The index of a start tag's closing `>`, skipping any `>` inside a quoted attribute value. */
    private fun endOfStartTag(body: String, lt: Int): Int {
        var quote = NONE
        for (j in lt + 1 until body.length) {
            val c = body[j]
            when {
                c == '<' -> refuse("'<' inside a start tag") // never legal raw in a name or an attribute value
                quote != NONE -> if (c == quote) quote = NONE
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return j
            }
        }
        refuse("unterminated start tag")
    }

    /** Truncates untrusted wire text folded into a refusal message so a hostile response can't bloat the log (M4). */
    private fun cap(value: String): String = if (value.length <= MAX_REFUSAL_CHARS) value else value.take(MAX_REFUSAL_CHARS) + "..."

    private fun refuse(reason: String): Nothing = throw ObjectStoreException("ListObjectsV2 response refused: $reason")

    /** The cap on untrusted wire text echoed into a refusal message (M4). */
    private const val MAX_REFUSAL_CHARS = 80

    /** [endOfStartTag]'s "not inside a quoted attribute value" sentinel (a quote is only ever `"` or `'`). */
    private const val NONE = ' '

    /**
     * XML's `XMLDecl` production, 0-anchored: `<?xml` then a REQUIRED quoted `version` (`1.0`, the only value
     * S3/R2 emit and the DOM oracle accepts), an OPTIONAL quoted `encoding` (an XML `EncName`) then an OPTIONAL
     * quoted `standalone` (`yes`/`no`), IN THAT ORDER, optional trailing whitespace, `?>`. Whitespace is XML `S`
     * exactly (space/tab/LF/CR, NOT `\s` which also admits VT/FF) so a form the oracle rejects cannot match.
     * Deliberately stricter than the spec's `version='1.[0-9]+'` - `1.0` is what real responses carry and what
     * the oracle certainly accepts; refusing a hypothetical `1.1` is safe (refusing never breaks the invariant).
     * The `EncName` charset check is deliberately-inert EXTRA caution: Reader-based DOM parsing ignores the
     * declared encoding (the input is already decoded chars), so validating it here can only ever over-refuse.
     */
    private val XML_DECLARATION = Regex(
        "^<\\?xml" +
            "[ \\t\\n\\r]+version[ \\t\\n\\r]*=[ \\t\\n\\r]*(\"1\\.0\"|'1\\.0')" +
            "([ \\t\\n\\r]+encoding[ \\t\\n\\r]*=[ \\t\\n\\r]*(\"[A-Za-z][A-Za-z0-9._-]*\"|'[A-Za-z][A-Za-z0-9._-]*'))?" +
            "([ \\t\\n\\r]+standalone[ \\t\\n\\r]*=[ \\t\\n\\r]*(\"(yes|no)\"|'(yes|no)'))?" +
            "[ \\t\\n\\r]*\\?>",
    )

    /** Markup a DOM parser reads differently than a literal scan - refused outright, never guessed at. */
    private val SUSPECT_MARKUP = listOf("<![CDATA[", "<!--", "<!DOCTYPE", "<!")
}
