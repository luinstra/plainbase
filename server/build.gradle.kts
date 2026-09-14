import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask
import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.graalvm.native)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
    application
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
}

tasks.named("check") {
    dependsOn(rootProject.tasks.named("lintKotlin"))
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

// The native source set uses main's internal runtime seams directly. Associate the Kotlin compilation with main;
// keep the explicit source-set output classpaths above because the native image wiring still consumes them.
kotlin.target.compilations.getByName("nativeTest")
    .associateWith(kotlin.target.compilations.getByName("main"))

configurations["nativeTestImplementation"].extendsFrom(configurations["implementation"])
configurations["nativeTestRuntimeOnly"].extendsFrom(configurations["runtimeOnly"])

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

// ---- Version self-report (C5 item 8): generate `com.plainbase.BuildInfo` from `project.version` -----
// (root build.gradle.kts derives `version` from `-PreleaseVersion`, SNAPSHOT as the dev fallback) so the
// binary self-reports the TAG-DRIVEN version with no drift (PlainbaseConfig.VERSION delegates to it). A
// generated Kotlin `const` needs NO runtime resource lookup/reflection - cleaner for the native bet than a
// classpath `version.properties` (Fork B of the addendum; kept only as a documented fallback).
val generatedBuildInfoDir = layout.buildDirectory.dir("generated/source/buildInfo/kotlin")

val generateBuildInfo =
    tasks.register("generateBuildInfo") {
        group = "build"
        description = "Generates com.plainbase.BuildInfo.VERSION from project.version"
        val versionValue = project.version.toString()
        inputs.property("version", versionValue)
        outputs.dir(generatedBuildInfoDir)
        doLast {
            // Defense-in-depth on the generated-source seam: `project.version` is tag-driven in the
            // release workflow (`-PreleaseVersion=${GITHUB_REF_NAME#v}`), so a hostile tag could try to
            // inject Kotlin here. The release workflow already validates the tag shape, but this seam
            // refuses anything outside a version alphabet regardless of how it was invoked.
            require(versionValue.matches(Regex("[A-Za-z0-9.+-]+"))) { "unexpected version: $versionValue" }
            val packageDir = generatedBuildInfoDir.get().asFile.resolve("com/plainbase")
            packageDir.mkdirs()
            packageDir.resolve("BuildInfo.kt").writeText(
                """
                |package com.plainbase
                |
                |// GENERATED at build time from `project.version` - do not edit (server/build.gradle.kts:generateBuildInfo).
                |object BuildInfo {
                |    const val VERSION: String = "$versionValue"
                |}
                |
                """.trimMargin(),
            )
        }
    }

sourceSets {
    main {
        kotlin.srcDir(generateBuildInfo)
    }
}

// ---- Test execution split: JVM runs EVERYTHING, native runs ONLY the nativeTest source set ----
//
// The JVM `test` task runs the FULL suite: its own Kotest/MockK logic tests PLUS the kotlin.test
// native-smoke tests from the `nativeTest` source set (folded in below). So `./gradlew build`
// always exercises every test on the JVM. The `nativeTest` source set additionally feeds the
// GraalVM native test image - and ONLY it does, so the closed-world image never sees Kotest/MockK.
val mainRuntimeClasspathInput = sourceSets["main"].runtimeClasspath

fun Test.configurePlainbaseMainRuntimeClasspath() {
    inputs.files(mainRuntimeClasspathInput)
        .withPropertyName("plainbaseMainRuntimeClasspath")
        .withNormalizer(ClasspathNormalizer::class)
    doFirst {
        systemProperty("plainbase.test.mainRuntimeClasspath", mainRuntimeClasspathInput.asPath)
    }
}

tasks.test {
    useJUnitPlatform()
    // ServerBootCliContractTest consumes this execution-time production classpath and evidence identity.
    configurePlainbaseMainRuntimeClasspath()
    doFirst {
        systemProperty(
            "plainbase.test.evidenceDir",
            layout.buildDirectory.dir("reports/cli-contract/run-${UUID.randomUUID()}").get().asFile.absolutePath,
        )
    }
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
// inside the image. Not wired into `check` - that would re-run the same classes twice per build.
val acceptanceTest = tasks.register<Test>("acceptanceTest") {
    description = "Runs ONLY the Phase-1 acceptance gate (Phase1AcceptanceTest + ForeverApiGoldenSuite)."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    configurePlainbaseMainRuntimeClasspath()
    filter { includeTestsMatching("com.plainbase.acceptance.*") }
    testLogging { events("passed", "failed", "skipped") }
}

// JVM task that runs ONLY the nativeTest source set. It feeds the GraalVM native test image (see
// the `nativeTestCompile` rewire under graalvmNative); the image's test set and classpath both come
// from here, which is what keeps Kotest/MockK out of the native binary. Not wired into `check`
// (those tests already run under `test`); its sole job is to record the native test list.
val nativeTestList = tasks.register<Test>("nativeTestList") {
    description = "Runs ONLY the nativeTest source set, recording the test list for the native image."
    group = "verification"
    val nativeTestSourceSet = sourceSets["nativeTest"]
    testClassesDirs = nativeTestSourceSet.output.classesDirs
    classpath = nativeTestSourceSet.runtimeClasspath
    useJUnitPlatform()
}

// P3 metadata regen: run the full-stack spike (incl. the new mcp-sse-handshake check) on the JVM under the
// native-image tracing agent, merging the SSE/MCP-server reachability delta into the committed kotlin-sdk metadata.
// Deliberate, manual step (not wired into `build`): run it when the SSE path or the SDK version changes.
tasks.register<Exec>("traceMcpSseMetadata") {
    group = "verification"
    description = "Run the spike under -agentlib:native-image-agent to regenerate kotlin-sdk SSE reflect metadata"
    dependsOn(tasks.named("classes"))
    val runtimeClasspath = sourceSets["main"].runtimeClasspath
    // The native-image tracing agent ships ONLY with GraalVM; a general Java 25 toolchain need not provide the
    // agent. Run under the SAME GraalVM the native image uses (GRAALVM_HOME/JAVA_HOME, toolchainDetection=false)
    // so the traced reachability matches what nativeCompile sees.
    val graalvmHome = providers.environmentVariable("GRAALVM_HOME")
    val javaHome = providers.environmentVariable("JAVA_HOME")

    doFirst {
        val home =
            graalvmHome.orNull?.takeIf { it.isNotBlank() }
                ?: javaHome.orNull?.takeIf { it.isNotBlank() }
                ?: throw GradleException(
                    "traceMcpSseMetadata needs GraalVM via GRAALVM_HOME or JAVA_HOME",
                )
        commandLine(
            "$home/bin/java",
            "--enable-native-access=ALL-UNNAMED",
            "-agentlib:native-image-agent=config-merge-dir=" +
                "src/main/resources/META-INF/native-image/io.modelcontextprotocol/kotlin-sdk",
            "-cp",
            runtimeClasspath.asPath,
            "com.plainbase.ApplicationKt",
            "spike",
        )
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
        named("test") {
            buildArgs.add("--no-fallback")
            buildArgs.add("--enable-native-access=ALL-UNNAMED")
            buildArgs.add("-J-Xmx6g")
            // Deliberately NO -R:MaxHeapSize here: the JUnit test set needs more runtime heap than the
            // shipped `main` default, and the `spike` gate already runs that capped main binary - so the
            // runtime cap stays gated without starving the test image.
            // The test image must also embed classpath resources (the SPA shell
            // under static/) or HealthRouteTest's root-route check 404s natively.
            resources.autodetect()
        }
    }
    metadataRepository {
        enabled.set(true) // pulls reachability metadata for sqlite-jdbc, etc.
    }
}

// ---- Re-point the native test image at the nativeTest source set --------------------------
// How the plugin's native test image gets its test set: GraalVM Native Build Tools attaches
// JUnit Platform UID tracking to a JVM `Test` task (system properties
// `junit.platform.listeners.uid.tracking.{enabled,output.dir}`); `nativeTestCompile`
// (BuildNativeImageTask) then reads that directory via `testListDirectory` and compiles/runs
// EXACTLY those tests, against the bound task's classpath. By default the auto-created `test`
// binary binds to the full JVM `test` task - whose classpath carries the Kotest engine, which the
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
        // run, so a prior run's files would otherwise linger - letting nativeTestCompile consume
        // removed/renamed test IDs and letting the guard below pass on a stale list. Clearing first
        // makes the recorded set EXACTLY the current src/nativeTest tests; the listener recreates it.
        doFirst { nativeTestListDir.get().asFile.deleteRecursively() }
    }

    // Build the native test image from the nativeTest source set's classpath (no Kotest/MockK),
    // not the default main-test runtime.
    graalvmNative.binaries.named("test") {
        classpath(nativeTestSourceSet.runtimeClasspath, nativeTestSourceSet.output)
    }

    tasks.named<BuildNativeImageTask>("nativeTestCompile") {
        // Read the native test set from `nativeTestList` (nativeTest source set) instead of the
        // full-suite `test` task. Replacing the plugin's classpath also removed its implicit producer
        // edge, so retain the real ordinary test prerequisite explicitly for the plugin's captured
        // UID-directory predicate. `testListDirectory` still selects only the nativeTest list below.
        dependsOn(nativeTestList)
        dependsOn(tasks.named<Test>("test"))
        testListDirectory.set(nativeTestListDir)
        options.get().classpath.setFrom(nativeTestSourceSet.runtimeClasspath, nativeTestSourceSet.output)
        // Anti-vacuous-green guard, on the CONSUMER side. The native image is built from EXACTLY the
        // UID list in testListDirectory; an empty/missing list yields a passing, test-free image - a
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

    tasks.named<org.graalvm.buildtools.gradle.tasks.NativeRunTask>("nativeTest") {
        // The plugin adds the default JVM `test` UID directory to the test binary's runtime arguments.
        // Re-point execution too, or the correctly compiled nativeTest image runs zero selected tests.
        runtimeArgs.add(
            nativeTestListDir.map {
                "-Djunit.platform.listeners.uid.tracking.output.dir=${it.asFile.absolutePath}"
            },
        )
    }
}

// ---- G3z Linux PID1 regression tasks -------------------------------------------------------
// These tasks deliberately execute only on Linux. Ordinary nativeTest/nativeTestList discovery remains usable on
// other hosts and records the topology-required method abort; the mandatory tasks must fail closed instead.
val g3zMethodClass = "com.plainbase.frameworks.git.GitExecutorZombieNativeTest"
val g3zMethodName = "reparentedZombieCompletesInvocation"
val g3zMethod = "$g3zMethodClass.$g3zMethodName"
val g3zJvmLauncher = "com.plainbase.frameworks.git.G3zJvmLauncher"
val g3zForcedTimeoutLauncher = "com.plainbase.frameworks.git.G3zForcedTimeoutLauncher"
val g3zNativeTestListDir = layout.buildDirectory.dir("test-results/nativeTestList/testlist")
val g3zJvmPreparationDir = layout.buildDirectory.dir("g3z/jvm-preparation")
val g3zNativeStageDir = layout.buildDirectory.dir("g3z/native")
val g3zNativeCompileTask = tasks.named<BuildNativeImageTask>("nativeTestCompile")
val g3zNativeOutput = g3zNativeCompileTask.flatMap { it.outputFile }
val g3zNativeRuntimeClasspath = sourceSets["nativeTest"].runtimeClasspath

fun g3zRequireLinux() {
    check(System.getProperty("os.name") == "Linux") {
        "G3z PID1 tasks are unsupported on non-Linux hosts (os.name=${System.getProperty("os.name")})"
    }
}

val g3zLinuxOnlyTasks = setOf(
    "gitZombieJvmPid1",
    "gitZombieNativePid1",
    "gitZombieForcedTimeoutPid1",
    "prepareGitZombieJvmPid1",
    "prepareGitZombieNativePid1",
)

gradle.taskGraph.whenReady {
    if (System.getProperty("os.name") != "Linux") {
        val requested = allTasks.filter { it.name in g3zLinuxOnlyTasks }
        if (requested.isNotEmpty()) {
            throw GradleException(
                "G3z PID1 tasks are unsupported on non-Linux hosts; refusing expensive dependencies: " +
                    requested.joinToString(", ") { it.path },
            )
        }
    }
}

fun g3zTrustedTool(name: String): File =
    listOf(File("/usr/bin/$name"), File("/bin/$name"))
        .firstOrNull { it.isFile && it.canExecute() }
        ?: throw GradleException("G3z requires trusted absolute tool /usr/bin/$name or /bin/$name")

fun g3zNumericIdentity(idTool: File, option: String): String {
    val process = ProcessBuilder(idTool.absolutePath, option)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exit = process.waitFor()
    require(exit == 0) { "${idTool.absolutePath} $option failed with exit $exit: ${output.trim()}" }
    val value = output.trim()
    require(value.matches(Regex("[0-9]+"))) { "${idTool.absolutePath} $option returned invalid UID/GID: $value" }
    return value
}

fun g3zWriteArgv(path: File, argv: List<String>) {
    path.writeText(argv.mapIndexed { index, value -> "$index\t$value" }.joinToString("\n", postfix = "\n"))
}

fun g3zCopyWithAttributes(source: File, destination: File) {
    Files.copy(
        source.toPath(),
        destination.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.COPY_ATTRIBUTES,
    )
}

data class G3zNamespaceLaunch(
    val runtime: String,
    val report: File,
    val workingDirectory: File,
    val home: File,
    val tmp: File,
    val executable: File,
    val executableArguments: List<String>,
)

data class G3zNamespaceRun(
    val exitCode: Int,
    val runnerUid: String,
    val runnerGid: String,
)

private data class G3zDrain(
    val input: java.io.InputStream,
    val thread: Thread,
    val failure: AtomicReference<Throwable?>,
)

internal data class G3zExecutionResult(
    val exitCode: Int?,
    val drainFailure: Throwable?,
    val identities: List<G3zHostProcessIdentity>,
    val identityFailure: Throwable?,
    val monitorStopped: Boolean,
    val drainsStopped: Boolean,
    val interrupted: Boolean,
)

internal data class G3zHostProcessIdentity(
    val pid: Long,
    val startTicks: Long,
    val handle: ProcessHandle,
)

internal data class G3zCleanupResult(
    val failure: Throwable?,
    val interrupted: Boolean,
)

private val g3zWatchdogWindowSeconds = 120L + 10L
private val g3zConfirmationAllowanceSeconds = 15L

fun g3zHostStartTicks(handle: ProcessHandle): Long? = runCatching {
    val raw = Files.readString(File("/proc/${handle.pid()}/stat").toPath())
    val close = raw.lastIndexOf(')')
    if (close <= 0) return@runCatching null
    raw.substring(close + 1).trim().split(Regex("\\s+"))
        .getOrNull(19)?.toLongOrNull()
}.getOrNull()

internal fun g3zCleanupRecordedProcesses(
    identities: List<G3zHostProcessIdentity>,
): G3zCleanupResult {
    var failure: Throwable? = null
    var interrupted = false
    fun record(candidate: Throwable) {
        if (candidate is InterruptedException) {
            interrupted = true
            Thread.interrupted()
        }
        if (failure == null) failure = candidate else failure?.addSuppressed(candidate)
    }
    // The namespace wrapper owns termination; this pass only confirms recorded identities and never signals a PID.
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(g3zConfirmationAllowanceSeconds)
    identities.forEach { identity ->
        while (System.nanoTime() < deadline) {
            val alive = runCatching { identity.handle.isAlive }.getOrNull()
            if (alive == null) {
                record(IllegalStateException("unknown liveness during G3z cleanup for PID ${identity.pid}"))
                break
            }
            if (!alive) break
            val currentTicks = g3zHostStartTicks(identity.handle)
            if (currentTicks == null) {
                if (runCatching { identity.handle.isAlive }.getOrNull() == false) break
                record(IllegalStateException("missing stat for live G3z PID ${identity.pid}"))
                break
            }
            if (currentTicks != identity.startTicks) {
                if (runCatching { identity.handle.isAlive }.getOrNull() == false) break
                record(IllegalStateException("G3z PID identity changed during cleanup: ${identity.pid}"))
                break
            }
            try {
                Thread.sleep(25L)
            } catch (error: InterruptedException) {
                record(error)
            }
        }
        if (runCatching { identity.handle.isAlive }.getOrDefault(true)) {
            record(IllegalStateException("G3z recorded PID remained alive after bounded cleanup: ${identity.pid}"))
        }
    }
    return G3zCleanupResult(failure, interrupted)
}

internal fun g3zExecute(
    argv: List<String>,
    workingDirectory: File,
    stdout: File,
    stderr: File,
    environment: Map<String, String>,
): G3zExecutionResult {
    stdout.parentFile.mkdirs()
    stderr.parentFile.mkdirs()
    val process = ProcessBuilder(argv)
        .directory(workingDirectory)
        .apply { environment().putAll(environment) }
        .start()
    val stdoutFailure = AtomicReference<Throwable?>(null)
    val stderrFailure = AtomicReference<Throwable?>(null)
    val identityFailure = AtomicReference<Throwable?>(null)
    val executionFailure = AtomicReference<Throwable?>(null)
    val identities = ConcurrentHashMap<Long, G3zHostProcessIdentity>()
    val processHandle = process.toHandle()
    val interruptedSeen = AtomicBoolean(Thread.interrupted())
    var monitor: Thread? = null
    val drains = mutableListOf<G3zDrain>()
    val watchdogDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(g3zWatchdogWindowSeconds)
    var exit: Int? = null
    fun record(reference: AtomicReference<Throwable?>, candidate: Throwable) {
        reference.updateAndGet { existing ->
            if (existing == null) {
                candidate
            } else {
                existing.addSuppressed(candidate)
                existing
            }
        }
        if (candidate is InterruptedException) {
            interruptedSeen.set(true)
            Thread.interrupted()
        }
    }
    fun awaitOwnedProcessUntilWatchdog() {
        while (true) {
            if (!runCatching { process.isAlive }.getOrDefault(true)) {
                exit = runCatching { process.exitValue() }.getOrNull()
                return
            }
            val remainingNanos = watchdogDeadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) {
                record(executionFailure, IllegalStateException("G3z owned wrapper exceeded its original watchdog window"))
                return
            }
            try {
                process.waitFor(remainingNanos, TimeUnit.NANOSECONDS)
            } catch (interrupted: InterruptedException) {
                record(executionFailure, interrupted)
            }
        }
    }
    fun captureIdentities() {
        val handles = listOf(processHandle) + runCatching { processHandle.descendants().toList() }
            .getOrElse { error ->
                if (runCatching { processHandle.isAlive }.getOrDefault(false)) record(identityFailure, error)
                emptyList()
            }
        handles.forEach { handle ->
            val alive = runCatching { handle.isAlive }.getOrNull()
            if (alive == true) {
                var startTicks = g3zHostStartTicks(handle)
                if (startTicks == null && runCatching { handle.isAlive }.getOrNull() == false) return@forEach
                if (startTicks == null) startTicks = g3zHostStartTicks(handle)
                if (startTicks == null) {
                    if (runCatching { handle.isAlive }.getOrNull() != false) {
                        record(
                            identityFailure,
                            IllegalStateException("unable to capture live G3z process identity for PID ${handle.pid()}"),
                        )
                    }
                } else {
                    val existing = identities[handle.pid()]
                    if (existing != null && existing.startTicks != startTicks) {
                        if (runCatching { handle.isAlive }.getOrNull() != false) {
                            record(
                                identityFailure,
                                IllegalStateException(
                                    "G3z PID identity changed while live: pid=${handle.pid()} " +
                                        "expected=${existing.startTicks} actual=$startTicks",
                                ),
                            )
                        }
                    } else {
                        identities.putIfAbsent(handle.pid(), G3zHostProcessIdentity(handle.pid(), startTicks, handle))
                    }
                }
            }
        }
    }
    val monitorStop = AtomicBoolean(false)
    try {
        if (interruptedSeen.get()) record(executionFailure, InterruptedException("G3z execution entered interrupted"))
        captureIdentities()
        monitor = Thread({
            while (!monitorStop.get()) {
                captureIdentities()
                try {
                    Thread.sleep(25L)
                } catch (error: InterruptedException) {
                    if (!monitorStop.get()) record(identityFailure, error)
                    Thread.interrupted()
                }
            }
            captureIdentities()
        }, "g3z-process-identity-monitor")
        runCatching { monitor?.start() }.onFailure { record(executionFailure, it) }
        fun startDrain(input: java.io.InputStream, target: File, name: String, failure: AtomicReference<Throwable?>) {
            val thread = Thread(
                {
                    try {
                        input.use { source ->
                            Files.newOutputStream(target.toPath()).use { destination -> source.copyTo(destination) }
                        }
                    } catch (error: Throwable) {
                        record(failure, error)
                    }
                },
                name,
            )
            thread.isDaemon = true
            val drain = G3zDrain(input, thread, failure)
            drains += drain
            runCatching { thread.start() }.onFailure { record(executionFailure, it) }
        }
        startDrain(process.inputStream, stdout, "g3z-stdout-drain", stdoutFailure)
        startDrain(process.errorStream, stderr, "g3z-stderr-drain", stderrFailure)
        try {
            exit = process.waitFor()
        } catch (error: Throwable) {
            record(executionFailure, error)
            // An interrupted/error return still owns the wrapper until its original 120s + 10s watchdog ends.
            awaitOwnedProcessUntilWatchdog()
        }
    } catch (error: Throwable) {
        record(executionFailure, error)
    } finally {
        if (runCatching { process.isAlive }.getOrDefault(true)) awaitOwnedProcessUntilWatchdog()
        monitorStop.set(true)
        monitor?.interrupt()
        monitor?.let {
            try {
                it.join(1_000L)
            } catch (error: InterruptedException) {
                record(executionFailure, error)
            }
            if (it.isAlive) record(executionFailure, IllegalStateException("G3z identity monitor did not stop"))
        }
        captureIdentities()
        val cleanup = g3zCleanupRecordedProcesses(identities.values.sortedBy { it.pid })
        cleanup.failure?.let { record(executionFailure, it) }
        if (cleanup.interrupted) interruptedSeen.set(true)
        drains.forEach { drain ->
            try {
                drain.thread.join(15_000L)
            } catch (error: InterruptedException) {
                record(executionFailure, error)
            }
            if (drain.thread.isAlive) {
                runCatching { drain.input.close() }.onFailure { record(executionFailure, it) }
                drain.thread.interrupt()
                try {
                    drain.thread.join(1_000L)
                } catch (error: InterruptedException) {
                    record(executionFailure, error)
                }
            }
            if (drain.thread.isAlive) {
                record(executionFailure, IllegalStateException("G3z log drain did not stop: ${drain.thread.name}"))
            }
            drain.failure.get()?.let { record(executionFailure, it) }
        }
        if (exit == null && runCatching { !process.isAlive }.getOrDefault(false)) {
            exit = runCatching { process.exitValue() }.getOrNull()
        }
        if (interruptedSeen.get()) Thread.currentThread().interrupt()
    }
    return G3zExecutionResult(
        exitCode = exit,
        drainFailure = executionFailure.get(),
        identities = identities.values.sortedBy { it.pid },
        identityFailure = identityFailure.get(),
        monitorStopped = monitor?.isAlive != true,
        drainsStopped = drains.all { !it.thread.isAlive },
        interrupted = interruptedSeen.get(),
    )
}

