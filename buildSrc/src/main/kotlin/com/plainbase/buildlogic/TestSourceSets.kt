package com.plainbase.buildlogic

import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask
import org.graalvm.buildtools.gradle.tasks.NativeRunTask
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import java.util.UUID

internal data class NativeTestWiring(
    val nativeTest: SourceSet,
    val nativeTestList: TaskProvider<Test>,
    val nativeTestListDir: Provider<Directory>,
    val nativeTestRuntimeClasspath: FileCollection,
    val nativeTestClasses: TaskProvider<org.gradle.api.Task>,
    val nativeTestCompile: TaskProvider<BuildNativeImageTask>,
    val nativeTestTask: TaskProvider<NativeRunTask>,
)

internal object TestSourceSets {
    fun configure(project: Project): NativeTestWiring {
        val sourceSets = project.extensions.getByType<SourceSetContainer>()
        val mainSourceSet = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
        val nativeTest = sourceSets.create("nativeTest") {
            compileClasspath += mainSourceSet.output
            runtimeClasspath += mainSourceSet.output
            resources.srcDir("src/test/resources")
        }

        val kotlin = project.extensions.getByType<KotlinJvmProjectExtension>()
        kotlin.target.compilations.getByName("nativeTest")
            .associateWith(kotlin.target.compilations.getByName("main"))
        project.configurations.named("nativeTestImplementation") {
            extendsFrom(project.configurations.getByName("implementation"))
        }
        project.configurations.named("nativeTestRuntimeOnly") {
            extendsFrom(project.configurations.getByName("runtimeOnly"))
        }

        val mainRuntimeClasspathInput = mainSourceSet.runtimeClasspath
        val testRuntimeClasspathInput = sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME).runtimeClasspath

        fun Test.configurePlainbaseMainRuntimeClasspath() {
            inputs.files(mainRuntimeClasspathInput)
                .withPropertyName("plainbaseMainRuntimeClasspath")
                .withNormalizer(ClasspathNormalizer::class.java)
            doFirst {
                systemProperty("plainbase.test.mainRuntimeClasspath", mainRuntimeClasspathInput.asPath)
            }
        }

        project.tasks.named<Test>("test") {
            useJUnitPlatform()
            configurePlainbaseMainRuntimeClasspath()
            inputs.files(testRuntimeClasspathInput)
                .withPropertyName("plainbaseTestRuntimeClasspath")
                .withNormalizer(ClasspathNormalizer::class.java)
            doFirst {
                systemProperty("plainbase.test.childRuntimeClasspath", testRuntimeClasspathInput.asPath)
                systemProperty(
                    "plainbase.test.evidenceDir",
                    project.layout.buildDirectory.dir("reports/cli-contract/run-${UUID.randomUUID()}").get().asFile.absolutePath,
                )
            }
            testClassesDirs += nativeTest.output.classesDirs
            classpath += nativeTest.runtimeClasspath
            testLogging {
                events("passed", "failed", "skipped")
                showStandardStreams = true
            }
        }

        // The existing check graph reaches this task; keep it as a named acceptance handle.
        project.tasks.register<Test>("acceptanceTest") {
            description = "Runs ONLY the Phase-1 acceptance gate (Phase1AcceptanceTest + ForeverApiGoldenSuite)."
            group = "verification"
            useJUnitPlatform()
            testClassesDirs = sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME).output.classesDirs
            classpath = sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME).runtimeClasspath
            configurePlainbaseMainRuntimeClasspath()
            filter { includeTestsMatching("com.plainbase.acceptance.*") }
            testLogging { events("passed", "failed", "skipped") }
        }

        val nativeTestListDir = project.layout.buildDirectory.dir("test-results/nativeTestList/testlist")
        // The existing check graph reaches this task through native-test wiring; it records the native UID list.
        val nativeTestList = project.tasks.register<Test>("nativeTestList") {
            description = "Runs ONLY the nativeTest source set, recording the test list for the native image."
            group = "verification"
            testClassesDirs = nativeTest.output.classesDirs
            classpath = nativeTest.runtimeClasspath
            useJUnitPlatform()
        }
        nativeTestList.configure {
            systemProperty("junit.platform.listeners.uid.tracking.enabled", true)
            systemProperty(
                "junit.platform.listeners.uid.tracking.output.dir",
                nativeTestListDir.get().asFile.absolutePath,
            )
            outputs.dir(nativeTestListDir)
            doFirst { nativeTestListDir.get().asFile.deleteRecursively() }
        }

        return NativeTestWiring(
            nativeTest = nativeTest,
            nativeTestList = nativeTestList,
            nativeTestListDir = nativeTestListDir,
            nativeTestRuntimeClasspath = nativeTest.runtimeClasspath,
            nativeTestClasses = project.tasks.named("nativeTestClasses"),
            nativeTestCompile = project.tasks.named<BuildNativeImageTask>("nativeTestCompile"),
            nativeTestTask = project.tasks.named<NativeRunTask>("nativeTest"),
        )
    }
}
