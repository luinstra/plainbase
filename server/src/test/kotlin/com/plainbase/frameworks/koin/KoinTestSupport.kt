package com.plainbase.frameworks.koin

import com.plainbase.frameworks.lifecycle.ServerResourceOwner
import com.plainbase.frameworks.lifecycle.ServerResourcePhase
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.koinApplication

/** Builds one admitted graph so definition installation cannot lose its context on failure. */
internal fun createOwnedTestKoinApplication(
    owner: ServerResourceOwner,
    modules: List<Module>,
): KoinApplication {
    val application = owner.construct("Koin context") {
        koinApplication().also { app ->
            owner.own(ServerResourcePhase.KOIN_CONTEXT, app) { it.close() }
        }
    }
    try {
        application.modules(modules)
    } catch (failure: Throwable) {
        owner.close()
        throw failure
    }
    return application
}