fun g3zRunNamespace(launch: G3zNamespaceLaunch): G3zNamespaceRun {
    g3zRequireLinux()
    launch.report.mkdirs()
    launch.workingDirectory.mkdirs()
    launch.home.mkdirs()
    launch.tmp.mkdirs()
    // sudo is retained only to create the isolated namespace; cleanup never authorizes a bare host-PID signal.
    val sudo = g3zTrustedTool("sudo")
    val timeout = g3zTrustedTool("timeout")
    val unshare = g3zTrustedTool("unshare")
    val setpriv = g3zTrustedTool("setpriv")
    val id = g3zTrustedTool("id")
    val runnerUid = g3zNumericIdentity(id, "-u")
    val runnerGid = g3zNumericIdentity(id, "-g")
    launch.report.resolve("runner-uid.txt").writeText("$runnerUid\n")
    launch.report.resolve("runner-gid.txt").writeText("$runnerGid\n")
    launch.report.resolve("host.txt").writeText(
        "os=${System.getProperty("os.name")}\narch=${System.getProperty("os.arch")}\n" +
            "runtime=${launch.runtime}\nworking-directory=${launch.workingDirectory.absolutePath}\n",
    )
    val argv = buildList {
        add(sudo.absolutePath)
        add("-n")
        add(timeout.absolutePath)
        add("--signal=TERM")
        add("--kill-after=10s")
        add("120s")
        add(unshare.absolutePath)
        add("--pid")
        add("--fork")
        add("--mount-proc")
        add("--net")
        add("--kill-child=KILL")
        add("--")
        add(setpriv.absolutePath)
        add("--reuid")
        add(runnerUid)
        add("--regid")
        add(runnerGid)
        add("--init-groups")
        add("--pdeathsig")
        add("keep")
        add("--")
        add(launch.executable.absolutePath)
        addAll(launch.executableArguments)
    }
    g3zWriteArgv(launch.report.resolve("command.argv"), argv)
    var execution: G3zExecutionResult? = null
    var failure: Throwable? = null
    try {
        execution = g3zExecute(
            argv = argv,
            workingDirectory = launch.workingDirectory,
            stdout = launch.report.resolve("stdout.log"),
            stderr = launch.report.resolve("stderr.log"),
            environment = mapOf(
                "HOME" to launch.home.absolutePath,
                "TMPDIR" to launch.tmp.absolutePath,
                "TMP" to launch.tmp.absolutePath,
                "TEMP" to launch.tmp.absolutePath,
            ),
        )
        execution.exitCode?.let { launch.report.resolve("exit.txt").writeText("$it\n") }
            ?: launch.report.resolve("exit-unavailable.txt").writeText("process did not expose an exit status\n")
        execution.identities.forEach { identity ->
            launch.report.resolve("process-identities.tsv").appendText("${identity.pid}\t${identity.startTicks}\n")
        }
        fun recordFailure(candidate: Throwable) {
            if (failure == null) failure = candidate else failure?.addSuppressed(candidate)
        }
        execution.identityFailure?.let {
            launch.report.resolve("identity-observation-failure.txt").writeText(it.stackTraceToString())
            recordFailure(it)
        }
        execution.drainFailure?.let {
            launch.report.resolve("cleanup-failure.txt").writeText(it.stackTraceToString())
            recordFailure(it)
        }
        if (!execution.monitorStopped) recordFailure(GradleException("G3z identity monitor did not stop"))
        if (!execution.drainsStopped) recordFailure(GradleException("G3z output drains did not stop"))
        if (execution.interrupted) recordFailure(InterruptedException("G3z namespace runner was interrupted"))
        if (execution.exitCode == null) recordFailure(GradleException("G3z process exited without an exit status"))
        launch.report.resolve("cleanup-completed.txt").writeText("${failure == null}\n")
    } catch (error: Throwable) {
        failure = error
        launch.report.resolve("exception.txt").writeText(error.stackTraceToString())
        launch.report.resolve("cleanup-completed.txt").writeText("false\n")
    }
    failure?.let { throw it }
    val completed = requireNotNull(execution) { "G3z namespace runner finished without a result" }
    return G3zNamespaceRun(requireNotNull(completed.exitCode), runnerUid, runnerGid)
}

