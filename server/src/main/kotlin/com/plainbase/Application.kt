package com.plainbase

import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.koin.configModule
import com.plainbase.frameworks.koin.repositoryModule
import com.plainbase.frameworks.koin.securityModule
import com.plainbase.frameworks.ktor.KtorServer
import com.plainbase.frameworks.spike.NativeSpike
import org.koin.core.context.startKoin
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "spike" -> exitProcess(NativeSpike.runAsMain())
        null, "serve" -> serve()
        else -> {
            System.err.println("Unknown command: ${args.first()} (expected: serve | spike)")
            exitProcess(2)
        }
    }
}

private fun serve() {
    val koin = startKoin {
        modules(configModule, repositoryModule, securityModule)
    }.koin

    val config = koin.get<PlainbaseConfig>()
    KtorServer(config).start(wait = true)
}
