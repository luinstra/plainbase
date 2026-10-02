package com.plainbase.buildlogic

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal object SharedBuildSource {
    private val inputRoots = listOf(
        "server/src", "frontend/src", "frontend/public", "frontend/scripts", "frontend/e2e", "buildSrc/src", "config", "gradle",
    )
    private val excludedRoots = listOf("", "server/", "frontend/", "buildSrc/").flatMap { module ->
        listOf("build", ".gradle", ".kotlin").map { "$module$it" }
    } + listOf(
        "node_modules", "frontend/dist", "frontend/node_modules", "frontend/.node", "frontend/.smoke-runs",
        "frontend/test-results", "frontend/playwright-report", "server/test-results-native",
    )

    fun commit(root: Path): String = git(root, "rev-parse", "HEAD").trim().also {
        require(it.matches(Regex("[0-9a-f]{40,64}"))) { "Invalid checkout commit" }
    }

    fun fingerprint(root: Path): String = fingerprint(root, git(root, "ls-files", "--stage", "-z"))

    internal fun fingerprint(root: Path, index: String): String {
        val paths = sortedMapOf<String, String>()
        index.split('\u0000').filter(String::isNotEmpty).forEach { record ->
            val header = record.substringBefore('\t').split(' ')
            require(header.size == 3 && header[2] == "0") { "Shared build refuses an unmerged index" }
            val name = record.substringAfter('\t')
            if (!excluded(name)) paths[name] = header[0]
        }
        inputRoots.forEach { name ->
            val directory = root.resolve(name)
            if (Files.exists(directory, NOFOLLOW_LINKS)) {
                SharedBuildFiles.rejectSymlinkAncestors(root, directory)
                Files.walk(directory).use { entries ->
                    entries.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }.forEach { file ->
                        val relative = root.relativize(file).toString().replace('\\', '/')
                        // Every file under these explicit source roots is an input, even if Git ignores its name.
                        paths.putIfAbsent(relative, "untracked")
                    }
                }
            }
        }
        listOf(root, root.resolve("server"), root.resolve("frontend"), root.resolve("buildSrc")).forEach { directory ->
            if (Files.isDirectory(directory, NOFOLLOW_LINKS)) {
                Files.list(directory).use { entries ->
                    entries.filter {
                        inputConfiguration(it.fileName.toString(), directory == root || directory == root.resolve("frontend")) &&
                        !excluded(it.fileName.toString()) &&
                        !Files.isDirectory(it, NOFOLLOW_LINKS)
                    }.forEach { file ->
                        paths.putIfAbsent(root.relativize(file).toString().replace('\\', '/'), "untracked")
                    }
                }
            }
        }
        return digestRecords(
            paths.map { (name, indexMode) ->
                SharedBuildFiles.safeRelative(name)
                val file = root.resolve(name)
                SharedBuildFiles.rejectSymlinkAncestors(root, file)
                if (Files.exists(file, NOFOLLOW_LINKS)) {
                    require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "Not an ordinary source file: $name" }
                    listOf(name, indexMode, Files.isExecutable(file).toString(), SharedBuildFiles.sha256(file))
                } else {
                    listOf(name, indexMode, "absent")
                }
            },
        )
    }

    fun checkFrontendEnvironment(root: Path, environment: Map<String, String>) {
        require(environment.keys.none { it.startsWith("VITE_") }) { "Shared preparation refuses VITE_* environment overrides" }
        Files.list(root.resolve("frontend")).use { entries ->
            require(entries.noneMatch { it.fileName.toString().startsWith(".env") }) { "Shared preparation refuses frontend .env* files" }
        }
    }

    private fun inputConfiguration(name: String, npmConfiguration: Boolean): Boolean =
        name.endsWith(".gradle.kts") || name.endsWith(".gradle") || name.endsWith(".properties") ||
            name in setOf("package.json", "package-lock.json", "index.html") || (npmConfiguration && name == ".npmrc") ||
            name.startsWith("vite.config.") || name.startsWith("vitest.config.") || name.startsWith("tsconfig") ||
            name.startsWith("playwright.config.")

    private fun excluded(name: String): Boolean = excludedRoots.any { name == it || name.startsWith("$it/") }

    private fun git(root: Path, vararg arguments: String): String {
        val output = Files.createTempFile("plainbase-shared-git-", ".out")
        val error = Files.createTempFile("plainbase-shared-git-", ".err")
        try {
            val process = ProcessBuilder(listOf("git") + arguments).directory(root.toFile())
                .redirectOutput(output.toFile()).redirectError(error.toFile()).start()
            try {
                check(process.waitFor(30, TimeUnit.SECONDS)) { "Shared build Git identity timed out" }
                check(process.exitValue() == 0) { "Shared build needs a Git checkout: ${Files.readString(error)}" }
                return Files.readString(output)
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    check(process.waitFor(5, TimeUnit.SECONDS)) { "Could not reap Git identity process" }
                }
            }
        } finally {
            Files.deleteIfExists(output)
            Files.deleteIfExists(error)
        }
    }
}

internal fun digestRecords(records: List<List<String>>): String {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output ->
        records.sortedWith(compareBy { it.joinToString("\u0000") }).forEach { record ->
            output.writeInt(record.size)
            record.forEach { field ->
                val encoded = field.toByteArray(Charsets.UTF_8)
                output.writeInt(encoded.size)
                output.write(encoded)
            }
        }
    }
    return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).toHex()
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
