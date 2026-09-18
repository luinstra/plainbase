package com.plainbase.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

class NativeTestsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        var kotlinJvmApplied = false
        var graalvmApplied = false
        var configured = false

        fun configureIfReady() {
            if (!configured && kotlinJvmApplied && graalvmApplied) {
                configured = true
                val wiring = TestSourceSets.configure(project)
                NativeBuildTasks.configure(project, wiring)
                GitChildProcessGates.configure(project, wiring)
            }
        }

        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            kotlinJvmApplied = true
            configureIfReady()
        }
        project.pluginManager.withPlugin("org.graalvm.buildtools.native") {
            graalvmApplied = true
            configureIfReady()
        }
    }
}
