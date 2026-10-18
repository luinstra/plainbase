package com.plainbase.domain.theming

const val THEMES_DIRECTORY = "themes"

enum class ThemeMode { LIGHT, DARK }

enum class ThemeFormat { CSS, TINTED_SCHEME }

/**
 * A theme filename whose `.css`, `.yaml` or `.yml` suffix excludes every DATA_DIR-owned name.
 * [THEMES_DIRECTORY] is the only directory a selection can name, exactly one level deep.
 */
@JvmInline
value class ThemeLeaf private constructor(val value: String) {
    val format: ThemeFormat get() = if (value.endsWith(".css")) ThemeFormat.CSS else ThemeFormat.TINTED_SCHEME

    override fun toString(): String = value

    companion object {
        private val LEAF = Regex("($THEMES_DIRECTORY/)?[A-Za-z0-9][A-Za-z0-9_-]{0,63}\\.(css|yaml|yml)")

        fun of(raw: String): ThemeLeaf? = raw.takeIf { !namesOverride(it) && LEAF.matches(it) }?.let(::ThemeLeaf)

        fun namesOverride(raw: String): Boolean =
            raw.equals("custom.css", ignoreCase = true) || raw.equals("$THEMES_DIRECTORY/custom.css", ignoreCase = true)
    }
}

/** A null leaf uses the bundled definition for that mode. */
data class ThemeSelection(val light: ThemeLeaf?, val dark: ThemeLeaf?) {
    fun leaf(mode: ThemeMode): ThemeLeaf? = when (mode) {
        ThemeMode.LIGHT -> light
        ThemeMode.DARK -> dark
    }

    companion object {
        val BUNDLED: ThemeSelection = ThemeSelection(null, null)
    }
}

class ThemeBuiltins(val light: ByteArray, val dark: ByteArray) {
    fun definition(mode: ThemeMode): ByteArray = when (mode) {
        ThemeMode.LIGHT -> light
        ThemeMode.DARK -> dark
    }
}
