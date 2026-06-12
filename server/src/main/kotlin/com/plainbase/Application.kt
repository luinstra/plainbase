package com.plainbase

import com.plainbase.frameworks.cli.AdoptCommand
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.koin.configModule
import com.plainbase.frameworks.koin.contentModule
import com.plainbase.frameworks.koin.repositoryModule
import com.plainbase.frameworks.koin.securityModule
import com.plainbase.frameworks.ktor.KtorServer
import com.plainbase.frameworks.spike.NativeSpike
import org.koin.core.context.startKoin
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "spike" -> exitProcess(NativeSpike.runAsMain())
        "adopt" -> exitProcess(AdoptCommand.runAsMain(args.drop(1)))
        null, "serve" -> serve()
        else -> {
            System.err.println("Unknown command: ${args.first()} (expected: serve | spike | adopt)")
            exitProcess(2)
        }
    }
}

private fun serve() {
    val koin = startKoin {
        modules(configModule, contentModule, repositoryModule, securityModule)
    }.koin

    val config = koin.get<PlainbaseConfig>()
    KtorServer(config).start(wait = true)
}
