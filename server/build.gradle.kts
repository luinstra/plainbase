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
    // sqlite-jdbc loads its bundled JNI library via System.load — sanctioned, allowlisted use.
    // JEP 472 (JDK 24+) warns on restricted native access unless granted; this carries the grant
    // on the `run`/installDist launchers. The native image bakes the same grant in via buildArgs.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// Native-smoke tests live in their own source set so the native test image's classpath carries NO
// Kotest/MockK engine (see the test-stack comment in dependencies{}). Declared before dependencies{}
// so its `nativeTestImplementation`/`nativeTestRuntimeOnly` configurations exist. It compiles against
// the main output and inherits main's own deps (ktor, kotlinx, …) via the extendsFrom wiring below.
val nativeTest: SourceSet =
    sourceSets.create("nativeTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
        // The golden resources (notably golden/known-broken-links.json, the chunk-8 acceptance
        // manifest) are shared with the native test image: putting src/test/resources on THIS
        // source set's resource path is what lets `resources.autodetect()` embed them in the
        // image (risk R6), keeping one committed copy as the single source of truth.
        resources.srcDir("src/test/resources")
    }
configurations["nativeTestImplementation"].extendsFrom(configurations["implementation"])
configurations["nativeTestRuntimeOnly"].extendsFrom(configurations["runtimeOnly"])

