package com.plainbase.domain.theming

enum class TintedFailureKind { SCHEME_SYNTAX, SCHEME_SYSTEM, SCHEME_VARIANT, SCHEME_PALETTE, VARIANT_MISMATCH }

internal enum class TintedRule(val kind: TintedFailureKind = TintedFailureKind.SCHEME_SYNTAX) {
    CONTROL_CHARACTER,
    CARRIAGE_RETURN,
    YAML_LINE_BREAK,
    DOCUMENT_MARKER,
    TAB_INDENT,
    KEY_FORM,
    DUPLICATE_KEY,
    VALUE_FORM,
    EMPTY_VALUE,
    PALETTE_VALUE_FORM(TintedFailureKind.SCHEME_PALETTE),
    INDENTATION,
    SYSTEM_MISSING(TintedFailureKind.SCHEME_SYSTEM),
    REQUIRED_KEY_MISSING,
    PALETTE_MISSING(TintedFailureKind.SCHEME_PALETTE),
    SYSTEM_UNSUPPORTED(TintedFailureKind.SCHEME_SYSTEM),
    VARIANT_UNSUPPORTED(TintedFailureKind.SCHEME_VARIANT),
    SLUG_FORM,
    SLOT_UNEXPECTED(TintedFailureKind.SCHEME_PALETTE),
    SLOT_MISSING(TintedFailureKind.SCHEME_PALETTE),
    HEX_SPELLING(TintedFailureKind.SCHEME_PALETTE),
    HEX_INVALID(TintedFailureKind.SCHEME_PALETTE),
    VARIANT_MISMATCH(TintedFailureKind.VARIANT_MISMATCH),
}

class TintedFailure internal constructor(internal val rule: TintedRule, val line: Int?) {
    val kind: TintedFailureKind get() = rule.kind
}

sealed interface TintedConversion {
    data class Definition(val css: String) : TintedConversion
    data class Rejected(val failure: TintedFailure) : TintedConversion
}

object TintedSchemes {
    private val reserved = setOf("null", "true", "false", "yes", "no", "on", "off", "y", "n", "inf", "nan", "infinity")
    private val scalarOnly = setOf("system", "variant", "slug")
    private val topEntry = Regex("^([A-Za-z][A-Za-z0-9_-]{0,1023}):(?= |$)")
    private val paletteEntry = Regex("^  ([A-Za-z0-9_-]+):(?= |$)")
    private val quoted = Regex("\"([^\"\\\\]*)\"(?:[ \\t]+#.*|[ \\t]*)")
    private val trail = Regex("(?:[ \\t]+#.*|[ \\t]*)")
    private val empty = Regex("[ \\t]*(?:#.*)?")
    private val canonicalHex = Regex("#[0-9a-fA-F]{6}")
    private val builderHex = Regex("#?(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})")
    private val base16Slots = "0123456789ABCDEF".map { "base0$it" }
    private val base24Slots = base16Slots + ('0'..'7').map { "base1$it" }
    private data class Mapping(val input: String, val slot: String, val fallback: String? = null)
    private val mapping = listOf(
        Mapping("--pb-theme-background", "base00"),
        Mapping("--pb-theme-foreground", "base05"),
        Mapping("--pb-theme-primary", "base0D"),
        Mapping("--pb-theme-primary-foreground", "base00"),
        Mapping("--pb-theme-muted", "base01"),
        Mapping("--pb-theme-muted-foreground", "base04"),
        Mapping("--pb-theme-ring", "base16", "base0D"),
        Mapping("--pb-theme-selection", "base02"),
        Mapping("--pb-theme-selection-foreground", "base05"),
        Mapping("--pb-theme-destructive", "base12", "base08"),
        Mapping("--pb-theme-warning", "base13", "base0A"),
        Mapping("--pb-theme-success", "base14", "base0B"),
        Mapping("--pb-theme-syntax-keyword", "base17", "base0E"),
        Mapping("--pb-theme-syntax-string", "base14", "base0B"),
        Mapping("--pb-theme-syntax-comment", "base04"),
        Mapping("--pb-theme-syntax-literal", "base09"),
        Mapping("--pb-theme-syntax-title", "base16", "base0D"),
        Mapping("--pb-theme-syntax-attribute", "base12", "base08"),
    )

