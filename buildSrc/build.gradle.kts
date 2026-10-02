plugins {
    `kotlin-dsl`
}

dependencies {
    testImplementation(gradleTestKit())
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    implementation(
        "${libs.plugins.kotlin.jvm.get().pluginId}:" +
            "${libs.plugins.kotlin.jvm.get().pluginId}.gradle.plugin:" +
            libs.plugins.kotlin.jvm.get().version.requiredVersion,
    )
    implementation(
        "${libs.plugins.kotlin.serialization.get().pluginId}:" +
            "${libs.plugins.kotlin.serialization.get().pluginId}.gradle.plugin:" +
            libs.plugins.kotlin.serialization.get().version.requiredVersion,
    )
    implementation(
        "${libs.plugins.graalvm.native.get().pluginId}:" +
            "${libs.plugins.graalvm.native.get().pluginId}.gradle.plugin:" +
            libs.plugins.graalvm.native.get().version.requiredVersion,
    )
}

gradlePlugin {
    plugins {
        register("serverBuild") {
            id = "plainbase.server-build"
            implementationClass = "com.plainbase.buildlogic.ServerBuildPlugin"
        }
        register("nativeTests") {
            id = "plainbase.native-tests"
            implementationClass = "com.plainbase.buildlogic.NativeTestsPlugin"
        }
        register("sharedBuild") {
            id = "plainbase.shared-build"
            implementationClass = "com.plainbase.buildlogic.SharedBuildPlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
