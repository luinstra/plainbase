package com.plainbase.domain.discussion

import com.plainbase.domain.page.Heading
import java.security.MessageDigest

class PageBytes private constructor(
    val raw: ByteArray,
    val hash: String,
    val page: ReanchorPage,
) {
    companion object {
        fun of(raw: ByteArray, headings: List<Heading>): PageBytes {
            val hash = "sha256:" + MessageDigest.getInstance("SHA-256").digest(raw).toHexString()
            return PageBytes(raw = raw, hash = hash, page = ReanchorPage.of(raw, headings))
        }
    }
}