fun g3zSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file.toPath()).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

fun g3zElfArchitecture(file: File): String {
    val bytes = file.inputStream().use { it.readNBytes(20) }
    require(
        bytes.size >= 20 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte(),
    ) { "G3z native executable is not an ELF file: ${file.absolutePath}" }
    val littleEndian = bytes[5].toInt() == 1
    val machine = if (littleEndian) {
        (bytes[18].toInt() and 0xff) or ((bytes[19].toInt() and 0xff) shl 8)
    } else {
        ((bytes[18].toInt() and 0xff) shl 8) or (bytes[19].toInt() and 0xff)
    }
    return when (machine) {
        62 -> "x86_64"
        183 -> "aarch64"
        40 -> "arm"
        243 -> "riscv64"
        else -> "EM_$machine"
    }
}

fun g3zReadKeyValues(path: File): Map<String, String> {
    require(path.isFile) { "missing G3z receipt: ${path.absolutePath}" }
    val lines = path.readLines().filter(String::isNotBlank)
    val entries = lines.map { line ->
        val separator = line.indexOf('=')
        require(separator > 0) { "G3z receipt ${path.absolutePath} has malformed line: $line" }
        line.substring(0, separator) to line.substring(separator + 1)
    }
    require(entries.size == entries.map { it.first }.toSet().size) {
        "G3z receipt ${path.absolutePath} contains duplicate keys"
    }
    return entries.toMap()
}

