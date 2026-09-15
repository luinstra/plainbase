package com.plainbase.frameworks.config

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.page.PageId
import com.plainbase.domain.root.HistoryMode
import com.plainbase.domain.root.ReservedSegments
import com.plainbase.domain.root.Root
import com.plainbase.domain.root.RootBackend
import com.plainbase.domain.root.RootName
import com.plainbase.domain.service.CommitGlob
import com.plainbase.frameworks.net.RemoteAddress
import com.typesafe.config.Config
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigValue
import java.nio.file.Path

/** Owns typed HOCON decoding and validation, without filesystem observation or application wiring. */
internal object ConfigDecoder {
    private val objectStorageKeys: List<Pair<String, String>> = listOf(
        "PLAINBASE_S3_ENDPOINT" to "storage.object.endpoint",
        "PLAINBASE_S3_BUCKET" to "storage.object.bucket",
        "PLAINBASE_S3_REGION" to "storage.object.region",
        "PLAINBASE_S3_PREFIX" to "storage.object.prefix",
        "PLAINBASE_S3_PATH_STYLE" to "storage.object.pathStyle",
        "PLAINBASE_S3_POLL_SECONDS" to "storage.object.pollSeconds",
    )

    fun decode(env: Map<String, String>, sources: ConfigSources): PlainbaseConfig {
        val file = sources.operator
        val managed = sources.managed
        val contentDirEnv = env["CONTENT_DIR"]
        val contentDirFile = file.stringOrNull("contentDir")
        val insecureHttp = env.boolStrict("PLAINBASE_INSECURE_HTTP") ?: file.boolStrict("auth.insecureHttp") ?: false
        val contentDir = Path.of(contentDirEnv ?: contentDirFile ?: "./content").toAbsolutePath().normalize()
        val storage = buildStorage(env, file, insecureHttp)
        val roots = RootsConfigParser.parse(file, managed, contentDir, storage)
        RootsConfigParser.requireCoherentMainHistory(
            roots,
            env.boolStrict("PLAINBASE_GIT_ENABLED") ?: file.boolStrict("git.enabled"),
        )
        return PlainbaseConfig(
            contentDir = contentDir,
            contentDirSource = contentDirSource(contentDirEnv, contentDirFile),
            storage = storage,
            roots = roots,
            dataDir = ConfigValuePolicy.dataDirFrom(env),
            host = env["PLAINBASE_HOST"] ?: file.stringOrNull("host") ?: PlainbaseConfig.DEFAULT_HOST,
            port = env.longStrict("PLAINBASE_PORT")?.toIntInRange("PLAINBASE_PORT")
                ?: file.intOrNull("port") ?: PlainbaseConfig.DEFAULT_PORT,
            maxWriteBodyBytes = positiveSize(
                env,
                file,
                "PLAINBASE_MAX_WRITE_BODY_BYTES",
                "maxWriteBodyBytes",
                PlainbaseConfig.DEFAULT_MAX_WRITE_BODY_BYTES,
            ),
            maxAssetBytes = positiveSize(
                env,
                file,
                "PLAINBASE_MAX_ASSET_BYTES",
                "maxAssetBytes",
                PlainbaseConfig.DEFAULT_MAX_ASSET_BYTES,
            ),
            git = buildGit(env, file),
            auth = buildAuth(env, file, insecureHttp, roots),
        )
    }

    private fun contentDirSource(envValue: String?, fileValue: String?): ConfigSource =
        when {
            envValue != null -> ConfigSource.ENV
            fileValue != null -> ConfigSource.FILE
            else -> ConfigSource.DEFAULT
        }

    private fun positiveSize(
        env: Map<String, String>,
        file: Config,
        envKey: String,
        filePath: String,
        default: Long,
    ): Long =
        env.positiveLongStrict(envKey)
            ?: file.longOrNull(filePath)?.takeIf { it > 0 }
            ?: default

