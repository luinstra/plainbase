package com.plainbase.frameworks.koin

import com.plainbase.domain.service.IndexBuilder
import com.plainbase.domain.service.idLessPageWarning
import com.plainbase.frameworks.config.DeploymentProfile
import com.plainbase.frameworks.config.PlainbaseConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.util.concurrent.atomic.AtomicBoolean

private val readerLogger = KotlinLogging.logger {}

internal fun readerModules(config: PlainbaseConfig): List<Module> =
    if (config.profile == DeploymentProfile.READER) {
        listOf(
            module {
                single<IndexBuilder.PublicationListener>(named("idLessPageWarning")) {
                    val published = AtomicBoolean()
                    IndexBuilder.PublicationListener { snapshot, _ ->
                        if (published.compareAndSet(false, true)) {
                            idLessPageWarning(snapshot)?.let { message -> readerLogger.warn { message } }
                        }
                    }
                }
            },
        )
    } else {
        emptyList()
    }
