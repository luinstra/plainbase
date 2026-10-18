package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.ktor.routes.bundleResourceBytes
import org.junit.jupiter.api.Tag
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Native charset coverage for the hash of the shell's inline bootstrap body. */
@Tag("native")
class ShellScriptHashNativeTest {

    @Test
    fun embeddedBootstrapHashMatchesBodyBytes() {
        val bytes = requireNotNull(bundleResourceBytes("index.html"))
        val shell = requireNotNull(bundledShellHtml())
        val opening = "<script>".toByteArray(Charsets.US_ASCII)
        val closing = "</script>".toByteArray(Charsets.US_ASCII)
        val start = bytes.indexOfSequence(opening) + opening.size
        val end = bytes.indexOfSequence(closing, start)
        val body = bytes.copyOfRange(start, end)
        val script = Regex("<script(?<attrs>[^>]*)>(?<body>.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .findAll(shell)
            .single { it.groups["attrs"]?.value == "" }

        assertContentEquals(body, requireNotNull(script.groups["body"]).value.toByteArray(Charsets.UTF_8))
        assertEquals(scriptHash(body), inlineScriptHash(shell))
    }

    @Test
    fun nonAsciiBootstrapHashUsesUtf8Bytes() {
        val shell = "<script>\"\u00e9\u20ac\uD83D\uDE00\";</script>"
        val body = byteArrayOf(
            0x22,
            0xC3.toByte(), 0xA9.toByte(),
            0xE2.toByte(), 0x82.toByte(), 0xAC.toByte(),
            0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte(),
            0x22, 0x3B,
        )

        assertEquals(scriptHash(body), inlineScriptHash(shell))
    }

    private fun ByteArray.indexOfSequence(needle: ByteArray, start: Int = 0): Int =
        (start..size - needle.size).first { offset -> needle.indices.all { index -> this[offset + index] == needle[index] } }

    private fun scriptHash(body: ByteArray): String =
        "sha256-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body))
}
