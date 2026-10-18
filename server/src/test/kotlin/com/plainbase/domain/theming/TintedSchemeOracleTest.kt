package com.plainbase.domain.theming

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readBytes
import kotlin.io.path.readText

class TintedSchemeOracleTest : FunSpec({
    val oracle = SchemeOracle()

    test("the scheme oracle record matches its sources") { oracle.identity().assertEmpty() }
    test("every pinned corpus scheme converts like the Tinted template") { oracle.corpus().assertEmpty() }
    test("hand-written edge cases match the builder and their expected verdict") { oracle.handCases().assertEmpty() }
    test("generated edge cases never accept what the builder rejects") { oracle.generated().assertEmpty() }
    test("the rule groups partition the rules and the hand cases name every rule") { oracle.partition().assertEmpty() }
    test("every builder-accepted rejection is a documented witness of its rule class") { oracle.witnesses().assertEmpty() }
})

private fun List<String>.assertEmpty() {
    withClue(joinToString("\n")) { shouldBeEmpty() }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
private fun JsonObject.objects(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }
private fun JsonElement.strings(): List<String> = jsonArray.map { it.jsonPrimitive.content }
private fun JsonObject.flag(key: String): Boolean = get(key)?.jsonPrimitive?.boolean == true

private class Comparisons {
    val differences = mutableListOf<String>()

    fun equal(id: String, mode: ThemeMode?, field: String, expected: Any?, actual: Any?) {
        if (expected != actual) {
            differences += "$id, ${mode?.name ?: "ALL"}, $field, " +
                "expected=${JsonPrimitive(expected.toString())}, actual=${JsonPrimitive(actual.toString())}"
        }
    }

    fun minimum(field: String, expected: Int, actual: Int) {
        equal("generated", null, field, "at least $expected", if (actual >= expected) "at least $expected" else actual)
    }
}

private data class SchemeInput(val id: String, val text: String, val record: JsonObject)

private class Observation(text: String) {
    val modes = ThemeMode.entries.associateWith { TintedSchemes.convert(text, it) }
    val failure = modes.values.filterIsInstance<TintedConversion.Rejected>()
        .firstOrNull { it.failure.rule != TintedRule.VARIANT_MISMATCH }?.failure
}

private class SchemeOracle {
    private val cases = resource("scheme-oracle-cases.json")
    private val record = resource("scheme-oracle.json")
    private val root = Path.of(
        requireNotNull(System.getProperty("plainbase.test.tintedCorpus")) {
            "plainbase.test.tintedCorpus is required; run through ./gradlew :server:test"
        },
    )
        .resolve(record.string("corpusCommit"))
        .also { require(Files.isDirectory(it)) { "Pinned Tinted corpus directory is missing: $it" } }
    private val inputs = record.getValue("inputs").strings()
    private val corpusRecords = record.objects("corpus")
    private val corpusByPath = corpusRecords.associateBy { it.string("path") }
    private val hand = cases.objects("hand")
    private val handById = record.objects("hand").associateBy { it.string("id") }
    private val classes = cases.getValue("rules").jsonObject.objects("classes")
    private val classRules = classes.flatMap { it.getValue("rules").strings() }.toSet()
    private val declarations = Regex("^  (--pb-theme-[a-z-]+): (#[0-9a-fA-F]{6});$", RegexOption.MULTILINE)

    fun identity(): List<String> {
        val checks = Comparisons()
        val stale = "scheme oracle record is stale: rerun tintedTemplateCheck and copy by hand"
        val recordedHand = record.objects("hand")
        val projected = recordedHand.map { entry ->
            JsonObject(entry.filterKeys { it !in setOf("inputSha256", "builder", "variant") })
        }
        checks.equal("record", null, stale, JsonArray(hand), JsonArray(projected))
        checks.equal("record", null, "hand ids are unique", hand.size, handById.size)
        val config = cases.getValue("fuzz").jsonObject
        checks.equal("record", null, "$stale (fuzz configuration)", config, record.getValue("fuzzConfig"))
        checks.equal("record", null, "$stale (seed)", config.getValue("seed"), record.getValue("fuzzSeed"))
        checks.equal("record", null, "$stale (count)", config.getValue("count"), record.getValue("fuzzCount"))
        checks.equal("record", null, "generated count", config.getValue("count").jsonPrimitive.int, record.objects("fuzz").size)
        checks.equal("record", null, "builder", "tinted-builder-rust 0.21.0", record.string("builder"))
        checks.equal("record", null, "input count", 18, inputs.size)
        checks.equal("record", null, "unique inputs", inputs.size, inputs.toSet().size)
        checks.equal("record", null, "corpus count", 571, corpusRecords.size)
        checks.equal("record", null, "unique corpus paths", corpusRecords.size, corpusByPath.size)
        val paths = listOf("base16", "base24").flatMap { system ->
            Files.walk(root.resolve(system)).use { files ->
                files.filter { Files.isRegularFile(it) && (it.toString().endsWith(".yaml") || it.toString().endsWith(".yml")) }
                    .map { root.relativize(it).toString().replace('\\', '/') }.toList()
            }
        }.toSet()
        checks.equal("record", null, "corpus paths", paths, corpusByPath.keys)
        corpusRecords.forEach { entry ->
            val path = entry.string("path")
            checks.equal(path, null, "sha256", entry.string("sha256"), sha256(root.resolve(path).readBytes()))
        }
        hand.forEach { entry ->
            val id = entry.string("id")
            checks.equal(id, null, "inputSha256", handById.getValue(id).string("inputSha256"), sha256(handText(entry)))
        }
        record.objects("fuzz").forEach { entry ->
            checks.equal(entry.string("id"), null, "inputSha256", entry.string("inputSha256"), sha256(fuzzText(entry)))
        }
        return checks.differences
    }

    fun corpus(): List<String> {
        val checks = Comparisons()
        corpusRecords.forEach { entry ->
            val input = SchemeInput(entry.string("path"), corpusText(entry.string("path")), entry)
            val observed = compare(input, checks)
            checks.equal(input.id, null, "parser", "parsed", observed.failure?.rule?.name ?: "parsed")
            checks.equal(input.id, null, "builder", "accepted", builderVerdict(entry))
        }
        return checks.differences
    }

    fun handCases(): List<String> {
        val checks = Comparisons()
        hand.forEach { entry ->
            val id = entry.string("id")
            val input = SchemeInput(id, handText(entry), handById.getValue(id))
            val base = entry.getValue("base").jsonPrimitive.contentOrNull?.let(corpusByPath::getValue)
            val expected = entry.string("expect")
            val observed = compare(input, checks, if (expected == "filesystem") base?.getValue("builder")?.jsonObject else null)
            when (expected) {
                "accepted", "filesystem" -> {
                    checks.equal(id, null, "parser", "parsed", observed.failure?.rule?.name ?: "parsed")
                    checks.equal(id, null, "builder", expected, builderVerdict(input.record))
                }
                else -> {
                    ThemeMode.entries.forEach { mode ->
                        val result = observed.modes.getValue(mode)
                        val actual = if (result is TintedConversion.Rejected) result.failure.rule.name else "parsed"
                        checks.equal(id, mode, "expected rule", expected, actual)
                    }
                    checks.equal(id, null, "filesystem permitted", false, input.record.getValue("builder").jsonObject.flag("filesystem"))
                }
            }
        }
        return checks.differences
    }

    fun generated(): List<String> {
        val checks = Comparisons()
        var accepted = 0
        var rejected = 0
        var divergent = 0
        record.objects("fuzz").forEach { entry ->
            val observed = compare(SchemeInput(entry.string("id"), fuzzText(entry), entry), checks)
            when (builderVerdict(entry)) {
                "accepted" -> if (observed.failure == null) accepted++ else divergent++
                "filesystem" -> if (observed.failure != null) divergent++
                "rejected" -> rejected++
            }
        }
        checks.minimum("accepted by both", 50, accepted)
        checks.minimum("rejected by builder", 50, rejected)
        checks.minimum("documented divergences", 1, divergent)
        return checks.differences
    }

    fun partition(): List<String> {
        val checks = Comparisons()
        val rules = cases.getValue("rules").jsonObject
        val grouped = rules.getValue("builderRejects").strings() +
            classes.flatMap { it.getValue("rules").strings() } + rules.getValue("mode").strings()
        val names = TintedRule.entries.map { it.name }.toSet()
        checks.equal("rules", null, "partition", names, grouped.toSet())
        grouped.groupingBy { it }.eachCount().forEach { (rule, count) -> checks.equal(rule, null, "group count", 1, count) }
        checks.equal("rules", null, "mode", listOf(TintedRule.VARIANT_MISMATCH.name), rules.getValue("mode").strings())
        val expected = hand.map { it.string("expect") }.filterNot { it == "accepted" || it == "filesystem" }.toSet()
        checks.equal("hand", null, "rule coverage", names - TintedRule.VARIANT_MISMATCH.name, expected)
        return checks.differences
    }

    fun witnesses(): List<String> {
        val checks = Comparisons()
        val actual = hand.mapNotNull { entry ->
            val id = entry.string("id")
            val failure = Observation(handText(entry)).failure
            if (builderVerdict(handById.getValue(id)) == "accepted" && failure != null) id to failure.rule.name else null
        }.toMap()
        val listed = classes.flatMap { group -> group.getValue("witnesses").jsonObject.keys }.toSet()
        (listed + actual.keys).forEach { id -> checks.equal(id, null, "witness membership", id in listed, id in actual) }
        classes.forEach { group ->
            val rules = group.getValue("rules").strings()
            group.getValue("witnesses").jsonObject.forEach { (id, phrase) ->
                checks.equal(id, null, "witness class ${group.string("id")}", true, actual[id] in rules)
                checks.equal(id, null, "witness phrase", true, phrase.jsonPrimitive.content in group.string("docs"))
            }
        }
        return checks.differences
    }

    private fun compare(input: SchemeInput, checks: Comparisons, filesystemBase: JsonObject? = null): Observation {
        val observed = Observation(input.text)
        val builder = input.record.getValue("builder").jsonObject
        val failure = observed.failure
        if (failure != null) {
            observed.modes.forEach { (mode, result) ->
                val other = (result as? TintedConversion.Rejected)?.failure
                checks.equal(input.id, mode, "non-mode rule", failure.rule, other?.rule)
                checks.equal(input.id, mode, "non-mode kind", failure.kind, other?.kind)
                checks.equal(input.id, mode, "non-mode line", failure.line, other?.line)
            }
            if (builder.flag("accepted") || builder.flag("filesystem")) {
                checks.equal(input.id, null, "documented rejection ${failure.rule}", true, failure.rule.name in classRules)
            }
        } else {
            checks.equal(input.id, null, "builder rejected parser acceptance", true, builder.flag("accepted") || builder.flag("filesystem"))
            compareModes(input, observed, builder, filesystemBase, checks)
        }
        return observed
    }

    private fun compareModes(
        input: SchemeInput,
        observed: Observation,
        builder: JsonObject,
        filesystemBase: JsonObject?,
        checks: Comparisons,
    ) {
        val variant = input.record.getValue("variant").jsonObject
        val present = variant.flag("present")
        val declared = variant.getValue("value").jsonPrimitive.contentOrNull
        if (present) checks.equal(input.id, null, "declared variant", true, declared in setOf("light", "dark"))
        if (present && builder.flag("accepted")) checks.equal(input.id, null, "builder selector", declared, builder.string("selector"))
        val rendered = mutableListOf<List<Pair<String, String>>>()
        observed.modes.forEach { (mode, result) ->
            val accepts = !present || declared == mode.name.lowercase()
            if (result is TintedConversion.Definition) {
                checks.equal(input.id, mode, "mode accepts", true, accepts)
                val rows = declarations.findAll(result.css).map { it.groupValues[1] to it.groupValues[2] }.toList()
                val shape = "\\A${Regex.escape(selector(mode))} \\{\\n(?:  --pb-theme-[a-z-]+: #[0-9a-f]{6};\\n){18}\\}\\n\\z"
                checks.equal(input.id, mode, "whole-output shape", true, Regex(shape).matches(result.css))
                checks.equal(input.id, mode, "declaration order", inputs, rows.map { it.first })
                val values = if (builder.flag("accepted")) builder else filesystemBase
                values?.getValue("values")?.strings()?.let { expected ->
                    inputs.forEachIndexed { index, name ->
                        checks.equal(input.id, mode, name, expected[index], rows.getOrNull(index)?.second)
                    }
                }
                rendered += rows
            } else {
                val rejected = result as TintedConversion.Rejected
                checks.equal(input.id, mode, "mode verdict", if (accepts) "accepted" else "VARIANT_MISMATCH", rejected.failure.rule.name)
            }
        }
        if (!present) {
            checks.equal(input.id, null, "accepts in both modes", 2, rendered.size)
            checks.equal(input.id, null, "mode-independent declarations", rendered.firstOrNull(), rendered.lastOrNull())
        }
    }

    private fun handText(entry: JsonObject): String {
        entry["text"]?.let { return it.jsonPrimitive.content }
        return entry.getValue("replace").jsonArray.fold(corpusText(entry.string("base"))) { text, pair ->
            val (old, replacement) = pair.strings()
            val offset = text.indexOf(old)
            require(old.isNotEmpty() && offset >= 0) { "Replacement target missing for ${entry.string("id")}" }
            StringBuilder(text).replace(offset, offset + old.length, replacement).toString()
        }
    }

    private fun fuzzText(entry: JsonObject): String =
        entry.getValue("edits").jsonArray.fold(corpusText(entry.string("base"))) { text, edit ->
            val triple = edit.jsonArray
            val offset = triple[0].jsonPrimitive.int
            val count = triple[1].jsonPrimitive.int
            require(offset >= 0 && count >= 0 && offset <= text.length && count <= text.length - offset)
            StringBuilder(text).replace(offset, offset + count, triple[2].jsonPrimitive.content).toString()
        }

    private fun corpusText(path: String): String = root.resolve(path).readText()

    private fun resource(name: String): JsonObject = requireNotNull(TintedSchemeOracleTest::class.java.getResourceAsStream("/tinted/$name"))
        .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }

    private fun builderVerdict(entry: JsonObject): String = when {
        entry.getValue("builder").jsonObject.flag("accepted") -> "accepted"
        entry.getValue("builder").jsonObject.flag("filesystem") -> "filesystem"
        else -> "rejected"
    }

    private fun selector(mode: ThemeMode): String = when (mode) {
        ThemeMode.LIGHT -> ":where(:root:not([data-theme=\"dark\"]))"
        ThemeMode.DARK -> ":where(:root[data-theme=\"dark\"])"
    }

    private fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
