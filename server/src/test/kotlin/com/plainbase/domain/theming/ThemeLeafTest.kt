package com.plainbase.domain.theming

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

class ThemeLeafTest : FunSpec({
    val accepted = listOf(
        "plain stem" to "paper.css",
        "single character stem" to "a.css",
        "64 character stem" to "a".repeat(64) + ".css",
        "mixed case and separators" to "Dracula_2-dark.css",
        "uppercase stem" to "PAPER.css",
    )
    accepted.forEach { (label, raw) ->
        test("accepts $label") {
            ThemeLeaf.of(raw).shouldNotBeNull().value shouldBe raw
        }
    }

    val rejected = listOf(
        "65 character stem" to "a".repeat(65) + ".css",
        "missing stem" to ".css",
        "leading hyphen" to "-a.css",
        "leading underscore" to "_a.css",
        "uppercase extension" to "paper.CSS",
        "uppercase override extension" to "CUSTOM.CSS",
        "extra dot" to "paper.min.css",
        "missing extension" to "paper",
        "extra extension" to "paper.css.bak",
        "parent traversal" to "../paper.css",
        "forward slash" to "a/b.css",
        "backslash" to "a\\b.css",
        "URL" to "https://x/a.css",
        "percent encoding" to "p%61per.css",
        "leading space" to " paper.css",
        "trailing newline" to "paper.css\n",
        "accented letter" to "päper.css",
        "full width digits" to "１２.css",
        "empty string" to "",
    )
    rejected.forEach { (label, raw) ->
        test("rejects $label") {
            ThemeLeaf.of(raw) shouldBe null
        }
    }

    listOf("custom.css", "Custom.css", "CUSTOM.css", "cUsToM.css").forEach { raw ->
        test("rejects override alias $raw") {
            ThemeLeaf.of(raw) shouldBe null
        }
    }
    listOf("custom.css", "Custom.css", "CUSTOM.css", "cUsToM.css", "CUSTOM.CSS").forEach { raw ->
        test("recognizes override alias $raw") {
            ThemeLeaf.namesOverride(raw) shouldBe true
        }
    }
    listOf("paper.css", "custom.min.css", "xcustom.css").forEach { raw ->
        test("does not recognize $raw as an override alias") {
            ThemeLeaf.namesOverride(raw) shouldBe false
        }
    }

    test("selection resolves each mode and bundled selections have no leaf") {
        val light = requireNotNull(ThemeLeaf.of("paper.css"))
        val dark = requireNotNull(ThemeLeaf.of("night.css"))
        val selection = ThemeSelection(light, dark)
        selection.leaf(ThemeMode.LIGHT) shouldBe light
        selection.leaf(ThemeMode.DARK) shouldBe dark
        ThemeMode.entries.forEach { ThemeSelection.BUNDLED.leaf(it) shouldBe null }
    }

    test("builtins resolve the complete definition for each mode") {
        val light = byteArrayOf(1, 2)
        val dark = byteArrayOf(3, 4)
        val builtins = ThemeBuiltins(light, dark)
        builtins.definition(ThemeMode.LIGHT) shouldBeSameInstanceAs light
        builtins.definition(ThemeMode.DARK) shouldBeSameInstanceAs dark
    }
})
