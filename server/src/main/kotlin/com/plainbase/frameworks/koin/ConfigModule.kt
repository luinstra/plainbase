package com.plainbase.frameworks.koin

import com.plainbase.frameworks.config.PlainbaseConfig
import org.koin.dsl.module

// Constructor DSL only - no annotations, no reflection (native-image constraint, §5.8).
// The server layers DATA_DIR/plainbase.conf under env (ADR-0009, env always wins), as do the `admin`, `adopt`
// and `reindex` CLIs: they share the DATA_DIR, so their storage-backend/auth decision MUST match serve for the
// same directory. Only the credential-free `spike` keeps the env-only fromEnv() fast path.
//
// TEST-ONLY as of R2-2: production `serve()` no longer wires this module - it resolves config DIRECTLY via
// PlainbaseConfig.loadForCommand(), then inlines `module { single { config } }`, so a bad config funnels to a
// clean `serve:` refusal instead of a Koin InstanceCreationException wrapper. This module remains only for the
// Koin wiring tests, which pin their env to temp dirs and want the lazy `single {}` shape.
val configModule = module {
    single { PlainbaseConfig.fromEnvAndFile() }
}