    private fun buildGit(env: Map<String, String>, file: Config): GitConfig =
        GitConfig(
            enabled = env.boolStrict("PLAINBASE_GIT_ENABLED") ?: file.boolStrict("git.enabled"),
            authorName = env["PLAINBASE_GIT_AUTHOR_NAME"] ?: file.stringOrNull("git.authorName")
                ?: PlainbaseConfig.DEFAULT_GIT_AUTHOR_NAME,
            authorEmail = env["PLAINBASE_GIT_AUTHOR_EMAIL"] ?: file.stringOrNull("git.authorEmail")
                ?: PlainbaseConfig.DEFAULT_GIT_AUTHOR_EMAIL,
        )

    private fun buildAuth(
        env: Map<String, String>,
        file: Config,
        insecureHttp: Boolean,
        roots: RootsConfig,
    ): AuthConfig =
        AuthConfig(
            mode = AuthMode.parse(env["PLAINBASE_AUTH_MODE"] ?: file.stringOrNull("auth.mode")),
            trustedProxyCidrs = requireParseableCidrs(
                env["PLAINBASE_TRUSTED_PROXY"]?.toCommaList()
                    ?: file.stringListOrNull("auth.trustedProxy")
                    ?: emptyList(),
            ),
            insecureHttp = insecureHttp,
            agentDirectCommitGlobs = requireParseableGlobs(mainDirectCommitGlobs(env, file)),
            agentDirectCommitGlobsByRoot = buildDirectCommitGlobsByRoot(file, roots),
            proxySecret = env["PLAINBASE_PROXY_SECRET"] ?: file.stringOrNull("auth.proxySecret"),
            proxyIdentityHeader = (
                env["PLAINBASE_PROXY_IDENTITY_HEADER"]
                    ?: file.stringOrNull("auth.proxyIdentityHeader")
            )?.trim()?.takeIf { it.isNotEmpty() } ?: PlainbaseConfig.DEFAULT_PROXY_IDENTITY_HEADER,
            mcpAllowedHosts = env["PLAINBASE_MCP_ALLOWED_HOSTS"]?.toCommaList()
                ?: file.stringListOrNull("auth.mcpAllowedHosts")
                ?: emptyList(),
            mcpAllowedOrigins = env["PLAINBASE_MCP_ALLOWED_ORIGINS"]?.toCommaList()
                ?: file.stringListOrNull("auth.mcpAllowedOrigins")
                ?: emptyList(),
        )

    private fun buildStorage(env: Map<String, String>, file: Config, insecureHttp: Boolean): StorageConfig {
        val backend = StorageBackend.parse(env["PLAINBASE_STORAGE_BACKEND"] ?: file.stringOrNull("storage.backend"))
        if (backend == StorageBackend.LOCAL) {
            val ignored = objectStorageKeys.mapNotNull { (envKey, filePath) ->
                if (env[envKey] != null) envKey else filePath.takeIf { file.hasPath(it) }
            }
            return StorageConfig(backend = backend, ignoredObjectKeys = ignored)
        }
        val endpoint = env["PLAINBASE_S3_ENDPOINT"] ?: file.stringOrNull("storage.object.endpoint")
            ?: throw IllegalArgumentException(
                "storage.object.endpoint is required when storage.backend=object (the R2/S3 endpoint URL)",
            )
        require(ConfigValuePolicy.isAbsoluteHttpUrl(endpoint)) {
            "storage.object.endpoint is not an absolute http(s) URL: '$endpoint'"
        }
        require(insecureHttp || ConfigValuePolicy.isHttpsUrl(endpoint)) {
            "storage.object.endpoint must be https to protect S3 credentials in transit: '$endpoint' " +
                "(set PLAINBASE_INSECURE_HTTP=1 to knowingly send credentials over plaintext)"
        }
        val bucket = (env["PLAINBASE_S3_BUCKET"] ?: file.stringOrNull("storage.object.bucket"))
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("storage.object.bucket is required when storage.backend=object")
        val accessKeyId = env["PLAINBASE_S3_ACCESS_KEY_ID"]?.takeIf { it.isNotBlank() }
        val secretAccessKey = env["PLAINBASE_S3_SECRET_ACCESS_KEY"]?.takeIf { it.isNotBlank() }
        if (accessKeyId == null || secretAccessKey == null) throw IllegalArgumentException(MISSING_S3_CREDENTIALS_MESSAGE)
        val prefix = env["PLAINBASE_S3_PREFIX"] ?: file.stringOrNull("storage.object.prefix") ?: ""
        if (prefix.isNotEmpty()) requireTreePathPrefix(prefix)
        return StorageConfig(
            backend = backend,
            endpoint = endpoint,
            bucket = bucket,
            region = env["PLAINBASE_S3_REGION"] ?: file.stringOrNull("storage.object.region")
                ?: PlainbaseConfig.DEFAULT_S3_REGION,
            prefix = prefix,
            pathStyle = env.boolStrict("PLAINBASE_S3_PATH_STYLE")
                ?: file.boolStrict("storage.object.pathStyle") ?: true,
            pollSeconds = env.positiveLongStrict("PLAINBASE_S3_POLL_SECONDS")
                ?: file.longOrNull("storage.object.pollSeconds")?.takeIf { it > 0 }
                ?: PlainbaseConfig.DEFAULT_S3_POLL_SECONDS,
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
        )
    }

