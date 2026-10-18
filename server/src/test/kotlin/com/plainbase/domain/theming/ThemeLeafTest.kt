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
        "yaml scheme" to "paper.yaml",
        "yml scheme" to "paper.yml",
        "theme directory stylesheet" to "themes/paper.css",
        "theme directory scheme" to "themes/dracula.yaml",
        "theme directory 64 character stem" to "themes/" + "a".repeat(64) + ".yaml",
        "custom scheme" to "custom.yaml",
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
        "theme directory parent traversal" to "themes/../a.css",
        "uppercase theme directory" to "Themes/a.css",
        "empty path segment" to "themes//a.css",
        "nested theme directory" to "themes/sub/a.css",
        "theme directory missing extension" to "themes/x",
        "different directory" to "theme/a.css",
        "absolute theme directory" to "/themes/a.css",
        "theme directory without filename" to "themes/",
        "uppercase scheme extension" to "paper.YAML",
        "scheme extra extension" to "paper.yaml.bak",
        "theme directory 65 character stem" to "themes/" + "a".repeat(65) + ".css",
    )
    rejected.forEach { (label, raw) ->
        test("rejects $label") {
            ThemeLeaf.of(raw) shouldBe null
        }
    }

    val overrideAliases = listOf(
        "custom.css", "Custom.css", "CUSTOM.css", "cUsToM.css", "CUSTOM.CSS",
        "themes/custom.css", "themes/Custom.css", "THEMES/CUSTOM.CSS",
    )
    overrideAliases.forEach { raw ->
        test("rejects override alias $raw") {
            ThemeLeaf.of(raw) shouldBe null
        }
    }
    overrideAliases.forEach { raw ->
        test("recognizes override alias $raw") {
            ThemeLeaf.namesOverride(raw) shouldBe true
        }
    }
    listOf("paper.css", "custom.min.css", "xcustom.css", "custom.yaml").forEach { raw ->
        test("does not recognize $raw as an override alias") {
            ThemeLeaf.namesOverride(raw) shouldBe false
        }
    }

    listOf(
        "paper.css" to ThemeFormat.CSS,
        "themes/paper.css" to ThemeFormat.CSS,
        "paper.yaml" to ThemeFormat.TINTED_SCHEME,
        "themes/paper.yaml" to ThemeFormat.TINTED_SCHEME,
        "paper.yml" to ThemeFormat.TINTED_SCHEME,
        "themes/paper.yml" to ThemeFormat.TINTED_SCHEME,
    ).forEach { (raw, expected) ->
        test("format follows the suffix of $raw") {
            ThemeLeaf.of(raw).shouldNotBeNull().format shouldBe expected
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
