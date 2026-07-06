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
import io.kotest.property.checkAll
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

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
 * attributed/self-closing/duplicated/missing target elements, comments/CDATA/PIs, shuffled
 * element order, 0-3 Contents blocks. The seed is pinned, so CI is deterministic.
 */
class ListResponseParserOracleTest : FunSpec({

    test("differential oracle - every accepted listing agrees with the JDK DOM parser") {
        checkAll(PropTestConfig(seed = ORACLE_SEED, iterations = 4000), documents) { xml ->
            val accepted = try {
                ListResponseParser.parse(xml)
            } catch (_: ObjectStoreException) {
                return@checkAll // refusal is the safe direction; assert nothing
            }
            // Pagination-termination invariant (fail-closed): an ACCEPTED truncated page must carry a
            // nonblank continuation token, else a paging loop refetches page 1 forever. The generator
            // routinely pairs <IsTruncated>true</IsTruncated> with an absent/blank token (independent
            // vocabularies, shuffled), so if the parser's refusal ever regressed this would catch it.
            withClue("accepted a truncated page with no continuation token (infinite-loop bait) - xml = $xml") {
                if (accepted.isTruncated) (accepted.nextContinuationToken?.isNotBlank() ?: false) shouldBe true
            }
            assertOracleAgrees(xml, accepted)
        }
    }
})

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
)

private val etagVocabulary = listOf(
    "&quot;fba9dede5f27731c9771645a39863328&quot;",
    "\"plainquotes\"",
    "&quot;multipart-2&quot;",
    "W/&quot;weak&quot;",
)

private val contentsNoise = listOf(
    "",
    "<LastModified>2026-07-01T12:00:00.000Z</LastModified>",
    "<Size>409</Size>",
    "<StorageClass>STANDARD</StorageClass>",
    "<Owner><ID>abc123</ID><DisplayName>owner</DisplayName></Owner>",
)

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
)

private val documentHostiles = listOf("", "", "", "<!-- a comment -->", "<?pi data?>")

private val declarations = listOf(
    "",
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
    "<?xml version=\"1.0\"", // unterminated declaration: refused (agy's `<?` gate edge), oracle rejects
)

/** A full document: shuffled top-level items inside the root, optional declaration. */
private val documents: Arb<String> = Arb.bind(
    Arb.element(declarations),
    Arb.element(truncatedVocabulary),
    Arb.element(tokenVocabulary),
    Arb.list(blocks, 0..3),
    Arb.list(Arb.element(topLevelNoise), 0..3),
    Arb.element(documentHostiles),
    Arb.int(0..7),
    Arb.element(listOf("", "\n", "\n    ")),
) { declaration, truncated, token, blockList, noise, hostile, rotation, separator ->
    val items = (listOf(truncated, token, hostile) + blockList + noise).filter { it.isNotEmpty() }
    val rotated = if (items.isEmpty()) items else items.drop(rotation % items.size) + items.take(rotation % items.size)
    declaration +
        "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
        rotated.joinToString(separator, prefix = separator, postfix = separator) +
        "</ListBucketResult>"
}
