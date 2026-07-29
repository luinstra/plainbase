import org.jmailen.gradle.kotlinter.tasks.FormatTask
import org.jmailen.gradle.kotlinter.tasks.LintTask

// Root build — module configuration lives in :server and :frontend and the
// version catalog (gradle/libs.versions.toml). The root only carries
// project-wide formatting for the root-level Gradle scripts.

plugins {
    alias(libs.plugins.kotlinter)
    alias(libs.plugins.kover)
}

group = "com.plainbase"
// C5: the release workflow drives this from the tag (`-PreleaseVersion=0.1.0`, `.github/workflows/release.yml`);
// dev/CI builds fall back to the snapshot. `:server` inherits this via `version = rootProject.version`
// (server/build.gradle.kts) and self-reports it through the generated `BuildInfo` (item 8).
version = (findProperty("releaseVersion") as String?)?.takeIf { it.isNotBlank() } ?: "0.1.0-SNAPSHOT"

dependencies {
    kover(project(":server"))
}

kotlinter {
    ktlintVersion = libs.versions.ktlint.get()
}

val kotlinFormattingSources =
    fileTree(rootDir) {
        include("*.gradle.kts")
        include("server/*.gradle.kts")
        include("server/src/**/*.kt")
        exclude("**/build/**")
    }

tasks.register<LintTask>("lintKotlin") {
    group = "verification"
    source(kotlinFormattingSources)
    reports.set(mapOf("plain" to layout.buildDirectory.file("reports/kotlinter/lint.txt").get().asFile))
}

tasks.register<FormatTask>("formatKotlin") {
    group = "verification"
    source(kotlinFormattingSources)
    report.set(layout.buildDirectory.file("reports/kotlinter/format.txt"))
}
