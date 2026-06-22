package com.plainbase.frameworks.ktor

import io.ktor.server.sessions.SessionSerializer

/**
 * The session payload is the random opaque cookie token: a [String] round-tripped by IDENTITY — no `kotlin.reflect`
 * (the default reflection-based Ktor session serializer is the native-image crash hazard `SessionCookieNativeTest`
 * proved this design avoids). ONE definition shared by the production `Sessions` install (KtorServer) and the
 * native test, so they exercise the same serializer.
 */
object OpaqueStringSerializer : SessionSerializer<String> {
    override fun serialize(session: String): String = session
    override fun deserialize(text: String): String = text
}
