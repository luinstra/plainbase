package com.plainbase.domain.service

import com.plainbase.domain.page.FrontmatterBlock
import com.plainbase.domain.page.PageId
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * PB-PATCH-1 (§A3) — the surgical frontmatter patcher: the **only** code path that ever writes
 * frontmatter programmatically. It splices a single `id:` line into a byte sequence and guarantees a
 * pure single-point insertion (`output = input[0..k) + insertion + input[k..n)`) — every original
 * byte survives in order, with no re-encoding, EOL normalization, trailing-newline addition, quoting
 * change, BOM change, or YAML reserialization. Atomic write is the ContentStore's job, not this one's.
 *
 * **Asymmetric freeze (§A3, governing):** acceptances are permanently frozen — a byte sequence the
 * patcher accepts, and the insertion it produces, can never change. Refusals may later be relaxed to
 * acceptances by a documented revision, never the reverse. This is what makes the conservative
 * strictest-subset grammar free: `adopt --dry-run` measures refusal rates and a documented relaxation
 * can follow without breaking any frozen acceptance.
 *
 * **Evaluation order (§A3, frozen):** block detection → invalid_encoding → block_too_large → mapping
 * check → id-presence → insertion → post-patch re-extraction. The mapping/encoding checks run BEFORE
 * the id-presence check, so an `id:value`-only block is `Refused(not_a_mapping)`, not `AlreadyPresent`
 * (P2): `AlreadyPresent` would mark the page materialized while a frontmatter parser cannot read an id
 * from a plain-scalar document.
 *
 * Shares the single [FrontmatterBlock] grammar with the renderer (no second frontmatter detector).
 * Pure domain code: no framework type, no I/O.
 */
class FrontmatterPatcher(private val maxBlockBytes: Int = DEFAULT_MAX_BLOCK_BYTES) {

    /** PB-PATCH-1's frozen refusal classes (§A3 case table). */
    enum class RefusalReason {
        /** Case 8 — the block region is not valid strict UTF-8. */
        INVALID_ENCODING,

        /** Case 10 — the block exceeds the sanity bound (default 64 KiB). */
        BLOCK_TOO_LARGE,

        /** Case 9 — the block is not provably a top-level block mapping under the strictest subset. */
        NOT_A_MAPPING,

        /** Case 11 — the post-patch re-extraction invariant failed on the computed output (the backstop). */
        POST_CHECK_FAILED,
    }

    /** The outcome of [patch]: a single-point insertion, an idempotent no-op, or a safe refusal. */
    sealed interface PatchResult {

        /** The patched bytes — input with exactly one `id:` line inserted (cases 1–4). */
        data class Patched(val bytes: ByteArray) : PatchResult {
            override fun equals(other: Any?): Boolean = other is Patched && other.bytes.contentEquals(bytes)
            override fun hashCode(): Int = bytes.contentHashCode()
        }

        /** A column-0 `id:` key already exists; the bytes are input-identical (idempotent, case 5). */
        data object AlreadyPresent : PatchResult

        /** A safe refusal (§A3): the page keeps its `id_map` identity; [message] names the rule. */
        data class Refused(val reason: RefusalReason, val message: String) : PatchResult
    }

    /**
     * Patches [original] to carry [id] as its frontmatter `id`, per the §A3 case table and evaluation
     * order. Pure function: no I/O, [original] is never mutated.
     */
    fun patch(original: ByteArray, id: PageId): PatchResult {
        val detection = FrontmatterBlock.detect(original)
        return when (detection) {
            is FrontmatterBlock.Detection.Present -> patchPresent(original, detection, id)
            is FrontmatterBlock.Detection.Absent -> insert(original, detection.bomLength, newBlock(original, id), id)
        }
    }

