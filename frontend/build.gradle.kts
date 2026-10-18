import com.github.gradle.node.npm.task.NpmTask
import java.security.MessageDigest
import java.util.Arrays
import java.util.HexFormat

plugins {
    alias(libs.plugins.node.gradle)
}

node {
    // Hermetic builds: Gradle downloads its own Node, so Docker/CI only need a JDK.
    download.set(true)
    // Latest stable Node Current; npm is updated separately.
    version.set("26.8.2")
    npmVersion.set("12.0.2")
    workDir.set(layout.projectDirectory.dir(".node/node"))
    npmWorkDir.set(layout.projectDirectory.dir(".node/npm"))
}

val npmBuild = tasks.register<NpmTask>("npmBuild") {
    dependsOn(tasks.npmInstall)
    args.set(listOf("run", "build"))
    inputs.files("package.json", "package-lock.json", "vite.config.ts", "tsconfig.json", "index.html")
    inputs.dir("src")
    inputs.dir("public") // copied verbatim into dist/ by vite; a changed brand asset must re-embed
    // Tailwind scans these, so they are build inputs.
    inputs.dir("e2e")
    inputs.dir("scripts")
    inputs.dir("plugins") // Vite config dependency and Tailwind scan source.
    inputs.files("playwright.config.ts", "build.gradle.kts")
    outputs.dir(layout.projectDirectory.dir("dist"))
}

// Unit/snapshot tests and the token-discipline gate (vitest). Part of `build`, so the
// JAR floor rejects hex colors outside src/styles/themes/light.css and src/styles/themes/dark.css.
val npmTest = tasks.register<NpmTask>("npmTest") {
    dependsOn(tasks.npmInstall)
    args.set(listOf("run", "test"))
    // No inputs declared: the task always runs by design (the token-discipline gate must run on every build).
    outputs.upToDateWhen { false }
}

// Targeted single-file vitest run for fast iteration (NOT part of `build`, no token-discipline gate):
//   ./gradlew :frontend:vitestFile -PtestFile=src/__tests__/searchPalette.test.tsx
// Omit -PtestFile to run the whole suite. Prefer this (or :frontend:npmTest) over a raw
// `./node_modules/.bin/vitest` invocation so the command stays a stable, whitelistable `./gradlew :*`.
tasks.register<NpmTask>("vitestFile") {
    group = "verification"
    description = "Runs vitest for a single file via -PtestFile=<path> (fast iteration)."
    dependsOn(tasks.npmInstall)
    val testFile = providers.gradleProperty("testFile").getOrElse("")
    args.set(listOf("run", "test", "--") + if (testFile.isBlank()) emptyList() else listOf(testFile))
    outputs.upToDateWhen { false }
}