    fun convert(text: String, mode: ThemeMode): TintedConversion = when (val parsed = parse(text)) {
        is DocumentResult.Rejected -> TintedConversion.Rejected(parsed.failure)
        is DocumentResult.Parsed -> {
            val variant = parsed.variant
            if (variant != null && variant.text != mode.name.lowercase()) {
                TintedConversion.Rejected(failure(TintedRule.VARIANT_MISMATCH, variant.line))
            } else {
                TintedConversion.Definition(render(parsed.palette, mode))
            }
        }
    }

    private fun render(palette: Map<String, String>, mode: ThemeMode): String = buildString {
        append(
            when (mode) {
                ThemeMode.LIGHT -> ":where(:root:not([data-theme=\"dark\"]))"
                ThemeMode.DARK -> ":where(:root[data-theme=\"dark\"])"
            },
        )
        append(" {\n")
        mapping.forEach { (input, slot, fallback) ->
            val hex = palette[slot] ?: palette.getValue(requireNotNull(fallback))
            append("  ").append(input).append(": #").append(hex).append(";\n")
        }
        append("}\n")
    }

    private sealed interface Value {
        val line: Int
        data class Text(val text: String, override val line: Int) : Value
        data class Literal(override val line: Int) : Value
        data class Palette(override val line: Int) : Value
    }

    private sealed interface ParseResult {
        data class Parsed(val entries: Map<String, Value>, val palette: Map<String, Value.Text>) : ParseResult
        data class Rejected(val failure: TintedFailure) : ParseResult
    }

    private sealed interface DocumentResult {
        data class Parsed(val palette: Map<String, String>, val variant: Value.Text?) : DocumentResult
        data class Rejected(val failure: TintedFailure) : DocumentResult
    }

    private sealed interface ScalarResult {
        data class Parsed(val value: Value) : ScalarResult
        data class Rejected(val rule: TintedRule) : ScalarResult
    }

    private sealed interface Context {
        data object TopLevel : Context
        data object Palette : Context
        data class BlockStart(val line: Int) : Context
        data class BlockContent(val indent: Int) : Context
    }

    private sealed interface LineResult {
        data object Accepted : LineResult
        data class Rejected(val failure: TintedFailure) : LineResult
    }

    private fun parse(text: String): DocumentResult = when (val lines = parseLines(text)) {
        is ParseResult.Rejected -> DocumentResult.Rejected(lines.failure)
        is ParseResult.Parsed -> validate(lines)
    }

    private fun validate(document: ParseResult.Parsed): DocumentResult {
        requiredFailure(document.entries)?.let { return DocumentResult.Rejected(it) }
        knownValueFailure(document.entries)?.let { return DocumentResult.Rejected(it) }
        val system = requireNotNull(document.entries["system"] as? Value.Text)
        val expected = if (system.text == "base24") base24Slots else base16Slots
        paletteFailure(document, expected)?.let { return DocumentResult.Rejected(it) }
        return DocumentResult.Parsed(
            document.palette.mapValues { (_, value) -> value.text.drop(1).lowercase() },
            document.entries["variant"] as? Value.Text,
        )
    }

    private fun requiredFailure(entries: Map<String, Value>): TintedFailure? = when {
        "system" !in entries -> failure(TintedRule.SYSTEM_MISSING, null)
        "name" !in entries || "author" !in entries -> failure(TintedRule.REQUIRED_KEY_MISSING, null)
        "palette" !in entries -> failure(TintedRule.PALETTE_MISSING, null)
        else -> null
    }