    /** Cases 1/4/5/8/9/10 — a block exists; run the frozen checks in order, then insert as the first line. */
    private fun patchPresent(original: ByteArray, block: FrontmatterBlock.Detection.Present, id: PageId): PatchResult {
        val inner = original.copyOfRange(block.innerStart, block.innerEnd)

        // Case 8 — invalid_encoding: the block region must decode as strict UTF-8. Per §A3's frozen
        // order, the encoding check precedes the size bound (a block that is both oversized and
        // malformed reports invalid_encoding); `inner` is already materialized, so size-first saves nothing.
        val innerText = strictUtf8(inner)
            ?: return PatchResult.Refused(RefusalReason.INVALID_ENCODING, "frontmatter block is not valid UTF-8")

        // Case 10 — block_too_large (sanity bound).
        if (inner.size > maxBlockBytes) {
            return PatchResult.Refused(RefusalReason.BLOCK_TOO_LARGE, "frontmatter block exceeds the $maxBlockBytes-byte sanity bound")
        }

        // Case 9 — strictest-subset mapping check (every non-blank, non-comment line is an unquoted simple key).
        if (!isBlockMapping(innerText)) return PatchResult.Refused(RefusalReason.NOT_A_MAPPING, NOT_A_MAPPING_MESSAGE)

        // Case 5 — id-presence (evaluated only after the checks above): a column-0 `id` key ⇒ no-op.
        if (hasIdKey(innerText)) return PatchResult.AlreadyPresent

        // Case 1/4 — insert as the first line inside the block, copying the opener line's terminator.
        val eol = openerTerminator(original, block)
        return insert(original, block.innerStart, idLine(id, eol), id)
    }

    /**
     * Computes the single-point insertion at [offset], runs the case-11 post-extraction invariant on
     * the result, and returns [PatchResult.Patched] only if the backstop holds. The insertion offset
     * is the one `k` from the byte-fidelity guarantee: cases 1/4 insert at the block's inner start,
     * cases 2/3 at the post-BOM offset.
     */
    private fun insert(original: ByteArray, offset: Int, insertion: ByteArray, id: PageId): PatchResult {
        val output = original.copyOfRange(0, offset) + insertion + original.copyOfRange(offset, original.size)
        return if (postCheckHolds(original, output, id)) {
            PatchResult.Patched(output)
        } else {
            PatchResult.Refused(RefusalReason.POST_CHECK_FAILED, "post-patch re-extraction invariant failed; nothing written")
        }
    }

    /**
     * Reads the raw value of the column-0 `id` key in [original]'s frontmatter block, or null when
     * there is no block, the block is not strict UTF-8, or no such key exists. This is the patcher's
     * own case-5 line grammar — the single id-detection grammar in the tree — exposed for adoption
     * (chunk 4b), which feeds the value through the §A4 shape gate itself (4a `resolve`).
     *
     * The colon must terminate like a mapping colon: an `id:value` line (no space after the colon)
     * is a plain scalar a YAML parser cannot read an id from, so it is never honored as identity —
     * the same reasoning that makes the P2 evaluation order refuse it rather than report
     * [PatchResult.AlreadyPresent].
     */
    fun readIdValue(original: ByteArray): String? {
        val detection = FrontmatterBlock.detect(original) as? FrontmatterBlock.Detection.Present ?: return null
        val innerText = strictUtf8(original.copyOfRange(detection.innerStart, detection.innerEnd)) ?: return null
        return innerText.lineSequence()
            .firstOrNull { ID_VALUE_LINE.matches(it.trimEnd('\r')) }
            ?.substringAfter(':')
            ?.trim()
    }

    /**
     * Case 11 (§A3) — re-run the shared detector + id extraction over the OUTPUT and require: (1) a
     * valid block is still found; (2) the extracted id equals the inserted UUID text exactly; (3) the
     * body after the block is byte-identical to the input body. The backstop, not the fix.
     *
     * `internal` so the case-11 test can feed a deliberately corrupt output (the "injected faulty
     * insertion" the spec mandates): a correct patcher never trips this organically, so the only
     * honest way to exercise the refusal is to hand it a damaged output directly.
     */
    internal fun postCheckHolds(input: ByteArray, output: ByteArray, id: PageId): Boolean {
        val detection = FrontmatterBlock.detect(output) as? FrontmatterBlock.Detection.Present ?: return false
        val innerText = strictUtf8(output.copyOfRange(detection.innerStart, detection.innerEnd)) ?: return false
        if (extractId(innerText) != id.value) return false

        // The body after the output block must equal the input body (case 1: input bytes after the
        // input block; cases 2–4: input bytes after the BOM, if any).
        val outputBody = output.copyOfRange(detection.bodyStart, output.size)
        val inputBody = input.copyOfRange(inputBodyStart(input), input.size)
        return outputBody.contentEquals(inputBody)
    }

