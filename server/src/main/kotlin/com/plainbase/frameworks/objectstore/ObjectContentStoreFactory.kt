package com.plainbase.frameworks.objectstore

import com.plainbase.domain.content.TreePath
import com.plainbase.domain.root.BindingEpoch
import com.plainbase.domain.root.RootBinding
import com.plainbase.domain.root.RowsAtStart
import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.filesystem.IgnoreRules
import com.plainbase.frameworks.filesystem.LocalContentStore

/**
 * The ONE construction recipe for the object-backend hybrid, shared by `createContentModule` and the
 * offline CLIs (`adopt`/`reindex`) so the three build it one way, never three: the SigV4 client from
 * `storage.object.*`, the inner mirror over `DATA_DIR/mirror` (app-owned derived state, Q10), and
 * the etag map at `DATA_DIR/mirror-state` (M1).
 *
 * Callers construct this ONLY on the object path (Koin laziness / the CLI backend switch), so a
 * local boot never runs it (R9).
 */
@Suppress("TooGenericExceptionCaught")
object ObjectContentStoreFactory {

    fun build(
        config: PlainbaseConfig,
        ignoreRules: IgnoreRules,
        dirtyPaths: () -> Set<TreePath>,
        // MINOR-1: an indexed per-path dirty check for the poll hot-path guard; defaults to membership in
        // [dirtyPaths] so a caller that has no cheaper query (a CLI over a tiny journal) need not wire one.
        isDirty: (TreePath) -> Boolean = { it in dirtyPaths() },
        // C3: the pagination boundary, read fresh before each LIST - the root's durable bindings AND its binding_epoch,
        // co-read (revoke-before-stamp, C5). Defaulted to NONE - a store built with no durable index behind it publishes
        // generations that cover nothing, so it can prove nothing gone. That is the right authority for the offline
        // CLIs, which reap on nobody's inference (they wire no proof source at all).
        rowsAtStart: () -> RowsAtStart = { RowsAtStart(emptySet(), BindingEpoch(0)) },
    ): ObjectContentStore = buildWithClient(
        config = config,
        ignoreRules = ignoreRules,
        dirtyPaths = dirtyPaths,
        isDirty = isDirty,
        rowsAtStart = rowsAtStart,
        clientFactory = ::S3ObjectClient,
    )

    /** Acquires the client before the mirror/store boundary so partial assembly can close it locally. */
    internal fun buildWithClient(
        config: PlainbaseConfig,
        ignoreRules: IgnoreRules,
        dirtyPaths: () -> Set<TreePath>,
        isDirty: (TreePath) -> Boolean,
        rowsAtStart: () -> RowsAtStart,
        clientFactory: (S3ClientConfig) -> ObjectStoreClient,
        storeFactory: (ObjectStoreClient) -> ObjectContentStore = {
            buildStore(it, config, ignoreRules, dirtyPaths, isDirty, rowsAtStart)
        },
    ): ObjectContentStore {
        val storage = config.storage
        val client = clientFactory(
            S3ClientConfig(
                endpoint = requireNotNull(storage.endpoint) { "storage.object.endpoint is required when storage.backend=object" },
                region = storage.region,
                bucket = requireNotNull(storage.bucket) { "storage.object.bucket is required when storage.backend=object" },
                accessKeyId = requireNotNull(storage.accessKeyId) { "PLAINBASE_S3_ACCESS_KEY_ID is required when storage.backend=object" },
                secretAccessKey = requireNotNull(storage.secretAccessKey) {
                    "PLAINBASE_S3_SECRET_ACCESS_KEY is required when storage.backend=object"
                },
                addressing = if (storage.pathStyle) S3Addressing.PATH_STYLE else S3Addressing.VIRTUAL_HOST,
                maxResponseBytes = S3ObjectClient.deriveMaxResponseBytes(config.maxAssetBytes),
            ),
        )
        return assembleWithClient(client) {
            storeFactory(client)
        }
    }

    /** The default mirror/store recipe; partial client ownership remains with [buildWithClient] until it returns. */
    internal fun buildStore(
        client: ObjectStoreClient,
        config: PlainbaseConfig,
        ignoreRules: IgnoreRules,
        dirtyPaths: () -> Set<TreePath>,
        isDirty: (TreePath) -> Boolean,
        rowsAtStart: () -> RowsAtStart,
    ): ObjectContentStore {
        val storage = config.storage
        val mirrorRoot = config.dataDir.resolve("mirror")
        return ObjectContentStore(
            client = client,
            mirror = LocalContentStore(root = mirrorRoot, ignoreRules = ignoreRules),
            state = MirrorState(config.dataDir.resolve("mirror-state")),
            binding = bindingOf(config),
            rowsAtStart = rowsAtStart,
            keyPrefix = if (storage.prefix.isEmpty()) "" else "${storage.prefix}/",
            pollSeconds = storage.pollSeconds,
            dirtyPaths = dirtyPaths,
            isDirty = isDirty,
            mirrorRoot = mirrorRoot,
            ignoreRules = ignoreRules,
        )
    }

    /** Typed seam for the client-to-store boundary: a failed store assembly still closes the acquired client. */
    internal fun <T> assembleWithClient(client: ObjectStoreClient, build: () -> T): T =
        try {
            build()
        } catch (failure: Throwable) {
            runCatching { client.close() }.onFailure { cleanup ->
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }

    /**
     * **WHICH BUCKET this configuration names** (C3): all three of endpoint, bucket and prefix, because changing ANY
     * of them points the root at a different universe. Derived HERE, in the one place that builds the client from
     * them, so the string the latch compares and the bucket the client talks to cannot drift apart.
     *
     * Residual, documented rather than pretended away: the same config string can be made to point at a different
     * backend (a DNS or gateway swap). No string can see that.
     */
    fun bindingOf(config: PlainbaseConfig): RootBinding =
        RootBinding("${config.storage.endpoint}|${config.storage.bucket}|${config.storage.prefix}")
}
