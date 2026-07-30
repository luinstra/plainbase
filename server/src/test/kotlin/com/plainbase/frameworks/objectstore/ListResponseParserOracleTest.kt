package com.plainbase.frameworks.objectstore

import io.kotest.assertions.fail
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choose
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.random.Random

/**
 * Differential-oracle fuzz for [ListResponseParser]: the JDK's built-in DOM parser (test-only,
 * never in the native image - JAXP-in-prod was explicitly rejected in the plan's Q7) referees
 * the hand-rolled five-element extractor, hunting extract-the-WRONG-text bugs.
 *
 * **The invariant is one-directional.** The extractor is deliberately a strict-subset
 * recognizer, so a refusal ([ObjectStoreException]) asserts nothing: refusing what a real
 * parser could read is always safe (the caller surfaces it; S3/R2 never send the refused
 * shapes). Only an ACCEPT is checked:
 *
 * 1. the document must parse in the oracle at all (accept-then-oracle-throws means we
 *    extracted "data" from something no XML parser would have read), and
 * 2. the oracle must agree on every extracted element: the `<Contents>` count and order, each
 *    block's single `<Key>`/`<ETag>` text (entities and character references decoded), and the
 *    top-level `<IsTruncated>`/`<NextContinuationToken>` - top-level meaning OUTSIDE any
 *    `<Contents>`, which is exactly the boundary a hostile key would try to cross.
 *
 * The generator is biased at the grammar boundary (random bytes are almost always refused and
 * prove nothing): entity/character-reference forms in text, empty and hostile-alphabet keys,
 * attributed/self-closing/duplicated/missing target elements, comments/CDATA/PIs, unbalanced and
 * mis-nested sibling markup (dangling starts, stray closes, crossed nesting), tags that BALANCE but
 * are internally malformed (whitespace self-close, leading-whitespace end tag, unquoted/bare/valueless
 * attributes), XML Name-production violations (digit/punct-leading tag AND attribute names, duplicate
 * attributes), entity/char-ref defects in NON-target element text, a raw `]]>` in CharData (the last XML
 * CharData exclusion), document root-shape defects (multiple roots, char data outside the root), shuffled
 * element order, 0-3 Contents blocks. The seed is pinned.
 */
class ListResponseParserOracleTest : FunSpec({

    // PASS 1 - ENUMERATED: the boundary-biased grammar generator (every violation CLASS by construction).
    test("differential oracle (enumerated) - every accepted listing agrees with the JDK DOM parser") {
        checkAll(PropTestConfig(seed = ORACLE_SEED, iterations = 8000), documents) { xml ->
            assertAcceptImpliesOracleParses(xml, "xml = $xml")
        }
    }

    // PASS 2 - MUTATION: take a WELL-FORMED listing and apply 1-3 random corruptions (insert/delete/replace/
    // truncate/duplicate over a broad alphabet incl. `<>&"'/]?=` space NBSP control `]]>` `<?xml`). This finds
    // divergence CLASSES the enumerated alphabet never produced - the recurring source of reviewer-found gaps.
    // The per-iteration mutation seed is printed on failure, so any counterexample is exactly reproducible.
    test("differential oracle (mutation) - a randomly corrupted well-formed listing never accepts what the oracle rejects") {
        checkAll(PropTestConfig(seed = MUTATION_SEED, iterations = 20000), mutationSeeds) { (base, mutationSeed) ->
            val xml = mutate(base, Random(mutationSeed))
            assertAcceptImpliesOracleParses(xml, "mutationSeed = $mutationSeed\n  base = $base\n  mutated = $xml")
        }
    }
})

/**
 * The one-directional differential check both passes share: if [ListResponseParser] ACCEPTS [xml], the JDK
 * DOM oracle must parse it too AND agree on every extracted element (a refusal asserts nothing). [clue] is
 * printed on failure so the counterexample is reproducible.
 */
private fun assertAcceptImpliesOracleParses(xml: String, clue: String) {
    val accepted = try {
        ListResponseParser.parse(xml)
    } catch (_: ObjectStoreException) {
        return // refusal is the safe direction; assert nothing
    }
    // Pagination-termination invariant (fail-closed): an ACCEPTED truncated page must carry a nonblank
    // continuation token, else a paging loop refetches page 1 forever.
    withClue("accepted a truncated page with no continuation token (infinite-loop bait) - $clue") {
        if (accepted.isTruncated) (accepted.nextContinuationToken?.isNotBlank() ?: false) shouldBe true
    }
    assertOracleAgrees(xml, accepted)
}

