import com.github.gradle.node.npm.task.NpmTask

plugins {
    alias(libs.plugins.node.gradle)
}

node {
    // Hermetic builds: Gradle downloads its own Node, so Docker/CI only need a JDK.
    download.set(true)
    version.set("22.22.3")
    workDir.set(layout.projectDirectory.dir(".node/node"))
    npmWorkDir.set(layout.projectDirectory.dir(".node/npm"))
}

val npmBuild = tasks.register<NpmTask>("npmBuild") {
    dependsOn(tasks.npmInstall)
    args.set(listOf("run", "build"))
    inputs.files("package.json", "package-lock.json", "vite.config.ts", "tsconfig.json", "index.html")
    inputs.dir("src")
    outputs.dir(layout.projectDirectory.dir("dist"))
}

tasks.register("build") {
    dependsOn(npmBuild)
}

tasks.register<Delete>("clean") {
    delete(layout.projectDirectory.dir("dist"))
}