    private fun requireTreePathPrefix(prefix: String) {
        requireNotNull(TreePath.of(prefix)) {
            "storage.object.prefix is not a valid key prefix: '$prefix' (a relative /-joined path, no . or .. segments)"
        }
    }

    private fun requireParseableCidrs(cidrs: List<String>): List<String> {
        cidrs.firstOrNull { !RemoteAddress.isParseableCidr(it) }?.let {
            throw IllegalArgumentException(
                "PLAINBASE_TRUSTED_PROXY contains an unparseable CIDR: '$it' (expected a.b.c.d/n or IPv6/n)",
            )
        }
        return cidrs
    }

    private fun requireParseableGlobs(globs: List<String>): List<String> {
        globs.forEach { CommitGlob.parse(it) }
        return globs
    }

    private fun mainDirectCommitGlobs(env: Map<String, String>, file: Config): List<String> {
        val fromEnv = env["PLAINBASE_AGENT_DIRECT_COMMIT_GLOBS"]?.toCommaList()
        val fromFile = file.stringListOrNull("auth.agentDirectCommit.globs")
        val fromBlock = file.stringListOrNull("auth.agentDirectCommit.roots.${RootName.PRIMARY}")
        if (fromBlock != null) {
            require(fromEnv == null) {
                "auth.agentDirectCommit.roots.${RootName.PRIMARY} and PLAINBASE_AGENT_DIRECT_COMMIT_GLOBS both declare " +
                    "the primary root's direct-commit globs. Declare the primary root's list ONCE - Plainbase " +
                    "will not guess which of the two you meant, and neither unioning them (which would widen what an " +
                    "agent may commit unreviewed) nor picking a winner (which would drop the other) is safe on an " +
                    "authorization surface."
            }
            require(fromFile == null) {
                "auth.agentDirectCommit.globs and auth.agentDirectCommit.roots.${RootName.PRIMARY} both declare the primary " +
                    "root's direct-commit globs. Declare the primary root's list ONCE (see the note on the roots block)."
            }
            return fromBlock
        }
        return fromEnv ?: fromFile ?: emptyList()
    }

    private fun buildDirectCommitGlobsByRoot(file: Config, roots: RootsConfig): Map<RootName, List<String>> {
        if (!file.hasPath("auth.agentDirectCommit.roots")) return emptyMap()
        val registered = roots.list.map { it.name }.toSet()
        return file.getObject("auth.agentDirectCommit.roots").keys
            .mapNotNull { key ->
                val name = RootName.of(key) ?: throw IllegalArgumentException(
                    "auth.agentDirectCommit.roots.$key is not a valid root name " +
                        "(a lowercase slug [a-z][a-z0-9]*(-[a-z0-9]+)*, 2-32 chars)",
                )
                require(name in registered) {
                    "auth.agentDirectCommit.roots.$key names no configured root (declared roots: " +
                        "${registered.joinToString(", ") { it.value }}). A direct-commit glob for a root that does not " +
                        "exist authorizes nothing - fix the name, or remove the entry."
                }
                if (name == RootName.PRIMARY) return@mapNotNull null
                name to requireParseableGlobs(file.stringListOrNull("auth.agentDirectCommit.roots.$key").orEmpty())
            }
            .toMap()
    }

