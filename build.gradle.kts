import org.jmailen.gradle.kotlinter.tasks.FormatTask
import org.jmailen.gradle.kotlinter.tasks.LintTask

// Root build — module configuration lives in :server and :frontend and the
// version catalog (gradle/libs.versions.toml). The root only carries
// project-wide formatting and the CI shared-build handoff.

plugins {
    alias(libs.plugins.kotlinter)
    alias(libs.plugins.kover)
    id("plainbase.shared-build")
}

group = "com.plainbase"
// Workflows set ORG_GRADLE_PROJECT_releaseVersion: CI uses 0.0.0-ci and releases use the validated tag version.
// Local source builds default to the snapshot; -PreleaseVersion can override it explicitly.
// :server inherits this value and reports it through generated BuildInfo, including consumed shared bytes.
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
        include("buildSrc/*.gradle.kts")
        include("server/*.gradle.kts")
        include("buildSrc/src/**/*.kt")
        include("server/src/**/*.kt")
        exclude("**/build/**")
    }

tasks.register<LintTask>("lintKotlin") {
    group = "verification"
    dependsOn(":server:detekt")
    source(kotlinFormattingSources)
    reports.set(mapOf("plain" to layout.buildDirectory.file("reports/kotlinter/lint.txt").get().asFile))
}

tasks.register<FormatTask>("formatKotlin") {
    group = "verification"
    source(kotlinFormattingSources)
    report.set(layout.buildDirectory.file("reports/kotlinter/format.txt"))
}