fun g3zRequireKeyValues(path: File, expected: Map<String, String>) {
    val actual = g3zReadKeyValues(path)
    expected.forEach { (key, value) ->
        require(actual[key] == value) {
            "G3z receipt ${path.absolutePath} expected $key=$value but found ${actual[key]}"
        }
    }
}

fun g3zRequireFixtureFacts(path: File) {
    val actual = g3zReadKeyValues(path)
    require(actual["stdin"] == "consumed=g3z-input") {
        "G3z fixture did not receipt the expected stdin: ${actual["stdin"]}"
    }
    val zombiePid = actual["zombie-pid"]?.toLongOrNull()
        ?: throw GradleException("G3z fixture receipt has no numeric zombie-pid")
    val livePid = actual["live-pid"]?.toLongOrNull()
        ?: throw GradleException("G3z fixture receipt has no numeric live-pid")
    require(actual["live-isAlive-before-release"] == "true") {
        "G3z live control was not independently live before release"
    }
    require(actual["before-live-release-state"]?.let { it != "Z" } == true) {
        "G3z fixture did not retain a stable live-process fact before release"
    }
    val retained = actual["production-retained"]?.split(',').orEmpty().filter(String::isNotBlank)
    require(retained.count { it.split(':').getOrNull(1) == "descendant" } >= 2) {
        "G3z production owner did not retain both child observations: $retained"
    }
    val completions = actual["production-process-completions"]?.split(',').orEmpty()
        .map { it.split(':') }
    fun hasDecision(pid: Long, complete: Boolean): Boolean = completions.any { fields ->
        fields.size >= 4 && fields[0].toLongOrNull() == pid && fields[2] == complete.toString()
    }
    require(hasDecision(zombiePid, complete = false) && hasDecision(zombiePid, complete = true)) {
        "G3z production observer did not record both zombie decisions: $completions"
    }
    require(hasDecision(livePid, complete = false) && hasDecision(livePid, complete = true)) {
        "G3z production observer did not record pending and completed live decisions: $completions"
    }
    val helpers = actual["production-helper-completions"]?.split(',').orEmpty()
    val requiredHelpers = setOf("git-stdout-drain", "git-stderr-drain", "git-stdin-writer")
    require(
        requiredHelpers.all { name ->
            helpers.any { it.split(':').getOrNull(1) == name && it.split(':').getOrNull(2) == "true" }
        },
    ) { "G3z production helper completion receipts are incomplete: $helpers" }
    val retainedIdentities = actual["production-retained-identities"]?.split(',').orEmpty().filter(String::isNotBlank)
    require(retainedIdentities.size >= 3) {
        "G3z production owner did not receipt identities for its original controls: $retainedIdentities"
    }
    require(actual["production-retention-capture-failures"].orEmpty().isEmpty()) {
        "G3z production identity capture failed: ${actual["production-retention-capture-failures"]}"
    }
    val captured = actual["cleanup-captured-processes"]?.toIntOrNull()
        ?: throw GradleException("G3z cleanup receipt has no captured process count")
    require(captured >= retainedIdentities.size) {
        "G3z cleanup did not cover every retained process identity: captured=$captured identities=$retainedIdentities"
    }
}