/** Pinned so CI is deterministic (no time-derived seed); bump deliberately to explore a new path. */
private const val ORACLE_SEED = 0x0B7EC7_57042EL

private fun assertOracleAgrees(xml: String, accepted: ListResponseParser.Listing) {
    val document = try {
        DocumentBuilderFactory.newInstance()
            .apply { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            .newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
    } catch (e: Exception) {
        fail("FALSE-ACCEPT: extractor accepted a document the DOM oracle rejects outright\n  xml = $xml\n  oracle said: ${e.message}")
    }

    val domContents = document.getElementsByTagName("Contents").asElements()
    withClue("Contents count disagrees (xml = $xml)") { accepted.contents.size shouldBe domContents.size }
    accepted.contents.zip(domContents).forEachIndexed { index, (entry, element) ->
        withClue("Contents[$index] Key text disagrees (xml = $xml)") {
            entry.key shouldBe element.singleChildText("Key")
        }
        withClue("Contents[$index] ETag text disagrees (xml = $xml)") {
            entry.etag shouldBe element.singleChildText("ETag")
        }
        // Size agreement, against the LENIENT spec (sizeOf): exactly one attribute-free <Size> element
        // anywhere in the block whose text is a plain Long extracts; every other well-formed shape is
        // null. The DOM side re-derives that spec independently, so a divergence between the substring
        // scan and real element structure (a <Sizes> sibling, a nested <Size>, a duplicated pair) fails
        // here rather than shipping.
        withClue("Contents[$index] Size disagrees (xml = $xml)") {
            entry.size shouldBe element.domDeclaredSize()
        }
    }

    val truncated = document.topLevelTexts("IsTruncated")
    withClue("IsTruncated disagrees (xml = $xml)") {
        truncated.singleOrNull() shouldBe accepted.isTruncated.toString()
    }
    val tokens = document.topLevelTexts("NextContinuationToken")
    withClue("NextContinuationToken disagrees (xml = $xml)") {
        tokens.singleOrNull() shouldBe accepted.nextContinuationToken
    }
}

private fun org.w3c.dom.NodeList.asElements(): List<Element> = (0 until length).map { item(it) as Element }

/**
 * The lenient declared-size spec, re-derived from REAL element structure: exactly one <Size> element
 * (descendant scope, matching the extractor's whole-block scan), no attributes (the extractor only
 * reads the plain form), text a plain non-negative Long. Anything else is null, never a failure -
 * Size is advisory and must not be able to refuse.
 */
private fun Element.domDeclaredSize(): Long? {
    val sizes = getElementsByTagName("Size").asElements()
    val single = sizes.singleOrNull() ?: return null
    if (single.attributes.length > 0) return null
    val text = single.textContent
    if (text.isEmpty() || text.any { it !in '0'..'9' }) return null
    return text.toLongOrNull()
}

/** The oracle's view of a target inside one Contents block: exactly one descendant, its text. */
private fun Element.singleChildText(tag: String): String {
    val matches = getElementsByTagName(tag).asElements()
    withClue("an accepted <Contents> must hold exactly one <$tag> for the oracle too") { matches.size shouldBe 1 }
    return matches.single().textContent
}

/** Texts of every [tag] element NOT inside a Contents block - the extractor's top-level scope. */
private fun org.w3c.dom.Document.topLevelTexts(tag: String): List<String> =
    getElementsByTagName(tag).asElements()
        .filterNot { element -> generateSequence<Node>(element) { it.parentNode }.any { it.nodeName == "Contents" && it !== element } }
        .map { it.textContent }

// ---- The boundary-biased generator --------------------------------------------------------------

/** Text-node fragments AS THEY APPEAR IN XML: entity forms, character references, refusal bait. */
private val textVocabulary = listOf(
    "a.md",
    "notes/sp%20ace%20%26%20co.md",
    "a&amp;b",
    "&#65;bc",
    "&#x1F600;",
    "&quot;quoted&quot;",
    "a&apos;&lt;&gt;z",
    "1/tok+X=",
    "",
    "  padded  ",
    "a&b", // bare ampersand: refused by the extractor, ill-formed for the oracle
    "a&unknown;b", // unknown entity: refused
    "&#xD800;", // surrogate character reference: refused (outside XML's legal Char production)
    // illegal-control character references: the DOM oracle rejects each, so an accepted one is a bug
    "&#x01;",
    "&#x08;",
    "&#x0B;",
    "&#x0C;",
    "&#x1F;",
    "&#xFFFE;",
    "&#x9;valid-tab", // the boundary's ACCEPT side: tab IS legal, must keep round-tripping
    "tricky]]&gt;",
    // P1: malformed numeric character references the DOM oracle REJECTS (strict ASCII CharRef only)
    "&#X41;", // uppercase X in the hex marker: refused
    "&#+65;", // leading sign in a decimal ref: refused
    "&#x+41;", // leading sign in a hex ref: refused
    "&#\u0666\u0665;", // Arabic-Indic digits: refused (ASCII only)
    "&#x\uFF14\uFF11;", // fullwidth digits: refused
    "&#;", // empty decimal ref: refused
    "&#x;", // empty hex ref: refused
    "&#99999999999;", // out-of-range: refused (kept)
    // P3: literal CR / CRLF - the oracle normalizes to LF (a `&#xD;` REFERENCE stays a real CR)
    "line1\rline2", // lone CR: normalized to LF
    "a\r\nb", // CRLF: normalized to LF
    "keep&#xD;me", // an explicit CR REFERENCE is preserved (not normalized), both sides agree
    "a]]>b", // raw ']]>' in CharData: XML forbids the literal (CDATA-terminator only), the oracle rejects it
    "]]>", // the bare terminator
    "a\u0001b", // a raw illegal XML control char (U+0001) in CharData: the DOM oracle rejects it
    "a\uD800b", // a lone high surrogate in CharData: outside XML's Char production, DOM-rejected
)

private val etagVocabulary = listOf(
    "&quot;fba9dede5f27731c9771645a39863328&quot;",
    "\"plainquotes\"",
    "&quot;multipart-2&quot;",
    "W/&quot;weak&quot;",
)

/**
 * Balanced-but-internally-MALFORMED tags (R2-3): the pair BALANCES so a tag-count-only check accepts
 * them, but each start/end tag violates XML's actual STag/ETag/EmptyElemTag production, so the DOM
 * oracle rejects the document. requireWellFormed must validate tag internals, not just balance.
 */
private val malformedInternalNoise = listOf(
    "<Bar/ ></Bar>", // self-close with whitespace before '>': '/' is not immediately before '>'
    "<Bar></ Bar>", // leading whitespace before an end-tag name (ETag is '</' Name S? '>')
    "<Bar attr=1></Bar>", // unquoted attribute value
    "<Bar attr></Bar>", // bare attribute, no '=value'
    "<Bar attr=></Bar>", // '=' with no value
    "<Bar a=\"1\"b=\"2\"></Bar>", // attributes not whitespace-separated
)

/**
 * XML Name-production violations (R3-2): a digit/punct-leading tag or attribute name, and a duplicate
 * attribute name. Each BALANCES so a structural check passes, but the DOM oracle rejects them.
 */
private val malformedNameNoise = listOf(
    "<1Bar></1Bar>", // tag name starts with a digit
    "<.Bar></.Bar>", // tag name starts with '.'
    "<Bar 1attr=\"v\"></Bar>", // attribute name starts with a digit
    "<Bar -a=\"v\"></Bar>", // attribute name starts with '-'
    "<Bar a=\"1\" a=\"2\"></Bar>", // duplicate attribute name
)

/**
 * Entity/char-ref defects in NON-target element text (R3-2): only <Key>/<ETag> text was entity-validated
 * before; a sibling like <Name> must be validated too. Includes an ACCEPT-side case (a valid entity in
 * non-target text) that must stay accepted, so the strengthened text check does not over-refuse.
 */
private val nonTargetTextDefects = listOf(
    "<Name>a&bogus;b</Name>", // undefined entity in non-target text
    "<Name>a&b</Name>", // bare '&' in non-target text
    "<Name>a&#x01;b</Name>", // illegal char reference in non-target text
    "<Name>a]]>b</Name>", // raw ']]>' in non-target CharData: forbidden literal, oracle rejects
    "<Name>a\u0001b</Name>", // raw illegal control char (U+0001) in non-target CharData: oracle rejects
    "<Bar a=\"x\u0001y\"></Bar>", // raw illegal control char in an ATTRIBUTE value: also DOM-rejected
    "<Prefix>ok&amp;fine</Prefix>", // ACCEPT side: a valid entity in non-target text stays accepted
)

/**
 * Unicode-whitespace at XML `S` positions (R3-2 addendum): NBSP U+00A0 / figure-space U+2007 / narrow
 * NBSP U+202F are Kotlin `Char.isWhitespace()` but NOT XML `S` (only space/tab/LF/CR), so a Unicode-space
 * attribute separator or end-tag trailing space is REJECTED by the DOM oracle - the parser must test `S`
 * exactly, never `isWhitespace()`.
 */
private val unicodeSpaceNoise = listOf(
    "<Bar\u00A0a=\"1\"></Bar>", // NBSP between element name and attribute
    "<Bar a=\"1\"\u00A0b=\"2\"></Bar>", // NBSP between two attributes
    "<Bar></Bar\u00A0>", // NBSP as end-tag trailing whitespace
    "<Bar\u2007a=\"1\"></Bar>", // U+2007 figure space as separator
    "<Bar a=\"1\"\u202Fb=\"2\"></Bar>", // U+202F narrow no-break space between attributes
)

private val contentsNoise = listOf(
    "",
    "<LastModified>2026-07-01T12:00:00.000Z</LastModified>",
    "<StorageClass>STANDARD</StorageClass>",
    // Size is now a READ (lenient) target, not noise: every well-formed variant must ACCEPT with the
    // parser and DOM agreeing on the extracted-or-null value (the domDeclaredSize comparison above).
    "<Size>409</Size>", // plain - extracts
    "<Size>0</Size>", // zero - extracts
    "<Size>-1</Size>", // signed - null
    "<Size>1e3</Size>", // not a plain integer - null
    "<Size></Size>", // empty - null
    "<Size>99999999999999999999</Size>", // overflows Long - null
    "<Size>1</Size><Size>2</Size>", // duplicated - ambiguous, null
    "<Size unit=\"bytes\">409</Size>", // attributed form - null
    "<Size/>", // self-closing - null
    "<Sizes>7</Sizes>", // a DIFFERENT element sharing the prefix - not a Size at all
    "<Owner><Size>77</Size></Owner>", // nested - descendant scope still extracts, both sides agree
    "<Owner><ID>abc123</ID><DisplayName>owner</DisplayName></Owner>",
    // Unbalanced / mis-nested ignorable siblings: the extractor reads only <Key>/<ETag> and would
    // otherwise silently ignore these, but the DOM oracle rejects the whole document - so an ACCEPT
    // here is a fail-closed violation the parser's well-formedness check must catch.
    "<Bar>", // dangling start tag, no close
    "</Bar>", // close with no matching open
    "<a><b></a></b>", // crossed nesting
    "<Owner><ID>x</ID>", // an ignorable container left open
) + malformedInternalNoise + malformedNameNoise + unicodeSpaceNoise

/** Well-shaped blocks (the accept side the oracle then referees). */
private val wellShapedBlock: Arb<String> = Arb.bind(
    Arb.element(textVocabulary),
    Arb.element(etagVocabulary),
    Arb.element(contentsNoise),
    Arb.element(listOf(true, false)),
) { key, etag, noise, keyFirst ->
    val pair = if (keyFirst) "<Key>$key</Key>$noise<ETag>$etag</ETag>" else "<ETag>$etag</ETag>$noise<Key>$key</Key>"
    "<Contents>$pair</Contents>"
}

/** Shape bait: most must refuse; whatever the extractor DOES accept is refereed by the oracle. */
private val hostileBlocks = listOf(
    "<Contents><ETag>\"x\"</ETag></Contents>", // missing Key
    "<Contents><Key>a.md</Key></Contents>", // missing ETag
    "<Contents><Key>a</Key><Key>b</Key><ETag>\"x\"</ETag></Contents>", // duplicate Key
    "<Contents><Key>a</Key><ETag>\"x\"</ETag><ETag>\"y\"</ETag></Contents>", // duplicate ETag
    "<Contents Foo=\"1\"><Key>a</Key><ETag>\"x\"</ETag></Contents>", // attributed Contents
    "<Contents><Key enc=\"url\">a</Key><ETag>\"x\"</ETag></Contents>", // attributed Key
    "<Contents><Key enc=\"url\">a</Key><Key>b</Key><ETag>\"x\"</ETag></Contents>", // attributed then plain
    "<Contents><Key><![CDATA[a.md]]></Key><ETag>\"x\"</ETag></Contents>", // CDATA text
    "<Contents><Key>a<i/>b</Key><ETag>\"x\"</ETag></Contents>", // markup inside text
    "<Contents><Key>a</Key><ETag>\"x\"</ETag><NextContinuationToken>fake</NextContinuationToken></Contents>",
)

private val blocks: Arb<String> = Arb.choose(8 to wellShapedBlock, 2 to Arb.element(hostileBlocks))

private val truncatedVocabulary = listOf(
    "<IsTruncated>true</IsTruncated>",
    "<IsTruncated>false</IsTruncated>",
    "<IsTruncated>TRUE</IsTruncated>", // refused value
    "<IsTruncated>1</IsTruncated>", // refused value
    "", // missing: refused
)

private val tokenVocabulary = listOf(
    "",
    "",
    "<NextContinuationToken>1ueGcxLPRx1Tr/XYExHnhbYLgveDs2J/wm36Hy4vbOwM=</NextContinuationToken>",
    "<NextContinuationToken>tok&amp;x</NextContinuationToken>",
    "<NextContinuationToken></NextContinuationToken>",
    "<NextContinuationToken/>", // self-closing: refused form
)

private val topLevelNoise = listOf(
    "",
    "<Name>scratch-bucket</Name>",
    "<Prefix>smoke-1234/</Prefix>",
    "<KeyCount>2</KeyCount>",
    "<MaxKeys>1000</MaxKeys>",
    "<EncodingType>url</EncodingType>",
    "<CommonPrefixes><Prefix>a/</Prefix></CommonPrefixes>",
    // Unbalanced / mis-nested top-level markup (OUTSIDE any Contents block): the oracle rejects the
    // document, so an ACCEPT is fail-closed violation - the well-formedness check must refuse.
    "<Bar>", // dangling start tag
    "</Bar>", // stray close
    "<a><b></a></b>", // crossed nesting
    "<CommonPrefixes><Prefix>a/</Prefix>", // ignorable container left open
) + malformedInternalNoise + malformedNameNoise + nonTargetTextDefects + unicodeSpaceNoise

private val documentHostiles = listOf("", "", "", "<!-- a comment -->", "<?pi data?>")

/**
 * Document ROOT-shape defects (R3-2): content OUTSIDE the single root. `(pre, post)` wraps the root.
 * Mostly clean (keeps valid-doc coverage high); whitespace outside the root is LEGAL Misc, but char data
 * or a SECOND root element is rejected by the oracle - so the parser must require exactly one root.
 */
private val rootDefects = listOf(
    "" to "",
    "" to "",
    "" to "",
    "  " to "", // leading whitespace: legal prolog Misc
    "" to "\n  ", // trailing whitespace: legal Misc
    "leading" to "", // char data before the root: rejected
    "" to "trailing", // char data after the root: rejected
    "" to "<Sibling/>", // a SECOND root element after: rejected
    "<Sibling/>" to "", // a SECOND root element before: rejected
    "\u00A0" to "", // NBSP in the prolog: not XML S, rejected
    "\uFEFF" to "", // a leading BOM: the StringReader oracle reads it as content-before-root and REJECTS (P2)
    "" to "\u00A0", // NBSP after the root: not XML S, rejected
)

private val declarations = listOf(
    // ACCEPT side (real declarations): must stay accepted after the strict XMLDecl validation.
    "",
    "<?xml version=\"1.0\"?>",
    "<?xml version='1.0'?>", // single-quoted version
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
    "<?xml version=\"1.0\" encoding='UTF-8' standalone=\"yes\"?>",
    "<?xml version=\"1.0\" standalone=\"no\"?>",
    // REFUSE side: TERMINATED-but-internally-malformed prologs the loose regex stripped but the oracle rejects.
    "<?xml version=1.0?>", // unquoted version value
    "<?xml version=\"2.0\"?>", // unsupported version number
    "<?xml encoding=\"UTF-8\"?>", // missing the required version
    "<?xml version=\"1.0\" standalone=\"yes\" encoding=\"UTF-8\"?>", // wrong attribute order
    "<?xml version=\"1.0\" foo=\"bar\"?>", // unknown attribute
    "<?xml VERSION=\"1.0\"?>", // wrong case ('version' is case-sensitive)
    "<?xml version=\"1.0\" standalone=\"maybe\"?>", // bad standalone value
    "<?xml version=\"1.0\"", // unterminated declaration: refused (agy's `<?` gate edge), oracle rejects
)

/** A full document: shuffled top-level items inside the root, optional declaration, optional root-shape defect. */
private val documents: Arb<String> = Arb.bind(
    Arb.element(declarations),
    Arb.element(truncatedVocabulary),
    Arb.element(tokenVocabulary),
    Arb.list(blocks, 0..3),
    Arb.list(Arb.element(topLevelNoise), 0..3),
    Arb.element(documentHostiles),
    Arb.int(0..7),
    Arb.element(listOf("", "\n", "\n    ")),
    Arb.element(rootDefects),
) { declaration, truncated, token, blockList, noise, hostile, rotation, separator, rootDefect ->
    val items = (listOf(truncated, token, hostile) + blockList + noise).filter { it.isNotEmpty() }
    val rotated = if (items.isEmpty()) items else items.drop(rotation % items.size) + items.take(rotation % items.size)
    val (pre, post) = rootDefect
    declaration + pre +
        "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
        rotated.joinToString(separator, prefix = separator, postfix = separator) +
        "</ListBucketResult>" + post
}

// ---- The mutation-based pass: corrupt a WELL-FORMED base, then run the SAME differential assertion --------

/** Pinned mutation-pass seed (distinct from ORACLE_SEED); deterministic, so a counterexample reproduces. */
private const val MUTATION_SEED = 0x0B7EC7_A11CE5L

private val validKeyText = listOf("a.md", "notes/sp%20ace.md", "a&amp;b", "&#65;bc", "dir/f.md", "")
private val validEtagText = listOf("\"abc123\"", "&quot;e1&quot;", "W/&quot;weak&quot;")

private val wellFormedBlock: Arb<String> = Arb.bind(Arb.element(validKeyText), Arb.element(validEtagText)) { key, etag ->
    "<Contents><Key>$key</Key><ETag>$etag</ETag></Contents>"
}

/** Guaranteed-VALID ListBucketResult documents (parser accepts AND oracle accepts) - the mutation base. */
private val wellFormedDocuments: Arb<String> = Arb.bind(
    Arb.element("", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>", "<?xml version=\"1.0\"?>"),
    Arb.element(listOf(true, false)),
    Arb.list(wellFormedBlock, 0..3),
) { declaration, truncated, blocks ->
    val token = if (truncated) "<NextContinuationToken>1ueGcx/tok+X=</NextContinuationToken>" else ""
    declaration + "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
        "<IsTruncated>$truncated</IsTruncated>" + token + blocks.joinToString("") + "</ListBucketResult>"
}

/** A well-formed base paired with a per-iteration RNG seed the mutator consumes (both pinned via MUTATION_SEED). */
private val mutationSeeds: Arb<Pair<String, Long>> = Arb.bind(wellFormedDocuments, Arb.long()) { base, seed -> base to seed }

/** The mutation alphabet: broad, grammar-relevant atoms (single chars plus a couple of structural fragments). */
private val mutationAlphabet = listOf(
    "<", ">", "&", "\"", "'", "/", "]", "?", "=", " ", "\u00A0", "\u0001", "\u00AA", "]]>", "<?xml", "a", "0", ";", "#", "-",
)

/** Applies 1-3 random corruptions (insert / delete / replace / truncate / duplicate a short run) to [doc]. */
private fun mutate(doc: String, rng: Random): String {
    var s = doc
    repeat(rng.nextInt(1, 4)) {
        if (s.isEmpty()) return s
        s = when (rng.nextInt(5)) {
            0 -> rng.nextInt(s.length + 1).let { s.substring(0, it) + mutationAlphabet.random(rng) + s.substring(it) }
            1 -> rng.nextInt(s.length).let { s.removeRange(it, it + 1) }
            2 -> rng.nextInt(s.length).let { s.substring(0, it) + mutationAlphabet.random(rng) + s.substring(it + 1) }
            3 -> s.take(rng.nextInt(s.length + 1))
            else -> {
                val i = rng.nextInt(s.length)
                val j = minOf(s.length, i + rng.nextInt(1, 6))
                s.substring(0, j) + s.substring(i, j) + s.substring(j)
            }
        }
    }
    return s
}
