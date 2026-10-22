package com.plainbase.frameworks.config

import com.plainbase.domain.service.PolicyMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class PolicyModeDerivationTest : FunSpec({
    DeploymentProfile.entries.forEach { profile ->
        AuthMode.entries.forEach { authMode ->
            val expected = when {
                profile == DeploymentProfile.READER -> PolicyMode.READER
                authMode == AuthMode.OFF -> PolicyMode.OPEN
                else -> PolicyMode.ENFORCED
            }
            test("should derive $expected for $profile with $authMode authentication") {
                val data = Files.createTempDirectory("pb-policy-mode")
                try {
                    Files.writeString(data.resolve("plainbase.conf"), "profile = ${profile.name.lowercase()}")
                    val config = ConfigLoader.fromEnvAndFile(
                        mapOf("DATA_DIR" to data.toString(), "PLAINBASE_AUTH_MODE" to authMode.name.lowercase()),
                    )
                    config.profile shouldBe profile
                    config.auth.mode shouldBe authMode
                    ConfigValuePolicy.policyMode(config) shouldBe expected
                } finally {
                    data.toFile().deleteRecursively()
                }
            }
        }
    }
})