data class G3zJUnitXmlCounts(
    val tests: Long,
    val failures: Long,
    val errors: Long,
    val skipped: Long,
    val aborted: Long,
    val classNames: List<String>,
    val methodNames: List<String>,
)

fun g3zReadJUnitXml(path: File): G3zJUnitXmlCounts {
    require(path.isFile) { "missing G3z XML: ${path.absolutePath}" }
    val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
        setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }
    val document = factory.newDocumentBuilder().parse(path)
    val suite = document.documentElement
    require(suite.tagName == "testsuite") { "G3z XML root is not testsuite: ${path.absolutePath}" }
    fun count(attribute: String): Long = suite.getAttribute(attribute).toLongOrNull()
        ?: throw GradleException("G3z XML ${path.absolutePath} has invalid $attribute")
    val testCases = suite.getElementsByTagName("testcase")
    val testCaseAttributes = (0 until testCases.length).map { index ->
        testCases.item(index).attributes
    }
    val classes = testCaseAttributes.map { attributes ->
        attributes.getNamedItem("classname")?.nodeValue
            ?: throw GradleException("G3z XML testcase is missing classname: ${path.absolutePath}")
    }
    val methods = testCaseAttributes.map { attributes ->
        attributes.getNamedItem("name")?.nodeValue
            ?: throw GradleException("G3z XML testcase is missing name: ${path.absolutePath}")
    }
    val aborted = suite.getElementsByTagName("aborted").length.toLong()
    return G3zJUnitXmlCounts(
        tests = count("tests"),
        failures = count("failures"),
        errors = count("errors"),
        skipped = count("skipped"),
        aborted = aborted,
        classNames = classes,
        methodNames = methods,
    )
}

data class G3zNativeSummaryCounts(
    val containersFound: Long,
    val containersSkipped: Long,
    val containersStarted: Long,
    val containersAborted: Long,
    val containersSuccessful: Long,
    val containersFailed: Long,
    val testsFound: Long,
    val testsStarted: Long,
    val testsSucceeded: Long,
    val testsAborted: Long,
    val testsFailed: Long,
    val testsSkipped: Long,
)

