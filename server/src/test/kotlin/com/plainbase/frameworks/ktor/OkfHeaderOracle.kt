package com.plainbase.frameworks.ktor

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.Tag
import java.io.StringReader

/** JVM-test-only representation-tree oracle; never constructs YAML objects or reads body as YAML. */
internal fun independentHeader(bytes: ByteArray): MappingNode {
    val text = bytes.decodeToString(throwOnInvalidSequence = true)
    val header = requireNotNull(EXACT_HEADER.find(text)) { "Expected exact frontmatter fences" }.groupValues[1]
    val documents = Yaml(LoaderOptions()).composeAll(StringReader(header)).toList()
    return requireNotNull(documents.singleOrNull() as? MappingNode) { "Expected one YAML mapping" }
}

internal fun MappingNode.stringValue(key: String): String? {
    val value = value.singleOrNull { (it.keyNode as? ScalarNode)?.value == key }?.valueNode as? ScalarNode
    return value?.takeIf { it.tag == Tag.STR }?.value
}

internal fun independentHeaderType(bytes: ByteArray): String =
    requireNotNull(independentHeader(bytes).stringValue("type")?.takeIf { it.isNotEmpty() }) { "Expected nonempty string type" }

private val EXACT_HEADER = Regex("\\A(?:\uFEFF)?---\\r?\\n(.*?)\\r?\\n---(?:\\r?\\n|$)", RegexOption.DOT_MATCHES_ALL)
