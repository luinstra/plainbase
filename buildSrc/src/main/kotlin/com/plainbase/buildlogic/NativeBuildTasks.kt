package com.plainbase.buildlogic

import org.graalvm.buildtools.gradle.dsl.GraalVMExtension
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

internal object NativeBuildTasks {
    fun configure(project: Project, wiring: NativeTestWiring) {
        val graalvmNative = project.extensions.getByType<GraalVMExtension>()
        graalvmNative.binaries.named("test") {
            classpath(wiring.nativeTestRuntimeClasspath, wiring.nativeTest.output)
            buildArgs.add("--no-fallback")
            buildArgs.add("--enable-native-access=ALL-UNNAMED")
            buildArgs.add("-J-Xmx6g")
            resources.autodetect()
        }

        wiring.nativeTestCompile.configure {
            dependsOn(wiring.nativeTestList)
            setOnlyIf { graalvmNative.testSupport.get() }
            testListDirectory.set(wiring.nativeTestListDir)
            options.get().classpath.setFrom(wiring.nativeTestRuntimeClasspath, wiring.nativeTest.output)
            doFirst {
                val recordedIds = wiring.nativeTestListDir.get().asFile.walk()
                    .filter { it.isFile }
                    .flatMap { it.readLines().asSequence() }
                    .filter { it.isNotBlank() }
                    .toList()
                if (recordedIds.isEmpty()) {
                    throw GradleException(
                        "Native gate has no tests: src/nativeTest produced an empty UID list, so the native " +
                            "image would be vacuously green. Ensure src/nativeTest holds at least one " +
                            "@Tag(\"native\") kotlin.test test.",
                    )
                }
                logger.lifecycle("Native gate: building image from ${recordedIds.size} recorded native test id(s).")
            }
        }

        wiring.nativeTestTask.configure {
            setOnlyIf { graalvmNative.testSupport.get() }
            runtimeArgs.add(
                wiring.nativeTestListDir.map {
                    "-Djunit.platform.listeners.uid.tracking.output.dir=${it.asFile.absolutePath}"
                },
            )
        }

        val mainRuntimeClasspath = project.extensions.getByType<SourceSetContainer>()
            .getByName("main")
            .runtimeClasspath
        val graalvmHome = project.providers.environmentVariable("GRAALVM_HOME")
        val javaHome = project.providers.environmentVariable("JAVA_HOME")
        project.tasks.register<Exec>("traceMcpSseMetadata") {
            group = "verification"
            description = "Run the spike under -agentlib:native-image-agent to regenerate kotlin-sdk SSE reflect metadata"
            dependsOn(project.tasks.named("classes"))
            doFirst {
                val home =
                    graalvmHome.orNull?.takeIf { it.isNotBlank() }
                        ?: javaHome.orNull?.takeIf { it.isNotBlank() }
                        ?: throw GradleException(
                            "traceMcpSseMetadata needs GraalVM via GRAALVM_HOME or JAVA_HOME",
                        )
                commandLine(
                    "$home/bin/java",
                    "--enable-native-access=ALL-UNNAMED",
                    "-agentlib:native-image-agent=config-merge-dir=" +
                        "src/main/resources/META-INF/native-image/io.modelcontextprotocol/kotlin-sdk",
                    "-cp",
                    mainRuntimeClasspath.asPath,
                    "com.plainbase.ApplicationKt",
                    "spike",
                )
            }
        }
    }
}
