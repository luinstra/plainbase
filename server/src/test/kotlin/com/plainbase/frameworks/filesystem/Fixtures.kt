package com.plainbase.frameworks.filesystem

import java.nio.file.Files
import java.nio.file.Path

/**
 * Locates the committed fixture tree at test time. Gradle runs the `:server` test task with
 * `server/` as the working directory, so `demo-docs` lives at `../fixtures/demo-docs`; we walk
 * up from `user.dir` to find the `fixtures` directory so the lookup survives a different CWD.
 */
object Fixtures {
    val demoDocs: Path by lazy { repoRoot().resolve("fixtures").resolve("demo-docs") }

    private fun repoRoot(): Path {
        var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("fixtures").resolve("demo-docs"))) return dir
            dir = dir.parent
        }
        error("Could not locate fixtures/demo-docs from ${System.getProperty("user.dir")}")
    }
}