fun g3zReadNativeSummary(path: File): G3zNativeSummaryCounts {
    require(path.isFile) { "missing native G3z stdout: ${path.absolutePath}" }
    val lines = path.readLines()
    fun count(label: String): Long {
        val pattern = Regex("^\\[\\s*(\\d+)\\s+$label\\s*]$")
        val matches = lines.mapNotNull { pattern.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
        require(matches.size == 1) { "native G3z stdout must contain one '$label' summary line, found ${matches.size}" }
        return matches.single()
    }
    return G3zNativeSummaryCounts(
        containersFound = count("containers found"),
        containersSkipped = count("containers skipped"),
        containersStarted = count("containers started"),
        containersAborted = count("containers aborted"),
        containersSuccessful = count("containers successful"),
        containersFailed = count("containers failed"),
        testsFound = count("tests found"),
        testsStarted = count("tests started"),
        testsSucceeded = count("tests successful"),
        testsAborted = count("tests aborted"),
        testsFailed = count("tests failed"),
        testsSkipped = count("tests skipped"),
    )
}

fun g3zRequireExactJUnitXml(path: File, expectedClassName: String, expectedMethodName: String) {
    val counts = g3zReadJUnitXml(path)
    require(counts.tests == 1L && counts.failures == 0L && counts.errors == 0L && counts.skipped == 0L) {
        "G3z XML count contract failed for ${path.absolutePath}: $counts"
    }
    require(
        counts.aborted == 0L &&
            counts.classNames == listOf(expectedClassName) &&
            counts.methodNames == listOf(expectedMethodName),
    ) {
        "G3z XML testcase identity contract failed for ${path.absolutePath}: $counts"
    }
}

fun g3zRequireRunnerOwnership(path: File, expectedUid: String) {
    val uid = expectedUid.toLongOrNull() ?: throw GradleException("invalid expected runner UID: $expectedUid")
    require(path.isDirectory) { "missing G3z ownership root: ${path.absolutePath}" }
    path.walkTopDown().forEach { file ->
        val actual = Files.getAttribute(file.toPath(), "unix:uid") as Number
        require(actual.toLong() == uid) {
            "G3z evidence file is not runner-owned: ${file.absolutePath} uid=${actual.toLong()} expected=$uid"
        }
    }
}

fun g3zWithRunFailureMarker(report: File, action: () -> Unit) {
    try {
        action()
    } catch (failure: Throwable) {
        report.resolve("cleanup-completed.txt").writeText("false\n")
        throw failure
    }
}

fun g3zRequireJvmEvidence(evidence: File, expectedUid: String, expectedGid: String) {
    val launcher = g3zReadKeyValues(evidence.resolve("launcher-receipt.txt"))
    g3zRequireKeyValues(
        evidence.resolve("launcher-receipt.txt"),
        mapOf(
            "method" to g3zMethod,
            "runtime" to "jvm",
            "testsFound" to "1",
            "testsStarted" to "1",
            "testsSucceeded" to "1",
            "testsFailed" to "0",
            "testsAborted" to "0",
            "testsSkipped" to "0",
            "containersFailed" to "0",
        ),
    )
    val containersFound = launcher["containersFound"]?.toLongOrNull()
        ?: throw GradleException("JVM launcher receipt has no containersFound count")
    require(
        containersFound > 0L &&
            launcher["containersStarted"]?.toLongOrNull() == containersFound &&
            launcher["containersSucceeded"]?.toLongOrNull() == containersFound &&
            launcher["containersAborted"] == "0" &&
            launcher["containersSkipped"] == "0",
    ) { "JVM launcher container count contract failed: $launcher" }
    g3zRequireKeyValues(
        evidence.resolve("fixture-receipt.txt"),
        mapOf(
            "method" to g3zMethod,
            "runtime" to "jvm",
            "effective-uid" to expectedUid,
            "effective-gid" to expectedGid,
            "pid1" to "true",
            "parent-reparented" to "true",
            "zombie-state" to "Z",
            "zombie-threads" to "1",
            "zombie-isAlive" to "true",
            "same-identity" to "true",
            "live-control" to "pending-until-release",
            "live-isAlive-before-release" to "true",
            "result" to "exitCode=0;production-observed=true",
            "cleanup-worker-stopped" to "true",
            "cleanup-helpers-stopped" to "true",
            "cleanup-processes-quiescent" to "true",
            "cleanup-identity-scoped" to "true",
        ),
    )
    g3zRequireFixtureFacts(evidence.resolve("fixture-receipt.txt"))
    g3zRequireExactJUnitXml(
        evidence.resolve("TEST-$g3zMethodClass.xml"),
        g3zMethodClass,
        "$g3zMethodName()",
    )
}

fun g3zRequireForcedTimeoutEvidence(report: File, evidence: File, run: G3zNamespaceRun) {
    require(run.exitCode == 124 || run.exitCode == 137 || run.exitCode == 143) {
        "G3z forced-timeout control must retain timeout exit 124/137/143, found ${run.exitCode}"
    }
    val started = g3zReadKeyValues(evidence.resolve("forced-started.txt"))
    require(started["pid"]?.toLongOrNull()?.let { it > 0L } == true && started["pid1"] == "true") {
        "G3z forced-timeout control did not start as namespace PID1: $started"
    }
    val termReceipt = evidence.resolve("forced-term-observed.txt")
    require(termReceipt.isFile) { "G3z forced-timeout TERM receipt is missing" }
    require(termReceipt.readText().trim() == "true") { "G3z forced-timeout TERM receipt is malformed" }
    require(report.resolve("process-identities.tsv").isFile && report.resolve("process-identities.tsv").readLines().isNotEmpty()) {
        "G3z forced-timeout control retained no host process identities"
    }
    require(report.resolve("cleanup-completed.txt").readText().trim() == "true") {
        "G3z forced-timeout control did not positively complete recorded-process cleanup"
    }
    g3zRequireRunnerOwnership(report, run.runnerUid)
}

fun g3zRequireNativeEvidence(evidence: File, nativeOutput: File, expectedUid: String, expectedGid: String) {
    require(nativeOutput.isFile && nativeOutput.canExecute()) { "missing staged native G3z executable: $nativeOutput" }
    val architecture = g3zElfArchitecture(nativeOutput)
    val hash = g3zSha256(nativeOutput)
    evidence.resolve("executable.sha256").writeText("$hash  ${nativeOutput.absolutePath}\n")
    evidence.resolve("executable.elf-architecture").writeText("$architecture\n")
    g3zRequireKeyValues(
        evidence.resolve("fixture-receipt.txt"),
        mapOf(
            "method" to g3zMethod,
            "runtime" to "runtime",
            "org.graalvm.nativeimage.imagecode" to "runtime",
            "effective-uid" to expectedUid,
            "effective-gid" to expectedGid,
            "pid1" to "true",
            "parent-reparented" to "true",
            "zombie-state" to "Z",
            "zombie-threads" to "1",
            "zombie-isAlive" to "true",
            "same-identity" to "true",
            "live-control" to "pending-until-release",
            "live-isAlive-before-release" to "true",
            "result" to "exitCode=0;production-observed=true",
            "cleanup-worker-stopped" to "true",
            "cleanup-helpers-stopped" to "true",
            "cleanup-processes-quiescent" to "true",
            "cleanup-identity-scoped" to "true",
        ),
    )
    g3zRequireFixtureFacts(evidence.resolve("fixture-receipt.txt"))
    val nativeStdout = evidence.resolve("stdout.log").readText()
    require(nativeStdout.contains("JUnit Platform on Native Image - report")) {
        "native G3z output did not contain the native launcher header"
    }
    val xmlFiles = evidence.resolve("xml").walkTopDown().filter { it.isFile && it.extension == "xml" }.toList()
    require(xmlFiles.size == 1) { "expected exactly one fresh native XML, found ${xmlFiles.size}" }
    val xmlCounts = g3zReadJUnitXml(xmlFiles.single())
    g3zRequireExactJUnitXml(xmlFiles.single(), g3zMethodClass, "$g3zMethodName()")
    val summary = g3zReadNativeSummary(evidence.resolve("stdout.log"))
    require(
        summary.testsFound == 1L &&
            summary.testsStarted == 1L &&
            summary.testsSucceeded == 1L &&
            summary.testsAborted == 0L &&
            summary.testsFailed == 0L &&
            summary.testsSkipped == 0L &&
            summary.containersFound > 0L &&
            summary.containersSkipped == 0L &&
            summary.containersStarted == summary.containersFound &&
            summary.containersAborted == 0L &&
            summary.containersSuccessful == summary.containersFound &&
            summary.containersFailed == 0L,
    ) { "native G3z summary count contract failed: $summary" }
    require(xmlCounts.errors == 0L) { "native XML reports a failed container/test error: $xmlCounts" }
    evidence.resolve("native-count-receipt.txt").writeText(
        "testsFound=${summary.testsFound}\n" +
            "testsStarted=${summary.testsStarted}\n" +
            "testsSucceeded=${summary.testsSucceeded}\n" +
            "testsAborted=${summary.testsAborted}\n" +
            "testsFailed=${summary.testsFailed}\n" +
            "testsSkipped=${summary.testsSkipped}\n" +
            "containersFound=${summary.containersFound}\n" +
            "containersSkipped=${summary.containersSkipped}\n" +
            "containersStarted=${summary.containersStarted}\n" +
            "containersAborted=${summary.containersAborted}\n" +
            "containersSuccessful=${summary.containersSuccessful}\n" +
            "containersFailed=${summary.containersFailed}\n",
    )
    g3zRequireKeyValues(
        evidence.resolve("native-count-receipt.txt"),
        mapOf(
            "testsFound" to "1",
            "testsStarted" to "1",
            "testsSucceeded" to "1",
            "testsAborted" to "0",
            "testsFailed" to "0",
            "testsSkipped" to "0",
            "containersFound" to summary.containersFound.toString(),
            "containersSkipped" to "0",
            "containersStarted" to summary.containersStarted.toString(),
            "containersAborted" to "0",
            "containersSuccessful" to summary.containersSuccessful.toString(),
            "containersFailed" to "0",
        ),
    )
}

val prepareGitZombieJvmPid1 = tasks.register("prepareGitZombieJvmPid1") {
    group = "verification"
    description = "Prepare the ordered nativeTest JVM classpath for the Linux G3z PID1 regression."
    dependsOn(tasks.named("nativeTestClasses"))
    inputs.files(g3zNativeRuntimeClasspath)
        .withPropertyName("g3zNativeTestRuntimeClasspath")
        .withNormalizer(ClasspathNormalizer::class)
    outputs.dir(g3zJvmPreparationDir)
    outputs.upToDateWhen { false }
    doLast {
        val preparation = g3zJvmPreparationDir.get().asFile
        preparation.deleteRecursively()
        preparation.mkdirs()
        val jvmLauncher = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        }.get().executablePath.asFile.absoluteFile
        require(jvmLauncher.isFile && jvmLauncher.canExecute()) { "JVM toolchain executable is unavailable: $jvmLauncher" }
        val orderedClasspath = g3zNativeRuntimeClasspath.asPath
        require(orderedClasspath.isNotBlank()) { "nativeTest runtime classpath is empty" }
        preparation.resolve("jvm-launcher.txt").writeText("${jvmLauncher.absolutePath}\n")
        preparation.resolve("classpath.txt").writeText("$orderedClasspath\n")
        preparation.resolve("classpath.entries.txt").writeText(
            g3zNativeRuntimeClasspath.files.joinToString("\n", postfix = "\n") { it.absoluteFile.normalize().path },
        )
        preparation.resolve("method.txt").writeText("$g3zMethod\n")
    }
}

