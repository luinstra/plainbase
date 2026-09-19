package com.plainbase.frameworks.filesystem

import com.plainbase.domain.content.ContentPathPolicy
import com.plainbase.domain.content.Nfc
import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.root.RootRegistry
import com.plainbase.frameworks.config.PlainbaseConfig
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher

/** Compiles the complete configured policy map used by server boot and offline commands. */
internal fun contentPolicies(
    registry: RootRegistry,
    config: PlainbaseConfig,
    ignoreRules: IgnoreRules,
): Map<RootName, ContentPathPolicy> = buildMap {
    val primary = registry.primary
    put(
        primary.name,
        when (primary.backend) {
            is RootBackend.Local -> localContentPathPolicy(primary, config.mainContentRoot(), ignoreRules, listOf(config.dataDir))
            is RootBackend.Object -> ContentPathPolicy.ALL
        },
    )
    registry.extras.forEach { configured ->
        put(
            configured.name,
            when (val backend = configured.backend) {
                is RootBackend.Local -> localContentPathPolicy(configured, backend.path, ignoreRules, listOf(config.dataDir))
                is RootBackend.Object -> ContentPathPolicy.ALL
            },
        )
    }
}

/** Compiles one configured root's path rules into the shared pure content-policy seam. */
internal fun localContentPathPolicy(
    rootConfig: Root,
    root: Path,
    ignoreRules: IgnoreRules,
    exclusions: List<Path>,
): ContentPathPolicy {
    val includePatterns = rootConfig.includes?.map(::compileGlob).orEmpty()
    val excludePatterns = rootConfig.excludes.map(::compileGlob)
    val includePrefixes = rootConfig.includes?.map(::literalPrefix).orEmpty()
    val includeTraversals = rootConfig.includes?.map(::compileIncludeTraversal).orEmpty()
    val excludePrefixes = rootConfig.excludes
        .mapNotNull(::terminalLiteralDirectoryPrefix)
    val rootNormalized = root.toAbsolutePath().normalize()
    val excludedPaths = effectiveExcludedDirectories(root, exclusions)
        .mapNotNull { rootNormalized.relativize(it).toTreePath() }

    fun structurallyAllowed(path: TreePath): Boolean = structurallyAllowed(
        path = path,
        excludedPaths = excludedPaths,
        excludePatterns = excludePatterns,
        includePrefixes = includePrefixes,
        ignoreRules = ignoreRules,
    )

    fun includeMatches(path: TreePath): Boolean =
        rootConfig.includes == null || includePatterns.any { it.matches(path.asPath()) }

    fun traversalAllowed(path: TreePath?): Boolean {
        if (path == null) return true
        if (!structurallyAllowed(path)) return false
        if (pathUnderSegments(path, excludePrefixes)) return false
        if (rootConfig.includes == null) return true
        if (rootConfig.includes.isEmpty()) return false
        return includeTraversals.any { include ->
            (include.maximumPathDepth == null || path.segments.size < include.maximumPathDepth) &&
                (include.prefix.isEmpty() || path.segments.hasPrefix(include.prefix) || include.prefix.hasPrefix(path.segments))
        }
    }

    fun metadataAllowed(folder: TreePath): Boolean =
        traversalAllowed(folder) && structurallyAllowed(folder.resolveChild(FOLDER_META_NAME))

    return ContentPathPolicy.create(
        fileEligibility = { path -> structurallyAllowed(path) && includeMatches(path) },
        traversalEligibility = ::traversalAllowed,
        metadataEligibility = ::metadataAllowed,
    )
}

/** Maps physical nesting back into the root's declared spelling, including missing exclusion tails. */
internal fun effectiveExcludedDirectories(root: Path, exclusions: Collection<Path>): List<Path> {
    val declaredRoot = root.toAbsolutePath().normalize()
    val comparableRoot = bestEffortCanonical(declaredRoot)
    return exclusions.mapNotNull { exclusion ->
        val comparableExclusion = bestEffortCanonical(exclusion)
        if (comparableExclusion == comparableRoot || !comparableExclusion.startsWith(comparableRoot)) {
            null
        } else {
            declaredRoot.resolve(comparableRoot.relativize(comparableExclusion)).normalize()
        }
    }.distinct()
}

