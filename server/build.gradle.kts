import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.graalvm.native)
    alias(libs.plugins.spotless)
    application
}

// Rule ENABLEMENT must be passed via editorConfigOverride — Spotless does not honor
// `ktlint_standard_<rule> = disabled` from .editorconfig files (rule CONFIG properties
// like max_line_length work fine from there). Keep in sync with /.editorconfig, which
// carries the same disables for IDE/ktlint-CLI users.
// Owner style: author's layout wins; the formatter enforces the 140-col limit and
// baseline style, but never restructures signatures, when-branches, or `=` wrapping.
val ktlintDisabledRules =
    mapOf(
        "ktlint_standard_class-signature" to "disabled",
        "ktlint_standard_function-signature" to "disabled",
        "ktlint_standard_when-entry-bracing" to "disabled",
        "ktlint_standard_blank-line-between-when-conditions" to "disabled",
        "ktlint_standard_multiline-expression-wrapping" to "disabled",
        // string-template-indent hard-depends on multiline-expression-wrapping
        "ktlint_standard_string-template-indent" to "disabled",
    )

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintDisabledRules)
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintDisabledRules)
    }
}

group = "com.plainbase"
version = rootProject.version

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

application {
    mainClass.set("com.plainbase.ApplicationKt")
    applicationName = "plainbase"
}

dependencies {
    // Ktor server — CIO engine only (native-image constraint; Netty banned)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // kotlinx — the only serializer in the tree
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // Persistence — SQLDelight on xerial sqlite-jdbc (FTS5 included)
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.sqlite.jdbc)

    // DI — Koin constructor DSL only
    implementation(libs.koin.core)

    // Git layer
    implementation(libs.jgit)

    // Markdown
    implementation(libs.flexmark)
    implementation(libs.flexmark.ext.gfm.tables)
    implementation(libs.flexmark.ext.gfm.strikethrough)
    implementation(libs.flexmark.ext.yaml.front.matter)
    implementation(libs.flexmark.ext.anchorlink)

    // argon2 (pure-Java Bouncy Castle — no JNA/JNI)
    implementation(libs.bouncycastle)

    // MCP Kotlin SDK (spike target; full server lands in Phase 5)
    implementation(libs.mcp.kotlin.sdk)

    // Spike needs an HTTP client to round-trip the CIO server
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    // Logging — kotlin-logging facade (house style; already transitive via MCP SDK) over logback
    implementation(libs.kotlin.logging)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
}

sqldelight {
    databases {
        create("PlainbaseDb") {
            packageName.set("com.plainbase.frameworks.sqldelight")
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:${libs.versions.sqldelight.get()}")
            schemaOutputDirectory.set(layout.projectDirectory.dir("src/main/sqldelight/schema"))
            verifyMigrations.set(true)
        }
    }
}

// ---- Frontend embedding: :frontend builds the Vite SPA, we ship it as static resources ----
val copyFrontend =
    tasks.register<Copy>("copyFrontend") {
        dependsOn(":frontend:npmBuild")
        from(rootProject.layout.projectDirectory.dir("frontend/dist"))
        into(layout.buildDirectory.dir("generated/frontend/static"))
    }

sourceSets {
    main {
        resources.srcDir(layout.buildDirectory.dir("generated/frontend"))
    }
}

tasks.processResources {
    dependsOn(copyFrontend)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

// ---- GraalVM native image ----
// Locally this targets the host platform; CI covers linux-x64 (see .github/workflows/ci.yml).
// The universal JAR remains the release floor: a native failure blocks the native artifact only.
graalvmNative {
    toolchainDetection.set(false) // CI/dev provide GraalVM via JAVA_HOME/GRAALVM_HOME
    binaries {
        named("main") {
            imageName.set("plainbase")
            mainClass.set("com.plainbase.ApplicationKt")
            buildArgs.add("--no-fallback")
            buildArgs.add("--enable-url-protocols=http")
            buildArgs.add("-J-Xmx6g")
            resources.autodetect()
        }
        named("test") {
            buildArgs.add("--no-fallback")
            buildArgs.add("-J-Xmx6g")
            // The test image must also embed classpath resources (the SPA shell
            // under static/) or HealthRouteTest's root-route check 404s natively.
            resources.autodetect()
        }
    }
    metadataRepository {
        enabled.set(true) // pulls reachability metadata for sqlite-jdbc, jgit, etc.
    }
}

// ---- Dependency discipline (native-image gate protection) ----
// Adding a server dependency is a deliberate act: justify it against the native gate,
// then run `./gradlew :server:writeDependencyAllowlist` and commit the updated
// dependency-allowlist.txt alongside the catalog change. `check` (and therefore CI)
// fails on any unrecorded drift of the runtime classpath — including transitives.

// Reflection-heavy / native-image-hostile groups fail resolution outright, even when
// pulled transitively. See master plan §3.
val bannedDependencyGroups = listOf(
    "io.netty",
    "com.fasterxml.jackson",
    "com.google.code.gson",
    "org.jetbrains.exposed",
)

configurations.configureEach {
    resolutionStrategy.eachDependency {
        val group = requested.group
        if (bannedDependencyGroups.any { group == it || group.startsWith("$it.") }) {
            throw GradleException(
                "Banned dependency group '$group' (via ${requested.name}): reflection-heavy and native-image-hostile. " +
                    "This ban is load-bearing for the single-binary distribution — see master plan §3.",
            )
        }
    }
}

val dependencyAllowlistFile = layout.projectDirectory.file("dependency-allowlist.txt").asFile

fun resolvedRuntimeModules(): List<String> =
    configurations.getByName("runtimeClasspath").incoming.resolutionResult.allComponents
        .mapNotNull { component ->
            (component.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)
                ?.let { "${it.group}:${it.module}" }
        }
        .distinct()
        .sorted()

tasks.register("writeDependencyAllowlist") {
    group = "verification"
    description = "Regenerate dependency-allowlist.txt from the resolved runtime classpath (a deliberate act — see comment above)"
    doLast {
        dependencyAllowlistFile.writeText(resolvedRuntimeModules().joinToString("\n", postfix = "\n"))
        println("Wrote ${dependencyAllowlistFile.name} (${resolvedRuntimeModules().size} modules)")
    }
}

tasks.register("verifyDependencyAllowlist") {
    group = "verification"
    description = "Fail if the server runtime dependency set drifted from dependency-allowlist.txt"
    doLast {
        val expected = if (dependencyAllowlistFile.exists()) {
            dependencyAllowlistFile.readLines().filter { it.isNotBlank() }.toSet()
        } else {
            emptySet()
        }
        val actual = resolvedRuntimeModules().toSet()
        val added = (actual - expected).sorted()
        val removed = (expected - actual).sorted()
        if (added.isNotEmpty() || removed.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Server runtime dependencies drifted from dependency-allowlist.txt.")
                    if (added.isNotEmpty()) appendLine("  Added:   ${added.joinToString(", ")}")
                    if (removed.isNotEmpty()) appendLine("  Removed: ${removed.joinToString(", ")}")
                    appendLine("New dependencies must be justified against the native gate (master plan §3).")
                    append("If deliberate: run ./gradlew :server:writeDependencyAllowlist and commit the result.")
                },
            )
        }
    }
}

tasks.named("check") {
    dependsOn("verifyDependencyAllowlist")
}