    private fun Map<String, String>.longStrict(key: String): Long? {
        val raw = this[key] ?: return null
        return raw.trim().toLongOrNull()
            ?: throw IllegalArgumentException("$key must be an integer, got '$raw'")
    }

    private fun Map<String, String>.positiveLongStrict(key: String): Long? {
        val value = longStrict(key) ?: return null
        require(value > 0) { "$key must be a positive integer, got '$value'" }
        return value
    }

    private fun Long.toIntInRange(key: String): Int {
        require(this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$key out of range, got '$this'" }
        return toInt()
    }
}

/** Parses one file's typed roots and keeps that parser private to the decoder. */
private object RootsConfigParser {
    fun parse(file: Config, managed: Config, contentDir: Path, storage: StorageConfig): RootsConfig {
        val declaredPresent = file.hasPath("roots")
        val declared = parseRootBlock(file)
        val managedRoots = parseRootBlock(managed)
        if (!declaredPresent && managedRoots.isEmpty()) return RootsConfig.synthesized(contentDir, storage)
        require(storage.backend != StorageBackend.OBJECT) {
            "roots {} cannot be combined with storage.backend=object in this release: the bucket is the primary root's content " +
                "authority and a roots block cannot describe it - remove the roots block to keep the object deployment"
        }
        if (declaredPresent) {
            require(declared.any { it.name == RootName.PRIMARY }) {
                "roots {} must declare a root named '${RootName.PRIMARY}' (the required, reserved primary): " +
                    "roots.docs { path = ... }"
            }
        }
        require(managedRoots.none { it.name == RootName.PRIMARY }) {
            "${PlainbaseConfig.MANAGED_ROOTS_FILE} must not declare 'docs': primary's directory comes from CONTENT_DIR, or " +
                "from a roots {} " +
                "block you wrote yourself in plainbase.conf. `plainbase root` never manages docs."
        }
        val overlap = declared.map { it.name }.intersect(managedRoots.map { it.name }.toSet())
        require(overlap.isEmpty()) {
            "root(s) ${overlap.joinToString(", ") { it.value }} are declared BOTH in plainbase.conf and in " +
                "${PlainbaseConfig.MANAGED_ROOTS_FILE}. Declare each root ONCE - Plainbase will not guess which declaration " +
                "you meant, and " +
                "merging them field-wise could silently take `editable` from one file and `path` from the other. Remove " +
                "the duplicate from plainbase.conf, or run `plainbase root remove <name>`."
        }
        val fromDeclaredFile = if (declaredPresent) {
            declared
        } else {
            listOf(RootsConfig.synthesized(contentDir, storage).primary)
        }
        return RootsConfig.of(
            list = fromDeclaredFile + managedRoots,
            origin = RootsOrigin.EXPLICIT,
            primaryDeclared = declaredPresent,
            managed = managedRoots.map { it.name }.toSet(),
        )
    }

    fun requireCoherentMainHistory(roots: RootsConfig, gitEnabled: Boolean?) {
        val primary = roots.primary
        require(!(primary.history == HistoryMode.NATIVE && gitEnabled == false)) {
            "roots.docs.history = native and git.enabled = false contradict each other: one claims primary's git " +
                "repository, the other turns git off. Set exactly one of them."
        }
        require(!(primary.history == HistoryMode.OFF && gitEnabled == true)) {
            "roots.docs.history = off and git.enabled = true contradict each other: one turns primary's history off, " +
                "the other forces it on. Set exactly one of them."
        }
    }

    private fun parseRootBlock(file: Config): List<Root> =
        if (!file.hasPath("roots")) {
            emptyList()
        } else {
            file.getObject("roots").entries
                .sortedWith(compareBy({ it.value.origin().lineNumber() }, { it.key }))
                .map { (key, value) -> parseRoot(key, value) }
        }

