package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.config.DeploymentProfile
import com.plainbase.frameworks.config.SiteConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.charset
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("native")
class ReaderDocumentNativeTest {
    @Test
    fun readerDocumentKeepsNonAsciiTitleBytes() {
        withRestServices(
            pages = mapOf("guide.md" to "---\ntitle: Gr\u00fc\u00dfe \u65e5\u672c\n---\n\n# Gr\u00fc\u00dfe\n"),
            authMode = AuthMode.OFF,
            profile = DeploymentProfile.READER,
            site = SiteConfig(name = "Dok\u00fcs"),
        ) { services ->
            testApplication {
                application { plainbaseModule(services) }
                val response = client.get("/docs/guide")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(Charsets.UTF_8, response.contentType()?.charset())
                val bytes = response.bodyAsBytes()
                val title = "<title>Gr\u00fc\u00dfe \u65e5\u672c \u00b7 Dok\u00fcs</title>".toByteArray(Charsets.UTF_8)
                assertTrue(
                    bytes.indices.any { start ->
                        start + title.size <= bytes.size && title.indices.all { bytes[start + it] == title[it] }
                    },
                    "The UTF-8 title bytes must occur in the initial document",
                )
            }
        }
    }
}