    private fun knownValueFailure(entries: Map<String, Value>): TintedFailure? {
        val system = requireNotNull(entries["system"] as? Value.Text)
        val variant = entries["variant"] as? Value.Text
        val declaredSlug = entries["slug"] as? Value.Text
        return when {
            system.text != "base16" && system.text != "base24" -> failure(TintedRule.SYSTEM_UNSUPPORTED, system.line)
            variant != null && variant.text != "light" && variant.text != "dark" -> failure(TintedRule.VARIANT_UNSUPPORTED, variant.line)
            declaredSlug != null && !isCanonicalSlug(declaredSlug.text) -> failure(TintedRule.SLUG_FORM, declaredSlug.line)
            else -> null
        }
    }

    private fun isCanonicalSlug(text: String): Boolean {
        var needsLetterOrDigit = true
        for (character in text) {
            when (character) {
                in 'a'..'z', in '0'..'9' -> needsLetterOrDigit = false
                '-' -> {
                    if (needsLetterOrDigit) return false
                    needsLetterOrDigit = true
                }
                else -> return false
            }
        }
        return !needsLetterOrDigit
    }

    private fun paletteFailure(document: ParseResult.Parsed, expected: List<String>): TintedFailure? {
        document.palette.entries.firstOrNull { it.key !in expected }?.let {
            return failure(TintedRule.SLOT_UNEXPECTED, it.value.line)
        }
        if (expected.any { it !in document.palette }) {
            return failure(TintedRule.SLOT_MISSING, document.entries.getValue("palette").line)
        }
        return document.palette.values.firstNotNullOfOrNull { value ->
            val rule = when {
                canonicalHex.matches(value.text) -> null
                builderHex.matches(value.text) -> TintedRule.HEX_SPELLING
                else -> TintedRule.HEX_INVALID
            }
            rule?.let { failure(it, value.line) }
        }
    }

    private fun parseLines(text: String): ParseResult {
        characterFailure(text)?.let { return ParseResult.Rejected(it) }
        val segments = text.split('\n')
        val lines = if (text.endsWith('\n')) segments.dropLast(1) else segments
        val parser = LineParser()
        for ((index, segment) in lines.withIndex()) {
            when (val result = parser.consume(segment.removeSuffix("\r"), index + 1)) {
                is LineResult.Rejected -> return ParseResult.Rejected(result.failure)
                LineResult.Accepted -> Unit
            }
        }
        return parser.finish()
    }

    private class LineParser {
        private val entries = linkedMapOf<String, Value>()
        private val palette = linkedMapOf<String, Value.Text>()
        private var context: Context = Context.TopLevel

        fun consume(line: String, number: Int): LineResult {
            val indent = line.takeWhile { it == ' ' }.length
            when (val previous = context) {
                is Context.BlockStart -> {
                    if (indent == 0 || line.isBlank()) return lineRejected(TintedRule.VALUE_FORM, previous.line)
                    context = Context.BlockContent(indent)
                    return LineResult.Accepted
                }
                is Context.BlockContent -> {
                    if (line.isNotBlank() && indent >= previous.indent) return LineResult.Accepted
                    context = Context.TopLevel
                }
                else -> Unit
            }
            return ordinaryLine(line, number, indent)
        }

        private fun ordinaryLine(line: String, number: Int, indent: Int): LineResult {
            val unindented = line.drop(indent)
            return when {
                line.startsWith("---") || line.startsWith("...") ->
                    if (number == 1 && line == "---") LineResult.Accepted else lineRejected(TintedRule.DOCUMENT_MARKER, number)
                unindented.startsWith('\t') -> lineRejected(TintedRule.TAB_INDENT, number)
                unindented.isEmpty() || unindented.startsWith('#') -> LineResult.Accepted
                indent > 0 ->
                    if (context == Context.Palette) paletteLine(line, number) else lineRejected(TintedRule.INDENTATION, number)
                else -> topLine(line, number)
            }
        }

        private fun paletteLine(line: String, number: Int): LineResult {
            val entry = paletteEntry.find(line) ?: return lineRejected(TintedRule.INDENTATION, number)
            val key = entry.groupValues[1]
            if (key in palette) return lineRejected(TintedRule.DUPLICATE_KEY, number)
            val value = quoted.matchEntire(line.substringAfter(':').trimStart(' '))
                ?: return lineRejected(TintedRule.PALETTE_VALUE_FORM, number)
            palette[key] = Value.Text(value.groupValues[1], number)
            return LineResult.Accepted
        }

