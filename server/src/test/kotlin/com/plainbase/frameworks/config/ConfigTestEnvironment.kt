package com.plainbase.frameworks.config

/** Pure decoding fixtures only. Tests opening state must supply their own disposable DATA_DIR. */
fun configTestEnv(vararg overrides: Pair<String, String>): Map<String, String> =
    mapOf("DATA_DIR" to "/plainbase-config-test-unused") + overrides
