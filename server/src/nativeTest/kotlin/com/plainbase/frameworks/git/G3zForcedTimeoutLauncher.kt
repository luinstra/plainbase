package com.plainbase.frameworks.git

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

/** PID1 body for the root-owned timeout proof; it deliberately outlives TERM until KILL arrives. */
object G3zForcedTimeoutLauncher {

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty()) { "G3zForcedTimeoutLauncher accepts no arguments" }
        val evidence =
            System.getProperty("plainbase.test.g3z.forced.evidence")?.takeIf { it.isNotBlank() }?.let(Path::of)
                ?: error("plainbase.test.g3z.forced.evidence is required")
        Files.createDirectories(evidence)
        Files.writeString(
            evidence.resolve("forced-started.txt"),
            "pid=${ProcessHandle.current().pid()}\npid1=${ProcessHandle.current().pid() == 1L}\n",
        )
        Runtime.getRuntime().addShutdownHook(
            Thread({
                Files.writeString(evidence.resolve("forced-term-observed.txt"), "true\n")
                while (true) {
                    try {
                        Thread.sleep(60_000L)
                    } catch (_: InterruptedException) {
                        // The watchdog's SIGKILL is the only completion path for this control.
                    }
                }
            }, "g3z-forced-timeout-shutdown-hook"),
        )
        CountDownLatch(1).await()
    }
}