/** Resolves the deepest existing ancestor so prospective paths retain physical alias identity. */
private fun bestEffortCanonical(path: Path): Path {
    val normalized = path.toAbsolutePath().normalize()
    var existing = normalized
    while (existing.parent != null && !Files.exists(existing)) existing = existing.parent
    return try {
        existing.toRealPath().resolve(existing.relativize(normalized))
    } catch (_: IOException) {
        normalized
    }
}

private fun compileGlob(pattern: String): PathMatcher =
    FileSystems.getDefault().getPathMatcher("glob:$pattern")

private data class IncludeTraversal(
    val prefix: List<String>,
    val maximumPathDepth: Int?,
)

private fun compileIncludeTraversal(pattern: String): IncludeTraversal {
    val normalizedPattern = Nfc.normalize(pattern)
    return IncludeTraversal(
        prefix = literalPrefix(normalizedPattern),
        maximumPathDepth = if ("**" in normalizedPattern) null else normalizedPattern.count { it == '/' } + 1,
    )
}

private fun structurallyAllowed(
    path: TreePath,
    excludedPaths: List<TreePath>,
    excludePatterns: List<PathMatcher>,
    includePrefixes: List<List<String>>,
    ignoreRules: IgnoreRules,
): Boolean =
    !pathUnder(path, excludedPaths) &&
        !pathOrAncestorMatches(path, excludePatterns) &&
        path.segments.none { it == ".git" } &&
        path.segments.none { it.startsWith(".") && !authorizedDotPath(path, includePrefixes) } &&
        !pathOrAncestorMatches(path, ignoreRules::isGlobIgnored)

private fun TreePath.asPath(): Path = Path.of(value)

private fun Path.toTreePath(): TreePath? = TreePath.of(toString().replace('\\', '/'))

private fun pathUnder(path: TreePath, prefixes: List<TreePath>): Boolean =
    prefixes.any { prefix -> path.segments.hasPrefix(prefix.segments) }

private fun pathUnderSegments(path: TreePath, prefixes: List<List<String>>): Boolean =
    prefixes.any { prefix -> path.segments.hasPrefix(prefix) }

private fun pathOrAncestorMatches(path: TreePath, patterns: List<PathMatcher>): Boolean =
    path.prefixes().any { candidate -> patterns.any { it.matches(candidate.asPath()) } }

private fun pathOrAncestorMatches(path: TreePath, matches: (String) -> Boolean): Boolean =
    path.prefixes().any { matches(it.value) }

private fun TreePath.prefixes(): Sequence<TreePath> =
    segments.indices.asSequence().map { lastIndex -> TreePath.require(segments.subList(0, lastIndex + 1).joinToString("/")) }

private fun <T> List<T>.hasPrefix(prefix: List<T>): Boolean =
    size >= prefix.size && subList(0, prefix.size) == prefix

private fun literalPrefix(pattern: String): List<String> {
    val segments = pattern.split('/')
    val prefix = mutableListOf<String>()
    for (segment in segments) {
        if (segment.any { it in "*?[]{}" }) break
        prefix += Nfc.normalize(segment)
    }
    return prefix
}

private fun terminalLiteralDirectoryPrefix(pattern: String): List<String>? {
    if (!pattern.endsWith("/**")) return null
    val prefix = pattern.removeSuffix("/**").split('/')
    return prefix.takeIf { segments -> segments.all { segment -> segment.none { it in "*?[]{}" } } }
        ?.map(Nfc::normalize)
}

private fun authorizedDotPath(path: TreePath, prefixes: List<List<String>>): Boolean =
    path.segments.withIndex().filter { it.value.startsWith(".") }.all { (index, _) ->
        prefixes.any { prefix -> prefix.size > index && prefix.subList(0, index + 1) == path.segments.subList(0, index + 1) }
    }
