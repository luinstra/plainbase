import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    alias(libs.plugins.sqldelight)
    id("org.graalvm.buildtools.native")
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
    application
    id("plainbase.server-build")
    id("plainbase.native-tests")
}

// Convention plugins own generated BuildInfo, allowlist verification, JVM/native test wiring, and G3z gates.

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
}

group = "com.plainbase"
version = rootProject.version

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

application {
    mainClass.set("com.plainbase.ApplicationKt")
    applicationName = "plainbase"
    // sqlite-jdbc loads its bundled JNI library via System.load - sanctioned, allowlisted use.
    // JEP 472 (JDK 24+) warns on restricted native access unless granted; this carries the grant
    // on the `run`/installDist launchers. The native image bakes the same grant in via buildArgs.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// Windows is not a supported runtime (native or JVM), so the distribution ships no Windows
// launcher. The release workflow's jar job asserts this exclusion held.
distributions {
    main {
        contents {
            exclude("**/plainbase.bat")
        }
    }
}

kover {
    currentProject {
        sources {
            includedSourceSets.add("main")
        }
    }

    reports {
        filters {
            excludes {
                classes("com.plainbase.ApplicationKt*")
                classes("com.plainbase.frameworks.cli.S3SmokeCommand*")
            }
        }
    }
}

dependencies {
    // Ktor server - CIO engine only (native-image constraint; Netty banned)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    // Sessions (A4a's opaque-string cookie); allowlisted + native-proven in A1 (SessionCookieNativeTest).
    implementation(libs.ktor.server.sessions)
    // SSE - the in-binary MCP transport (P3). The mcp(Route) overload asserts install(SSE); pin it directly (not the
    // SDK transitive) at the same Ktor version. Already allowlisted (rode the SDK transitively); zero drift expected.
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.serialization.kotlinx.json)

    // HOCON config (ADR-0009): explicit - already allowlisted transitively, so zero allowlist drift, but the
    // direct ConfigFactory/Config use in PlainbaseConfig must not ride a transitive a Ktor bump could drop.
    implementation(libs.typesafe.config)

    // kotlinx - the only serializer in the tree
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    // kotlinx-datetime - real ISO calendar validation of the editorial page `updated` field
    // (LocalDate.parse rejects 2026-02-30; reflection-free / native-image safe).
    implementation(libs.kotlinx.datetime)

    // Persistence - SQLDelight on xerial sqlite-jdbc (FTS5 included)
    implementation(libs.sqldelight.jdbc.driver)
    testImplementation(libs.sqldelight.sqlite.driver)
    implementation(libs.sqlite.jdbc)

    // DI - Koin constructor DSL only
    implementation(libs.koin.core)

    // Markdown
    implementation(libs.flexmark)
    implementation(libs.flexmark.ext.gfm.tables)
    implementation(libs.flexmark.ext.gfm.strikethrough)
    implementation(libs.flexmark.ext.gfm.tasklist)
    implementation(libs.flexmark.ext.yaml.front.matter)
    implementation(libs.flexmark.ext.anchorlink)

    // argon2 (pure-Java Bouncy Castle - no JNA/JNI)
    implementation(libs.bouncycastle)

    // MCP Kotlin SDK (spike target; full server lands in Phase 5)
    implementation(libs.mcp.kotlin.sdk)

    // Ktor CIO HTTP client: backs the S3/object-store client (hand-rolled SigV4 over CIO, storage plan C0)
    // and the spike's TLS + MCP-SSE round-trips. CIO only (native-image constraint), no new dependency.
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    // Logging - kotlin-logging facade (house style; already transitive via MCP SDK) over logback
    implementation(libs.kotlin.logging)
    runtimeOnly(libs.logback.classic)

    // --- Test stack split (native-gate-aware) -------------------------------------------------
    // Two test source sets, split by what can survive a closed-world GraalVM native image:
    //
    //   src/test       - JVM logic tests on Kotest + MockK. MockK does runtime bytecode generation
    //                    (ByteBuddy/Objenesis) and Kotest's runner/Arb machinery use reflection that
    //                    native-image cannot satisfy, so these NEVER run natively.
    //   src/nativeTest - native-smoke tests on kotlin.test (→ junit-jupiter), which compiles and runs
    //                    cleanly inside the native image. This is THE native gate's proof set.
    //
    // The split is by SOURCE SET, not just JUnit tag: the GraalVM native test launcher discovers via
    // every TestEngine on its classpath, so merely tag-filtering a shared classpath still drags the
    // Kotest engine into the image (it fails on java.lang.Module.getLayer under native). Keeping
    // Kotest/MockK off the nativeTest classpath is the only robust guarantee. All test deps are
    // test-scoped → absent from the runtime classpath, so the dependency allowlist is unaffected.
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.framework.engine)
    testImplementation(libs.kotest.property)
    testImplementation(libs.mockk)
    // SnakeYAML - JVM-test-ONLY differential oracle for the FrontmatterPatcher fuzz test (the real
    // YAML parser the strictest-subset recognizer is checked against). testImplementation-scoped, so
    // it is absent from runtimeClasspath (allowlist unaffected). The nativeTest configurations below
    // extend `implementation`/`runtimeOnly` - NOT `testImplementation` - so this never reaches the
    // native test image's classpath; the parser stays off the native gate. See FrontmatterPatcherOracleTest.
    testImplementation(libs.snakeyaml)
    // JGit - JVM-test-ONLY differential oracle for the W4 Git-history layer (reads commits back via
    // RevCommit to assert against the shell-`git` writes the production GitCliHistoryProvider makes).
    // testImplementation-scoped, so it is absent from runtimeClasspath (allowlist unaffected); the
    // nativeTest configurations extend `implementation`/`runtimeOnly` - NOT `testImplementation` - so
    // it never reaches the native test image. Production ships the system `git` binary, never JGit (ADR-0006).
    testImplementation(libs.jgit)
    // JUnit Platform launcher API - the chunk-8 acceptance suites (Phase1AcceptanceTest,
    // ForeverApiGoldenSuite) run existing test classes by SELECTION through an in-process launcher
    // (suite-without-duplication, with executed-test floors against vacuous green). Already in the
    // catalog for nativeTest; testImplementation-scoped, so the runtime allowlist is unaffected.
    testImplementation(libs.junit.platform.launcher)
    // Logback at TEST-COMPILE scope (it is already the runtime backend above): the S5 watcher test
    // asserts the DATA_DIR-nested-in-CONTENT_DIR startup warning by attaching a ListAppender, which
    // needs the logback classes at compile time. Runtime allowlist unaffected (same artifact).
    testImplementation(libs.logback.classic)

    // nativeTest source set: kotlin.test (+ its JUnit 6 binding), the JUnit Platform launcher/engine,
    // GraalVM's native JUnit launcher, and the ktor test host ONLY - deliberately no Kotest/MockK, so
    // the native test image's classpath carries no native-hostile engine. The junit-platform pieces
    // and GraalVM launcher are explicit here because the main `test` set inherited the jupiter engine
    // transitively from Kotest (which this set does not depend on) and the plugin injects its native
    // launcher only into the default `test` runtime classpath - we wire our own source set by hand.
    "nativeTestImplementation"(libs.kotlin.test)
    "nativeTestImplementation"(libs.kotlin.test.junit5)
    "nativeTestImplementation"(libs.junit.jupiter.engine)
    "nativeTestImplementation"(libs.junit.platform.launcher)
    // The native launcher silently omits XML when this optional standard reporter is absent. Keep it
    // native-test-only and aligned with the launcher/engine platform version; production runtime is unchanged.
    "nativeTestRuntimeOnly"(libs.junit.platform.reporting)
    "nativeTestImplementation"(libs.graalvm.junit.platform.native)
    "nativeTestImplementation"(libs.ktor.server.test.host)
    // Logback at nativeTest-COMPILE scope, for the same reason it is at test-compile scope above: the
    // deferred-log-emission falsifier attaches its own blocking appender to the real backend. Logback already
    // reaches this source set's RUNTIME classpath (`runtimeOnly` above, via nativeTestRuntimeOnly.extendsFrom),
    // but nativeTestImplementation extends `implementation` only, so the classes are absent at COMPILE time.
    // Test-scoped, same artifact, so the runtime allowlist is unaffected.
    "nativeTestImplementation"(libs.logback.classic)
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
            // JEP 472 grant for sqlite-jdbc's System.load, baked into the image at build time
            // (mirrors applicationDefaultJvmArgs above; without it every start warns on stderr).
            buildArgs.add("--enable-native-access=ALL-UNNAMED")
            // Runtime (-R:) default max heap, compiled into the image - NOT the -J: builder-JVM heap
            // below, which tunes only the image build. Without it the Serial GC (CE default) lets RSS
            // ratchet toward a large physical-memory-derived default and squat there. 256m boots the
            // ~1k–3k design range with wide margin (measured) and keeps the boot-OOM cliff far from
            // realistic corpora; it stays overridable by a runtime -Xmx for very large trees.
            buildArgs.add("-R:MaxHeapSize=256m")
            buildArgs.add("-J-Xmx6g")
            // Preserve the macOS 14 deployment floor on newer build hosts.
            if (System.getProperty("os.name") == "Mac OS X") {
                buildArgs.add("-H:NativeLinkerOption=-mmacosx-version-min=14.0")
            }
            resources.autodetect()
        }
    }
    metadataRepository {
        enabled.set(true) // pulls reachability metadata for sqlite-jdbc, etc.
    }
}

// ---- Dependency discipline (native-image gate protection) ----
// Adding a server dependency is a deliberate act: justify it against the native gate,
// then run `./gradlew :server:writeDependencyAllowlist` and commit the updated
// dependency-allowlist.txt alongside the catalog change. `check` (and therefore CI)
// fails on any unrecorded drift of the runtime classpath - including transitives.

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
                    "This ban is load-bearing for the single-binary distribution - see master plan §3.",
            )
        }
    }
}
