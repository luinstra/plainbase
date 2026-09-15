package com.plainbase.frameworks.config

/** One shared object-mode credential refusal used by both decoding and retained boot inspection. */
internal const val MISSING_S3_CREDENTIALS_MESSAGE: String =
    "PLAINBASE_S3_ACCESS_KEY_ID and PLAINBASE_S3_SECRET_ACCESS_KEY are required when storage.backend=object " +
        "(secrets stay in env, never plainbase.conf)"

/** Which backend holds the authoritative content bytes. */
enum class StorageBackend {
    LOCAL,
    OBJECT,
    ;

    companion object {
        /** Parses a backend name case-insensitively; absent and blank values use [LOCAL]. */
        fun parse(raw: String?): StorageBackend {
            val token = raw?.trim()
            if (token.isNullOrEmpty()) return LOCAL
            return entries.firstOrNull { it.name.equals(token, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Unknown storage.backend '$token' - legal values: ${entries.joinToString(", ") { it.name.lowercase() }}",
                )
        }
    }
}

/** Restart-only storage-backend configuration. Secrets are supplied only by environment variables. */
data class StorageConfig(
    val backend: StorageBackend = StorageBackend.LOCAL,
    val endpoint: String? = null,
    val bucket: String? = null,
    val region: String = PlainbaseConfig.DEFAULT_S3_REGION,
    val prefix: String = "",
    val pathStyle: Boolean = true,
    val pollSeconds: Long = PlainbaseConfig.DEFAULT_S3_POLL_SECONDS,
    val accessKeyId: String? = null,
    val secretAccessKey: String? = null,
    val ignoredObjectKeys: List<String> = emptyList(),
)
