package com.plainbase.frameworks.ktor

import com.plainbase.frameworks.config.AuthMode
import com.plainbase.frameworks.config.DeploymentProfile
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/** In-image metadata proof. The in-process empty-body check is non-discriminating; socket tests own the body proof. */
@Tag("native")
class ReaderHeadNativeTest {

    @Test
    fun readerHeadMatchesGetMetadataInImage() {
        withRestServices(
            pages = mapOf("page.md" to "# Reader Page\n\nBody.\n"),
            authMode = AuthMode.OFF,
            profile = DeploymentProfile.READER,
        ) { services ->
            testApplication {
                application { plainbaseModule(services) }
                for (path in listOf("/healthz", "/api/v1/tree", "/_plainbase/theme-light.css")) {
                    val get = client.get(path)
                    val head = client.head(path)
                    assertEquals(HttpStatusCode.OK, get.status, path)
                    assertEquals(get.status, head.status, path)
                    assertEquals(get.headers.getAll(HttpHeaders.ContentType), head.headers.getAll(HttpHeaders.ContentType), path)
                    assertEquals(get.headers.getAll(HttpHeaders.ContentLength), head.headers.getAll(HttpHeaders.ContentLength), path)
                    assertEquals("", head.bodyAsText(), path)
                }
            }
        }
    }
}
