// Root build — module configuration lives in :server and :frontend and the
// version catalog (gradle/libs.versions.toml). The root only carries
// project-wide formatting for the root-level Gradle scripts.

plugins {
    alias(libs.plugins.spotless)
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

spotless {
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}
