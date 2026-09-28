package com.plainbase.domain.service

import com.plainbase.domain.root.RootName

class DiscussionReadFailed(val root: RootName, val reason: String, cause: Throwable? = null) :
    RuntimeException("discussion read failed for root ${root.value}: $reason", cause)
