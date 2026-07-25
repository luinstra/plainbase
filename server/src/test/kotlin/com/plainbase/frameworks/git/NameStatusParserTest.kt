package com.plainbase.frameworks.git

import com.plainbase.domain.content.TreePath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.text.Normalizer

/**
 * The extracted pure parser behind [GitCliHistoryProvider.deletedIn] (C4), driven WITHOUT a git binary. Its whole
 * contract is fail-closed: a stream it cannot FULLY frame or convert nulls the entire diff, because "a diff we
 * half-understood is not a smaller diff". The real-git end-to-end arms live in `GitAbsenceOracleNativeTest`.
 */
class NameStatusParserTest : FunSpec({

    // A `git diff --name-status -z` stream: NUL-TERMINATED tokens, status then path, repeating. `Char(0)`
    // rather than a literal NUL in the source - one raw 0x00 byte makes git call this whole file binary.
    fun stream(vararg tokens: String) = tokens.joinToString("") { "$it${Char(0)}" }.toByteArray()

    test("a well-formed -z stream parses to (status, path) records - a rename is D old + A new under --no-renames") {
        val records = parseNameStatusRecords(stream("D", "notes/old.md", "A", "notes/new.md", "M", "guides/deploy.md"))
        records.shouldNotBeNull()
        records.map { it.status to it.path.value } shouldBe listOf(
            "D" to "notes/old.md",
            "A" to "notes/new.md",
            "M" to "guides/deploy.md",
        )
    }

    test("an empty stream is zero records, never null - a clean no-change diff") {
        parseNameStatusRecords(ByteArray(0)) shouldBe emptyList()
    }

    test("a truncated final record (no trailing NUL) fails CLOSED to null") {
        parseNameStatusRecords("D${Char(0)}notes/rollback.md".toByteArray()).shouldBeNull()
    }

    test("an odd-arity stream (a status with no path) fails CLOSED to null") {
        parseNameStatusRecords(stream("D", "notes/rollback.md", "A")).shouldBeNull()
    }

    test("an empty status token fails CLOSED to null") {
        parseNameStatusRecords(stream("", "notes/rollback.md")).shouldBeNull()
    }

    test("an unconvertible path (empty / absolute / traversal) fails CLOSED to null") {
        parseNameStatusRecords(stream("D", "")).shouldBeNull()
        parseNameStatusRecords(stream("D", "/etc/passwd")).shouldBeNull()
        parseNameStatusRecords(stream("D", "../escape.md")).shouldBeNull()
    }

    test("a path whose bytes are not UTF-8 fails CLOSED to null - never the lossy U+FFFD substitute") {
        // A git path is BYTES. 0xFF is not UTF-8, and a lenient decode would hand back "notes/�.md" - a path we
        // invented, which covers no binding and yet still lets the advance consume the deletion it misread.
        val malformed = "D${Char(0)}notes/".toByteArray() + byteArrayOf(0xFF.toByte()) + ".md${Char(0)}".toByteArray()
        parseNameStatusRecords(malformed).shouldBeNull()
    }

    test("an NFD-encoded path normalizes to its NFC TreePath - the same form the walk's per-segment construction makes") {
        val nfd = Normalizer.normalize("café/résumé.md", Normalizer.Form.NFD)
        val nfc = Normalizer.normalize("café/résumé.md", Normalizer.Form.NFC)
        val records = parseNameStatusRecords(stream("D", nfd))
        records.shouldNotBeNull()
        records.single().path shouldBe TreePath.require(nfc)
    }
})