tasks.register("gitZombieJvmPid1") {
    group = "verification"
    description = "Run the exact GitExecutor G3z method as PID1 in a Linux namespace on the JVM."
    dependsOn(prepareGitZombieJvmPid1)
    inputs.files(g3zNativeRuntimeClasspath)
        .withPropertyName("g3zNativeTestRuntimeClasspath")
        .withNormalizer(ClasspathNormalizer::class)
    outputs.dir(layout.buildDirectory.dir("reports/g3z/jvm"))
    outputs.upToDateWhen { false }
    doLast {
        g3zRequireLinux()
        val preparation = g3zJvmPreparationDir.get().asFile
        val jvmLauncher = File(preparation.resolve("jvm-launcher.txt").readText().trim())
        val orderedClasspath = preparation.resolve("classpath.txt").readText().trim()
        require(jvmLauncher.isFile && jvmLauncher.canExecute()) { "prepared JVM executable is missing: $jvmLauncher" }
        require(orderedClasspath.isNotBlank()) { "prepared nativeTest classpath is empty" }

        val runId = UUID.randomUUID().toString()
        val report = layout.buildDirectory.dir("reports/g3z/jvm/$runId").get().asFile
        val evidence = report.resolve("evidence")
        val working = layout.buildDirectory.dir("g3z/jvm-work/$runId").get().asFile
        val home = working.resolve("home")
        val tmp = working.resolve("tmp")
        report.mkdirs()
        evidence.mkdirs()
        g3zWithRunFailureMarker(report) {
            val run = g3zRunNamespace(
                G3zNamespaceLaunch(
                    runtime = "jvm",
                    report = report,
                    workingDirectory = working,
                    home = home,
                    tmp = tmp,
                    executable = jvmLauncher,
                    executableArguments = listOf(
                        "--enable-native-access=ALL-UNNAMED",
                        "-Dplainbase.test.g3z.pid1=true",
                        "-Dplainbase.test.g3z.evidence=${evidence.absolutePath}",
                        "-Duser.home=${home.absolutePath}",
                        "-Djava.io.tmpdir=${tmp.absolutePath}",
                        "-cp",
                        orderedClasspath,
                        g3zJvmLauncher,
                    ),
                ),
            )
            require(run.exitCode == 0) { "Linux JVM G3z namespace command failed with exit ${run.exitCode}; see $report" }
            g3zRequireRunnerOwnership(evidence, run.runnerUid)
            g3zRequireJvmEvidence(evidence, run.runnerUid, run.runnerGid)
        }
    }
}