    /** The input offset the output body must match: the input block's bodyStart, or the post-BOM offset. */
    private fun inputBodyStart(input: ByteArray): Int =
        when (val d = FrontmatterBlock.detect(input)) {
            is FrontmatterBlock.Detection.Present -> d.bodyStart
            is FrontmatterBlock.Detection.Absent -> d.bomLength
        }

    /**
     * Case-9 strictest-subset mapping check (§A3): the block is a top-level block mapping iff its
     * first content line is a column-0 key, EVERY column-0 content line is an unquoted-simple-key
     * line ([isSimpleKeyLine]), and every space-indented continuation run is provably the nested
     * block of the key line directly above it ([continuationRunAllowed]). Blank lines and full-line
     * `#` comments (at any indentation — YAML allows both anywhere) never gate; a genuinely
     * content-free block (case 4) accepts vacuously: the `id:` line becomes the first.
     *
     * **Blank is ASCII-blank** — empty or all `' '`/`'\t'`, nothing wider. A Unicode-whitespace-only
     * line (U+2000, U+00A0, U+3000, …) is CONTENT a real parser chokes on, so it must survive the
     * filter and refuse below; the Unicode-aware `isNotBlank()` once dropped it here (review-found).
     *
     * **One line model** — this grammar breaks lines at CR/LF only, but YAML 1.1 parsers also break
     * at NEL/LS/PS, silently shifting the structure out from under the byte-offset splice; any
     * occurrence of those anywhere in the block refuses outright.
     *
     * **Only `' '` indents** — a TAB never legally indents YAML (the parser rejects a tab in
     * indentation position outright), so a `\t`-led line makes the block not provably a mapping ⇒
     * refuse. A line led by any other char (Unicode whitespace like U+2000/U+3000 included) reaches
     * [isSimpleKeyLine], whose own `first.isWhitespace()` check then refuses it.
     *
     * The first-line rule closes an oracle-found hole: a continuation run BEFORE the first key
     * (` indented` then `title: x`) is an indented root scalar a real parser rejects against the
     * column-0 key that follows — inserting `id:` above it corrupts the document. It subsumes the
     * earlier anchoring rule (` indented scalar` alone is an indented plain SCALAR, not a mapping).
     */
    private fun isBlockMapping(innerText: String): Boolean {
        if (innerText.any { it in YAML_1_1_EXTRA_BREAKS }) return false
        val lines = innerText.lineSequence()
            .filter { line -> line.any { it != ' ' && it != '\t' } }
            .filterNot { it.trimStart(' ').startsWith("#") }
            .toList()
        // A tab can never legally indent YAML; one in indentation position would make the document invalid.
        if (lines.any { it[0] == '\t' }) return false
        // Empty block (case 4): no content lines at all ⇒ vacuously a mapping (the `id:` line becomes the first).
        if (lines.isEmpty()) return true
        // A continuation run before the first key would be an indented root scalar — nothing anchors it.
        if (lines.first()[0] == ' ') return false
        val keyIndices = lines.indices.filter { lines[it][0] != ' ' }
        if (!keyIndices.all { isSimpleKeyLine(lines[it]) }) return false
        // Each key line owns the indented run that follows it, up to the next column-0 key line.
        return keyIndices.withIndex().all { (ordinal, keyIndex) ->
            val runEnd = keyIndices.getOrElse(ordinal + 1) { lines.size }
            continuationRunAllowed(lines[keyIndex], lines.subList(keyIndex + 1, runEnd))
        }
    }

