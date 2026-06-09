plugins {
    // Auto-provisions the JDK 21 toolchain when the host JDK differs.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "plainbase"

include(":server")
include(":frontend")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
