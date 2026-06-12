package com.plainbase

import com.plainbase.domain.service.IndexBuilder
import com.plainbase.frameworks.cli.AdoptCommand
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.koin.configModule
import com.plainbase.frameworks.koin.contentModule
import com.plainbase.frameworks.koin.indexModule
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
        modules(configModule, contentModule, repositoryModule, securityModule, indexModule)
    }.koin

    val config = koin.get<PlainbaseConfig>()
    // Fail fast, actionably: a missing CONTENT_DIR must name itself, not surface as the scan's
    // bare NoSuchFileException — and never silently serve an empty tree.
    config.requireContentDir()
    // Full scan at startup builds the snapshot (§C4); the chunk-6 rescan route rebuilds on demand.
    koin.get<IndexBuilder>().rebuild()
    KtorServer(config).start(wait = true)
}
