package com.plainbase.frameworks.markdown

import com.plainbase.domain.page.Frontmatter
import com.plainbase.domain.page.FrontmatterBlock
import com.plainbase.domain.page.FrontmatterParser
import com.plainbase.domain.page.FrontmatterValue
import com.vladsch.flexmark.ext.yaml.front.matter.AbstractYamlFrontMatterVisitor
import com.vladsch.flexmark.ext.yaml.front.matter.YamlFrontMatterExtension
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Frontmatter VALUE extraction over the documented §C2 subset, fed ONLY the block region the
 * authoritative [FrontmatterBlock] detector identified (M2 bridging) — never the raw file head.
 *
 * The flexmark yaml-front-matter extension's [AbstractYamlFrontMatterVisitor] does the parse; this
 * reader maps its `Map<String, List<String>>` into the domain [Frontmatter] subset:
 *  - a multi-entry list (a block `- item` list) → [FrontmatterValue.StringList];
 *  - a single entry that is a flow list `[a, b]` → [FrontmatterValue.StringList] (the extension hands
 *    flow lists back as one raw `[a, b]` string, so the flow syntax is split here — quote-aware, so a
 *    quoted comma like `["a, b", c]` is not mis-split);
 *  - any other single entry → [FrontmatterValue.Scalar] with matching surrounding quotes stripped.
 *
 * **Subset enforcement with a per-page warning (§C2):** flexmark's metadata regex is more permissive
 * than our documented subset — it surfaces shallow-nested keys (≤3 leading spaces, e.g. `author:` then
 * `  name: Jane` yields BOTH `author` AND `name` as top-level), and other out-of-subset shapes leak in
 * as top-level scalars. This reader therefore scans the detector's inner block region itself to find
 * the *legitimate* top-level keys: a column-0 `key:` line whose value introduces no out-of-subset
 * construct. Anything the extension surfaces that is NOT a legitimate top-level key (an indented/nested
 * key, a key whose value uses a block-scalar `|`/`>`, an anchor/alias `&`/`*`, a tag `!`, or any other
 * shape outside the subset) is **dropped, with a per-page warning** — never silently surfaced. The
 * page still renders (§C2 / R10); only the offending value is omitted. `id` detection is the patcher's
 * line grammar, unaffected.
 *
 * The extension needs a real frontmatter document to parse, so the detector's inner region is
 * re-wrapped in `---`/`---` delimiters before parsing — the bridging guarantee holds because the
 * region itself came from our grammar, not flexmark's.
 */
class FrontmatterReader : FrontmatterParser {

    // The ONLY parser in the tree carrying the yaml-front-matter extension — and it only ever sees the
    // detector's block region, re-wrapped in delimiters, never the raw file head (M2).
    private val parser =
        Parser.builder(
            MutableDataSet().set(Parser.EXTENSIONS, listOf(YamlFrontMatterExtension.create())).toImmutable(),
        ).build()

    /** The [FrontmatterParser] port: detect (the single M2 grammar) then [read] — body untouched. */
    override fun parse(source: ByteArray): Frontmatter = read(source, FrontmatterBlock.detect(source))

    /** Extracts [Frontmatter] from the block [detection] found in [source], or [Frontmatter.EMPTY] when absent. */
    fun read(source: ByteArray, detection: FrontmatterBlock.Detection): Frontmatter {
        if (detection !is FrontmatterBlock.Detection.Present) return Frontmatter.EMPTY

        val inner = String(source, detection.innerStart, detection.innerEnd - detection.innerStart, Charsets.UTF_8)
        val document = parser.parse("---\n$inner---\n")
        val visitor = AbstractYamlFrontMatterVisitor().apply { visit(document) }

        // The keys the §C2 subset legitimately admits — derived from the block text, NOT from whatever
        // the more-permissive extension chose to surface. Anything outside this set is dropped + warned.
        val supported = supportedKeys(inner)
        val values = LinkedHashMap<String, FrontmatterValue>()
        for ((key, raw) in visitor.data) {
            if (key in supported) {
                values[key] = toValue(raw)
            } else {
                logger.warn { "frontmatter key '$key' is outside the supported subset (§C2) — dropped" }
            }
        }
        return Frontmatter(values)
    }

