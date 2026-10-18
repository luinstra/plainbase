package com.plainbase.frameworks.config

import com.plainbase.domain.theming.ThemeSelection

data class ThemeConfig(
    val selection: ThemeSelection = ThemeSelection.BUNDLED,
    val rejections: List<ThemeKeyRejection> = emptyList(),
)

data class ThemeKeyRejection(
    val key: String,
    val reason: ThemeRejectionReason,
    val valueType: String? = null,
)

enum class ThemeRejectionReason { NOT_A_BLOCK, NULL, NOT_A_STRING, BLANK, NOT_A_LEAF, OVERRIDE_NAME }
