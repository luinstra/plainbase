package com.plainbase.domain.service

import com.plainbase.domain.content.ContentPathPolicy
import com.plainbase.domain.root.RootName

internal fun allowAllNativePolicies(roots: Iterable<RootName> = listOf(RootName.PRIMARY)): Map<RootName, ContentPathPolicy> =
    roots.toSet().associateWith { ContentPathPolicy.ALL }
