// Root build — module configuration lives in :server and :frontend and the
// version catalog (gradle/libs.versions.toml). The root only carries
// project-wide formatting for the root-level Gradle scripts.

plugins {
    alias(libs.plugins.spotless)
}

group = "com.plainbase"
version = "0.1.0-SNAPSHOT"

spotless {
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}
