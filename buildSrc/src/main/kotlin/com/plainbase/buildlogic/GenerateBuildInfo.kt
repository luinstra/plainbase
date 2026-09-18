package com.plainbase.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Generated source is tied to the current project version.")
abstract class GenerateBuildInfo : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val versionValue = version.get()
        require(versionValue.matches(Regex("[A-Za-z0-9.+-]+"))) { "unexpected version: $versionValue" }
        val packageDir = outputDirectory.get().asFile.resolve("com/plainbase")
        packageDir.mkdirs()
        packageDir.resolve("BuildInfo.kt").writeText(
            """
            |package com.plainbase
            |
            |// GENERATED at build time from `project.version` - do not edit.
            |// Source/task: buildSrc/src/main/kotlin/com/plainbase/buildlogic/GenerateBuildInfo.kt:generateBuildInfo.
            |object BuildInfo {
            |    const val VERSION: String = "$versionValue"
            |}
            |
            """.trimMargin(),
        )
    }
}
