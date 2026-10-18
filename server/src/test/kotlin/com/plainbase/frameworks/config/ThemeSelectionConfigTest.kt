package com.plainbase.frameworks.config

import com.plainbase.domain.theming.ThemeLeaf
import com.plainbase.domain.theming.ThemeSelection
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.filesystem.FOLDER_META_NAME
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files

class ThemeSelectionConfigTest : FunSpec({
    fun <T> withDataDir(
        conf: String? = null,
        managed: String? = null,
        env: Map<String, String> = emptyMap(),
        block: (Map<String, String>) -> T,
    ): T {
        val data = Files.createTempDirectory("pb-theme-config")
        try {
            if (conf != null) Files.writeString(data.resolve("plainbase.conf"), conf)
            if (managed != null) Files.writeString(data.resolve("roots.conf"), managed)
            return block(env + ("DATA_DIR" to data.toString()))
        } finally {
            Files.walk(data).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    fun loadTheme(
        conf: String? = null,
        managed: String? = null,
        env: Map<String, String> = emptyMap(),
    ): PlainbaseConfig = withDataDir(conf, managed, env) { ConfigLoader.fromEnvAndFile(it) }

    fun assertTheme(
        actual: PlainbaseConfig,
        selection: ThemeSelection,
        rejections: List<ThemeKeyRejection> = emptyList(),
        warnings: List<String> = emptyList(),
    ) {
        assertSoftly {
            actual.theme.selection shouldBe selection
            actual.theme.rejections shouldBe rejections
            ConfigValuePolicy.themeWarnings(actual) shouldBe warnings
        }
    }

    val paper = requireNotNull(ThemeLeaf.of("paper.css"))
    val night = requireNotNull(ThemeLeaf.of("night.css"))
    val selected = ThemeSelection(paper, night)
    val invalidLeafReason =
        "is not a DATA_DIR theme name: optionally themes/, then 1 to 64 ASCII letters, digits, '_' or '-' " +
            "starting with a letter or digit, then .css, .yaml or .yml"

    test("block form selects both definitions") {
        assertTheme(loadTheme("""theme { light = "paper.css", dark = "night.css" }"""), selected)
    }
    test("dotted form equals block form") {
        val dotted = loadTheme(
            """theme.light = "paper.css"
                |theme.dark = "night.css"
            """.trimMargin(),
        )
        assertTheme(dotted, selected)
        dotted.theme shouldBe loadTheme("""theme { light = "paper.css", dark = "night.css" }""").theme
    }
    test("light alone leaves dark bundled") {
        assertTheme(loadTheme("""theme.light = "paper.css""""), ThemeSelection(paper, null))
    }
    test("dark alone leaves light bundled") {
        assertTheme(loadTheme("""theme.dark = "night.css""""), ThemeSelection(null, night))
    }
    test("an unquoted leaf parses as a string and selects light") {
        assertTheme(loadTheme("theme.light = paper.css"), ThemeSelection(paper, null))
    }
    test("both modes can select the same leaf") {
        assertTheme(loadTheme("""theme { light = "paper.css", dark = "paper.css" }"""), ThemeSelection(paper, paper))
    }

    test("an absent theme is silently bundled") {
        assertTheme(loadTheme(), ThemeSelection.BUNDLED)
    }
    test("an empty theme block is silently bundled") {
        assertTheme(loadTheme("theme {}"), ThemeSelection.BUNDLED)
    }
    test("an unknown theme sibling is silently ignored") {
        assertTheme(loadTheme("theme.extra = 1"), ThemeSelection.BUNDLED)
    }

    listOf("light", "dark").forEach { mode ->
        test("null $mode is rejected without affecting the other mode") {
            assertTheme(
                loadTheme("theme.$mode = null"),
                ThemeSelection.BUNDLED,
                listOf(ThemeKeyRejection("theme.$mode", ThemeRejectionReason.NULL)),
                listOf("theme.$mode is null (remove the key to use the bundled theme); serving the bundled $mode theme"),
            )
        }
    }

    listOf("NUMBER" to "5", "BOOLEAN" to "true", "LIST" to "[paper.css]", "OBJECT" to "{ value = paper.css }")
        .forEach { (type, value) ->
            test("$type light is rejected without string coercion") {
                assertTheme(
                    loadTheme("theme.light = $value"),
                    ThemeSelection.BUNDLED,
                    listOf(ThemeKeyRejection("theme.light", ThemeRejectionReason.NOT_A_STRING, type)),
                    listOf("theme.light is not a string (found $type); serving the bundled light theme"),
                )
            }
        }
    listOf("empty" to "", "spaces" to "  ").forEach { (label, raw) ->
        test("$label light is rejected as blank") {
            assertTheme(
                loadTheme("theme.light = ${hoconString(raw)}"),
                ThemeSelection.BUNDLED,
                listOf(ThemeKeyRejection("theme.light", ThemeRejectionReason.BLANK)),
                listOf("theme.light is blank; serving the bundled light theme"),
            )
        }
    }

    val grammarRejects = listOf(
        "65 character stem" to "a".repeat(65) + ".css",
        "missing stem" to ".css",
        "leading hyphen" to "-a.css",
        "leading underscore" to "_a.css",
        "uppercase extension" to "paper.CSS",
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
    )
    grammarRejects.forEach { (label, raw) ->
        test("$label light is rejected as an invalid leaf") {
            assertTheme(
                loadTheme("theme.light = ${hoconString(raw)}"),
                ThemeSelection.BUNDLED,
                listOf(ThemeKeyRejection("theme.light", ThemeRejectionReason.NOT_A_LEAF)),
                listOf("theme.light $invalidLeafReason; serving the bundled light theme"),
            )
        }
    }
    test("invalid dark is rejected without affecting the other mode") {
        assertTheme(
            loadTheme("""theme.dark = "../paper.css""""),
            ThemeSelection.BUNDLED,
            listOf(ThemeKeyRejection("theme.dark", ThemeRejectionReason.NOT_A_LEAF)),
            listOf("theme.dark $invalidLeafReason; serving the bundled dark theme"),
        )
    }

    listOf(
        "custom.css", "Custom.css", "CUSTOM.css", "cUsToM.css", "CUSTOM.CSS",
        "themes/custom.css", "themes/Custom.css", "THEMES/CUSTOM.CSS",
    ).forEach { raw ->
        test("override alias $raw is rejected before the grammar check") {
            assertTheme(
                loadTheme("theme.light = ${hoconString(raw)}"),
                ThemeSelection.BUNDLED,
                listOf(ThemeKeyRejection("theme.light", ThemeRejectionReason.OVERRIDE_NAME)),
                listOf(
                    "theme.light uses a name reserved for the fixed DATA_DIR/custom.css override and cannot be selected; " +
                        "serving the bundled light theme",
                ),
            )
        }
    }

    test("DATA_DIR-owned names cannot select a theme at either allowed location") {
        val config = loadTheme()
        val ownedNames = listOf(
            PlainbaseConfig.MANAGED_ROOTS_FILE,
            DataDirLock.LOCK_FILE_NAME,
            DataDirLock.ROOTS_LOCK_FILE_NAME,
            FOLDER_META_NAME,
            config.appDatabasePath.fileName.toString(),
            config.searchDatabasePath.fileName.toString(),
            config.discussionDatabasePath.fileName.toString(),
            "plainbase.conf", "mirror", "mirror-state", "git-home", "tmp", "restore-pending",
        )
        assertSoftly {
            ownedNames.forEach { name ->
                withClue(name) {
                    ThemeLeaf.of(name) shouldBe null
                    ThemeLeaf.of("themes/$name") shouldBe null
                }
            }
        }
    }

    test("a null parent rejects the block once") {
        assertTheme(
            loadTheme("theme = null"),
            ThemeSelection.BUNDLED,
            listOf(ThemeKeyRejection("theme", ThemeRejectionReason.NULL)),
            listOf("theme is null (remove the key to use the bundled theme); serving the bundled light and dark themes"),
        )
    }
    test("a string parent rejects the block once") {
        assertTheme(
            loadTheme("""theme = "paper.css""""),
            ThemeSelection.BUNDLED,
            listOf(ThemeKeyRejection("theme", ThemeRejectionReason.NOT_A_BLOCK, "STRING")),
            listOf(
                "theme must be a block such as theme { light = \"<name>.css\" } (found STRING); " +
                    "serving the bundled light and dark themes",
            ),
        )
    }
    test("an invalid light preserves a selected dark") {
        assertTheme(
            loadTheme("""theme { light = "../paper.css", dark = "paper.css" }"""),
            ThemeSelection(null, paper),
            listOf(ThemeKeyRejection("theme.light", ThemeRejectionReason.NOT_A_LEAF)),
            listOf("theme.light $invalidLeafReason; serving the bundled light theme"),
        )
    }

    test("environment aliases never select a definition") {
        assertTheme(
            loadTheme(env = mapOf("THEME_LIGHT" to "paper.css", "PLAINBASE_THEME_LIGHT" to "paper.css")),
            ThemeSelection.BUNDLED,
        )
    }
    test("managed roots cannot select a definition") {
        assertTheme(
            loadTheme(
                managed = """roots {}
                    |theme.light = "paper.css"
                """.trimMargin(),
            ),
            ThemeSelection.BUNDLED,
        )
    }

    listOf(
        "absolute path" to "/abs/zz-sentinel-a1.css",
        "relative path" to "../zz-sentinel-b2.css",
        "URL" to "https://zz-sentinel-c3/a.css",
    ).forEach { (label, raw) ->
        test("a rejected $label is absent from rejection facts and warnings") {
            val config = loadTheme("theme.light = ${hoconString(raw)}")
            assertSoftly {
                config.theme.rejections.size shouldBe 1
                ConfigValuePolicy.themeWarnings(config).size shouldBe 1
                config.theme.rejections.toString() shouldNotContain "zz-sentinel"
                ConfigValuePolicy.themeWarnings(config).forEach { it shouldNotContain "zz-sentinel" }
            }
        }
    }

    test("the command funnel accepts rejected theme values without an error line") {
        withDataDir("""theme { light = "../paper.css", dark = 5 }""") { env ->
            val errors = mutableListOf<String>()
            val config = ConfigLoader.loadForCommand("serve", errors::add, resolve = { ConfigLoader.fromEnvAndFile(env) })
            assertSoftly {
                config.shouldNotBeNull()
                errors shouldBe emptyList()
            }
        }
    }

    test("the command funnel still refuses whole-file syntax errors") {
        withDataDir("theme { light = ") { env ->
            val errors = mutableListOf<String>()
            val config = ConfigLoader.loadForCommand("serve", errors::add, resolve = { ConfigLoader.fromEnvAndFile(env) })
            assertSoftly {
                config shouldBe null
                errors.size shouldBe 1
                errors.all { it.startsWith("serve:") } shouldBe true
            }
        }
    }

    test("the command funnel still refuses unresolved theme substitutions") {
        withDataDir("theme { light = \${zz_missing_theme}, dark = \"paper.css\" }") { env ->
            val errors = mutableListOf<String>()
            val config = ConfigLoader.loadForCommand("serve", errors::add, resolve = { ConfigLoader.fromEnvAndFile(env) })
            assertSoftly {
                config shouldBe null
                errors.size shouldBe 1
                errors.all { it.startsWith("serve:") } shouldBe true
            }
        }
    }
})

private fun hoconString(raw: String): String =
    "\"${raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