    /**
     * Whether [keyLine] may carry the space-indented continuation [run] that follows it. Each rule
     * mirrors a YAML hard error the differential oracle surfaced; refusing more than a real parser
     * would (plain-scalar folding under `key: value`, block scalars under `key: |`) stays safe and
     * relaxable under the §A3 asymmetric freeze:
     *
     *  - **only a value-less key opens a nested block** — under `key: value` the value is already a
     *    scalar, and an indented `nested: w` after it is YAML's "mapping values are not allowed here";
     *  - **one indent** — a run varying its indentation mixes plain-scalar continuation with nested
     *    structure (`key:` + `␣cont` + `␣␣nested: v`), which no single YAML node can be;
     *  - **one shape** — a nested block is ONE node, so every run line must read as the same
     *    [ContinuationShape]: a mapping entry and a sequence entry cannot share a block.
     */
    private fun continuationRunAllowed(keyLine: String, run: List<String>): Boolean {
        if (run.isEmpty()) return true
        if (beforeInlineComment(keyLine).substringAfter(':').isNotBlank()) return false
        val indents = run.map { line -> line.takeWhile { it == ' ' }.length }
        if (indents.distinct().size != 1) return false
        val shapes = run.map { continuationShape(it.substring(indents.first())) }
        return null !in shapes && shapes.distinct().size == 1
    }

    /**
     * The single node kind [content] (a continuation line, indentation stripped) provably reads as,
     * or null when there is none: an indicator-led line, a scalar carrying a mapping colon, or a tab
     * still sitting in indentation position prove nothing and refuse the block.
     */
    private fun continuationShape(content: String): ContinuationShape? = when {
        isSimpleKeyLine(content) -> ContinuationShape.MAPPING_ENTRY
        isSequenceEntry(content) -> ContinuationShape.SEQUENCE_ENTRY
        !content[0].isWhitespace() && content[0] !in INDICATOR_CHARS && hasNoMappingColon(beforeInlineComment(content)) ->
            ContinuationShape.PLAIN_SCALAR
        else -> null
    }

    /**
     * Whether [content] is provably a single-node sequence entry: a bare `-` (a null item — exactly
     * what the reference parser reads), or `- ` followed by a plain scalar or one simple
     * `key: plainvalue` pair. An item opening anything else (`- [x`, `- "x`, `- *a`, `- !x`) hands
     * the block to a construct this grammar does not model — the third-pass twin of the value rule
     * in [isSimpleKeyLine], and the same refusal.
     */
    private fun isSequenceEntry(content: String): Boolean {
        if (content == "-") return true
        if (!content.startsWith("- ")) return false
        val item = content.substring(2).trimStart(' ', '\t')
        return isPlainScalar(item) || isSimpleKeyLine(item)
    }

    /** The node kind a continuation line reads as — one nested block is ONE node, so runs must not mix. */
    private enum class ContinuationShape { MAPPING_ENTRY, SEQUENCE_ENTRY, PLAIN_SCALAR }

    /**
     * The single frozen acceptance pattern (§A3): an unquoted simple key anchored at column 0 — the
     * first character is neither whitespace nor any YAML indicator (`- ? : , [ ] { } # & * ! | > ' " % @` `` ` ``),
     * the key contains no colon, the colon is terminated by whitespace or end-of-line, and the value
     * is a plain scalar or absent ([isPlainScalar]). There is deliberately no second pattern: quoted
     * keys, complex keys, anchors/aliases, tags, block scalars, directives, flow collections, and
     * `key:value` (no space after colon) all refuse via this gate.
     *
     * The value rule is oracle- and review-found, twice over: `k: v: w` is YAML's "mapping values are
     * not allowed here" error, not a mapping entry — and a value that OPENS a non-plain node
     * (`title: "unterminated`, `tags: [a, b`, `r: *a`, `s: |`) hands the rest of the document to a
     * construct this grammar does not model, so only a PLAIN value proves a mapping entry.
     * `url: http://x` (extra colons never whitespace-terminated) and `order: -1` stay accepted.
     */
    private fun isSimpleKeyLine(line: String): Boolean {
        val first = line[0]
        if (first.isWhitespace() || first in INDICATOR_CHARS) return false
        // Strip any YAML inline comment first: it begins at the first `#` preceded by whitespace, so a
        // `:` living inside the comment (e.g. `just text # why: because`) must not be read as a mapping
        // colon. A non-comment `#` (e.g. `title: foo#bar`) is not preceded by whitespace and survives.
        val content = beforeInlineComment(line)
        // The first colon is the mapping colon (so the key itself contains none); it must terminate.
        val colon = content.indexOf(':')
        if (colon < 0 || !isMappingColonAt(content, colon)) return false
        return isPlainScalar(content.substring(colon + 1))
    }