val prepareGitZombieNativePid1 = tasks.register("prepareGitZombieNativePid1") {
    group = "verification"
    description = "Stage the existing nativeTestCompile executable and select exactly one G3z method UID."
    dependsOn(g3zNativeCompileTask, tasks.named("nativeTestList"))
    inputs.file(g3zNativeOutput).withPropertyName("g3zNativeExecutable")
    inputs.dir(g3zNativeTestListDir).withPropertyName("g3zNativeTestList")
    outputs.dir(g3zNativeStageDir)
    outputs.upToDateWhen { false }
    doLast {
        val stage = g3zNativeStageDir.get().asFile
        stage.deleteRecursively()
        stage.mkdirs()
        val output = g3zNativeOutput.get().asFile.absoluteFile
        require(output.isFile && output.canExecute()) { "nativeTestCompile output is missing or not executable: $output" }
        val stagedExecutable = stage.resolve(output.name)
        g3zCopyWithAttributes(output, stagedExecutable)
        require(stagedExecutable.canExecute()) {
            "nativeTestCompile executable permissions were not preserved: $stagedExecutable"
        }
        val adjacent = output.parentFile.listFiles()
            ?.filter { it.isFile && it.name != output.name }
            ?.sortedBy(File::getName)
            .orEmpty()
        stage.resolve("adjacent-libraries.txt").writeText(
            adjacent.joinToString("\n", postfix = if (adjacent.isEmpty()) "" else "\n") { it.name },
        )
        adjacent.forEach { file ->
            val staged = stage.resolve(file.name)
            g3zCopyWithAttributes(file, staged)
            if (file.canExecute()) {
                require(staged.canExecute()) { "native staging lost executable permission for ${file.name}" }
            }
        }

        val uidFiles = g3zNativeTestListDir.get().asFile.walkTopDown()
            .filter { it.isFile && it.name.startsWith("junit-platform-unique-ids") && it.extension == "txt" }
            .sortedBy(File::getName)
            .toList()
        require(uidFiles.isNotEmpty()) { "nativeTestList produced no full compile-input UID files" }
        val compileIds = stage.resolve("compile-ids")
        compileIds.mkdirs()
        uidFiles.forEach { file -> file.copyTo(compileIds.resolve(file.name), overwrite = true) }
        val allIds = uidFiles.flatMap { it.readLines() }.filter { it.isNotBlank() }
        val classMarker = "[class:$g3zMethodClass]"
        val methodMarker = "[method:$g3zMethodName("
        val selectedIds = allIds.filter { it.contains(classMarker) && it.contains(methodMarker) }.distinct()
        require(selectedIds.size == 1) {
            "expected exactly one distinct compile-input UID for $g3zMethod, found ${selectedIds.size}"
        }
        val selectedId = selectedIds.single()
        require(allIds.count { it == selectedId } >= 1) { "selected G3z UID was not retained in compile input" }
        val ids = stage.resolve("ids")
        ids.mkdirs()
        ids.resolve("junit-platform-unique-ids-g3z.txt").writeText("$selectedId\n")
        stage.resolve("method.txt").writeText("$g3zMethod\n")
        stage.resolve("source-uid-manifest.txt").writeText(
            uidFiles.joinToString("\n", postfix = "\n") { file -> "${file.name}\t${g3zSha256(file)}" },
        )
    }
}

tasks.register("gitZombieNativePid1") {
    group = "verification"
    description = "Run the existing nativeTestCompile executable as PID1 for the exact Linux G3z method UID."
    dependsOn(prepareGitZombieNativePid1)
    inputs.file(g3zNativeOutput).withPropertyName("g3zNativeExecutable")
    inputs.dir(g3zNativeStageDir).withPropertyName("g3zNativeStage")
    outputs.dir(layout.buildDirectory.dir("reports/g3z/native"))
    outputs.upToDateWhen { false }
    doLast {
        g3zRequireLinux()
        val stage = g3zNativeStageDir.get().asFile
        val executable = stage.resolve(g3zNativeOutput.get().asFile.name)
        val ids = stage.resolve("ids/junit-platform-unique-ids-g3z.txt")
        require(executable.isFile && executable.canExecute()) { "staged native G3z executable is missing: $executable" }
        require(ids.isFile && ids.readLines().count { it.isNotBlank() } == 1) {
            "staged native G3z selector must contain exactly one UID: $ids"
        }
        val runId = UUID.randomUUID().toString()
        val report = layout.buildDirectory.dir("reports/g3z/native/$runId").get().asFile
        val evidence = report
        val working = layout.buildDirectory.dir("g3z/native-work/$runId").get().asFile
        val home = working.resolve("home")
        val tmp = working.resolve("tmp")
        val xml = evidence.resolve("xml")
        report.mkdirs()
        xml.mkdirs()
        g3zWithRunFailureMarker(report) {
            val run = g3zRunNamespace(
                G3zNamespaceLaunch(
                    runtime = "native",
                    report = report,
                    workingDirectory = working,
                    home = home,
                    tmp = tmp,
                    executable = executable,
                    executableArguments = listOf(
                        "-Dplainbase.test.g3z.pid1=true",
                        "-Dplainbase.test.g3z.evidence=${evidence.absolutePath}",
                        "-Djunit.platform.listeners.uid.tracking.output.dir=${ids.parentFile.absolutePath}",
                        "--xml-output-dir",
                        xml.absolutePath,
                    ),
                ),
            )
            require(run.exitCode == 0) { "Linux native G3z namespace command failed with exit ${run.exitCode}; see $report" }
            g3zRequireNativeEvidence(evidence, executable, run.runnerUid, run.runnerGid)
            g3zRequireRunnerOwnership(evidence, run.runnerUid)
        }
    }
}

tasks.register("gitZombieForcedTimeoutPid1") {
    group = "verification"
    description = "Prove the scoped G3z namespace timeout and recorded-identity cleanup path."
    dependsOn(prepareGitZombieJvmPid1)
    outputs.dir(layout.buildDirectory.dir("reports/g3z/forced"))
    outputs.upToDateWhen { false }
    doLast {
        g3zRequireLinux()
        val preparation = g3zJvmPreparationDir.get().asFile
        val jvmLauncher = File(preparation.resolve("jvm-launcher.txt").readText().trim())
        val orderedClasspath = preparation.resolve("classpath.txt").readText().trim()
        require(jvmLauncher.isFile && jvmLauncher.canExecute()) { "prepared JVM executable is missing: $jvmLauncher" }
        require(orderedClasspath.isNotBlank()) { "prepared nativeTest classpath is empty" }
        val runId = UUID.randomUUID().toString()
        val report = layout.buildDirectory.dir("reports/g3z/forced/$runId").get().asFile
        val evidence = report.resolve("evidence")
        val working = layout.buildDirectory.dir("g3z/forced-work/$runId").get().asFile
        val home = working.resolve("home")
        val tmp = working.resolve("tmp")
        report.mkdirs()
        evidence.mkdirs()
        g3zWithRunFailureMarker(report) {
            val run = g3zRunNamespace(
                G3zNamespaceLaunch(
                    runtime = "jvm-forced-timeout",
                    report = report,
                    workingDirectory = working,
                    home = home,
                    tmp = tmp,
                    executable = jvmLauncher,
                    executableArguments = listOf(
                        "-Dplainbase.test.g3z.pid1=true",
                        "-Dplainbase.test.g3z.forced.evidence=${evidence.absolutePath}",
                        "-Duser.home=${home.absolutePath}",
                        "-Djava.io.tmpdir=${tmp.absolutePath}",
                        "-cp",
                        orderedClasspath,
                        g3zForcedTimeoutLauncher,
                    ),
                ),
            )
            g3zRequireForcedTimeoutEvidence(report, evidence, run)
        }
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
    description = "Regenerate dependency-allowlist.txt from the resolved runtime classpath (a deliberate act - see comment above)"
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
