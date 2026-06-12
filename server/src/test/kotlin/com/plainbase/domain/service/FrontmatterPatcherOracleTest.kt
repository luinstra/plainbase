package com.plainbase.domain.service

import com.plainbase.domain.page.FrontmatterBlock
import com.plainbase.domain.page.PageId
import io.kotest.assertions.fail
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml

/**
 * PB-PATCH-1 (§A3) differential-oracle fuzz test: SnakeYAML — a real YAML parser, JVM-test-scoped
 * ONLY, never on the runtime or native-test classpath — referees the hand-rolled strictest-subset
 * mapping recognizer, hunting **false-accepts**.
 *
 * **The invariant is one-directional** (§A3's asymmetric freeze in test form). The recognizer is
 * deliberately STRICTER than "valid YAML mapping", so `Refused` and `AlreadyPresent` assert nothing:
 * refusing what a real parser accepts is always safe, and a pre-existing `id` key is outside the
 * oracle's scope. Only a `Patched` result is checked, on two clauses:
 *
 * 1. the INPUT inner region parses as a top-level `Map` — or parses cleanly to `null`, the
 *    content-free document (a blank or comment-only block: the case-4 class of legitimate accepts,
 *    where the inserted `id:` line becomes the whole mapping). A parser EXCEPTION on the input
 *    always fails: accept-then-throws means we spliced into invalid YAML while calling it a mapping;
 * 2. the OUTPUT block, re-extracted with the shared [FrontmatterBlock] detector, parses as a
 *    top-level `Map` whose `id` stringifies to the inserted id and that carries every other
 *    top-level pair of the input parse unchanged — the patch only ever ADDS `id`.
 *
 * The generator is biased toward the grammar boundary — random noise is almost always refused and
 * proves nothing. Bodies are 1–6 lines from a vocabulary stressing every dimension the §A3 reviews
 * fought over: indicator-led lines, inline-comment traps, multi-colon scalars, Unicode whitespace
 * (both whole lines of it and YAML 1.1's extra break chars), quotes/flow/anchors/tags in BOTH key
 * and value position, sequence items, indented continuations, bare scalars — and the plain values
 * (`order: -1`, `time: 14:30:00`) that must keep accepting right at that boundary. The seed is
 * pinned, so CI is deterministic and any failure replays exactly.
 */
class FrontmatterPatcherOracleTest : FunSpec({

    val patcher = FrontmatterPatcher()
    val id = PageId.require("0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a")

    test("differential oracle — every Patched block is a real top-level YAML mapping, spliced intact") {
        checkAll(PropTestConfig(seed = ORACLE_SEED, iterations = 6000), blockBodies) { inner ->
            val document = "---\n$inner\n---\nbody\n".toByteArray()
            val result = patcher.patch(document, id)
            // Refused / AlreadyPresent: assert nothing — over-refusal is the safe direction (§A3).
            if (result is FrontmatterPatcher.PatchResult.Patched) assertHonestAccept(inner, result.bytes, id)
        }
    }
})

/** Pinned so CI is deterministic (no time-derived seed); bump deliberately to explore a new path. */
private const val ORACLE_SEED = 0xB10C_4A11L

/** The two oracle clauses for an accepted block (see class KDoc); any violation is a real grammar bug. */
private fun assertHonestAccept(inner: String, output: ByteArray, id: PageId) {
    // Clause 1 — the input really was a top-level mapping (or a content-free document).
    val inputDocument = when (val verdict = referenceParse(inner)) {
        is Verdict.ParseError -> fail(
            "FALSE-ACCEPT: patcher accepted a block the reference parser rejects outright\n" +
                "  inner = ${inner.visible()}\n  parser said: ${verdict.message}",
        )
        is Verdict.Document -> verdict.value
    }
    if (inputDocument != null) {
        withClue("FALSE-ACCEPT: patcher accepted a block that parses as a non-mapping (inner = ${inner.visible()})") {
            inputDocument.shouldBeInstanceOf<Map<*, *>>()
        }
    }
    // inputDocument == null is the clean content-free parse: the patcher only ever accepts a block
    // that is content-free (case 4) or that holds at least one column-0 key line — and a block with
    // a key line can never parse to null — so a null here is exactly the legitimate empty-block accept.

    // Clause 2 — the output still detects, parses as a mapping, gained exactly `id`, lost nothing.
    val block = withClue("Patched output lost its frontmatter block on re-extraction (inner = ${inner.visible()})") {
        FrontmatterBlock.detect(output).shouldBeInstanceOf<FrontmatterBlock.Detection.Present>()
    }
    val outInner = output.copyOfRange(block.innerStart, block.innerEnd).toString(Charsets.UTF_8)
    val outMapping = when (val verdict = referenceParse(outInner)) {
        is Verdict.ParseError -> fail(
            "patched output no longer parses\n  inner = ${inner.visible()}\n" +
                "  outInner = ${outInner.visible()}\n  parser said: ${verdict.message}",
        )
        is Verdict.Document -> withClue("patched output is not a top-level mapping (outInner = ${outInner.visible()})") {
            verdict.value.shouldBeInstanceOf<Map<*, *>>()
        }
    }
    withClue("inserted id must read back through the reference parser (outInner = ${outInner.visible()})") {
        outMapping["id"]?.toString() shouldBe id.value
    }
    val inputMapping = inputDocument as? Map<*, *> ?: emptyMap<Any?, Any?>()
    inputMapping.forEach { (key, value) ->
        withClue("pre-existing pair must survive the splice unchanged (key = $key, inner = ${inner.visible()})") {
            outMapping.containsKey(key) shouldBe true
            outMapping[key] shouldBe value
        }
    }
}

