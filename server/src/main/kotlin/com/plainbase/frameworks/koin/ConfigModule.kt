package com.plainbase.frameworks.koin

import com.plainbase.frameworks.config.PlainbaseConfig
import org.koin.dsl.module

// Constructor DSL only — no annotations, no reflection (native-image constraint, §5.8).
// The server layers DATA_DIR/plainbase.conf under env (ADR-0009, env always wins), as does the `admin` CLI
// (it needs the file-configured auth.mode); the spike + content-only CLIs (reindex, adopt) keep the env-only
// fromEnv() fast path.
val configModule = module {
    single { PlainbaseConfig.fromEnvAndFile() }
}
