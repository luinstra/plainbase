package com.plainbase.frameworks.protocol

import com.plainbase.domain.service.DiscussionRefusal

/** A transport-neutral read refusal retaining authorized root retry candidates. */
class DiscussionReadRefused(val refusal: DiscussionRefusal) : RuntimeException(refusal.code) {
    constructor(status: Int, code: String) : this(DiscussionRefusal(status, code))

    val status: Int get() = refusal.status
    val code: String get() = refusal.code
}