/** SnakeYAML's verdict on a document: the parsed value (null = content-free) or the parse error. */
private sealed interface Verdict {
    data class Document(val value: Any?) : Verdict
    data class ParseError(val message: String) : Verdict
}

/**
 * Safe-by-default SnakeYAML 2.x load — no unsafe global constructor, and it only ever sees generated
 * test input. Duplicate keys stay allowed (silently: the generator repeats vocabulary lines by design,
 * and key uniqueness is not the dimension under test — warning 4000 times would flood the CI log).
 * Every parser exception becomes [Verdict.ParseError], never a rethrow.
 */
private fun referenceParse(yaml: String): Verdict =
    try {
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = true
            isWarnOnDuplicateKeys = false
        }
        Verdict.Document(Yaml(options).load(yaml))
    } catch (e: RuntimeException) {
        Verdict.ParseError(e.message ?: (e::class.simpleName ?: "unnamed parser error"))
    }

/** [this] with breaks, tabs, and non-ASCII made visible — a counterexample must be unambiguous. */
private fun String.visible(): String =
    buildString {
        append('"')
        this@visible.forEach { ch ->
            when {
                ch == '\\' -> append("\\\\")
                ch == '"' -> append("\\\"")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch.code in 32..126 -> append(ch)
                else -> append("\\u%04X".format(ch.code))
            }
        }
        append('"')
    }

// ---- The boundary-biased generator --------------------------------------------------------------

/**
 * One line per grammar dimension. Unicode whitespace is pinned as `\u` escapes (never raw bytes) so
 * an editor cannot silently fold it to ASCII spaces.
 */
private val vocabulary = listOf(
    // valid simple keys (the accept side the splice clauses then exercise)
    "key: value",
    "key:",
    "two words: v",
    "k: v # comment",
    "title: A Title",
    "count: 42",
    // indicator-led — must refuse
    "- item",
    "? complex",
    ": orphan",
    ", comma",
    "[ bracket",
    "] bracket",
    "{ brace",
    "} brace",
    "# full-line comment",
    "& anchor",
    "* alias",
    "! tag",
    "| literal",
    "> folded",
    "' single",
    "\" double",
    "% directive",
    "@ reserved",
    "` backtick",
    // inline-comment traps: a `:` hiding inside a comment must not read as a mapping colon
    "scalar # note: colon",
    "text   #c: d",
    "plain text # why: because",
    // multi-colon / scalar-with-colon traps
    "a: b: c",
    "http://x",
    "k: v: w",
    "url: http://example.com",
    // whitespace: ASCII space/tab = YAML indentation (continuations); Unicode whitespace is NOT
    " leading space cont",
    "\tleading tab cont",
    "  nested: v",
    "  - alpha",
    "trailing space: v   ",
    "\u00A0nbsp: v", // NO-BREAK SPACE
    "\u2000enquad: v", // EN QUAD
    "\u3000ideographic: v", // IDEOGRAPHIC SPACE
    "\u1680ogham: v", // OGHAM SPACE MARK
    "\u2028lineSep: v", // LINE SEPARATOR
    "\u00A0: orphan-colon scalar",
    "\u2000just text",
    // quotes / flow / anchors / tags / complex keys — all refuse
    "\"q\": v",
    "'q': v",
    "{a: 1}",
    "[1,2]",
    "&a x",
    "*a",
    "!!str x",
    "? complex key",
    "&anchor [1, 2]",
    "\"foo\\\" : bar\"", // the escape-quote scalar that killed quoted-key acceptance (§A3)
    // non-plain VALUES (third-pass holes): the value after the mapping colon must be a plain scalar
    "title: \"unterminated",
    "k: \"quoted\"",
    "tags: [a, b",
    "tags: [a, b]",
    "m: {a: 1",
    "m: {a: 1}",
    "r: *a",
    "r: &a x",
    "t: !!str x",
    "s: |",
    "s: >",
    "v: - item",
    // plain values that must STAY accepted — the accept side of the same boundary
    "order: -1",
    "count: -5",
    "time: 14:30:00",
    "url: https://example.com",
    // sequence items: only a plain scalar or a single simple pair is provably one node
    "  - [unterminated",
    "  - *missing",
    "  - \"x",
    "  - !x",
    "  - key: v",
    // whitespace-ONLY lines: only ASCII space/tab lines are blank — these must reach the key check
    "\u2000", // EN QUAD alone
    "\u00A0", // NO-BREAK SPACE alone
    "\u3000", // IDEOGRAPHIC SPACE alone
    // YAML 1.1 extra break chars (NEL/LS/PS): a real parser splits the line there; CR/LF models don't
    "k: a\u0085b", // NEXT LINE inside a value
    "k: a\u2028b", // LINE SEPARATOR inside a value
    // no space after the colon (B2 — a plain scalar, refuse)
    "key:value",
    "id:value",
    // bare scalars
    "just text",
    "lone scalar",
    // blank line
    "",
    // id-key forms (drive the out-of-scope AlreadyPresent path)
    "id: 0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a",
    "id : spaced",
)

/** Block bodies between the fences: 1–6 vocabulary lines (bias beats noise at the grammar boundary). */
private val blockBodies: Arb<String> = Arb.list(Arb.element(vocabulary), 1..6).map { it.joinToString("\n") }