    /**
     * Whether [content] provably reads as a PLAIN scalar — or is empty once its inline comment and
     * leading ASCII separation are stripped, the value-less form (the only one that may open a nested
     * block). The first character must be plain-legal per YAML's `ns-plain-first`: not an indicator,
     * except `-` `?` `:` immediately followed by a non-space (`order: -1`, `?x`, `:8080` stay plain,
     * while `- item` and a lone `-` are "sequence entries are not allowed here"). Everything
     * non-plain — quoted, flow, anchor/alias, tag, block scalar — refuses: there is deliberately no
     * tokenizer for quote termination or flow nesting (ADR 0001), so the whole class refuses,
     * well-formed (`k: "quoted"`) and unterminated (`k: "untermin`) alike. Finally, a plain scalar
     * carries no mapping colon ([hasNoMappingColon] — the `k: v: w` rule).
     */
    private fun isPlainScalar(content: String): Boolean {
        val scalar = beforeInlineComment(content).trimStart(' ', '\t')
        if (scalar.isEmpty()) return true
        val plainFirst = scalar[0] !in INDICATOR_CHARS ||
            (scalar[0] in PLAIN_FIRST_EXEMPT && scalar.length > 1 && scalar[1] != ' ' && scalar[1] != '\t')
        return plainFirst && hasNoMappingColon(scalar)
    }

    /** A `:` is a MAPPING colon only when terminated by space, tab, or end-of-line (§A3 grammar). */
    private fun isMappingColonAt(content: String, index: Int): Boolean =
        index + 1 == content.length || content[index + 1] == ' ' || content[index + 1] == '\t'

    /** Whether [content] carries no mapping colon — what separates a plain scalar (`http://x`) from `b: c`. */
    private fun hasNoMappingColon(content: String): Boolean =
        content.indices.none { content[it] == ':' && isMappingColonAt(content, it) }

    /** [line] truncated at its YAML inline comment (a `#` at line start or preceded by whitespace), or whole if none. */
    private fun beforeInlineComment(line: String): String {
        val hash = line.indices.firstOrNull { line[it] == '#' && (it == 0 || line[it - 1] == ' ' || line[it - 1] == '\t') }
        return if (hash == null) line else line.substring(0, hash)
    }

    /** Case 5 — a column-0 `id` key: `^id[ \t]*:` (quoted `id` keys never reach here; case 9 refuses first). */
    private fun hasIdKey(innerText: String): Boolean =
        innerText.lineSequence().any { ID_KEY_LINE.matches(it) }

    /**
     * Extracts the value of the column-0 `id` key for the post-check (case 11). Mirrors the simple-key
     * grammar: the first column-0 `id:` line wins; the value is everything after the colon, trimmed.
     */
    private fun extractId(innerText: String): String? =
        innerText.lineSequence()
            .firstOrNull { ID_KEY_LINE.matches(it) }
            ?.substringAfter(':')
            ?.trim()

    /** Case 2/3 — a complete block: `---{EOL}id: …{EOL}---{EOL}`. EOL is the file's first terminator (`\n` if none). */
    private fun newBlock(original: ByteArray, id: PageId): ByteArray {
        val eol = firstLineTerminator(original)
        return "---".toByteArray(Charsets.US_ASCII) + eol + idLine(id, eol) + "---".toByteArray(Charsets.US_ASCII) + eol
    }

    /** The inserted mapping line: `id: ` + the 36-byte canonical UUID + the terminator. All ASCII. */
    private fun idLine(id: PageId, eol: ByteArray): ByteArray =
        "id: ${id.value}".toByteArray(Charsets.US_ASCII) + eol

