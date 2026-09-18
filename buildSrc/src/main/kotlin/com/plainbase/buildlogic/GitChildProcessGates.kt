package com.plainbase.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.Exec
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import java.io.File

internal object GitChildProcessGates {
    fun configure(project: Project, wiring: NativeTestWiring) {
        val runnerSource = project.layout.projectDirectory.file("scripts/GitChildProcessCleanup.java").asFile
        val java = project.extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
        val nativeTestListDir = wiring.nativeTestListDir
        val nativeOutput = wiring.nativeTestCompile.flatMap { it.outputFile }
        val jvmReportRoot = project.layout.buildDirectory.dir("reports/g3z/jvm").get().asFile
        val nativeReportRoot = project.layout.buildDirectory.dir("reports/g3z/native").get().asFile
        val forcedReportRoot = project.layout.buildDirectory.dir("reports/g3z/forced").get().asFile

        val gitChildProcessCleanupRunnerTest = project.tasks.register<Exec>("gitChildProcessCleanupRunnerTest") {
            group = "verification"
            description = "Run the platform-neutral Git child-process runner self-test"
            inputs.file(runnerSource)
            outputs.upToDateWhen { false }
            commandLine(java.get().executablePath.asFile, runnerSource.absolutePath, "--self-test")
        }
        val gitZombieJvmPid1 = project.tasks.register<Exec>("gitZombieJvmPid1") {
            description = "Run the Linux PID1 Git child-process JVM gate"
            dependsOn(wiring.nativeTestClasses)
            inputs.files(wiring.nativeTestRuntimeClasspath)
                .withPropertyName("g3zNativeTestRuntimeClasspath")
                .withNormalizer(ClasspathNormalizer::class.java)
            configureG3z(runnerSource, jvmReportRoot) {
                val executable = java.get().executablePath.asFile
                commandLine(
                    executable,
                    runnerSource.absolutePath,
                    "--mode",
                    "jvm",
                    "--report-dir",
                    jvmReportRoot.absolutePath,
                    "--java",
                    executable.absolutePath,
                    "--classpath",
                    wiring.nativeTestRuntimeClasspath.asPath,
                )
            }
        }
        val gitZombieNativePid1 = project.tasks.register<Exec>("gitZombieNativePid1") {
            description = "Run the Linux PID1 Git child-process native gate"
            dependsOn(wiring.nativeTestCompile, wiring.nativeTestList)
            inputs.file(nativeOutput).withPropertyName("g3zNativeExecutable")
            inputs.dir(nativeTestListDir).withPropertyName("g3zNativeTestList")
            configureG3z(runnerSource, nativeReportRoot) {
                val executable = java.get().executablePath.asFile
                commandLine(
                    executable,
                    runnerSource.absolutePath,
                    "--mode",
                    "native",
                    "--report-dir",
                    nativeReportRoot.absolutePath,
                    "--native-image",
                    nativeOutput.get().asFile.absolutePath,
                    "--uid-dir",
                    nativeTestListDir.get().asFile.absolutePath,
                )
            }
        }
        val gitZombieForcedTimeoutPid1 = project.tasks.register<Exec>("gitZombieForcedTimeoutPid1") {
            description = "Run the Linux PID1 forced-timeout Git child-process gate"
            dependsOn(wiring.nativeTestClasses)
            inputs.files(wiring.nativeTestRuntimeClasspath)
                .withPropertyName("g3zNativeTestRuntimeClasspath")
                .withNormalizer(ClasspathNormalizer::class.java)
            configureG3z(runnerSource, forcedReportRoot) {
                val executable = java.get().executablePath.asFile
                commandLine(
                    executable,
                    runnerSource.absolutePath,
                    "--mode",
                    "forced",
                    "--report-dir",
                    forcedReportRoot.absolutePath,
                    "--java",
                    executable.absolutePath,
                    "--classpath",
                    wiring.nativeTestRuntimeClasspath.asPath,
                )
            }
        }

        val linuxOnlyTaskNames = setOf(
            gitZombieJvmPid1.name,
            gitZombieNativePid1.name,
            gitZombieForcedTimeoutPid1.name,
        )
        project.gradle.taskGraph.whenReady {
            if (System.getProperty("os.name") != "Linux") {
                val requested = allTasks.filter { it.name in linuxOnlyTaskNames }
                if (requested.isNotEmpty()) {
                    throw GradleException(
                        "G3z PID1 tasks are unsupported on non-Linux hosts; refusing expensive dependencies: " +
                            requested.joinToString(", ") { it.path },
                    )
                }
            }
        }

        project.tasks.named("check") { dependsOn(gitChildProcessCleanupRunnerTest) }
    }

    private fun Exec.configureG3z(runnerSource: File, reportRoot: File, command: Exec.() -> Unit) {
        group = "verification"
        inputs.file(runnerSource).withPropertyName("g3zRunnerSource")
        outputs.dir(reportRoot)
        outputs.upToDateWhen { false }
        doFirst { command() }
    }
}
