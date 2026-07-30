package com.plainbase.frameworks.objectstore

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Golden + refusal tests for the five-element ListObjectsV2 extractor. The response bodies here
 * are hand-authored in the documented S3/R2 response shape; the raw per-provider captures the
 * credentialed `s3-smoke` records (plan C0) join them as goldens once the live run happens.
 * The fail-closed grammar boundary itself is fuzzed against the JDK DOM oracle in
 * [ListResponseParserOracleTest].
 */
class ListResponseParserTest : FunSpec({

    test("golden: a one-page listing in the documented S3 shape") {
        val listing = ListResponseParser.parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <Name>examplebucket</Name>
                <Prefix>notes/</Prefix>
                <KeyCount>2</KeyCount>
                <MaxKeys>1000</MaxKeys>
                <EncodingType>url</EncodingType>
                <IsTruncated>false</IsTruncated>
                <Contents>
                    <Key>notes/a.md</Key>
                    <LastModified>2026-07-01T12:00:00.000Z</LastModified>
                    <ETag>&quot;fba9dede5f27731c9771645a39863328&quot;</ETag>
                    <Size>409</Size>
                    <StorageClass>STANDARD</StorageClass>
                </Contents>
                <Contents>
                    <Key>notes/sp%20ace%20%26%20unicode%20%E3%82%AC.md</Key>
                    <LastModified>2026-07-01T12:00:01.000Z</LastModified>
                    <ETag>&quot;9b2cf535f27731c9771645a39863328c-2&quot;</ETag>
                    <Size>1024</Size>
                    <StorageClass>STANDARD</StorageClass>
                </Contents>
            </ListBucketResult>
            """.trimIndent(),
        )
        listing.isTruncated shouldBe false
        listing.nextContinuationToken shouldBe null
        listing.contents shouldBe listOf(
            // Keys stay RAW/URL-encoded (encoding-type=url); etags keep their quotes (opaque).
            ListResponseParser.Entry("notes/a.md", "\"fba9dede5f27731c9771645a39863328\"", size = 409L),
            ListResponseParser.Entry(
                "notes/sp%20ace%20%26%20unicode%20%E3%82%AC.md",
                "\"9b2cf535f27731c9771645a39863328c-2\"",
                size = 1024L,
            ),
        )
    }

    test("golden: a truncated page carries its continuation token") {
        val listing = ListResponseParser.parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <IsTruncated>true</IsTruncated>
                <NextContinuationToken>1ueGcxLPRx1Tr/XYExHnhbYLgveDs2J/wm36Hy4vbOwM=</NextContinuationToken>
                <Contents>
                    <Key>a.md</Key>
                    <ETag>&quot;00000000000000000000000000000000&quot;</ETag>
                </Contents>
            </ListBucketResult>
            """.trimIndent(),
        )
        listing.isTruncated shouldBe true
        listing.nextContinuationToken shouldBe "1ueGcxLPRx1Tr/XYExHnhbYLgveDs2J/wm36Hy4vbOwM="
        listing.contents.map { it.key } shouldBe listOf("a.md")
    }

    test("an empty listing has no contents and no token") {
        val listing = ListResponseParser.parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <ListBucketResult><KeyCount>0</KeyCount><IsTruncated>false</IsTruncated></ListBucketResult>
            """.trimIndent(),
        )
        listing.contents shouldBe emptyList()
        listing.isTruncated shouldBe false
        listing.nextContinuationToken shouldBe null
    }

    test("entities and numeric character references decode in text nodes") {
        val listing = ListResponseParser.parse(
            "<ListBucketResult><IsTruncated>false</IsTruncated>" +
                "<Contents><Key>a&amp;b&lt;c&gt;d&apos;e&#65;&#x42;</Key><ETag>&quot;x&quot;</ETag></Contents>" +
                "</ListBucketResult>",
        )
        listing.contents.single().key shouldBe "a&b<c>d'eAB"
    }

    test("a token inside a Contents block never masquerades as pagination state") {
        val listing = ListResponseParser.parse(
            "<ListBucketResult><IsTruncated>false</IsTruncated>" +
                "<Contents><Key>a.md</Key><ETag>\"x\"</ETag><NextContinuationToken>fake</NextContinuationToken></Contents>" +
                "</ListBucketResult>",
        )
        listing.nextContinuationToken shouldBe null
    }

    test("a present <Size> must be a plain non-negative integer; absent stays null, malformed refuses") {
        fun sizeDoc(size: String) = "<ListBucketResult><IsTruncated>false</IsTruncated>" +
            "<Contents><Key>a.md</Key><ETag>\"x\"</ETag><Size>$size</Size></Contents></ListBucketResult>"
        listOf(
            "-1", // signed
            "+1", // signed
            " 12", // padded
            "1e3", // not a plain integer
            "", // empty element
            "99999999999999999999", // overflows Long
        ).forEach { size ->
            shouldThrow<ObjectStoreException> { ListResponseParser.parse(sizeDoc(size)) }
                .message.orEmpty() shouldContain "<Size>"
        }
        ListResponseParser.parse(sizeDoc("0")).contents.single().size shouldBe 0L
        // Absent <Size> is null - a declared value is never invented (the packer treats null conservatively).
        ListResponseParser.parse(
            "<ListBucketResult><IsTruncated>false</IsTruncated>" +
                "<Contents><Key>a.md</Key><ETag>\"x\"</ETag></Contents></ListBucketResult>",
        ).contents.single().size shouldBe null
    }

    test("a leading UTF-8 BOM is REFUSED, matching the StringReader DOM oracle (P2)") {
        // The oracle parses already-decoded chars, so a literal U+FEFF is content-before-root it rejects;
        // real S3/R2 responses never carry a BOM, so refusing is fail-closed and accept-implies-oracle-agrees.
        shouldThrow<ObjectStoreException> {
            ListResponseParser.parse(
                "\uFEFF" + "<ListBucketResult><IsTruncated>false</IsTruncated>" +
                    "<Contents><Key>a.md</Key><ETag>\"x\"</ETag></Contents></ListBucketResult>",
            )
        }.message.orEmpty() shouldContain "character data outside the root element"
    }

    test("numeric character references are strict ASCII CharRefs; malformed forms refuse (P1)") {
        // Kotlin toIntOrNull(radix) would accept these, but the DOM oracle rejects every one.
        fun keyDoc(key: String) = "<ListBucketResult><IsTruncated>false</IsTruncated>" +
            "<Contents><Key>$key</Key><ETag>\"x\"</ETag></Contents></ListBucketResult>"
        listOf(
            "&#X41;", // uppercase X marker
            "&#+65;", // sign in decimal
            "&#x+41;", // sign in hex
            "&#\u0666\u0665;", // Arabic-Indic digits
            "&#x\uFF14\uFF11;", // fullwidth digits
            "&#;", // empty decimal
            "&#x;", // empty hex
            "&#99999999999;", // out of Int range
        ).forEach { ref ->
            shouldThrow<ObjectStoreException> { ListResponseParser.parse(keyDoc(ref)) }
                .message.orEmpty() shouldContain "entity"
        }
        // ...and the well-formed forms still decode, incl. an explicit CR reference (NOT line-normalized).
        ListResponseParser.parse(keyDoc("&#65;&#x42;&#xD;")).contents.single().key shouldBe "AB\r"
    }

    test("literal CR / CRLF in accepted text is normalized to LF, matching the DOM parser (P3)") {
        val listing = ListResponseParser.parse(
            "<ListBucketResult><IsTruncated>false</IsTruncated>" +
                "<Contents><Key>a\rb</Key><ETag>\"x\r\ny\"</ETag></Contents></ListBucketResult>",
        )
        listing.contents.single().key shouldBe "a\nb" // lone CR -> LF
        listing.contents.single().etag shouldBe "\"x\ny\"" // CRLF -> LF
    }

    test("an untrusted value in a refusal message is truncated to a sane cap (M4)") {
        val hugeEntity = "z".repeat(200)
        val message = shouldThrow<ObjectStoreException> {
            ListResponseParser.parse(
                "<ListBucketResult><IsTruncated>false</IsTruncated>" +
                    "<Contents><Key>a&$hugeEntity;b</Key><ETag>\"x\"</ETag></Contents></ListBucketResult>",
            )
        }.message.orEmpty()
        message shouldContain "unknown entity"
        message shouldContain ("z".repeat(80) + "...") // capped at 80 then elided
        message shouldNotContain "z".repeat(81) // the full 200-char value never reaches the log
    }

    test("valid XML declaration variants (single-quoted, with standalone) are accepted") {
        listOf(
            "<?xml version=\"1.0\"?>",
            "<?xml version='1.0'?>",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
            "<?xml version=\"1.0\" encoding='UTF-8' standalone=\"yes\"?>",
            "<?xml version=\"1.0\" standalone=\"no\"?>",
        ).forEach { declaration ->
            val listing = ListResponseParser.parse(
                "$declaration<ListBucketResult><IsTruncated>false</IsTruncated></ListBucketResult>",
            )
            listing.isTruncated shouldBe false
            listing.contents shouldBe emptyList()
        }
    }

    test("a non-ASCII-named element refuses (the ASCII XML Name restriction, edition-independent)") {
        // isName is restricted to the ASCII XML Name subset, so a Unicode-letter tag name (whose legality
        // depends on the JDK parser's unpinned XML edition) is refused outright - fail-closed, one-directional
        // -safe (over-refusing a name real S3/R2 never send). A direct golden, since over-refusal is invariant
        // -safe and so would NOT fail the one-directional oracle fuzz.
        listOf("ªbar", "中bar").forEach { name ->
            shouldThrow<ObjectStoreException> {
                ListResponseParser.parse(
                    "<ListBucketResult><IsTruncated>false</IsTruncated><$name>x</$name></ListBucketResult>",
                )
            }.message.orEmpty() shouldContain "malformed XML name"
        }
    }

    test("a Contents block with a dangling sibling tag is refused, not read as a bogus entry") {
        // The extraction reads only <Key>/<ETag> and would ignore the unbalanced <Bar>, returning
        // Entry(key=a, etag=x) - but the JDK DOM oracle rejects the document, so accept-implies-oracle
        // -agrees demands a refusal. The well-formedness check catches the leftover open tag.
        val message = shouldThrow<ObjectStoreException> {
            ListResponseParser.parse(
                "<ListBucketResult><Contents><Key>a</Key><ETag>\"x\"</ETag><Bar></Contents></Bar>" +
                    "<IsTruncated>false</IsTruncated></ListBucketResult>",
            )
        }.message.orEmpty()
        message shouldContain "Bar"
    }

    test("refusals: everything outside the frozen shape throws, never guesses") {
        // A minimal valid listing around [inner], so each case isolates ONE refusal.
        fun doc(inner: String) = "<ListBucketResult><IsTruncated>false</IsTruncated>$inner</ListBucketResult>"

        // A minimal valid listing PREFIXED by [decl], for the XML-declaration refusal cases.
        fun withDecl(decl: String) = "$decl<ListBucketResult><IsTruncated>false</IsTruncated></ListBucketResult>"
        listOf(
            // markup a DOM parser reads differently than a literal scan
            doc("<!-- c -->") to "unsupported markup",
            doc("<Contents><Key><![CDATA[k]]></Key><ETag>\"x\"</ETag></Contents>") to "unsupported markup",
            "<!DOCTYPE x>${doc("")}" to "unsupported markup",
            doc("<?pi ?>") to "processing instruction",
            // a malformed/unterminated XML declaration must not slip past the `<?` gate, even when a LATER
            // `?>` (here a PI) could be mistaken for the declaration's close - refused as a malformed decl
            withDecl("<?xml version=\"1.0\"") to "malformed XML declaration",
            "<?xml version=\"1.0\"<ListBucketResult><?pi ?><IsTruncated>false</IsTruncated>" +
                "</ListBucketResult>" to "malformed XML declaration",
            // a TERMINATED-but-internally-malformed declaration is validated against XMLDecl and refused,
            // not accepted-and-stripped (the loose regex's bug): the DOM oracle rejects each of these too
            withDecl("<?xml version=1.0?>") to "malformed XML declaration", // unquoted version
            withDecl("<?xml encoding=\"UTF-8\"?>") to "malformed XML declaration", // missing version
            withDecl("<?xml version=\"1.0\" standalone=\"yes\" encoding=\"UTF-8\"?>") to "malformed XML declaration", // wrong order
            // structural refusals
            doc("<Contents><Key>a</Key><ETag>\"x\"</ETag>") to "unclosed <Contents>",
            doc("<Contents Foo=\"1\"><Key>a</Key><ETag>\"x\"</ETag></Contents>") to "unsupported form of <Contents>",
            doc("<Contents><ETag>\"x\"</ETag></Contents>") to "missing <Key>",
            doc("<Contents><Key>a</Key></Contents>") to "missing <ETag>",
            doc("<Contents><Key>a</Key><Key>b</Key><ETag>\"x\"</ETag></Contents>") to "duplicate <Key>",
            "<ListBucketResult></ListBucketResult>" to "missing <IsTruncated>",
            "<ListBucketResult><IsTruncated>TRUE</IsTruncated></ListBucketResult>" to "unexpected <IsTruncated> value",
            doc("<NextContinuationToken/>") to "unsupported form of <NextContinuationToken>",
            // truncated-without-token: refuse rather than let a paging loop refetch page 1 forever (missing token)
            "<ListBucketResult><IsTruncated>true</IsTruncated></ListBucketResult>" to "truncated page carries no continuation token",
            // ...and the blank-token variant (present tag, empty text) is the same fail-closed class
            "<ListBucketResult><IsTruncated>true</IsTruncated><NextContinuationToken></NextContinuationToken></ListBucketResult>"
                to "truncated page carries no continuation token",
            // text-node refusals
            doc("<Contents><Key>a&b</Key><ETag>\"x\"</ETag></Contents>") to "bare '&'",
            doc("<Contents><Key>a&nbsp;b</Key><ETag>\"x\"</ETag></Contents>") to "unknown entity",
            // char references outside XML 1.0's legal Char production: surrogate AND illegal-control
            doc("<Contents><Key>a&#xD800;</Key><ETag>\"x\"</ETag></Contents>") to "not a legal XML character",
            doc("<Contents><Key>a&#x01;</Key><ETag>\"x\"</ETag></Contents>") to "not a legal XML character",
            doc("<Contents><Key>a&#x0B;</Key><ETag>\"x\"</ETag></Contents>") to "not a legal XML character",
        ).forEach { (xml, reason) ->
            shouldThrow<ObjectStoreException> { ListResponseParser.parse(xml) }.message.orEmpty() shouldContain reason
        }
    }
})