dependencies {
    // Ktor server — CIO engine only (native-image constraint; Netty banned)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
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

    // --- Test stack split (native-gate-aware) -------------------------------------------------
    // Two test source sets, split by what can survive a closed-world GraalVM native image:
    //
    //   src/test       — JVM logic tests on Kotest + MockK. MockK does runtime bytecode generation
    //                    (ByteBuddy/Objenesis) and Kotest's runner/Arb machinery use reflection that
    //                    native-image cannot satisfy, so these NEVER run natively.
    //   src/nativeTest — native-smoke tests on kotlin.test (→ junit-jupiter), which compiles and runs
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
    // SnakeYAML — JVM-test-ONLY differential oracle for the FrontmatterPatcher fuzz test (the real
    // YAML parser the strictest-subset recognizer is checked against). testImplementation-scoped, so
    // it is absent from runtimeClasspath (allowlist unaffected). The nativeTest configurations below
    // extend `implementation`/`runtimeOnly` — NOT `testImplementation` — so this never reaches the
    // native test image's classpath; the parser stays off the native gate. See FrontmatterPatcherOracleTest.
    testImplementation(libs.snakeyaml)
    // JUnit Platform launcher API — the chunk-8 acceptance suites (Phase1AcceptanceTest,
    // ForeverApiGoldenSuite) run existing test classes by SELECTION through an in-process launcher
    // (suite-without-duplication, with executed-test floors against vacuous green). Already in the
    // catalog for nativeTest; testImplementation-scoped, so the runtime allowlist is unaffected.
    testImplementation(libs.junit.platform.launcher)

    // nativeTest source set: kotlin.test (+ its JUnit 5 binding), the JUnit Platform launcher/engine,
    // GraalVM's native JUnit launcher, and the ktor test host ONLY — deliberately no Kotest/MockK, so
    // the native test image's classpath carries no native-hostile engine. The junit-platform pieces
    // and GraalVM launcher are explicit here because the main `test` set inherited the jupiter engine
    // transitively from Kotest (which this set does not depend on) and the plugin injects its native
    // launcher only into the default `test` runtime classpath — we wire our own source set by hand.
    "nativeTestImplementation"(libs.kotlin.test)
    "nativeTestImplementation"(libs.kotlin.test.junit5)
    "nativeTestImplementation"(libs.junit.jupiter.engine)
    "nativeTestImplementation"(libs.junit.platform.launcher)
    "nativeTestImplementation"(libs.graalvm.junit.platform.native)
    "nativeTestImplementation"(libs.ktor.server.test.host)
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

// ---- Test execution split: JVM runs EVERYTHING, native runs ONLY the nativeTest source set ----
//
// The JVM `test` task runs the FULL suite: its own Kotest/MockK logic tests PLUS the kotlin.test
// native-smoke tests from the `nativeTest` source set (folded in below). So `./gradlew build`
// always exercises every test on the JVM. The `nativeTest` source set additionally feeds the
// GraalVM native test image — and ONLY it does, so the closed-world image never sees Kotest/MockK.
tasks.test {
    useJUnitPlatform()
    // Fold the native-smoke source set into the JVM `test` run so the JVM suite stays complete.
    val nativeTestSourceSet = sourceSets["nativeTest"]
    testClassesDirs += nativeTestSourceSet.output.classesDirs
    classpath += nativeTestSourceSet.runtimeClasspath
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

// The Phase-1 acceptance gate as one named task (chunk 8). The gate ALREADY runs inside `test`
// (and therefore `build`/CI) like every other suite; this is the convenience handle to run it
// alone. The native half of the gate is Phase1AcceptanceNativeTest, which `nativeTest` runs
// inside the image. Not wired into `check` — that would re-run the same classes twice per build.
val acceptanceTest by tasks.registering(Test::class) {
    description = "Runs ONLY the Phase-1 acceptance gate (Phase1AcceptanceTest + ForeverApiGoldenSuite)."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("com.plainbase.acceptance.*") }
    testLogging { events("passed", "failed", "skipped") }
}

// JVM task that runs ONLY the nativeTest source set. It feeds the GraalVM native test image (see
// the `nativeTestCompile` rewire under graalvmNative); the image's test set and classpath both come
// from here, which is what keeps Kotest/MockK out of the native binary. Not wired into `check`
// (those tests already run under `test`); its sole job is to record the native test list.
val nativeTestList by tasks.registering(Test::class) {
    description = "Runs ONLY the nativeTest source set, recording the test list for the native image."
    group = "verification"
    val nativeTestSourceSet = sourceSets["nativeTest"]
    testClassesDirs = nativeTestSourceSet.output.classesDirs
    classpath = nativeTestSourceSet.runtimeClasspath
    useJUnitPlatform()
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
            buildArgs.add("-J-Xmx6g")
            resources.autodetect()
        }
        named("test") {
            buildArgs.add("--no-fallback")
            buildArgs.add("--enable-native-access=ALL-UNNAMED")
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

// ---- Re-point the native test image at the nativeTest source set --------------------------
// How the plugin's native test image gets its test set: GraalVM Native Build Tools 1.1.1 attaches
// JUnit Platform UID tracking to a JVM `Test` task (system properties
// `junit.platform.listeners.uid.tracking.{enabled,output.dir}`); `nativeTestCompile`
// (BuildNativeImageTask) then reads that directory via `testListDirectory` and compiles/runs
// EXACTLY those tests, against the bound task's classpath. By default the auto-created `test`
// binary binds to the full JVM `test` task — whose classpath carries the Kotest engine, which the
// native test launcher discovers and chokes on (java.lang.Module.getLayer is unsupported under
// native). The plugin exposes no DSL to re-point the auto-created `test` binary (re-calling
// registerTestBinary("test") throws "NativeImageOptions ... already exists"), so we re-point its
// own mechanism here: run the UID listener on `nativeTestList` (the nativeTest source set only) and
// feed BOTH the test list AND the image classpath from that source set. Net effect: JVM `test`
// still runs the FULL suite; the native image is built from kotlin.test-only code, no Kotest/MockK.
run {
    val nativeTestListDir = layout.buildDirectory.dir("test-results/nativeTestList/testlist")
    val nativeTestSourceSet = sourceSets["nativeTest"]

    nativeTestList.configure {
        // Mirror the plugin's UID-tracking wiring onto this source-set-scoped task.
        systemProperty("junit.platform.listeners.uid.tracking.enabled", true)
        systemProperty("junit.platform.listeners.uid.tracking.output.dir", nativeTestListDir.get().asFile.absolutePath)
        outputs.dir(nativeTestListDir)
        // Start from a clean dir: the UID listener writes a NEW junit-platform-unique-ids-*.txt each
        // run, so a prior run's files would otherwise linger — letting nativeTestCompile consume
        // removed/renamed test IDs and letting the guard below pass on a stale list. Clearing first
        // makes the recorded set EXACTLY the current src/nativeTest tests; the listener recreates it.
        doFirst { nativeTestListDir.get().asFile.deleteRecursively() }
    }

    // Build the native test image from the nativeTest source set's classpath (no Kotest/MockK),
    // not the default main-test runtime.
    graalvmNative.binaries.named("test") {
        classpath(nativeTestSourceSet.runtimeClasspath, nativeTestSourceSet.output)
    }

    tasks.named<org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask>("nativeTestCompile") {
        // Read the native test set from `nativeTestList` (nativeTest source set) instead of the
        // full-suite `test` task. (The plugin leaves a `dependsOn(test)` edge so the JVM suite runs
        // first; harmless — `testListDirectory` is what decides the image's test set.)
        dependsOn(nativeTestList)
        testListDirectory.set(nativeTestListDir)
        options.get().classpath.setFrom(nativeTestSourceSet.runtimeClasspath, nativeTestSourceSet.output)
        // Anti-vacuous-green guard, on the CONSUMER side. The native image is built from EXACTLY the
        // UID list in testListDirectory; an empty/missing list yields a passing, test-free image — a
        // silent no-op gate. The guard lives HERE, not on nativeTestList, because Gradle skips that
        // task as NO-SOURCE when src/nativeTest is empty, so a guard there never fires in the exact
        // case it defends against. This task always runs before the image is built. Fail loud.
        doFirst {
            val recordedIds = nativeTestListDir.get().asFile.walk()
                .filter { it.isFile }
                .flatMap { it.readLines().asSequence() }
                .filter { it.isNotBlank() }
                .toList()
            if (recordedIds.isEmpty()) {
                throw GradleException(
                    "Native gate has no tests: src/nativeTest produced an empty UID list, so the native " +
                        "image would be vacuously green. Ensure src/nativeTest holds at least one " +
                        "@Tag(\"native\") kotlin.test test.",
                )
            }
            logger.lifecycle("Native gate: building image from ${recordedIds.size} recorded native test id(s).")
        }
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
