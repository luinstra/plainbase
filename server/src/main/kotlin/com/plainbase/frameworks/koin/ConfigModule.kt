package com.plainbase.frameworks.koin

import com.plainbase.frameworks.config.PlainbaseConfig
import org.koin.dsl.module

// Constructor DSL only — no annotations, no reflection (native-image constraint, §5.8).
val configModule = module {
    single { PlainbaseConfig.fromEnv() }
}
