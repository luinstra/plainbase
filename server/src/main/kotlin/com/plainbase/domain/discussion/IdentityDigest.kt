package com.plainbase.domain.discussion

import com.plainbase.domain.principal.SubjectKey
import java.nio.ByteBuffer
import java.security.MessageDigest

object IdentityDigest {
    fun of(key: SubjectKey): String {
        val issuer = key.issuer.encodeToByteArray()
        val id = key.id.encodeToByteArray()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(issuer.size).array())
        digest.update(issuer)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(id.size).array())
        digest.update(id)
        return "sha256:" + digest.digest().toHexString()
    }
}
