package com.plainbase.frameworks.objectstore

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

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
            ListResponseParser.Entry("notes/a.md", "\"fba9dede5f27731c9771645a39863328\""),
            ListResponseParser.Entry("notes/sp%20ace%20%26%20unicode%20%E3%82%AC.md", "\"9b2cf535f27731c9771645a39863328c-2\""),
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

    test("refusals: everything outside the frozen shape throws, never guesses") {
        // A minimal valid listing around [inner], so each case isolates ONE refusal.
        fun doc(inner: String) = "<ListBucketResult><IsTruncated>false</IsTruncated>$inner</ListBucketResult>"
        listOf(
            // markup a DOM parser reads differently than a literal scan
            doc("<!-- c -->") to "unsupported markup",
            doc("<Contents><Key><![CDATA[k]]></Key><ETag>\"x\"</ETag></Contents>") to "unsupported markup",
            "<!DOCTYPE x>${doc("")}" to "unsupported markup",
            doc("<?pi ?>") to "processing instruction",
            // a malformed/unterminated XML declaration must not slip past the `<?` gate,
            // even when a LATER `?>` (here a PI) could be mistaken for the declaration's close
            "<?xml version=\"1.0\"<ListBucketResult><IsTruncated>false</IsTruncated></ListBucketResult>" to "processing instruction",
            "<?xml version=\"1.0\"<ListBucketResult><?pi ?><IsTruncated>false</IsTruncated></ListBucketResult>" to "processing instruction",
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
