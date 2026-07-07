package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.TreePath
import com.plainbase.frameworks.filesystem.IgnoreRules
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `S3WireKey.decode` -> NFC -> [TreePath] funnel (charset/Unicode divergence surface) over the SP1
 * hostile-key corpus: `%2F`, `%20`, UTF-8 %-bytes, NFD names, dot-prefix, and the sandbox-escape
 * forms (`..\x`, drive-letter). Exercises the REAL [MirrorKeyFunnel] (the single source of truth
 * [ObjectContentStore] uses), NOT a private reproduction, so real-funnel drift fails natively too.
 * kotlin.test + `@Tag("native")` only.
 */
@Tag("native")
class S3WireKeyTreePathTest {

    private val ignoreRules = IgnoreRules()
    private val mirrorRoot: Path = Files.createTempDirectory("pb-native-funnel-mirror")

    /** The REAL funnel decision (charset-decoded key -> eligible TreePath or null). */
    private fun eligible(rawRelative: String): TreePath? = MirrorKeyFunnel.eligible(rawRelative, mirrorRoot, ignoreRules)

    @Test
    fun `an encoded slash and a space decode to the raw path and parse as a valid TreePath`() {
        val decoded = S3WireKey.decode("guides%2Fdeploy%20guide.md")
        assertEquals("guides/deploy guide.md", decoded)
        assertEquals(TreePath.require("guides/deploy guide.md"), eligible(decoded))
    }

    @Test
    fun `the SP1 hostile-key corpus (ampersand, dollar, plus, UTF-8 percent-bytes) decodes and parses`() {
        val cases = mapOf(
            "a%26b.md" to "a&b.md",
            "a%24b.md" to "a\$b.md",
            "a%2Bb.md" to "a+b.md",
            "%E3%82%AC.md" to "ガ.md", // UTF-8 %-bytes (a Katakana character)
        )
        for ((wire, raw) in cases) {
            val decoded = S3WireKey.decode(wire)
            assertEquals(raw, decoded)
            assertEquals(TreePath.of(raw), eligible(decoded))
        }
    }

    @Test
    fun `never a plus-for-space - a literal plus decodes to a literal plus, never a space`() {
        assertEquals("a+b.md", S3WireKey.decode("a+b.md"))
    }

    @Test
    fun `an NFD-named key decodes raw, then TreePath NFC-normalizes it (P4 raw-name preservation upstream)`() {
        val nfd = "café.md" // 'e' + combining acute accent (U+0301)
        val decoded = S3WireKey.decode(nfd) // no percent-escapes here - decode is a pass-through
        assertEquals(nfd, decoded)
        assertEquals("caf\u00e9.md", eligible(decoded)?.value) // NFC form (precomposed U+00E9)
    }

    @Test
    fun `a dot-prefixed segment is ineligible - skipped by the funnel, never admitted`() {
        assertNull(eligible(".git/HEAD"))
        assertNull(eligible("docs/.hidden.md"))
        assertNull(eligible(".plainbase/history-bundle.tar"))
    }

    @Test
    fun `a key that does not parse as a valid TreePath (traversal, empty segment) is ineligible`() {
        assertNull(eligible("../escape.md"))
        assertNull(eligible("a//b.md"))
        assertNull(eligible(""))
    }

    @Test
    fun `a sandbox-escape key (backslash, drive-letter, traversal) is rejected on ANY platform`() {
        // These pass the shared POSIX TreePath gate yet escape the mirror on a Windows host - the funnel's
        // escapesRoot guard rejects them regardless of OS, so the test is meaningful on this box.
        for (hostile in listOf("..\\evil.md", "C:/evil.md", "../../evil.md", "sub\\..\\evil.md")) {
            assertTrue(MirrorKeyFunnel.escapesRoot(hostile, mirrorRoot), "escapesRoot must flag '$hostile'")
            assertNull(eligible(hostile), "funnel must reject '$hostile'")
        }
        // A legit key does NOT trip the escape guard.
        assertFalse(MirrorKeyFunnel.escapesRoot("guides/intro.md", mirrorRoot))
        assertEquals(TreePath.require("guides/intro.md"), eligible("guides/intro.md"))
    }

    @Test
    fun `a malformed percent-escape throws ObjectStoreException rather than admitting a guess`() {
        var threw = false
        try {
            S3WireKey.decode("bad%2.md")
        } catch (_: ObjectStoreException) {
            threw = true
        }
        assertTrue(threw, "a malformed escape must refuse, never decode to a guess")
    }
}
