package com.plainbase

import com.plainbase.frameworks.cli.systemCommandOutput
import com.plainbase.frameworks.config.GitConfig
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.filesystem.DataDirLock
import com.plainbase.frameworks.lifecycle.ServerRunControl
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Small child-process entry for the runtime-hook test. It accepts explicit fixture paths and delegates to the shared
 * internal server entry; production environment loading is intentionally not used.
 */
fun main(args: Array<String>) {
    val arguments = Arguments(args)
    val output = systemCommandOutput()
    val report = arguments.report
    fun closeReceipt(kind: String, close: () -> Unit) {
        val lockHeld = DataDirLock.tryAcquire(arguments.data)
        if (lockHeld != null) lockHeld.close()
        check(lockHeld == null) { "DATA_DIR lock was acquirable while closing $kind" }
        close()
        Files.writeString(
            report,
            "close=$kind lockHeld=true\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }
    val control = ServerRunControl(
        closeDriver = { driver -> closeReceipt("app", driver::close) },
        closeSearch = { search -> closeReceipt("search", search::close) },
        closeContext = { context -> closeReceipt("context", context::close) },
    )
    val config = PlainbaseConfig(
        contentDir = arguments.content,
        dataDir = arguments.data,
        host = "127.0.0.1",
        port = arguments.port,
        git = GitConfig(enabled = false),
    )
    check(runServer(config, output, control = control) == 0) { "server returned a refusal" }
}

private class Arguments(args: Array<String>) {
    private val values = args.toList().chunked(2).associate { pair ->
        require(pair.size == 2 && pair[0].startsWith("--")) { "expected --name value arguments" }
        pair[0] to pair[1]
    }

    val content: Path get() = Path.of(required("--content"))
    val data: Path get() = Path.of(required("--data"))
    val report: Path get() = Path.of(required("--report"))
    val port: Int get() = required("--port").toInt()

    init {
        Files.createDirectories(report.parent)
        require(Files.isDirectory(content)) { "content fixture is not a directory: $content" }
        require(Files.isDirectory(data)) { "data fixture is not a directory: $data" }
    }

    private fun required(name: String): String = requireNotNull(values[name]) { "missing $name" }
}
