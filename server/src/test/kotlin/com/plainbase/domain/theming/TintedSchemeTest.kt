package com.plainbase.domain.theming

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TintedSchemeTest : FunSpec({
    fun accepts(label: String, text: String, modes: List<ThemeMode> = ThemeMode.entries) {
        test("accepts $label") {
            modes.forEach { mode ->
                withClue(mode) { TintedSchemes.convert(text, mode).shouldBeInstanceOf<TintedConversion.Definition>() }
            }
        }
    }

    fun rejects(
        label: String,
        text: String,
        rule: TintedRule,
        line: Int?,
        kind: TintedFailureKind = TintedFailureKind.SCHEME_SYNTAX,
        modes: List<ThemeMode> = ThemeMode.entries,
    ) {
        test("rejects $label") {
            assertSoftly {
                modes.forEach { mode ->
                    withClue(mode) {
                        val failure = TintedSchemes.convert(text, mode).shouldBeInstanceOf<TintedConversion.Rejected>().failure
                        failure.rule shouldBe rule
                        failure.kind shouldBe kind
                        failure.line shouldBe line
                    }
                }
            }
        }
    }

    val sample = buildString {
        append("system: \"base16\"\nname: \"Paper\"\nauthor: \"Author\"\npalette:\n")
        "0123456789ABCDEF".forEach { slot -> append("  base0$slot: \"#abcdef\"\n") }
    }
    fun named(value: String) = sample.replaceFirst("name: \"Paper\"", "name: $value")
    fun paletteEntry(value: String) = sample.replaceFirst("  base00: \"#abcdef\"", "  base00: $value")
    fun stylesheet(text: String, mode: ThemeMode) =
        TintedSchemes.convert(text, mode).shouldBeInstanceOf<TintedConversion.Definition>().css

    val byteCap = 262_144
    val positions = listOf("system", "name", "author", "variant", "slug", "description", "palette", "extra", "palette slot", "comment")
    fun withScalar(position: String, value: String): String = when (position) {
        "system" -> sample.replaceFirst("\"base16\"", value)
        "name" -> named(value)
        "author" -> sample.replaceFirst("\"Author\"", value)
        "palette" -> sample.replaceFirst("palette:", "palette: $value")
        "palette slot" -> paletteEntry(value)
        "comment" -> "$sample# $value\n"
        else -> "$position: $value\n$sample"
    }
    positions.forEach { position ->
        listOf("plain" to false, "quoted" to true).forEach { (form, quoted) ->
            listOf("canonical" to "", "trailing underscore" to "_").forEach { (label, suffix) ->
                test("conversion is total for a near-cap $form $position with $label text") {
                    fun document(value: String) = withScalar(position, if (quoted) "\"$value\"" else value)
                    val room = byteCap - document(suffix).encodeToByteArray().size
                    val value = "a-".repeat((room - 1) / 2) + "a" + suffix
                    val text = document(value)
                    (text.encodeToByteArray().size in byteCap - 1..byteCap) shouldBe true
                    ThemeMode.entries.forEach { mode ->
                        val result = shouldNotThrowAny { TintedSchemes.convert(text, mode) }
                        when (result) {
                            is TintedConversion.Definition -> result.css shouldNotContain value
                            is TintedConversion.Rejected -> result.failure.kind.name.startsWith("SCHEME_") shouldBe true
                        }
                    }
                }
            }
        }
    }
    val reportedSlug = "a-".repeat(10000) + "a"
    accepts("a long hyphen-separated slug", "slug: \"$reportedSlug\"\n$sample")
    rejects("a long slug ending in an underscore", "slug: \"${reportedSlug}_\"\n$sample", TintedRule.SLUG_FORM, 1)

    accepts("a complete scheme", sample)
    accepts("CRLF endings", sample.replace("\n", "\r\n"))
    accepts("mixed LF and CRLF endings", sample.replaceFirst("\n", "\r\n"))
    accepts("no final line ending", sample.removeSuffix("\n"))

    val forbidden = (0..8) + listOf(11, 12) + (14..31) + (127..132) + (134..159) + listOf(65534, 65535)
    forbidden.forEach { code ->
        rejects("control U+${code.toString(16)} in a comment", "# a${code.toChar()}b\n$sample", TintedRule.CONTROL_CHARACTER, 1)
        rejects("control U+${code.toString(16)} in a quoted value", named("\"a${code.toChar()}b\""), TintedRule.CONTROL_CHARACTER, 2)
    }
    listOf(133, 8232, 8233).forEach { code ->
        rejects("line separator U+${code.toString(16)} in a comment", "# a${code.toChar()}b\n$sample", TintedRule.YAML_LINE_BREAK, 1)
        rejects("line separator U+${code.toString(16)} in a value", named("\"a${code.toChar()}b\""), TintedRule.YAML_LINE_BREAK, 2)
    }
    listOf("high" to "\uD800", "low" to "\uDC00").forEach { (label, value) ->
        rejects("an unpaired $label surrogate", named("\"a${value}b\""), TintedRule.CONTROL_CHARACTER, 2)
    }
    listOf(
        "ASCII bounds" to " ~",
        "non-ASCII bounds" to "\u00A0\uD7FF\uE000\uFFFD",
        "astral bounds" to "\uD800\uDC00\uDBFF\uDFFF",
        "an embedded BOM" to "a\uFEFFb",
        "a quoted TAB" to "a\tb",
    ).forEach { (label, value) -> accepts(label, named("\"$value\"")) }
    accepts("BOM and non-breaking space inside a plain value", named("a\uFEFF\u00A0b"))

    rejects("a lone carriage return", "# first\n# a\rb\n$sample", TintedRule.CARRIAGE_RETURN, 2)
    rejects("a carriage return at EOF", "$sample\r", TintedRule.CARRIAGE_RETURN, 21)
    rejects("a carriage return inside a quoted value", named("\"a\rb\""), TintedRule.CARRIAGE_RETURN, 2)
    rejects("a CRLF inside a quoted value", named("\"a\r\nb\""), TintedRule.VALUE_FORM, 2)
    rejects("a later control before an earlier key defect", "? invalid\n# a\u0000b", TintedRule.CONTROL_CHARACTER, 2)
    rejects("a later line separator before an earlier value defect", "name: 'bad'\n# a\u0085b", TintedRule.YAML_LINE_BREAK, 2)

    listOf("LF" to "\n", "CRLF" to "\r\n").forEach { (label, eol) ->
        listOf(false, true).forEach { finalEol ->
            val suffix = if (finalEol) eol else ""
            rejects(
                "a value on line three with $label and final EOL $finalEol",
                "name: \"Paper\"$eol# comment${eol}extra: 'bad'$suffix",
                TintedRule.VALUE_FORM,
                3,
            )
            rejects(
                "a control on line three with $label and final EOL $finalEol",
                "# first$eol# second$eol# \u0080$suffix",
                TintedRule.CONTROL_CHARACTER,
                3,
            )
        }
    }

    accepts("a document start on the first line", "---\n$sample")
    listOf(
        "a document end" to "...",
        "a first-line marker comment" to "--- # c",
        "a marker suffix" to "---x",
        "a document end suffix" to "...x",
    ).forEach { (label, text) -> rejects(label, "$text\n$sample", TintedRule.DOCUMENT_MARKER, 1) }
    rejects("a later document start", "# comment\n---\n$sample", TintedRule.DOCUMENT_MARKER, 2)
    rejects("a second document", "---\n$sample---", TintedRule.DOCUMENT_MARKER, 22)

    accepts("column-zero comments and empty or SP-only lines", "# c\n\n   \n$sample")
    accepts("comments and blank lines inside a palette", sample.replace("palette:\n", "palette:\n# c\n  # c\tmore\n\n   \n"))
    listOf("\tname: x", "\t", " \t", " \t# c").forEachIndexed { index, text ->
        rejects("TAB indentation shape $index", "$text\n$sample", TintedRule.TAB_INDENT, 1)
    }

    listOf(
        "a quoted key" to "\"name\": x",
        "a single-quoted key" to "'name': x",
        "SP before the colon" to "name : x",
        "TAB before the colon" to "name\t: x",
        "a TAB immediately after a top-level colon" to "name:\tx",
        "a TAB immediately after the palette colon" to "palette:\t# c",
        "no SP after the colon" to "name:\"x\"",
        "a sequence" to "- x",
        "an explicit key" to "? x",
        "a YAML directive" to "%YAML 1.2",
        "a root scalar" to "scalar",
        "a digit-first key" to "1name: x",
        "a hyphen-first key" to "-name: x",
        "an underscore-first key" to "_name: x",
        "a non-ASCII key" to "nämé: x",
        "a key with punctuation" to "na.me: x",
        "a BOM before a key" to "\uFEFFname: x",
        "a BOM before a comment" to "\uFEFF# c",
        "two leading BOMs" to "\uFEFF\uFEFFname: x",
        "a 1025-character key" to "${"a".repeat(1025)}: x",
    ).forEach { (label, text) -> rejects(label, "$text\n$sample", TintedRule.KEY_FORM, 1) }
    accepts("a 1024-character key", "${"a".repeat(1024)}: x\n$sample")
    accepts("case-distinct top-level keys", "Name: \"Other\"\n$sample")
    accepts("ASCII key separators", "Extra_2-key: x\n$sample")
    listOf("null", "true", "false", "yes", "no", "on", "off", "y", "n", "inf", "nan", "infinity").forEach { word ->
        listOf(word, word.uppercase()).forEach { spelling ->
            rejects("reserved key $spelling", "$spelling: x\n$sample", TintedRule.KEY_FORM, 1)
            rejects("reserved plain value $spelling", named(spelling), TintedRule.VALUE_FORM, 2)
        }
    }
    rejects("a repeated known key before its malformed value", "name: x\nname: 'bad'\n$sample", TintedRule.DUPLICATE_KEY, 2)
    rejects("a repeated unknown key", "extra: x\nextra: y\n$sample", TintedRule.DUPLICATE_KEY, 2)
    rejects("a repeated palette header", "${sample}palette: {}", TintedRule.DUPLICATE_KEY, 21)

    listOf(
        "single quotes" to "'Paper'",
        "an escape" to "\"a\\nb\"",
        "an unterminated quote" to "\"Paper",
        "text after a quote" to "\"Paper\"x",
        "an adjacent quoted comment" to "\"Paper\"# c",
        "a flow sequence" to "[a, b]",
        "a flow mapping" to "{a: b}",
        "an anchor" to "&a Paper",
        "an alias" to "*a",
        "a tag" to "!!str Paper",
        "a folded block" to ">\n  Paper",
        "a literal strip indicator" to "|-\n  Paper",
        "a literal keep indicator" to "|+\n  Paper",
        "a literal trailing comment" to "| # c\n  Paper",
        "a literal trailing SP" to "| \n  Paper",
        "an internal hash in PLAIN" to "Pa#per",
        "an internal TAB in PLAIN" to "Pa\tper",
        "a colon followed by SP in PLAIN" to "Paper: theme",
        "a terminal colon in PLAIN" to "Paper :  ",
        "a non-ASCII initial PLAIN letter" to "éclair",
        "a numeric PLAIN value" to "123",
        "a non-breaking-space before a comment" to "\"Paper\"\u00A0# c",
    ).forEach { (label, value) -> rejects(label, named(value), TintedRule.VALUE_FORM, 2) }
    listOf("", " ", "#x", "\t", "\t# c").forEachIndexed { index, value ->
        rejects("an empty top-level value shape $index", named(value), TintedRule.EMPTY_VALUE, 2)
    }
    rejects(
        "a top-level colon followed immediately by EOL",
        sample.replaceFirst("name: \"Paper\"", "name:"),
        TintedRule.EMPTY_VALUE,
        2,
    )
    listOf("system", "variant", "slug").forEach { key ->
        rejects("a literal block for $key", "$key: |\n  value\n$sample", TintedRule.VALUE_FORM, 1)
    }
    listOf(
        "a quoted SP trailer" to "\"Paper\"  ",
        "a quoted TAB trailer" to "\"Paper\"\t",
        "a quoted comment" to "\"Paper\" \t# c",
        "an empty quoted value" to "\"\"",
        "a PLAIN value" to "Paper",
        "a PLAIN comment" to "Paper # c",
        "a PLAIN TAB trailer" to "Paper\t",
        "a PLAIN SP TAB trailer" to "Paper \t",
        "a PLAIN TAB comment trailer" to "Paper \t# c",
        "an embedded PLAIN quote" to "Pa\"per",
        "a PLAIN colon without following SP" to "a :b",
    ).forEach { (label, value) -> accepts(label, named(value)) }
    accepts("a trimmed system TAB trailer", sample.replaceFirst("system: \"base16\"", "system: base16\t"))
    accepts("a trimmed system SP TAB trailer", sample.replaceFirst("system: \"base16\"", "system: base16 \t"))
    accepts("multiple SP after a top-level colon", named("   \"Paper\""))

    listOf("", " ", " # c", " \t# c", " \t").forEachIndexed { index, suffix ->
        accepts("palette header trailer shape $index", sample.replaceFirst("palette:", "palette:$suffix"))
    }
    rejects(
        "a palette flow mapping",
        sample.replaceFirst("palette:", "palette: {}"),
        TintedRule.PALETTE_VALUE_FORM,
        4,
        TintedFailureKind.SCHEME_PALETTE,
    )
    listOf(
        "empty" to "",
        "comment" to "#abcdef",
        "plain" to "abcdef",
        "single-quoted" to "'#abcdef'",
        "anchor" to "&a \"#abcdef\"",
        "alias" to "*a",
        "unterminated" to "\"#abcdef",
        "adjacent-comment" to "\"#abcdef\"# c",
        "trailing-text" to "\"#abcdef\"x",
        "escaped" to "\"#abc\\ndef\"",
    ).forEach { (label, value) ->
        rejects("a $label palette value", paletteEntry(value), TintedRule.PALETTE_VALUE_FORM, 5, TintedFailureKind.SCHEME_PALETTE)
    }
    rejects(
        "a palette entry colon followed immediately by EOL",
        sample.replaceFirst("  base00: \"#abcdef\"", "  base00:"),
        TintedRule.PALETTE_VALUE_FORM,
        5,
        TintedFailureKind.SCHEME_PALETTE,
    )
    listOf(
        "four SP" to "    base00: \"#abcdef\"",
        "one SP" to " base00: \"#abcdef\"",
        "TAB after the colon" to "  base00:\t\"#abcdef\"",
        "SP before the colon" to "  base00 : \"#abcdef\"",
        "a key punctuation" to "  base.00: \"#abcdef\"",
        "no SP after the colon" to "  base00:\"#abcdef\"",
    ).forEach { (label, entry) ->
        rejects("$label in a palette prefix", sample.replaceFirst("  base00: \"#abcdef\"", entry), TintedRule.INDENTATION, 5)
    }
    accepts("several SP after a palette entry colon", paletteEntry("   \"#abcdef\""))
    accepts("a palette value trailer", paletteEntry("\"#abcdef\" \t# c"))
    rejects(
        "a repeated palette key before its malformed value",
        sample.replaceFirst("  base01: \"#abcdef\"", "  base00: 'bad'"),
        TintedRule.DUPLICATE_KEY,
        6,
    )

    listOf(
        "a literal description" to "description: |\n  one\n  two\n",
        "a literal unknown key" to "extra: |\n one\n  two\n",
        "an indented hash as literal content" to "description: |\n  # content\n  two\n",
        "TAB inside literal content" to "description: |\n  \tcontent\n  two\n",
        "a less-indented trailing comment" to "description: |\n    one\n  # c\n",
    ).forEach { (label, prefix) -> accepts(label, prefix + sample) }
    accepts("literal content ending at EOF", sample + "description: |\n  content")
    listOf(
        "no next line" to "description: |",
        "a final line ending" to "description: |\n",
        "an immediate empty line" to "description: |\n\n",
        "an immediate SP-only line" to "description: |\n  \n",
        "an immediate TAB-only line" to "description: |\n \t\n",
        "an unindented next line" to "description: |\ncontent\n",
        "a column-zero comment" to "description: |\n# c\n",
    ).forEach { (label, text) -> rejects("a literal block with $label", text, TintedRule.VALUE_FORM, 1) }
    rejects("literal content after a blank line", "description: |\n  one\n\n  two\n$sample", TintedRule.INDENTATION, 4)
    rejects("literal content after a less-indented comment", "description: |\n    one\n  # c\n    two\n$sample", TintedRule.INDENTATION, 4)
    rejects("less-indented literal text", "description: |\n    one\n  two\n$sample", TintedRule.INDENTATION, 3)
    rejects("an unrelated indented line", "  stray\n$sample", TintedRule.INDENTATION, 1)
    rejects("an indented PLAIN continuation", named("Paper\n  continued"), TintedRule.INDENTATION, 3)
    rejects("entries under an empty unknown key", "extra:\n  nested: x\n$sample", TintedRule.EMPTY_VALUE, 1)

    val base24 = sample.replaceFirst("system: \"base16\"", "system: \"base24\"") +
        ('0'..'7').joinToString("") { slot -> "  base1$slot: \"#abcdef\"\n" }

    val payload = "PBHOSTILE </style> url(javascript: */ @import"
    fun twin(metadata: String) = buildString {
        append("name: \"$metadata\"\nsystem: \"base16\"\nauthor: \"$metadata\"\n")
        append("description: \"$metadata\"\nextra: \"$metadata\"\n# $metadata\npalette:\n")
        append(sample.substringAfter("palette:\n"))
    }
    listOf(
        ThemeMode.LIGHT to ":where(:root:not([data-theme=\"dark\"]))",
        ThemeMode.DARK to ":where(:root[data-theme=\"dark\"])",
    ).forEach { (mode, selector) ->
        listOf("Base16" to sample, "Base24" to base24).forEach { (system, text) ->
            test("renders $system in the selected $mode scope without a declared variant") {
                stylesheet(text, mode) shouldMatch Regex(
                    "\\A${Regex.escape(selector)} \\{\\n(?:  --pb-theme-[a-z-]+: #[0-9a-f]{6};\\n){18}\\}\\n\\z",
                )
            }
        }
        test("hostile and benign metadata render identical bytes in $mode") {
            stylesheet(twin(payload), mode).toByteArray(Charsets.UTF_8).toList() shouldBe
                stylesheet(twin("Paper"), mode).toByteArray(Charsets.UTF_8).toList()
        }
        test("hostile metadata is absent from the rendered $mode stylesheet") {
            val css = stylesheet(twin(payload), mode)
            assertSoftly {
                listOf("PBHOSTILE", "</style>", "url(", "*/", "@import").forEach { marker ->
                    withClue(marker) { css shouldNotContain marker }
                }
            }
        }
    }

    fun withoutKey(key: String) = sample.lineSequence()
        .filterNot { it.startsWith("$key:") || (key == "palette" && it.startsWith("  ")) }.joinToString("\n")

    listOf(
        Triple("system", TintedRule.SYSTEM_MISSING, TintedFailureKind.SCHEME_SYSTEM),
        Triple("name", TintedRule.REQUIRED_KEY_MISSING, TintedFailureKind.SCHEME_SYNTAX),
        Triple("author", TintedRule.REQUIRED_KEY_MISSING, TintedFailureKind.SCHEME_SYNTAX),
        Triple("palette", TintedRule.PALETTE_MISSING, TintedFailureKind.SCHEME_PALETTE),
    ).forEach { (key, rule, kind) -> rejects("a missing $key", withoutKey(key), rule, null, kind) }
    rejects("an entirely absent document", "", TintedRule.SYSTEM_MISSING, null, TintedFailureKind.SCHEME_SYSTEM)
    rejects(
        "a missing palette before an unsupported system",
        withoutKey("palette").replaceFirst("base16", "other"),
        TintedRule.PALETTE_MISSING,
        null,
        TintedFailureKind.SCHEME_PALETTE,
    )
    rejects(
        "a missing author before an unsupported variant",
        "variant: Other\n${withoutKey("author")}",
        TintedRule.REQUIRED_KEY_MISSING,
        null,
    )

    listOf("base16" to sample, "base24" to base24).forEach { (system, text) ->
        accepts("the $system system as PLAIN", text.replaceFirst("system: \"$system\"", "system: $system"))
        accepts("the $system system as DQ", text)
    }
    listOf("Base16", "BASE16", "tinted8", "other").forEach { token ->
        listOf("PLAIN" to token, "DQ" to "\"$token\"").forEach { (form, value) ->
            rejects(
                "unsupported system $token as $form",
                sample.replaceFirst("system: \"base16\"", "system: $value"),
                TintedRule.SYSTEM_UNSUPPORTED,
                1,
                TintedFailureKind.SCHEME_SYSTEM,
            )
        }
    }
    listOf("", " base16", "base16 ", "Error: Unable to create file").forEachIndexed { index, token ->
        rejects(
            "unsupported quoted system content $index",
            sample.replaceFirst("system: \"base16\"", "system: \"$token\""),
            TintedRule.SYSTEM_UNSUPPORTED,
            1,
            TintedFailureKind.SCHEME_SYSTEM,
        )
    }
    rejects(
        "a non-breaking space retained in a PLAIN system",
        sample.replaceFirst("system: \"base16\"", "system: base16\u00A0"),
        TintedRule.SYSTEM_UNSUPPORTED,
        1,
        TintedFailureKind.SCHEME_SYSTEM,
    )

    listOf("Dark", "LIGHT", "other").forEach { token ->
        listOf("PLAIN" to token, "DQ" to "\"$token\"").forEach { (form, value) ->
            rejects(
                "unsupported variant $token as $form",
                "variant: $value\n$sample",
                TintedRule.VARIANT_UNSUPPORTED,
                1,
                TintedFailureKind.SCHEME_VARIANT,
            )
        }
    }
    listOf("", " dark", "dark ", "Error: Unable to create file").forEachIndexed { index, token ->
        rejects(
            "unsupported quoted variant content $index",
            "variant: \"$token\"\n$sample",
            TintedRule.VARIANT_UNSUPPORTED,
            1,
            TintedFailureKind.SCHEME_VARIANT,
        )
    }
    rejects(
        "a BOM retained in a PLAIN variant",
        "variant: dark\uFEFF\n$sample",
        TintedRule.VARIANT_UNSUPPORTED,
        1,
        TintedFailureKind.SCHEME_VARIANT,
    )
    listOf("paper", "my-theme-2", "123", "0-a").forEach { slug ->
        accepts("a canonical quoted slug $slug", "slug: \"$slug\"\n$sample")
    }
    listOf("paper", "my-theme-2").forEach { slug -> accepts("a canonical PLAIN slug $slug", "slug: $slug\n$sample") }
    listOf("a/b", "", "My_Theme", "UPPER", "a b", "a.b", "..", "éclair", "-a", "a-", "a--b").forEachIndexed { index, slug ->
        rejects("a malformed quoted slug $index", "slug: \"$slug\"\n$sample", TintedRule.SLUG_FORM, 1)
    }
    listOf("a/b", "My_Theme", "a b", "a.b", "a--b").forEachIndexed { index, slug ->
        rejects("a malformed PLAIN slug $index", "slug: $slug\n$sample", TintedRule.SLUG_FORM, 1)
    }
    rejects(
        "known system validation before variant and slug in file order",
        "slug: \"a/b\"\nvariant: Other\n${sample.replaceFirst("base16", "other")}",
        TintedRule.SYSTEM_UNSUPPORTED,
        3,
        TintedFailureKind.SCHEME_SYSTEM,
    )
    rejects(
        "known variant validation before an earlier slug",
        "slug: \"a/b\"\nvariant: Other\n$sample",
        TintedRule.VARIANT_UNSUPPORTED,
        2,
        TintedFailureKind.SCHEME_VARIANT,
    )
    rejects("slug validation before palette colors", "slug: \"a/b\"\n${paletteEntry("\"badhex\"")}", TintedRule.SLUG_FORM, 1)

    val extraBeforeMissing = sample.replaceFirst("  base00: \"#abcdef\"", "  name: \"#abcdef\"")
    rejects(
        "a palette key named like a top-level key before its missing slot",
        extraBeforeMissing,
        TintedRule.SLOT_UNEXPECTED,
        5,
        TintedFailureKind.SCHEME_PALETTE,
    )
    accepts("a top-level key named like a palette slot", "base00: spare\n$sample")
    rejects(
        "the first extra palette key in file order",
        sample.replaceFirst("  base00: \"#abcdef\"", "  zebra: \"#abcdef\"\n  alpha: \"#abcdef\""),
        TintedRule.SLOT_UNEXPECTED,
        5,
        TintedFailureKind.SCHEME_PALETTE,
    )
    rejects(
        "lowercase slot letters as unexpected keys",
        sample.replaceFirst("  base0A:", "  base0a:"),
        TintedRule.SLOT_UNEXPECTED,
        15,
        TintedFailureKind.SCHEME_PALETTE,
    )
    rejects(
        "uppercase slot prefixes as unexpected keys",
        sample.replaceFirst("  base00:", "  BASE00:"),
        TintedRule.SLOT_UNEXPECTED,
        5,
        TintedFailureKind.SCHEME_PALETTE,
    )
    rejects(
        "an extended slot in a Base16 palette",
        sample + "  base12: \"#abcdef\"\n",
        TintedRule.SLOT_UNEXPECTED,
        21,
        TintedFailureKind.SCHEME_PALETTE,
    )
    listOf(
        "the first Base16 slot" to sample.replaceFirst("  base00: \"#abcdef\"\n", ""),
        "the last Base16 slot" to sample.replaceFirst("  base0F: \"#abcdef\"\n", ""),
        "every Base16 slot" to sample.substringBefore("  base00:"),
        "the last Base24 slot" to base24.replaceFirst("  base17: \"#abcdef\"\n", ""),
        "a Base24 slot in the basic set" to base24.replaceFirst("  base0A: \"#abcdef\"\n", ""),
        "the extended set in a Base24 palette" to sample.replaceFirst("base16", "base24"),
    ).forEach { (label, text) ->
        rejects("a missing $label", text, TintedRule.SLOT_MISSING, 4, TintedFailureKind.SCHEME_PALETTE)
    }
    rejects(
        "extra slot validation before an earlier invalid color",
        paletteEntry("\"badhex\"") + "  extra: \"#abcdef\"\n",
        TintedRule.SLOT_UNEXPECTED,
        21,
        TintedFailureKind.SCHEME_PALETTE,
    )
    rejects(
        "missing slot validation before an earlier invalid color",
        paletteEntry("\"badhex\"").replaceFirst("  base0F: \"#abcdef\"\n", ""),
        TintedRule.SLOT_MISSING,
        4,
        TintedFailureKind.SCHEME_PALETTE,
    )

    listOf("abcdef", "ABCDEF", "123456", "abc", "#abc", "#A0b").forEachIndexed { index, hex ->
        rejects(
            "noncanonical hex spelling $index",
            paletteEntry("\"$hex\""),
            TintedRule.HEX_SPELLING,
            5,
            TintedFailureKind.SCHEME_PALETTE,
        )
    }
    listOf(
        "eight digits" to "#12345678",
        "double hash" to "##abcdef",
        "non-ASCII lookalike" to "#abｃdef",
        "empty" to "",
        "hash only" to "#",
        "one digit" to "#1",
        "two digits" to "#12",
        "four digits" to "#1234",
        "five digits" to "#12345",
        "seven digits" to "#1234567",
        "four bare digits" to "1234",
        "five bare digits" to "12345",
        "seven bare digits" to "1234567",
        "leading padding" to " #abcdef",
        "trailing padding" to "#abcdef ",
        "space after hash" to "# abcdef",
        "0x prefix" to "0xabcdef",
        "nonhex characters" to "#abcdeg",
        "internal TAB" to "#ab\tcde",
    ).forEach { (label, hex) ->
        rejects("invalid hex with $label", paletteEntry("\"$hex\""), TintedRule.HEX_INVALID, 5, TintedFailureKind.SCHEME_PALETTE)
    }
    test("accepts mixed-case six-digit slot hex") {
        assertSoftly {
            ThemeMode.entries.forEach { mode ->
                withClue(mode) {
                    val css = stylesheet(paletteEntry("\"#e0f0eF\""), mode)
                    css shouldContain "  --pb-theme-background: #e0f0ef;\n"
                    css shouldContain "  --pb-theme-primary-foreground: #e0f0ef;\n"
                    css shouldNotContain "#e0f0eF"
                }
            }
        }
    }
    test("renders uppercase Base24 source hex as exact lowercase values") {
        assertSoftly {
            ThemeMode.entries.forEach { mode ->
                withClue(mode) {
                    val css = stylesheet(base24.replace("#abcdef", "#ABCDEF"), mode)
                    css shouldContain "  --pb-theme-background: #abcdef;\n"
                    css shouldContain "  --pb-theme-ring: #abcdef;\n"
                    css shouldNotContain "#ABCDEF"
                }
            }
        }
    }
    rejects(
        "invalid hex at its own later entry",
        sample.replaceFirst("  base0F: \"#abcdef\"", "  base0F: \"#abcdeg\""),
        TintedRule.HEX_INVALID,
        20,
        TintedFailureKind.SCHEME_PALETTE,
    )

    listOf("light" to ThemeMode.LIGHT, "dark" to ThemeMode.DARK).forEach { (variant, mode) ->
        val other = ThemeMode.entries.single { it != mode }
        listOf("PLAIN" to variant, "DQ" to "\"$variant\"").forEach { (form, value) ->
            val text = "variant: $value\n$sample"
            accepts("matching $variant variant as $form", text, listOf(mode))
            rejects(
                "a $variant variant selected as $other in $form",
                text,
                TintedRule.VARIANT_MISMATCH,
                1,
                TintedFailureKind.VARIANT_MISMATCH,
                listOf(other),
            )
        }
    }
    accepts("no declared variant in either mode", sample)
    accepts("no declared variant in a Base24 scheme", base24)
    accepts(
        "PLAIN values in a yml-shaped scheme",
        sample.replaceFirst("system: \"base16\"", "system: base16").replaceFirst("name: \"Paper\"", "name: Paper"),
    )
    rejects("a character defect before mode comparison", "variant: light\n$sample# \u0000", TintedRule.CONTROL_CHARACTER, 22)
    rejects("a line defect before mode comparison", "variant: light\n? invalid\n$sample", TintedRule.KEY_FORM, 2)
    rejects(
        "a missing required field before mode comparison",
        "variant: light\n${withoutKey("author")}",
        TintedRule.REQUIRED_KEY_MISSING,
        null,
    )
    rejects(
        "an unsupported system before mode comparison",
        "variant: light\n${sample.replaceFirst("base16", "other")}",
        TintedRule.SYSTEM_UNSUPPORTED,
        2,
        TintedFailureKind.SCHEME_SYSTEM,
    )
    rejects(
        "an extra palette key before mode comparison",
        "variant: light\n$extraBeforeMissing",
        TintedRule.SLOT_UNEXPECTED,
        6,
        TintedFailureKind.SCHEME_PALETTE,
    )
    rejects(
        "a missing slot before mode comparison",
        "variant: light\n${sample.replaceFirst("  base0F: \"#abcdef\"\n", "")}",
        TintedRule.SLOT_MISSING,
        5,
        TintedFailureKind.SCHEME_PALETTE,
    )
    rejects(
        "a noncanonical hex spelling before mode comparison",
        "variant: light\n${paletteEntry("\"abc\"")}",
        TintedRule.HEX_SPELLING,
        6,
        TintedFailureKind.SCHEME_PALETTE,
    )
    rejects(
        "an invalid hex before mode comparison",
        "variant: light\n${paletteEntry("\"badhex\"")}",
        TintedRule.HEX_INVALID,
        6,
        TintedFailureKind.SCHEME_PALETTE,
    )

    val cases = requireNotNull(TintedSchemeTest::class.java.getResourceAsStream("/tinted/scheme-oracle-cases.json"))
        .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject.getValue("hand").jsonArray }
    val crlf = cases.single { it.jsonObject.getValue("id").jsonPrimitive.content == "crlf" }.jsonObject
        .getValue("text").jsonPrimitive.content
    accepts("the measured CRLF hand text without a variant", crlf.replace("variant: \"dark\"\r\n", ""))
})
