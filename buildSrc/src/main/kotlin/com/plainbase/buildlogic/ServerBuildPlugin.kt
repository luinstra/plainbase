package com.plainbase.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

class ServerBuildPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            val generatedBuildInfoDir = project.layout.buildDirectory.dir("generated/source/buildInfo/kotlin")
            val generateBuildInfo = project.tasks.register<GenerateBuildInfo>("generateBuildInfo") {
                group = "build"
                description = "Generates com.plainbase.BuildInfo.VERSION from project.version"
                version.set(project.providers.provider { project.version.toString() })
                outputDirectory.set(generatedBuildInfoDir)
            }
            project.extensions.getByType<KotlinJvmProjectExtension>().sourceSets.named("main") {
                kotlin.srcDir(generateBuildInfo)
            }

            val runtimeClasspath = project.configurations.named("runtimeClasspath")
            val runtimeModules = project.providers.provider {
                runtimeClasspath.get().incoming.resolutionResult.allComponents
                    .mapNotNull { component ->
                        (component.id as? ModuleComponentIdentifier)?.let { "${it.group}:${it.module}" }
                    }
                    .distinct()
                    .sorted()
            }
            val dependencyAllowlistFile = project.layout.projectDirectory.file("dependency-allowlist.txt")
            val expectedModules = project.providers.provider {
                if (dependencyAllowlistFile.asFile.exists()) {
                    dependencyAllowlistFile.asFile.readLines().filter { it.isNotBlank() }.distinct().sorted()
                } else {
                    emptyList()
                }
            }
            project.tasks.register<WriteDependencyAllowlist>("writeDependencyAllowlist") {
                group = "verification"
                description =
                    "Regenerate dependency-allowlist.txt from the resolved runtime classpath (a deliberate act - see comment above)"
                modules.set(runtimeModules)
                outputFile.set(dependencyAllowlistFile)
            }
            val verifyDependencyAllowlist = project.tasks.register<VerifyDependencyAllowlist>("verifyDependencyAllowlist") {
                group = "verification"
                description = "Fail if the server runtime dependency set drifted from dependency-allowlist.txt"
                modules.set(runtimeModules)
                this.expectedModules.set(expectedModules)
            }

            project.tasks.named("check") {
                dependsOn(project.rootProject.tasks.named("lintKotlin"), verifyDependencyAllowlist)
            }
        }
    }
}
