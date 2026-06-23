package com.plainbase.frameworks.security

import com.plainbase.frameworks.sqldelight.DatabaseFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.encoding.Base64

/**
 * A4b WI-4: the proxy-CSRF HMAC server key in `app_meta`. First load generates + persists; a second load over the
 * SAME db returns the byte-identical key (restart stability, so a token issued before a restart still validates); the
 * stored value is base64url of 32 random bytes (decodes to 32 bytes), and two fresh DBs yield different keys.
 */
class ServerKeyStoreTest : FunSpec({

    val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    test("first load generates + persists; a second load over the same db returns the SAME key (restart-stable)") {
        DatabaseFactory.createInMemoryDriver().use { driver ->
            val db = DatabaseFactory.createDatabase(driver)
            val first = loadOrCreateProxyCsrfKey(db)
            val second = loadOrCreateProxyCsrfKey(db)
            first.size shouldBe 32
            second.contentEquals(first) shouldBe true
        }
    }

    test("the stored app_meta value is base64url of 32 random bytes — not the raw key, and distinct across DBs") {
        DatabaseFactory.createInMemoryDriver().use { d1 ->
            DatabaseFactory.createInMemoryDriver().use { d2 ->
                val db1 = DatabaseFactory.createDatabase(d1)
                val db2 = DatabaseFactory.createDatabase(d2)
                loadOrCreateProxyCsrfKey(db1)
                loadOrCreateProxyCsrfKey(db2)

                val stored1 = db1.appMetaQueries.selectByKey("proxy_csrf_hmac_key").executeAsOne()
                val stored2 = db2.appMetaQueries.selectByKey("proxy_csrf_hmac_key").executeAsOne()
                base64.decode(stored1).size shouldBe 32
                (stored1 == stored2) shouldBe false // two fresh DBs → two different random keys
            }
        }
    }
})
