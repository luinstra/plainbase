package com.plainbase.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.Properties
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedBuildPluginTest {
    @TempDir
    lateinit var root: Path

    private fun write(path: String, value: String) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, value)
    }

    private fun environment(parentEnvironment: Map<String, String> = System.getenv()): Map<String, String> = parentEnvironment.filterKeys {
        !it.startsWith("ORG_GRADLE_PROJECT_") &&
            it !in setOf("CI_SHARED_BUILD_REQUIRED", "GITHUB_ACTIONS", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_OUTPUT") &&
            !it.startsWith("VITE_")
    }

    private fun runner(
        vararg arguments: String,
        required: Boolean = false,
        parentEnvironment: Map<String, String> = System.getenv(),
    ): GradleRunner = GradleRunner.create()
        .withProjectDir(root.toFile()).withPluginClasspath()
        .withEnvironment(environment(parentEnvironment) + if (required) mapOf("CI_SHARED_BUILD_REQUIRED" to "true") else emptyMap())
        .withArguments(*arguments, "--console=plain", "--stacktrace")

    private fun fixture() {
        write("settings.gradle", "rootProject.name = 'fixture'\ninclude 'server', 'frontend'\n")
        write(
            "build.gradle",
            """
            plugins { id 'plainbase.shared-build' }
            version = providers.gradleProperty('releaseVersion').getOrElse('0.1.0-SNAPSHOT')
        """.trimIndent(),
        )
        write(
            "gradle/wrapper/gradle-wrapper.properties",
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n",
        )
        // A read-only Git identity fixture: no commands mutate the real repository or a test repository.
        write(".git/HEAD", "${"a".repeat(40)}\n")
        Files.createDirectories(root.resolve(".git/objects"))
        Files.createDirectories(root.resolve(".git/refs"))
        write(".git/config", "[core]\nrepositoryformatversion = 0\nbare = false\n")
        val repository = root.resolve("repository/org/example/library/1")
        Files.createDirectories(repository)
        JarOutputStream(Files.newOutputStream(repository.resolve("library-1.jar"))).close()
        write(
            "repository/org/example/library/1/library-1.pom",
            "<project><modelVersion>4.0.0</modelVersion><groupId>org.example</groupId>" +
                "<artifactId>library</artifactId><version>1</version></project>",
        )
        write(
            "frontend/build.gradle",
            """
            ext.ciSharedBuildNodeVersion = providers.provider { '26.8.2' }
            ext.ciSharedBuildNpmVersion = providers.provider { '12.0.2' }
            tasks.register('nodeSetup') { doLast { println 'NODE_ACTION' } }
            tasks.register('npmSetup') { dependsOn 'nodeSetup' }
            tasks.register('npmInstall') { dependsOn 'npmSetup' }
            tasks.register('npmBuild') {
                dependsOn 'npmInstall'
                doLast {
                    file('dist/assets').mkdirs()
                    file('dist/index.html').text = 'index'
                    file('dist/assets/app.js').text = 'assets'
                    println 'VITE_ACTION'
                }
            }
            tasks.register('build') { dependsOn 'npmBuild' }
        """.trimIndent(),
        )
        write("server/src/main/java/Example.java", "public class Example { public static void main(String[] args) {} }")
        write(
            "server/build.gradle",
            """
            plugins { id 'application' }
            version = rootProject.version
            application { mainClass = 'Example'; applicationName = 'plainbase' }
            java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
            repositories { maven { url = rootProject.file('repository') } }
            configurations { nativeTestRuntimeClasspath { extendsFrom runtimeClasspath } }
            dependencies { implementation 'org.example:library:1' }
            distributions.main.contents.exclude('**/plainbase.bat')
            def put = { path, value -> def f = file(path); f.parentFile.mkdirs(); f.bytes = value as byte[] }
            ['main', 'test', 'nativeTest'].each { set ->
                def name = set == 'main' ? 'compileKotlin' : 'compile' + set.capitalize() + 'Kotlin'
                tasks.register(name) {
                    doLast {
                        put("build/classes/kotlin/" + set + "/Example.class", [-54, -2, -70, -66, 0, 0, 0, 69])
                        put("build/classes/kotlin/" + set + "/META-INF/fixture.kotlin_module", [1])
                        println 'KOTLIN_ACTION'
                    }
                }
            }
            tasks.register('generateBuildInfo') {
                doLast { put('build/generated/source/buildInfo/kotlin/com/plainbase/BuildInfo.kt', rootProject.version.toString().bytes) }
            }
            tasks.register('generateMainPlainbaseDbInterface') {
                doLast { put('build/generated/sqldelight/code/PlainbaseDb/main/Database.kt', [1]) }
            }
            tasks.register('copyFrontend') {
                dependsOn ':frontend:npmBuild'
                doLast {
                    put('build/generated/frontend/static/index.html', [1])
                    put('build/resources/main/static/index.html', [1])
                    put('build/resources/main/META-INF/native-image/reflect-config.json', [1])
                }
            }
            tasks.named('processResources') { dependsOn 'copyFrontend' }
            sourceSets.main.resources.srcDir('build/generated/frontend')
            tasks.named('classes') { dependsOn 'compileKotlin', 'generateBuildInfo', 'generateMainPlainbaseDbInterface' }
            tasks.named('testClasses') { dependsOn 'classes', 'compileTestKotlin' }
            tasks.register('compileNativeTestJava', JavaCompile) {
                source = files()
                classpath = files()
                destinationDirectory = layout.buildDirectory.dir('classes/java/nativeTest')
            }
            tasks.register('processNativeTestResources') { }
            tasks.register('nativeTestClasses') {
                dependsOn 'classes', 'compileNativeTestKotlin', 'compileNativeTestJava', 'processNativeTestResources'
            }
            tasks.register('nativeTestList', Test) { dependsOn 'nativeTestClasses' }
            tasks.register('acceptanceTest', Test) { dependsOn 'testClasses' }
            tasks.register('nativeCompile') { dependsOn 'classes'; doLast { println 'NATIVE_READER' } }
            tasks.register('nativeTestCompile') { dependsOn 'nativeTestList'; doLast { println 'NATIVE_READER' } }
            tasks.register('nativeTest') { dependsOn 'nativeTestCompile'; doLast { println 'NATIVE_READER' } }
            tasks.register('gitZombieJvmPid1') { dependsOn 'nativeTestClasses' }
            tasks.register('gitZombieNativePid1') { dependsOn 'nativeTestCompile' }
            tasks.register('futureCompiler', JavaCompile) {
                source = fileTree('src/main/java')
                classpath = files()
                destinationDirectory = layout.buildDirectory.dir('future')
            }
        """.trimIndent(),
        )
        write("server/src/main/resources/META-INF/native-image/reflect-config.json", "[]")
    }

    @Test
    fun `should preserve source mode without Git or shared identity and fail required blank artifacts`() {
        fixture()
        SharedBuildFiles.deleteTree(root.resolve(".git"))
        val source = runner(":server:classes").build()
        assertEquals(TaskOutcome.SUCCESS, source.task(":server:compileJava")?.outcome)
        assertTrue(source.output.contains("VITE_ACTION"))
        val required = runner("help", required = true).buildAndFail()
        assertTrue(required.output.contains("ciSharedBuild is required"))
        val explicit = runner("help", "-PciSharedBuild=").buildAndFail()
        assertTrue(explicit.output.contains("ciSharedBuild is required"))
    }

    @Test
    fun `should prune producer chains consume before direct readers and return to source mode afterwards`() {
        fixture()
        val prepared = runner("prepareSharedBuild", "-PciSharedBuildRunId=local-fixture").build()
        assertTrue(prepared.task(":server:test") == null && prepared.task(":server:nativeTestList") == null)
        val bundle = root.resolve("build/ci-shared/shared-build.zip")
        val arguments =
            arrayOf(
                "-PciSharedBuild=$bundle", "-PciSharedBuildSha256=${SharedBuildFiles.sha256(bundle)}", "-PciSharedBuildRunId=local-fixture",
            )
        val consumed = runner(
            ":server:classes", ":server:testClasses", ":server:nativeTestClasses", ":server:run",
            ":server:test", ":server:nativeTest", ":server:gitZombieJvmPid1", ":server:gitZombieNativePid1", *arguments,
        ).build()
        assertEquals(TaskOutcome.SUCCESS, consumed.task(":consumeSharedBuild")?.outcome)
        assertEquals(TaskOutcome.SKIPPED, consumed.task(":server:compileJava")?.outcome)
        assertFalse(
            consumed.output.contains("KOTLIN_ACTION") || consumed.output.contains("VITE_ACTION") || consumed.output.contains("NODE_ACTION"),
        )
        assertTrue(consumed.tasks.indexOf(consumed.task(":consumeSharedBuild")) < consumed.tasks.indexOf(consumed.task(":server:run")))
        val future = runner(":server:futureCompiler", *arguments).buildAndFail()
        assertTrue(future.output.contains("Project compilation is forbidden"))
        val source = runner(":server:classes", "--rerun-tasks").build()
        assertEquals(TaskOutcome.SUCCESS, source.task(":server:compileJava")?.outcome)
        assertTrue(source.output.contains("VITE_ACTION"))
    }

    @Test
    fun `should keep parent Actions outputs unchanged when preparing an isolated fixture`() {
        // Arrange
        fixture()
        val parentOutput = root.resolve("parent-actions-output.txt")
        val sentinel = "parent-step=preserved\n"
        Files.writeString(parentOutput, sentinel)
        // Act: inject an inherited Actions output file through the same runner used by every fixture.
        val prepared = runner(
            "prepareSharedBuild", "-PciSharedBuildRunId=local-output-isolation",
            parentEnvironment = System.getenv() + ("GITHUB_OUTPUT" to parentOutput.toString()),
        ).build()
        // Assert: preparation really exports a hash, but cannot append it to the parent step's file.
        assertEquals(TaskOutcome.SUCCESS, prepared.task(":prepareSharedBuild")?.outcome)
        assertTrue(prepared.output.contains("bundle_sha256="))
        assertEquals(sentinel, Files.readString(parentOutput))
    }

    @Test
    fun `should stamp snapshot and explicit release versions in isolated fixtures`() {
        fixture()
        listOf(null, "1.2.3-rc.1").forEach { version ->
            val arguments = if (version == null) emptyArray() else arrayOf("-PreleaseVersion=$version")
            runner("prepareSharedBuild", "-PciSharedBuildRunId=local-version", *arguments).build()
            assertEquals(
                version ?: "0.1.0-SNAPSHOT",
                Files.readString(root.resolve("server/build/generated/source/buildInfo/kotlin/com/plainbase/BuildInfo.kt")),
            )
        }
    }

    @Test
    fun `should reject obsolete shared build profiles instead of silently ignoring them`() {
        fixture()
        listOf("", "runtime").forEach { profile ->
            val result = runner("help", "-PciSharedBuildProfile=$profile").buildAndFail()
            assertTrue(result.output.contains("ciSharedBuildProfile is obsolete"))
            assertTrue(result.output.contains("all three dependency configurations"))
        }
    }

    @Test
    fun `should derive producer frontend provenance from configured providers and validate the metadata round trip`() {
        fixture()
        Files.writeString(
            root.resolve("frontend/build.gradle"),
            "\next.ciSharedBuildNodeVersion = providers.provider { '27.0.0' }\n" +
                "ext.ciSharedBuildNpmVersion = providers.provider { '13.0.0' }\n",
            APPEND,
        )
        runner("prepareSharedBuild", "-PciSharedBuildRunId=local-metadata", "-PreleaseVersion=0.0.0-ci").build()
        val bundle = root.resolve("build/ci-shared/shared-build.zip")
        ZipFile(bundle.toFile()).use { zip ->
            val manifest = Properties().apply { zip.getInputStream(zip.getEntry("manifest.properties")).use(::load) }
            assertEquals("27.0.0", manifest.getProperty("producer.node"))
            assertEquals("13.0.0", manifest.getProperty("producer.npm"))
            assertEquals("0.0.0-ci", manifest.getProperty("version"))
            assertEquals(SHARED_BUILD_GRADLE_VERSION, manifest.getProperty("gradle.version"))
            SharedBuildContract.configurations.forEach { configuration ->
                assertEquals("1", manifest.getProperty("dependency.$configuration.count"))
            }
        }
        val consumed = runner(
            ":server:classes", ":server:testClasses", ":server:nativeTestClasses",
            "-PciSharedBuild=$bundle", "-PciSharedBuildSha256=${SharedBuildFiles.sha256(bundle)}",
            "-PciSharedBuildRunId=local-metadata", "-PreleaseVersion=0.0.0-ci",
        ).build()
        assertEquals(TaskOutcome.SUCCESS, consumed.task(":consumeSharedBuild")?.outcome)
        assertFalse(consumed.output.contains("KOTLIN_ACTION") || consumed.output.contains("VITE_ACTION"))
    }

    @Test
    fun `should include bounded helper failure diagnostics and remove temporary capture files`() {
        val error = assertFailsWith<IllegalStateException> {
            SharedBuildPlugin.verifyRuntimeTar(
                listOf(
                    "python3", "-B", "-c",
                    "import sys; print('useful helper failure', flush=True); " +
                        "print('stderr explanation', file=sys.stderr, flush=True); sys.stdout.write('x' * 20000); sys.exit(7)",
                ),
                root,
            )
        }
        assertTrue(error.message.orEmpty().contains("useful helper failure"))
        assertTrue(error.message.orEmpty().contains("stderr explanation"))
        assertTrue(error.message.orEmpty().contains("verifier exit 7"))
        assertTrue(error.message.orEmpty().contains("diagnostics truncated"))
        assertTrue(error.message.orEmpty().length < 17000)
        assertTrue(Files.list(root).use { it.findAny().isEmpty })
    }

    @Test
    fun `should include helper timeout diagnostics reap the process and remove capture files`() {
        val error = assertFailsWith<IllegalStateException> {
            SharedBuildPlugin.verifyRuntimeTar(
                listOf("python3", "-B", "-c", "import os,time; print('pid=' + str(os.getpid()), flush=True); time.sleep(60)"),
                root,
                timeoutSeconds = 1,
            )
        }
        assertTrue(error.message.orEmpty().contains("verification timed out"))
        val pid = requireNotNull(Regex("pid=([0-9]+)").find(error.message.orEmpty())).groupValues[1].toLong()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        assertTrue(Files.list(root).use { it.findAny().isEmpty })
    }
}
