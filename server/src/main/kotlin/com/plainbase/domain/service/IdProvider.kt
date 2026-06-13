package com.plainbase.domain.service

import com.plainbase.domain.page.PageId

/**
 * Mints fresh, unique page identities. Isolating id generation behind this port keeps the generator
 * swappable and keeps tests free of clock/RNG mocking: the production impl ([UuidV7IdProvider])
 * delegates to the stdlib UUIDv7 generator, while tests inject a deterministic fake that hands back
 * known ids.
 */
fun interface IdProvider {

    /** A freshly minted [PageId]. Successive calls return distinct ids. */
    fun next(): PageId
}
