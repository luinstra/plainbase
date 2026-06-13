@file:OptIn(ExperimentalUuidApi::class)

package com.plainbase.frameworks.sqldelight

import com.plainbase.domain.page.PageId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.nio.ByteBuffer
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * ID storage policy (decision log #6) at the conversion point: a [PageId] encodes to exactly its 16
 * raw bytes, `msb||lsb` big-endian, and decoding is the identity inverse. The chunk 4b acceptance
 * criterion's round-trip + byte-layout proof.
 */
class PageIdColumnAdapterTest : FunSpec({

    test("a known UUID encodes to exactly its msb||lsb big-endian 16 bytes") {
        val id = PageId.require("00112233-4455-6677-8899-aabbccddeeff")
        PageIdColumnAdapter.encode(id) shouldBe byteArrayOf(
            0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
            0x88.toByte(), 0x99.toByte(), 0xaa.toByte(), 0xbb.toByte(),
            0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte(),
        )
    }

    test("encode equals msb||lsb for arbitrary UUIDs and decode(encode) is the identity") {
        checkAll(Arb.long(), Arb.long()) { msb, lsb ->
            val id = PageId.of(Uuid.fromLongs(msb, lsb))
            val bytes = PageIdColumnAdapter.encode(id)
            bytes shouldBe ByteBuffer.allocate(16).putLong(msb).putLong(lsb).array()
            PageIdColumnAdapter.decode(bytes) shouldBe id
        }
    }
})