    /**
     * Case-1 `{EOL}` rule: copy the terminator of the opening `---` line (the line immediately before
     * the insertion point). The opener spans `[block.bomLength, block.innerStart)`; its terminator is
     * the trailing CR/LF run.
     */
    private fun openerTerminator(original: ByteArray, block: FrontmatterBlock.Detection.Present): ByteArray =
        terminatorOf(original, block.bomLength, block.innerStart)

    /**
     * Case-2 `{EOL}` rule: copy the file's FIRST line terminator; a file with no terminator at all
     * uses `\n` (case 3, the empty file, falls here too). The first line spans from the post-BOM
     * offset to its terminator.
     */
    private fun firstLineTerminator(original: ByteArray): ByteArray {
        val from = FrontmatterBlock.detect(original).bomLength
        var i = from
        while (i < original.size && original[i] != LF && original[i] != CR) i++
        return terminatorOf(original, from, lineEndAfter(original, i))
    }

    /** The CR/LF run at the end of `[start, lineEnd)`, or `\n` when there is none (EOF without a terminator). */
    private fun terminatorOf(original: ByteArray, start: Int, lineEnd: Int): ByteArray {
        // Walk back over a trailing LF then CR (covers \n, \r, \r\n) without crossing the line content.
        var termStart = lineEnd
        if (termStart - 1 >= start && original[termStart - 1] == LF) termStart--
        if (termStart - 1 >= start && original[termStart - 1] == CR) termStart--
        val terminator = original.copyOfRange(termStart, lineEnd)
        return if (terminator.isEmpty()) byteArrayOf(LF) else terminator
    }

    /** Given an index sitting on the first CR/LF (or at EOF), returns the offset just past the full terminator. */
    private fun lineEndAfter(original: ByteArray, contentEnd: Int): Int {
        var i = contentEnd
        if (i < original.size && original[i] == CR) i++
        if (i < original.size && original[i] == LF) i++
        return i
    }

    /** Strict UTF-8 decode (REPORT on malformed/unmappable), or null — never emits U+FFFD. */
    private fun strictUtf8(bytes: ByteArray): String? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private companion object {
        /** §A3 default sanity bound for the block's inner region (64 KiB). */
        const val DEFAULT_MAX_BLOCK_BYTES = 64 * 1024

        private const val LF = '\n'.code.toByte()
        private const val CR = '\r'.code.toByte()

        /**
         * The §A3 YAML-indicator exclusion set: a column-0 key may not START with any of these.
         * Covers sequences (`-`), complex keys (`?`), mapping/flow punctuation, comments (`#`),
         * anchors/aliases (`&`/`*`), tags (`!`), block scalars (`|`/`>`), quotes (`'`/`"`), directives
         * (`%`), and `@`/`` ` `` (reserved).
         */
        val INDICATOR_CHARS = "-?:,[]{}#&*!|>'\"%@`".toSet()

        /** The `ns-plain-first` exemptions: `-` `?` `:` may begin a plain scalar when a non-space follows (`-1`, `?x`, `:8080`). */
        const val PLAIN_FIRST_EXEMPT = "-?:"

        /** YAML 1.1's extra line-break chars (NEL/LS/PS): a real parser breaks lines there; this CR/LF model never does. */
        val YAML_1_1_EXTRA_BREAKS = "\u0085\u2028\u2029".toSet()

        /** Case-5/post-check column-0 `id` key: `^id[ \t]*:` — the `id` key terminated by an optional run then a colon. */
        val ID_KEY_LINE = Regex("^id[ \\t]*:.*")

        /** [readIdValue]'s stricter form: the colon must be a MAPPING colon (whitespace/EOL after), so `id:value` is never identity. */
        val ID_VALUE_LINE = Regex("^id[ \\t]*:([ \\t].*)?")

        /** The rule-naming case-9 refusal message (the §A3 frozen requirement: operators must learn what to change). */
        const val NOT_A_MAPPING_MESSAGE =
            "frontmatter keys and values must be plain unquoted scalars — quoted, flow, anchor/alias, tag, " +
                "and block-scalar syntax is not supported for in-place id insertion"
    }
}