    private fun parseRoot(key: String, value: ConfigValue): Root {
        if (PageId.of(key) != null) {
            throw IllegalArgumentException(
                "roots.$key: a root name may not look like a page id (a 32-hex or UUID string) - " +
                    "it would be ambiguous with a bare /p/{id} permalink. Rename this root.",
            )
        }
        val name = RootName.of(key) ?: throw IllegalArgumentException(
            "roots.$key is not a valid root name (a lowercase slug [a-z][a-z0-9]*(-[a-z0-9]+)*, 2-32 chars)",
        )
        if (ReservedSegments.isReserved(name)) {
            throw IllegalArgumentException(
                "roots.$key: '$key' is a reserved segment - Plainbase owns that top-level URL, or expects to. " +
                    "Rename this root by editing the file that declares it (plainbase.conf, or DATA_DIR/roots.conf); " +
                    "plainbase root remove cannot run while the config is refused.",
            )
        }
        val entry = (value as? ConfigObject)?.toConfig()
            ?: throw IllegalArgumentException("roots.$key must be a block: roots.$key { path = ... }")
        val backend = entry.stringOrNull("backend")?.trim() ?: "local"
        require(backend.equals("local", ignoreCase = true)) {
            "Unknown roots.$key.backend '$backend' - the only legal value in this release is local " +
                "(object-backed roots are a recorded v1 scope cut)"
        }
        val raw = entry.stringOrNull("path")?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("roots.$key.path is required and must be a non-blank directory path")
        val isPrimary = name == RootName.PRIMARY
        val history = parseHistoryMode("roots.$key.history", entry.stringOrNull("history"))
            ?: if (isPrimary) HistoryMode.AUTO else HistoryMode.OFF
        require(isPrimary || history != HistoryMode.AUTO) {
            "roots.$key.history = auto is not allowed on an extra root: auto detects a repository and may create " +
                "one, which Plainbase will not do in a tree it does not own. Use `native` to claim an existing " +
                "repository at that path (Plainbase then refuses to start if it is a linked worktree, a submodule, " +
                "or somebody else's checkout), or `off` for no history."
        }
        return Root(
            name = name,
            backend = RootBackend.Local(Path.of(raw).toAbsolutePath().normalize()),
            editable = entry.boolStrict("editable", "roots.$key.editable") ?: isPrimary,
            history = history,
        )
    }

    private fun parseHistoryMode(key: String, raw: String?): HistoryMode? {
        val token = raw?.trim()
        if (token.isNullOrEmpty()) return null
        return HistoryMode.entries.firstOrNull { it.name.equals(token, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "Unknown $key '$token' - legal values: ${HistoryMode.entries.joinToString(", ") { it.name.lowercase() }}",
            )
    }
}

private fun String.toCommaList(): List<String> = split(',').map { it.trim() }.filter { it.isNotEmpty() }

private fun Config.stringOrNull(path: String): String? = if (hasPath(path)) getString(path) else null

private fun Config.intOrNull(path: String): Int? = if (hasPath(path)) getInt(path) else null

private fun Config.longOrNull(path: String): Long? = if (hasPath(path)) getLong(path) else null

private fun Config.stringListOrNull(path: String): List<String>? = if (hasPath(path)) getStringList(path) else null

private fun Config.boolStrict(path: String, label: String = path): Boolean? {
    val raw = stringOrNull(path) ?: return null
    return when (raw.trim().lowercase()) {
        "1", "true" -> true
        "0", "false" -> false
        else -> throw IllegalArgumentException("$label must be one of 1/0/true/false, got '$raw'")
    }
}

private fun Map<String, String>.boolStrict(key: String): Boolean? {
    val raw = this[key] ?: return null
    return when (raw.trim().lowercase()) {
        "1", "true" -> true
        "0", "false" -> false
        else -> throw IllegalArgumentException("$key must be one of 1/0/true/false, got '$raw'")
    }
}
