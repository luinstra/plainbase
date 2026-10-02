package com.plainbase.buildlogic

import java.io.DataInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class SharedBuildIdentity(val commit: String, val source: String, val run: String, val version: String)

internal const val SHARED_BUILD_GRADLE_VERSION = "9.7.1"

internal data class SharedDependency(val coordinates: String, val variant: String, val file: String, val sha256: String) {
    fun record(): List<String> = listOf(coordinates, variant, file, sha256)
}

internal data class SharedOutput(val path: String, val sha256: String, val executable: Boolean) {
    fun record(): List<String> = listOf(path, sha256, executable.toString())
}

internal object SharedBuildFiles {
    fun sha256(path: Path): String = Files.newInputStream(path).use(::sha256)

    fun sha256(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().toHex()
    }

    fun safeRelative(name: String) {
        require(name.isNotBlank() && !name.contains('\\') && !name.contains(':') && !name.startsWith('/')) { "Unsafe path: $name" }
        require(name.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe path: $name" }
    }

    fun rejectSymlinkAncestors(root: Path, path: Path) {
        require(path.normalize().startsWith(root.normalize())) { "Path outside checkout: $path" }
        var current: Path? = path
        while (current != null) {
            require(!Files.isSymbolicLink(current)) { "Shared build refuses symlink: $current" }
            if (current == root) break
            current = current.parent
        }
    }

    fun deleteTree(path: Path) {
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            Files.walk(path).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    // JDK ZipEntry does not expose Unix file type bits. Read the central directory before extraction.
    fun rejectSpecialZipEntries(archive: Path) {
        RandomAccessFile(archive.toFile(), "r").use { file ->
            val tailSize = minOf(file.length(), 65557L).toInt()
            val tail = ByteArray(tailSize)
            file.seek(file.length() - tailSize)
            file.readFully(tail)
            fun number(bytes: ByteArray, offset: Int, size: Int): Long =
                (0 until size).fold(0L) { value, index -> value or ((bytes[offset + index].toLong() and 255) shl (8 * index)) }
            val end = (tail.size - 22 downTo 0).firstOrNull {
                number(tail, it, 4) == 0x06054b50L && it + 22 + number(tail, it + 20, 2) == tail.size.toLong()
            }
            require(end != null) { "Missing ZIP central directory" }
            val count = number(tail, end + 10, 2)
            val offset = number(tail, end + 16, 4)
            require(count < 65535 && offset < 0xffffffffL && number(tail, end + 4, 4) == 0L) { "Unsupported multipart/ZIP64 bundle" }
            file.seek(offset)
            repeat(count.toInt()) {
                val header = ByteArray(46)
                file.readFully(header)
                require(number(header, 0, 4) == 0x02014b50L) { "Invalid ZIP central directory" }
                val type = (number(header, 38, 4) shr 16).toInt() and 0xf000
                require(type in setOf(0, 0x8000, 0x4000)) { "ZIP links and special files are forbidden" }
                file.seek(file.filePointer + number(header, 28, 2) + number(header, 30, 2) + number(header, 32, 2))
            }
        }
    }
}

internal class SharedBuildContract(private val archivePaths: List<String>) {
    companion object {
        val directories = listOf(
            "frontend/dist",
            "server/build/classes/kotlin/main", "server/build/classes/kotlin/test", "server/build/classes/kotlin/nativeTest",
            "server/build/classes/java/main", "server/build/classes/java/test", "server/build/classes/java/nativeTest",
            "server/build/resources/main", "server/build/resources/test", "server/build/resources/nativeTest",
            "server/build/generated/source/buildInfo/kotlin", "server/build/generated/sqldelight/code/PlainbaseDb/main",
            "server/build/generated/frontend", "server/build/scripts", "server/build/install/plainbase",
        )
        val configurations = listOf("runtimeClasspath", "testRuntimeClasspath", "nativeTestRuntimeClasspath")
    }

    init {
        require(archivePaths.size == 3 && archivePaths.distinct().size == 3) { "Expected jar, distTar and distZip" }
        archivePaths.forEach(SharedBuildFiles::safeRelative)
        require(archivePaths.count { it.startsWith("server/build/libs/") && it.endsWith(".jar") } == 1) {
            "Expected one server JAR under server/build/libs"
        }
        require(archivePaths.count { it.startsWith("server/build/distributions/") && it.endsWith(".tar") } == 1) {
            "Expected one distTar under server/build/distributions"
        }
        require(archivePaths.count { it.startsWith("server/build/distributions/") && it.endsWith(".zip") } == 1) {
            "Expected one distZip under server/build/distributions"
        }
        require(archivePaths.all { it.substringAfter("server/build/").split('/').size == 2 }) {
            "Distribution archives must be direct children of their approved output directories"
        }
    }

    fun prepare(
        root: Path,
        archive: Path,
        identity: SharedBuildIdentity,
        dependencies: Map<String, List<SharedDependency>>,
        provenance: Map<String, String>,
    ) {
        val outputs = inventory(root)
        requireLogicalOutputs(root, outputs)
        val manifest = Properties()
        manifest.setProperty("schema", "1")
        manifest.setProperty("commit", identity.commit)
        manifest.setProperty("source", identity.source)
        manifest.setProperty("run", identity.run)
        manifest.setProperty("version", identity.version)
        manifest.setProperty("java.target", "25")
        manifest.setProperty("gradle.version", SHARED_BUILD_GRADLE_VERSION)
        provenance.forEach { (key, value) -> manifest.setProperty("producer.$key", value) }
        directories.forEachIndexed { index, path ->
            manifest.setProperty("root.$index.path", path)
            manifest.setProperty("root.$index.present", Files.isDirectory(root.resolve(path), NOFOLLOW_LINKS).toString())
        }
        archivePaths.forEachIndexed { index, path -> manifest.setProperty("archive.$index", path) }
        manifest.setProperty("output.count", outputs.size.toString())
        manifest.setProperty("output.digest", digestRecords(outputs.map(SharedOutput::record)))
        outputs.forEachIndexed { index, output ->
            manifest.setProperty("output.$index.path", output.path)
            manifest.setProperty("output.$index.sha256", output.sha256)
            manifest.setProperty("output.$index.executable", output.executable.toString())
        }
        require(dependencies.keys == configurations.toSet()) { "All three dependency inventories are required" }
        dependencies.forEach { (configuration, entries) ->
            val sorted = entries.sortedBy { it.record().joinToString("\u0000") }
            manifest.setProperty("dependency.$configuration.count", sorted.size.toString())
            manifest.setProperty("dependency.$configuration.digest", digestRecords(sorted.map(SharedDependency::record)))
            sorted.forEachIndexed { index, entry ->
                listOf("coordinates", "variant", "file", "sha256").zip(entry.record()).forEach { (key, value) ->
                    manifest.setProperty("dependency.$configuration.$index.$key", value)
                }
            }
        }
        SharedBuildFiles.rejectSymlinkAncestors(root, archive)
        Files.createDirectories(archive.parent)
        val temporary = Files.createTempFile(archive.parent, "bundle-", ".zip")
        try {
            ZipOutputStream(Files.newOutputStream(temporary)).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.properties"))
                manifest.store(zip, "Plainbase shared build")
                zip.closeEntry()
                outputs.forEach { output ->
                    zip.putNextEntry(ZipEntry(output.path))
                    Files.copy(root.resolve(output.path), zip)
                    zip.closeEntry()
                }
            }
            // Detect producer output changes during packaging as well as input changes in the convention.
            validateInto(root, temporary, identity, dependencies) { }
            Files.move(temporary, archive, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun consume(
        root: Path,
        archive: Path,
        expectedSha256: String,
        identity: SharedBuildIdentity,
        dependencies: Map<String, List<SharedDependency>>,
    ): Boolean {
        require(expectedSha256.matches(Regex("[0-9a-f]{64}"))) { "ciSharedBuildSha256 must be the producer bundle SHA-256" }
        require(SharedBuildFiles.sha256(archive) == expectedSha256) { "Shared bundle checksum mismatch" }
        val reused = validateInto(root, archive, identity, dependencies, reuseInstalled = true) { staging ->
            // Validate every destination before deleting anything. Shared libs/distributions keep other named files.
            (directories + archivePaths).forEach { path ->
                val destination = root.resolve(path)
                SharedBuildFiles.rejectSymlinkAncestors(root, destination)
                if (Files.isDirectory(destination, NOFOLLOW_LINKS)) {
                    Files.walk(destination).use { files -> files.forEach { SharedBuildFiles.rejectSymlinkAncestors(root, it) } }
                }
            }
            (directories + archivePaths).forEach { path ->
                val destination = root.resolve(path)
                SharedBuildFiles.deleteTree(destination)
                val source = staging.resolve(path)
                if (Files.exists(source, NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination.parent)
                    Files.move(source, destination)
                }
            }
        }
        return !reused
    }

    private fun inventory(root: Path): List<SharedOutput> {
        val files = mutableListOf<Path>()
        directories.forEach { name ->
            val path = root.resolve(name)
            SharedBuildFiles.rejectSymlinkAncestors(root, path)
            if (Files.exists(path, NOFOLLOW_LINKS)) {
                require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "Expected output directory: $name" }
                Files.walk(path).use { entries -> entries.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }.forEach(files::add) }
            }
        }
        archivePaths.forEach { files.add(root.resolve(it)) }
        return files.map { path ->
            SharedBuildFiles.rejectSymlinkAncestors(root, path)
            require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Missing or special output: $path" }
            SharedOutput(root.relativize(path).toString().replace('\\', '/'), SharedBuildFiles.sha256(path), Files.isExecutable(path))
        }.sortedBy(SharedOutput::path)
    }

    private fun validateInto(
        root: Path,
        archive: Path,
        identity: SharedBuildIdentity,
        dependencies: Map<String, List<SharedDependency>>,
        reuseInstalled: Boolean = false,
        use: (Path) -> Unit,
    ): Boolean {
        SharedBuildFiles.rejectSpecialZipEntries(archive)
        val stagingParent = root.resolve("build/ci-shared")
        SharedBuildFiles.rejectSymlinkAncestors(root, stagingParent)
        Files.createDirectories(stagingParent)
        val staging = Files.createTempDirectory(stagingParent, "staging-")
        try {
            ZipFile(archive.toFile()).use { zip ->
                val entries = zip.entries().toList()
                require(entries.map { it.name }.distinct().size == entries.size) { "Duplicate ZIP paths" }
                val metadata = entries.singleOrNull { it.name == "manifest.properties" && !it.isDirectory }
                require(metadata != null && metadata.size in 1..16_000_000) { "Missing/oversized shared manifest" }
                val manifest = Properties().apply { zip.getInputStream(metadata).use(::load) }
                fun field(key: String): String = requireNotNull(manifest.getProperty(key)) { "Missing manifest field: $key" }
                require(field("schema") == "1" && field("java.target") == "25" && field("gradle.version") == SHARED_BUILD_GRADLE_VERSION) {
                    "Unsupported shared build schema/toolchain"
                }
                require(field("commit") == identity.commit) { "Shared build commit mismatch" }
                require(field("source") == identity.source) { "Shared build source fingerprint mismatch" }
                require(field("run") == identity.run) { "Shared build run mismatch" }
                require(field("version") == identity.version) { "Shared build version mismatch" }
                directories.forEachIndexed { index, path ->
                    require(field("root.$index.path") == path) { "Unexpected managed root" }
                    when (field("root.$index.present")) {
                        "true" -> Files.createDirectories(staging.resolve(path))
                        "false" -> Unit
                        else -> error("Invalid managed-root presence")
                    }
                }
                archivePaths.forEachIndexed { index, path -> require(field("archive.$index") == path) { "Archive paths mismatch" } }
                require(dependencies.keys == configurations.toSet()) { "All three dependency inventories are required" }
                configurations.forEach { configuration ->
                    val count = field("dependency.$configuration.count").toInt().also {
                        require(it in 1..10000) { "Invalid $configuration dependency count: $it (expected 1..10000)" }
                    }
                    val recorded = (0 until count).map { index ->
                        SharedDependency(
                            field("dependency.$configuration.$index.coordinates"), field("dependency.$configuration.$index.variant"),
                            field("dependency.$configuration.$index.file"), field("dependency.$configuration.$index.sha256"),
                        )
                    }
                    require(digestRecords(recorded.map(SharedDependency::record)) == field("dependency.$configuration.digest")) {
                        "Corrupt $configuration inventory"
                    }
                    require(
                        recorded.sortedBy { it.record().joinToString("\u0000") } ==
                        dependencies.getValue(configuration).sortedBy { it.record().joinToString("\u0000") },
                    ) {
                        "Shared build dependency mismatch: $configuration"
                    }
                }
                val count = field("output.count").toInt().also {
                    require(it in 1..60000) { "Invalid shared output count: $it (expected 1..60000)" }
                }
                val outputs = (0 until count).map { index ->
                    SharedOutput(
                        field("output.$index.path"), field("output.$index.sha256"), field("output.$index.executable").toBooleanStrict(),
                    )
                }.sortedBy(SharedOutput::path)
                require(outputs.map(SharedOutput::path).distinct().size == count) { "Duplicate manifest outputs" }
                require(digestRecords(outputs.map(SharedOutput::record)) == field("output.digest")) { "Corrupt output inventory" }
                outputs.forEach { output ->
                    SharedBuildFiles.safeRelative(output.path)
                    require(allowedFile(output.path)) { "Output outside allowlist: ${output.path}" }
                }
                require(
                    entries.filterNot(ZipEntry::isDirectory).map { it.name }.toSet() ==
                    outputs.map(SharedOutput::path).toSet() + "manifest.properties",
                ) { "ZIP inventory mismatch" }
                entries.filter(ZipEntry::isDirectory).forEach { entry ->
                    val path = entry.name.removeSuffix("/")
                    SharedBuildFiles.safeRelative(path)
                    require(
                        directories.any {
                            it == path || path.startsWith("$it/") || it.startsWith("$path/")
                        },
                    ) { "Unexpected ZIP directory" }
                }
                val matching = reuseInstalled && runCatching {
                    inventory(root) == outputs && directories.withIndex().all { (index, path) ->
                        val directory = root.resolve(path)
                        val present = field("root.$index.present").toBooleanStrict()
                        Files.isDirectory(directory, NOFOLLOW_LINKS) == present ||
                            (!present && Files.isDirectory(directory, NOFOLLOW_LINKS) && Files.list(directory).use { it.findAny().isEmpty })
                    }
                }.getOrDefault(false)
                if (matching) {
                    outputs.forEach { output ->
                        val hash = zip.getInputStream(zip.getEntry(output.path)).use(SharedBuildFiles::sha256)
                        require(hash == output.sha256) { "Corrupt output: ${output.path}" }
                    }
                    requireLogicalOutputs(root, outputs)
                    // Gradle creates declared output directories before the action, including absent Java roots.
                    directories.forEachIndexed { index, path ->
                        if (field("root.$index.present") == "false") Files.deleteIfExists(root.resolve(path))
                    }
                    return true
                }
                outputs.forEach { output ->
                    val destination = staging.resolve(output.path)
                    Files.createDirectories(destination.parent)
                    zip.getInputStream(zip.getEntry(output.path)).use { Files.copy(it, destination) }
                    require(SharedBuildFiles.sha256(destination) == output.sha256) { "Corrupt output: ${output.path}" }
                    check(destination.toFile().setExecutable(output.executable, false)) { "Cannot restore output permissions" }
                }
                directories.forEachIndexed { index, path ->
                    require(Files.isDirectory(staging.resolve(path), NOFOLLOW_LINKS) == field("root.$index.present").toBooleanStrict()) {
                        "Managed-root presence mismatch: $path"
                    }
                }
                requireLogicalOutputs(staging, outputs)
                use(staging)
            }
        } finally {
            SharedBuildFiles.deleteTree(staging)
        }
        return false
    }

    private fun allowedFile(path: String): Boolean = path in archivePaths || directories.any { path.startsWith("$it/") }

    private fun requireLogicalOutputs(root: Path, outputs: List<SharedOutput>) {
        val paths = outputs.map(SharedOutput::path)
        listOf("main", "test", "nativeTest").forEach { sourceSet ->
            val prefix = "server/build/classes/kotlin/$sourceSet/"
            require(paths.any { it.startsWith(prefix) && it.endsWith(".class") }) { "Missing $sourceSet classes" }
            require(paths.any { it.startsWith(prefix) && it.endsWith(".kotlin_module") }) { "Missing $sourceSet module metadata" }
        }
        require("frontend/dist/index.html" in paths && paths.any { it.startsWith("frontend/dist/assets/") }) { "Missing frontend output" }
        require("server/build/generated/source/buildInfo/kotlin/com/plainbase/BuildInfo.kt" in paths) { "Missing BuildInfo source" }
        require(paths.any { it.startsWith("server/build/generated/sqldelight/code/PlainbaseDb/main/") && it.endsWith(".kt") }) {
            "Missing SQLDelight source"
        }
        require("server/build/resources/main/static/index.html" in paths && "server/build/generated/frontend/static/index.html" in paths) {
            "Missing embedded frontend resources"
        }
        require(paths.any { it.startsWith("server/build/resources/main/META-INF/native-image/") }) { "Missing native metadata" }
        require("server/build/scripts/plainbase" in paths && "server/build/install/plainbase/bin/plainbase" in paths) { "Missing launcher" }
        require(outputs.single { it.path == "server/build/install/plainbase/bin/plainbase" }.executable) { "Launcher is not executable" }
        require(paths.any { it.startsWith("server/build/install/plainbase/lib/") && it.endsWith(".jar") }) { "Missing runtime libraries" }
        archivePaths.forEach { require(it in paths && Files.size(root.resolve(it)) > 0) { "Missing distribution archive: $it" } }
        paths.filter { it.endsWith(".class") }.forEach { path ->
            DataInputStream(Files.newInputStream(root.resolve(path))).use { input ->
                require(input.readInt() == 0xcafebabe.toInt()) { "Invalid class: $path" }
                input.readUnsignedShort()
                require(input.readUnsignedShort() == 69) { "Expected Java 25 bytecode: $path" }
            }
        }
    }
}
