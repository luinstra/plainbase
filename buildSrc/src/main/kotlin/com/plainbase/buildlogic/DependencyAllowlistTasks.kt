package com.plainbase.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "The allowlist writer is an explicit, always-executed operator action.")
abstract class WriteDependencyAllowlist : DefaultTask() {
    @get:Input
    abstract val modules: ListProperty<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun write() {
        val resolvedModules = modules.get()
        outputFile.get().asFile.writeText(resolvedModules.joinToString("\n", postfix = "\n"))
        logger.lifecycle("Wrote ${outputFile.get().asFile.name} (${resolvedModules.size} modules)")
    }
}

@DisableCachingByDefault(because = "Verification has no outputs and must inspect the resolved runtime classpath.")
abstract class VerifyDependencyAllowlist : DefaultTask() {
    @get:Input
    abstract val modules: ListProperty<String>

    @get:Input
    abstract val expectedModules: ListProperty<String>

    @TaskAction
    fun verify() {
        val expected = expectedModules.get().toSet()
        val actual = modules.get().toSet()
        val added = (actual - expected).sorted()
        val removed = (expected - actual).sorted()
        if (added.isNotEmpty() || removed.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Server runtime dependencies drifted from dependency-allowlist.txt.")
                    if (added.isNotEmpty()) appendLine("  Added:   ${added.joinToString(", ")}")
                    if (removed.isNotEmpty()) appendLine("  Removed: ${removed.joinToString(", ")}")
                    appendLine("New dependencies must be justified against the native gate (master plan §3).")
                    append("If deliberate: run ./gradlew :server:writeDependencyAllowlist and commit the result.")
                },
            )
        }
    }
}
