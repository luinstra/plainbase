package com.plainbase.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SharedBuildPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        require(project == project.rootProject) { "Shared build convention must be applied to the root" }
        require(!project.providers.gradleProperty("ciSharedBuildProfile").isPresent) {
            "ciSharedBuildProfile is obsolete; shared builds always validate all three dependency configurations"
        }
        val artifact = project.providers.gradleProperty("ciSharedBuild")
        val required = project.providers.environmentVariable("CI_SHARED_BUILD_REQUIRED").orNull == "true"
        val consumer = artifact.isPresent || required
        if (consumer && artifact.orNull.isNullOrBlank()) throw GradleException("ciSharedBuild is required and must not be blank")
        if (consumer) require(Path.of(artifact.get()).isAbsolute) { "ciSharedBuild must be an absolute archive path" }
        val root = project.rootDir.toPath()
        val output = project.layout.buildDirectory.dir("ci-shared").get().asFile.toPath()
        var beforePreparation: String? = null

        val consume = project.tasks.register("consumeSharedBuild") {
            group = "build"
            description = "Validates the same-run compilation bundle and restores its managed outputs"
            doNotTrackState("External outputs and identity/dependencies must be validated on every invocation")
            outputs.dirs(SharedBuildContract.directories.map { root.resolve(it) })
            doLast {
                check(consumer) { "consumeSharedBuild needs an explicit ciSharedBuild artifact" }
                val start = System.nanoTime()
                val server = project.project(":server")
                val changed = contract(server).consume(
                    root, Path.of(artifact.get()), project.providers.gradleProperty("ciSharedBuildSha256").getOrElse(""),
                    identity(project, root), dependencies(server),
                )
                project.logger.lifecycle(
                    "Shared build validated in {} ms ({})", (System.nanoTime() - start) / 1_000_000,
                    if (changed) "restored outputs" else "reused outputs",
                )
            }
        }
        val prepare = project.tasks.register("prepareSharedBuild") {
            group = "build"
            description = "Compiles and packages portable application, frontend and test outputs without running tests"
            dependsOn(
                ":frontend:npmBuild", ":server:classes", ":server:testClasses", ":server:nativeTestClasses",
                ":server:installDist", ":server:distTar", ":server:distZip",
            )
            doNotTrackState("A shared bundle is bound to its current checkout, inputs and workflow run")
            outputs.file(output.resolve("shared-build.zip"))
            doLast {
                check(!consumer) { "Cannot prepare a shared build while consuming one" }
                val current = identity(project, root)
                check(beforePreparation == current.source) { "Shared preparation changed source inputs (including package-lock.json)" }
                val server = project.project(":server")
                val compiler = server.extensions.getByType<JavaToolchainService>().compilerFor {
                    languageVersion.set(JavaLanguageVersion.of(25))
                }.get().metadata
                contract(server).prepare(
                    root, output.resolve("shared-build.zip"), current, dependencies(server),
                    mapOf(
                        "attempt" to project.providers.environmentVariable("GITHUB_RUN_ATTEMPT").getOrElse("local"),
                        "java.vendor" to compiler.vendor, "java.runtime" to compiler.javaRuntimeVersion,
                        "java.vm" to compiler.jvmVersion, "os" to System.getProperty("os.name"), "arch" to System.getProperty("os.arch"),
                    ) + frontendVersions(project.project(":frontend")),
                )
                check(beforePreparation == SharedBuildSource.fingerprint(root)) { "Shared preparation inputs changed during packaging" }
                exportHash(project, "bundle_sha256", output.resolve("shared-build.zip"))
            }
        }
        val verifyVersion = project.tasks.register<VerifySharedBuildVersion>("verifySharedBuildVersion") {
            group = "verification"
            description = "Boots the installed shared distribution and verifies the resolved version over a real socket"
            expectedVersion.set(project.providers.provider { project.version.toString() })
            installedDistribution.set(project.layout.projectDirectory.dir("server/build/install/plainbase"))
            dependsOn(":server:installDist")
            mustRunAfter(prepare)
        }
        project.tasks.register("prepareSharedRuntime") {
            group = "build"
            description = "Exports the validated runtime tar after an executed version proof"
            dependsOn(prepare, verifyVersion)
            doNotTrackState("Runtime export must follow current shared-build and version validation")
            outputs.file(output.resolve("runtime-dist.tar"))
            doLast {
                val server = project.project(":server")
                val tar = server.tasks.named<AbstractArchiveTask>("distTar").get().archiveFile.get().asFile.toPath()
                verifyRuntimeTar(
                    listOf(
                        "python3", "-B", root.resolve("scripts/ci/prepare-runtime-context.py").toString(),
                        "--archive", tar.toString(), "--sha256", SharedBuildFiles.sha256(tar),
                        "--installed-dist", root.resolve("server/build/install/plainbase").toString(),
                    ),
                    output,
                )
                check(beforePreparation == SharedBuildSource.fingerprint(root)) { "Runtime preparation source inputs changed" }
                Files.copy(tar, output.resolve("runtime-dist.tar"), REPLACE_EXISTING)
                exportHash(project, "runtime_sha256", output.resolve("runtime-dist.tar"))
            }
        }

        project.gradle.projectsEvaluated {
            val server = project.project(":server")
            val archives = archivePaths(server).map { root.resolve(it) }
            consume.configure { outputs.files(archives) }
            if (consumer) {
                val inactive = serverProducers.map { server.tasks.findByName(it) ?: error("Missing shared producer :server:$it") } +
                    (project.project(":frontend").tasks.findByName("npmBuild") ?: error("Missing shared producer :frontend:npmBuild"))
                inactive.forEach { task ->
                    task.setDependsOn(listOf(consume))
                    task.onlyIf("The validated shared bundle owns these outputs") { false }
                    task.doNotTrackState("The shared consumer supplies this producer's outputs")
                }
                project.allprojects.forEach { child ->
                    child.tasks.configureEach {
                        if (this is Test || this is JavaExec || name.startsWith("kover") || name.startsWith("gitZombie") ||
                            name in setOf("nativeCompile", "nativeTestCompile", "nativeTest", "traceMcpSseMetadata")
                        ) {
                            dependsOn(consume)
                        }
                        if (this is KotlinCompile || this is JavaCompile || path == ":frontend:npmBuild") {
                            doFirst { error("Project compilation is forbidden in shared consumer mode: $path") }
                        }
                    }
                }
                server.tasks.named<Test>("nativeTestList") {
                    outputs.cacheIf("Native UIDs stay platform-local; only unchanged workspace outputs may be reused") { false }
                    // consumeSharedBuild validates this archive identity before every discovery/reuse decision.
                    inputs.property("ciSharedBuildSha256", project.providers.gradleProperty("ciSharedBuildSha256"))
                    listOf("os.name", "os.arch", "os.version").forEach { property ->
                        inputs.property("nativeDiscovery.$property", project.providers.systemProperty(property))
                    }
                    inputs.property("nativeDiscovery.java.vendor", javaLauncher.map { it.metadata.vendor })
                    inputs.property("nativeDiscovery.java.runtime", javaLauncher.map { it.metadata.javaRuntimeVersion })
                    inputs.property("nativeDiscovery.java.vm", javaLauncher.map { it.metadata.jvmVersion })
                }
            }
        }
        project.gradle.taskGraph.whenReady {
            if (hasTask(prepare.get())) {
                check(!consumer) { "Shared producer and consumer modes cannot be combined" }
                verifyToolchain(root)
                runIdentity(System.getenv(), project.providers.gradleProperty("ciSharedBuildRunId").orNull)
                SharedBuildSource.checkFrontendEnvironment(root, System.getenv())
                beforePreparation = SharedBuildSource.fingerprint(root)
            }
        }
    }

    companion object {
        internal val serverProducers = listOf(
            "generateBuildInfo", "generateMainPlainbaseDbInterface", "copyFrontend",
            "compileKotlin", "compileJava", "processResources", "compileTestKotlin", "compileTestJava", "processTestResources",
            "compileNativeTestKotlin", "compileNativeTestJava", "processNativeTestResources",
            "jar", "startScripts", "installDist", "distTar", "distZip",
        )

        internal fun runIdentity(environment: Map<String, String>, override: String?): String {
            if (environment["GITHUB_ACTIONS"] == "true") {
                require(override == null) { "ciSharedBuildRunId is forbidden inside Actions" }
                return requireNotNull(environment["GITHUB_RUN_ID"]?.takeIf { it.matches(Regex("[0-9]+")) }) { "Missing GITHUB_RUN_ID" }
            }
            return requireNotNull(override?.takeIf { it.matches(Regex("local-[A-Za-z0-9._-]+")) }) {
                "Local shared builds require an explicit ciSharedBuildRunId=local-<unique-id>"
            }
        }

        private fun identity(project: Project, root: Path): SharedBuildIdentity {
            verifyToolchain(root)
            return SharedBuildIdentity(
                SharedBuildSource.commit(root), SharedBuildSource.fingerprint(root),
                runIdentity(System.getenv(), project.providers.gradleProperty("ciSharedBuildRunId").orNull), project.version.toString(),
            )
        }

        private fun verifyToolchain(root: Path) {
            require(Runtime.version().feature() == 25) { "Shared builds require a compatible Java 25 runtime" }
            require(GradleVersion.current().version == SHARED_BUILD_GRADLE_VERSION) {
                "Shared builds require Gradle $SHARED_BUILD_GRADLE_VERSION"
            }
            val wrapper = Properties().apply { Files.newInputStream(root.resolve("gradle/wrapper/gradle-wrapper.properties")).use(::load) }
            require(wrapper.getProperty("distributionUrl").endsWith("/gradle-$SHARED_BUILD_GRADLE_VERSION-bin.zip")) {
                "Shared builds require the pinned wrapper"
            }
        }

        private fun frontendVersions(frontend: Project): Map<String, String> =
            listOf("node" to "ciSharedBuildNodeVersion", "npm" to "ciSharedBuildNpmVersion").associate { (name, key) ->
                val configured = frontend.extensions.extraProperties.get(key) as? Provider<*>
                val version = configured?.orNull as? String
                require(!version.isNullOrBlank()) { "Shared builds require :frontend's configured $name version provider ($key)" }
                name to version
            }

        internal fun verifyRuntimeTar(command: List<String>, diagnosticsDirectory: Path, timeoutSeconds: Long = 60) {
            val diagnostics = Files.createTempFile(diagnosticsDirectory, "runtime-verifier-", ".log")
            val reader = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "shared-runtime-diagnostics").apply { isDaemon = true }
            }
            var process: Process? = null
            try {
                val child = ProcessBuilder(command).redirectErrorStream(true).start()
                process = child
                child.outputStream.close()
                val captured = reader.submit {
                    child.inputStream.use { input ->
                        Files.newOutputStream(diagnostics).use { output ->
                            val buffer = ByteArray(4096)
                            var remaining = 16 * 1024
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                // Keep draining after the limit so a noisy helper cannot block or grow the temporary file.
                                val kept = minOf(count, remaining)
                                output.write(buffer, 0, kept)
                                remaining -= kept
                            }
                        }
                    }
                }
                val completed = child.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!completed) {
                    child.destroyForcibly()
                    check(child.waitFor(5, TimeUnit.SECONDS)) { "Could not reap runtime verifier" }
                }
                captured.get(5, TimeUnit.SECONDS)
                val detail = Files.readAllBytes(diagnostics).toString(Charsets.UTF_8) +
                    if (Files.size(diagnostics) == 16 * 1024L) "\n[diagnostics truncated]" else ""
                check(completed) { "Runtime tar verification timed out: $detail" }
                check(child.exitValue() == 0) {
                    "Runtime tar differs from the validated installed distribution (verifier exit ${child.exitValue()}): $detail"
                }
            } finally {
                try {
                    process?.let { child ->
                        if (child.isAlive) {
                            child.destroyForcibly()
                            check(child.waitFor(5, TimeUnit.SECONDS)) { "Could not reap runtime verifier" }
                        }
                        child.inputStream.close()
                    }
                } finally {
                    reader.shutdownNow()
                    try {
                        check(reader.awaitTermination(5, TimeUnit.SECONDS)) { "Could not stop runtime diagnostic reader" }
                    } finally {
                        Files.deleteIfExists(diagnostics)
                    }
                }
            }
        }

        private fun archivePaths(server: Project): List<String> = listOf("jar", "distTar", "distZip").map { name ->
            server.rootDir.toPath().relativize(server.tasks.named<AbstractArchiveTask>(name).get().archiveFile.get().asFile.toPath())
                .toString().replace('\\', '/')
        }

        private fun contract(server: Project): SharedBuildContract = SharedBuildContract(archivePaths(server))

        private fun dependencies(server: Project): Map<String, List<SharedDependency>> =
            SharedBuildContract.configurations.associateWith { name ->
                // Only external configurations: SourceSet outputs would introduce builtBy cycles back to consumeSharedBuild.
                // Cross-project resolution in root task actions is not configuration-cache/isolated-project compatible.
                server.configurations.getByName(name).incoming.artifacts.artifacts.mapNotNull { artifact ->
                    val component = artifact.id.componentIdentifier as? ModuleComponentIdentifier ?: return@mapNotNull null
                    val attributes = artifact.variant.attributes
                    val variant = attributes.keySet().sortedBy { it.name }.map { key -> "${key.name}=${attributes.getAttribute(key)}" } +
                        artifact.variant.capabilities.map { "capability=${it.group}:${it.name}:${it.version}" }.sorted()
                    SharedDependency(
                        "${component.group}:${component.module}:${component.version}|${artifact.id.displayName}",
                        variant.joinToString(";"), artifact.file.name, SharedBuildFiles.sha256(artifact.file.toPath()),
                    )
                }
            }

        private fun exportHash(project: Project, name: String, path: Path) {
            val hash = SharedBuildFiles.sha256(path)
            project.logger.lifecycle("{}={}", name, hash)
            project.providers.environmentVariable("GITHUB_OUTPUT").orNull?.let { output ->
                Path.of(output).toFile().appendText("$name=$hash\n")
            }
        }
    }
}