        private fun topLine(line: String, number: Int): LineResult {
            val entry = topEntry.find(line) ?: return lineRejected(TintedRule.KEY_FORM, number)
            val key = entry.groupValues[1]
            val suffix = line.substringAfter(':')
            return when {
                key.lowercase() in reserved -> lineRejected(TintedRule.KEY_FORM, number)
                key in entries -> lineRejected(TintedRule.DUPLICATE_KEY, number)
                key == "palette" -> {
                    if (!trail.matches(suffix)) {
                        lineRejected(TintedRule.PALETTE_VALUE_FORM, number)
                    } else {
                        entries[key] = Value.Palette(number)
                        context = Context.Palette
                        LineResult.Accepted
                    }
                }
                else -> when (val scalar = scalar(key, suffix.trimStart(' '), number)) {
                    is ScalarResult.Rejected -> lineRejected(scalar.rule, number)
                    is ScalarResult.Parsed -> {
                        entries[key] = scalar.value
                        context = if (scalar.value is Value.Literal) Context.BlockStart(number) else Context.TopLevel
                        LineResult.Accepted
                    }
                }
            }
        }

        fun finish(): ParseResult = when (val last = context) {
            is Context.BlockStart -> rejected(TintedRule.VALUE_FORM, last.line)
            else -> ParseResult.Parsed(entries, palette)
        }
    }

    private fun lineRejected(rule: TintedRule, line: Int): LineResult.Rejected =
        LineResult.Rejected(failure(rule, line))

    private fun scalar(key: String, text: String, line: Int): ScalarResult {
        if (empty.matches(text)) return ScalarResult.Rejected(TintedRule.EMPTY_VALUE)
        quoted.matchEntire(text)?.let { return ScalarResult.Parsed(Value.Text(it.groupValues[1], line)) }
        if (text == "|" && key !in scalarOnly) return ScalarResult.Parsed(Value.Literal(line))
        val value = plainText(text)
        return if (value != null && ": " !in value && !value.endsWith(':') && value.lowercase() !in reserved) {
            ScalarResult.Parsed(Value.Text(value, line))
        } else {
            ScalarResult.Rejected(TintedRule.VALUE_FORM)
        }
    }

    private fun plainText(text: String): String? {
        val content = text.substringBefore('#')
        if ('#' in text && content.lastOrNull() != ' ' && content.lastOrNull() != '\t') return null
        if (content.substringAfter('\t', "").any { it != ' ' && it != '\t' }) return null
        return content.substringBefore('\t').trimEnd(' ').takeIf { value ->
            value.firstOrNull()?.let { it in 'A'..'Z' || it in 'a'..'z' } == true
        }
    }

    private fun characterFailure(text: String): TintedFailure? {
        var line = 1
        for (index in text.indices) {
            val char = text[index]
            val rule = when {
                char == '\r' -> if (text.getOrNull(index + 1) == '\n') null else TintedRule.CARRIAGE_RETURN
                char == '\u0085' || char == '\u2028' || char == '\u2029' -> TintedRule.YAML_LINE_BREAK
                char == '\t' || char == '\n' || char in ' '..'~' || char in '\u00A0'..'\uD7FF' || char in '\uE000'..'\uFFFD' -> null
                char.isHighSurrogate() && text.getOrNull(index + 1)?.isLowSurrogate() == true -> null
                char.isLowSurrogate() && text.getOrNull(index - 1)?.isHighSurrogate() == true -> null
                else -> TintedRule.CONTROL_CHARACTER
            }
            if (rule != null) return failure(rule, line)
            if (char == '\n') line++
        }
        return null
    }

    private fun rejected(rule: TintedRule, line: Int): ParseResult.Rejected =
        ParseResult.Rejected(failure(rule, line))

    private fun failure(rule: TintedRule, line: Int?): TintedFailure = TintedFailure(rule, line)
}