// Tinted gate: GPL-3.0 tool executed at build time, never bundled.
val tintedBuilderVersion = "0.21.0"
val tintedSchemesCommit = "3ac91137e879502c442ab89ba0f1a98077d0da9d"
val tintedCorpusContentSha256 = "7560e34476b715f801ebb8f1e23db03045a6d77ae05d3ecd1dcd7ebb01975ca8"
val tintedBuilderPins = mapOf(
    "macos-arm64" to ("aarch64-apple-darwin" to "b8fee54cea95ff7631bfb9e7175a733d99c6131348146ff3932cbc67479eadf0"),
    "linux-x64" to ("x86_64-unknown-linux-musl" to "89fd095b63a7be8190e1d3e2b3162340f8f09da9851909683cf412ad3164f66b"),
    "linux-arm64" to ("aarch64-unknown-linux-gnu" to "1cd31a16104d69248766a71641bbd43802a1327e0aeba339db9f182553834a3a"),
)
val tintedBuilderPin = providers.provider {
    val osName = System.getProperty("os.name")
    val osArch = System.getProperty("os.arch")
    val platform = when {
        osName == "Mac OS X" && osArch == "aarch64" -> "macos-arm64"
        osName == "Linux" && osArch in listOf("amd64", "x86_64") -> "linux-x64"
        osName == "Linux" && osArch == "aarch64" -> "linux-arm64"
        else -> throw GradleException(
            "Unsupported Tinted builder host: $osName/$osArch. Supported: macos-arm64, linux-x64, linux-arm64.",
        )
    }
    tintedBuilderPins.getValue(platform)
}
repositories {
    exclusiveContent {
        forRepository {
            ivy {
                name = "tintedBuilderReleases"
                url = uri("https://github.com/tinted-theming/tinted-builder-rust/releases/download")
                patternLayout { artifact("v[revision]/[module]-[classifier].[ext]") }
                metadataSources { artifact() }
            }
        }
        filter { includeModule("tinted-theming", "tinted-builder-rust") }
    }
    exclusiveContent {
        forRepository {
            ivy {
                name = "tintedSchemeCorpus"
                url = uri("https://codeload.github.com/tinted-theming/schemes/tar.gz")
                patternLayout { artifact("[revision]") }
                metadataSources { artifact() }
            }
        }
        filter { includeModule("tinted-theming", "schemes") }
    }
}
val tintedBuilder = configurations.create("tintedBuilder") {
    isTransitive = false
    isCanBeConsumed = false
    isCanBeResolved = true
}
tintedBuilder.dependencies.addLater(tintedBuilderPin.map { (target, _) ->
    dependencies.create("tinted-theming:tinted-builder-rust:$tintedBuilderVersion:$target@tar.gz")
})
val tintedCorpus = configurations.create("tintedCorpus") {
    isTransitive = false
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies.add(tintedCorpus.name, "tinted-theming:schemes:$tintedSchemesCommit@tar.gz")
val tintedCorpusDir = layout.buildDirectory.dir("tinted/corpus/$tintedSchemesCommit")
val tintedCorpusSetup = tasks.register<Sync>("tintedCorpusSetup") {
    from(providers.provider { tarTree(resources.gzip(tintedCorpus.singleFile)) }) {
        include("*/base16/**", "*/base24/**", "*/LICENSE")
        eachFile {
            relativePath = RelativePath(!isDirectory, *relativePath.segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    into(tintedCorpusDir)
    inputs.files(tintedCorpus)
    inputs.property("commit", tintedSchemesCommit)
    inputs.property("contentSha256", tintedCorpusContentSha256)
    doLast {
        val manifest = destinationDir.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(destinationDir).invariantSeparatorsPath to it }
            .sortedWith { left, right ->
                Arrays.compareUnsigned(left.first.toByteArray(Charsets.UTF_8), right.first.toByteArray(Charsets.UTF_8))
            }
            .joinToString("") { (path, file) ->
                val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
                "$path\t$hash\n"
            }
        val actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifest.toByteArray(Charsets.UTF_8)))
        if (actual != tintedCorpusContentSha256) {
            delete(destinationDir)
            throw GradleException("Tinted corpus content SHA-256 mismatch: expected $tintedCorpusContentSha256, actual $actual")
        }
    }
}
fun locateTintedBinary(dir: File): File {
    val found = dir.walkTopDown().filter { it.isFile && it.name == "tinted-builder-rust" }.toList()
    if (found.size != 1) {
        throw GradleException("Expected exactly one tinted-builder-rust under $dir; found: $found")
    }
    return found.single()
}
val tintedBuilderDir = tintedBuilderPin.flatMap { (target, _) ->
    layout.buildDirectory.dir("tinted/builder/v$tintedBuilderVersion/$target")
}
// Verified before tarTree sees it: Sync fingerprints (and so expands) its sources before any task action runs.
fun verifiedTintedArchive(): File {
    val archive = tintedBuilder.singleFile
    val actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive.readBytes()))
    val expected = tintedBuilderPin.get().second
    if (actual != expected) {
        throw GradleException("Tinted builder SHA-256 mismatch: expected $expected, actual $actual")
    }
    return archive
}
val tintedBuilderSetup = tasks.register<Sync>("tintedBuilderSetup") {
    from(providers.provider { tarTree(resources.gzip(verifiedTintedArchive())) })
    into(tintedBuilderDir)
    inputs.files(tintedBuilder)
    inputs.property("version", tintedBuilderVersion)
    inputs.property("target", tintedBuilderPin.map { it.first })
    inputs.property("sha256", tintedBuilderPin.map { it.second })
    doLast {
        val binary = locateTintedBinary(destinationDir)
        if (!binary.setExecutable(true) || !binary.canExecute()) {
            throw GradleException("Cannot make Tinted builder executable: $binary")
        }
    }
}
val tintedTemplateCheck = tasks.register<NpmTask>("tintedTemplateCheck") {
    group = "verification"
    description = "Checks the offline Tinted template using the pinned builder."
    dependsOn(tasks.npmInstall, tintedBuilderSetup, tintedCorpusSetup)
    args.set(listOf(
        "run", "test", "--", "--config", "src/__tests__/tinted/vitest.tinted.config.ts",
        "--reporter=default", "--reporter=json", "--outputFile.json=build/tinted/results/vitest-report.json",
    ))
    environment.put("PB_TINTED_BUILDER", providers.provider {
        locateTintedBinary(tintedBuilderSetup.get().destinationDir).absolutePath
    })
    environment.put("PB_TINTED_BUILDER_VERSION", tintedBuilderVersion)
    environment.put("PB_TINTED_CORPUS", tintedCorpusDir.map { it.asFile.absolutePath })
    environment.put("PB_TINTED_WORK", layout.buildDirectory.dir("tinted/work").map { it.asFile.absolutePath })
    environment.put("PB_TINTED_RESULTS", layout.buildDirectory.dir("tinted/results").map { it.asFile.absolutePath })
    inputs.dir(rootProject.layout.projectDirectory.dir("contrib/tinted"))
    inputs.dir("src/__tests__")
    inputs.dir("src/styles")
    inputs.files("package.json", "package-lock.json")
    inputs.files(tintedBuilderSetup)
    inputs.files(tintedCorpusSetup)
    inputs.dir(rootProject.layout.projectDirectory.dir("server/src/test/resources/tinted"))
    inputs.property("version", tintedBuilderVersion)
    inputs.property("target", tintedBuilderPin.map { it.first })
    inputs.property("sha256", tintedBuilderPin.map { it.second })
    outputs.dir(layout.buildDirectory.dir("tinted/results"))
    doFirst {
        delete(layout.buildDirectory.dir("tinted/work"), layout.buildDirectory.dir("tinted/results"))
    }
}

tasks.register("build") {
    dependsOn(npmBuild, npmTest, tintedTemplateCheck)
}

// Playwright smoke flow against the REAL server (installed dist + embedded SPA + fixture
// tree). See playwright.config.ts and the test-scoped server fixture. A separate invocation, NOT part of `build`: it
// downloads an unpinned Chromium on first run, unlike the SHA-pinned, Gradle-cached Tinted builder the floor uses.
tasks.register<NpmTask>("smokeTest") {
    group = "verification"
    description = "Runs the Playwright smoke flow against the installed server serving fixtures/demo-docs"
    dependsOn(tasks.npmInstall, ":server:installDist")
    // smokeArgs is whitespace-tokenized; callers cannot pass one argument containing spaces.
    val smokeArgs = providers.gradleProperty("smokeArgs")
        .map { value -> value.trim().split(Regex("\\s+")).filter(String::isNotBlank) }
        .getOrElse(emptyList())
    args.set(listOf("run", "smoke", "--") + smokeArgs)
}

tasks.register<Delete>("clean") {
    delete(layout.projectDirectory.dir("dist"))
    delete(layout.buildDirectory.dir("tinted"))
}
