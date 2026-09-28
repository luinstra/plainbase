package com.plainbase.domain.discussion

class DiscussionPersistenceFailure(cause: Throwable) : RuntimeException("discussion persistence failed", cause)
