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

// Unit/snapshot tests + the §5.9 token-discipline gate (vitest). Part of `build`, so the
// JAR floor (`./gradlew build`) fails on a hex color outside tokens.css.
val npmTest = tasks.register<NpmTask>("npmTest") {
    dependsOn(tasks.npmInstall)
    args.set(listOf("run", "test"))
    inputs.files("package.json", "package-lock.json", "vite.config.ts", "tsconfig.json", "index.html")
    inputs.dir("src")
    inputs.dir("e2e") // scanned by the token-discipline test
    outputs.upToDateWhen { false }
}

// Targeted single-file vitest run for fast iteration (NOT part of `build`, no token-discipline gate):
//   ./gradlew :frontend:vitestFile -PtestFile=src/__tests__/searchPalette.test.tsx
// Omit -PtestFile to run the whole suite. Prefer this (or :frontend:npmTest) over a raw
// `./node_modules/.bin/vitest` invocation so the command stays a stable, whitelistable `./gradlew :*`.
tasks.register<NpmTask>("vitestFile") {
    group = "verification"
    description = "Runs vitest for a single file via -PtestFile=<path> (fast iteration)."
    dependsOn(tasks.npmInstall)
    val testFile = providers.gradleProperty("testFile").getOrElse("")
    args.set(listOf("run", "test", "--") + if (testFile.isBlank()) emptyList() else listOf(testFile))
    outputs.upToDateWhen { false }
}

tasks.register("build") {
    dependsOn(npmBuild, npmTest)
}

// Playwright smoke flow against the REAL server (installed dist + embedded SPA + fixture
// tree) — see playwright.config.ts. A separate invocation, NOT part of `build`: it
// downloads a Chromium on first run, which would break the hermetic JAR floor.
tasks.register<NpmTask>("smokeTest") {
    group = "verification"
    description = "Runs the Playwright smoke flow against the installed server serving fixtures/demo-docs"
    dependsOn(tasks.npmInstall, ":server:installDist")
    args.set(listOf("run", "smoke"))
}

tasks.register<Delete>("clean") {
    delete(layout.projectDirectory.dir("dist"))
}
