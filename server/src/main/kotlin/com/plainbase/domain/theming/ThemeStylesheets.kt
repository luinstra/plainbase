package com.plainbase.domain.theming

/** Blocking reads of complete theme definitions and the possibly empty fixed override. */
interface ThemeStylesheets {
    fun definition(mode: ThemeMode): ByteArray
    fun override(): ByteArray
}

/** Bundled-only, filesystem-free construction that never reads the process working directory. */
class BundledThemeStylesheets(private val builtins: ThemeBuiltins) : ThemeStylesheets {
    override fun definition(mode: ThemeMode): ByteArray = builtins.definition(mode)

    override fun override(): ByteArray = ByteArray(0)
}