    /**
     * The set of column-0 keys in [inner] whose line stays inside the §C2 subset. A line counts when
     * it is an unindented `key:` (the colon followed by EOL or whitespace) AND its value introduces no
     * out-of-subset construct — a block-scalar indicator (`|`/`>`), an anchor/alias (`&`/`*`), or a tag
     * (`!`). Indented lines (nested-map members, block-list `- item` continuations) are never keys, so
     * a leaked nested scalar like `name` (under `author:`) is absent here and gets dropped + warned.
     */
    private fun supportedKeys(inner: String): Set<String> {
        val keys = LinkedHashSet<String>()
        for (line in inner.split("\n")) {
            if (line.isEmpty() || line.first() == ' ' || line.first() == '\t') continue // indented ⇒ not a top-level key
            val trimmedRight = line.trimEnd('\r')
            if (trimmedRight.isEmpty() || trimmedRight.first() == '#') continue // blank or comment
            val colon = trimmedRight.indexOf(':')
            if (colon <= 0) continue
            val afterColon = trimmedRight.substring(colon + 1)
            // The colon must terminate the key: EOL or whitespace right after (not `key:value`).
            if (afterColon.isNotEmpty() && afterColon.first() != ' ' && afterColon.first() != '\t') continue
            val value = afterColon.trim()
            if (value.isNotEmpty() && value.first() in OUT_OF_SUBSET_VALUE_INDICATORS) continue // block-scalar/anchor/alias/tag
            keys += trimmedRight.substring(0, colon)
        }
        return keys
    }

    /** Maps one key's raw extension entries to a subset [FrontmatterValue] (block list, flow list, or scalar). */
    private fun toValue(raw: List<String>): FrontmatterValue =
        when {
            raw.size > 1 -> FrontmatterValue.StringList(raw.map(::stripQuotes))
            raw.size == 1 -> flowList(raw.single()) ?: FrontmatterValue.Scalar(stripQuotes(raw.single()))
            else -> FrontmatterValue.Scalar("")
        }

    /** Parses a flow list `[a, b, c]` into a [FrontmatterValue.StringList], or null if [value] is not one. */
    private fun flowList(value: String): FrontmatterValue.StringList? {
        val trimmed = value.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return null
        val inner = trimmed.substring(1, trimmed.length - 1).trim()
        val items = if (inner.isEmpty()) emptyList() else splitTopLevel(inner).map { stripQuotes(it.trim()) }
        return FrontmatterValue.StringList(items)
    }

    /**
     * Splits a flow-list interior on commas that sit OUTSIDE quotes, so `"a, b", c` yields two items
     * (`"a, b"` and `c`), not three. Quote state tracks single- and double-quote runs; a comma inside
     * an open quote is data. If a quote is left unbalanced at end of input the split is abandoned and
     * the whole interior is returned as one item — the scalar fallback in [toValue] then keeps it intact
     * rather than emit a mangled list.
     */
    private fun splitTopLevel(inner: String): List<String> {
        val items = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (c in inner) {
            when {
                quote != null -> {
                    current.append(c)
                    if (c == quote) quote = null
                }
                c == '"' || c == '\'' -> {
                    quote = c
                    current.append(c)
                }
                c == ',' -> {
                    items += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
        }
        if (quote != null) return listOf(inner) // unbalanced quote ⇒ bail; treat the interior as one item
        items += current.toString()
        return items
    }

    /** Strips a single pair of matching surrounding quotes (`"…"` or `'…'`); leaves anything else verbatim. */
    private fun stripQuotes(value: String): String {
        if (value.length < 2) return value
        val first = value.first()
        return if ((first == '"' || first == '\'') && value.last() == first) value.substring(1, value.length - 1) else value
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /** Value-position YAML indicators that put a key outside the §C2 subset: block scalars, anchors/aliases, tags. */
        private val OUT_OF_SUBSET_VALUE_INDICATORS = setOf('|', '>', '&', '*', '!')
    }
}
