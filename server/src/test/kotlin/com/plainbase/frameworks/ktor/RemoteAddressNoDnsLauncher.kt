package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.net.RemoteAddress
import java.net.InetAddress
import java.nio.file.Files

fun main(args: Array<String>) {
    val lane = args.singleOrNull() ?: error("expected one lane")
    if (lane == "stall") {
        positiveControl()
        Thread.sleep(30_000)
        return
    }
    if (lane == "overflow") {
        positiveControl()
        println("x".repeat(70_000))
        System.out.flush()
        Thread.sleep(30_000)
        return
    }
    require(lane in RemoteAddressNoDnsCases.lanes) { "unknown lane: $lane" }
    positiveControl()

    val rows = if (lane == "config") {
        runConfigLane()
    } else {
        RemoteAddressNoDnsCases.cases(lane).map { testCase -> measure(testCase) { valueFor(lane, testCase) } }
    }
    rows.forEach { println(RemoteAddressNoDnsProtocol.row(it)) }
    println(RemoteAddressNoDnsProtocol.completion(lane, rows.size))
    System.out.flush()

    val expected = RemoteAddressNoDnsCases.cases(lane)
    val failures = buildList {
        if (rows.map { it.id } != expected.map { it.id }) add("row IDs differ")
        rows.zip(expected).forEach { (actual, required) ->
            if (actual.verdict != required.expected) {
                add("${actual.id}: expected ${required.expected}, got ${actual.verdict}")
            }
            if (actual.attempts != 0) add("${actual.id}: resolver attempts=${actual.attempts}")
        }
    }
    if (failures.isNotEmpty()) error("lane $lane failed: ${failures.joinToString("; ")}")
}

private fun positiveControl() {
    RemoteAddressResolverAttempts.reset()
    val address = InetAddress.getByName("control.invalid")
    check(address.address.contentEquals(byteArrayOf(127, 0, 0, 1))) { "resolver positive control returned the wrong address" }
    check(RemoteAddressResolverAttempts.get() > 0) { "resolver positive control did not use the installed provider" }
    RemoteAddressResolverAttempts.reset()
}

private fun valueFor(lane: String, testCase: RemoteAddressNoDnsCase): String = when (lane) {
    "bind" -> RemoteAddress.isNonLoopbackBind(testCase.input).toString()
    "remote" -> RemoteAddress.isLoopbackAddress(testCase.input).toString()
    "remote-cidr" -> RemoteAddress.isInAnyCidr(testCase.input, listOf(requireNotNull(testCase.auxiliary))).toString()
    "network-cidr" -> RemoteAddress.isInAnyCidr(requireNotNull(testCase.auxiliary), listOf(testCase.input)).toString()
    "parse-cidr" -> RemoteAddress.isParseableCidr(testCase.input).toString()
    else -> error("unsupported non-config lane: $lane")
}

private fun runConfigLane(): List<RemoteAddressNoDnsRow> {
    val base = Files.createTempDirectory("plainbase-no-dns-config")
    val data = Files.createDirectory(base.resolve("data"))
    val content = Files.createDirectory(base.resolve("content"))
    return try {
        RemoteAddressNoDnsCases.cases("config").map { testCase ->
            measure(testCase) {
                try {
                    PlainbaseConfig.fromEnv(
                        mapOf(
                            "DATA_DIR" to data.toString(),
                            "CONTENT_DIR" to content.toString(),
                            "PLAINBASE_TRUSTED_PROXY" to testCase.input,
                        ),
                    )
                    "loaded"
                } catch (failure: IllegalArgumentException) {
                    if (failure.message?.contains("PLAINBASE_TRUSTED_PROXY") == true) {
                        "iae:PLAINBASE_TRUSTED_PROXY"
                    } else {
                        "iae:other"
                    }
                }
            }
        }
    } finally {
        base.toFile().deleteRecursively()
    }
}

private fun measure(testCase: RemoteAddressNoDnsCase, action: () -> String): RemoteAddressNoDnsRow {
    RemoteAddressResolverAttempts.reset()
    val verdict = runCatching { action() }.getOrElse { failure ->
        "exception:${failure::class.simpleName ?: "unknown"}"
    }
    return RemoteAddressNoDnsRow(testCase.id, verdict, RemoteAddressResolverAttempts.get())
}
