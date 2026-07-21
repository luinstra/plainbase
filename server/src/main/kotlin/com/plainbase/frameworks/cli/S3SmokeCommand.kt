package com.plainbase.frameworks.cli

import com.plainbase.frameworks.config.PlainbaseConfig
import com.plainbase.frameworks.objectstore.FetchedObject
import com.plainbase.frameworks.objectstore.ListResponseParser
import com.plainbase.frameworks.objectstore.ObjectStat
import com.plainbase.frameworks.objectstore.PutCondition
import com.plainbase.frameworks.objectstore.PutOutcome
import com.plainbase.frameworks.objectstore.S3Addressing
import com.plainbase.frameworks.objectstore.S3ClientConfig
import com.plainbase.frameworks.objectstore.S3ObjectClient
import com.plainbase.frameworks.objectstore.S3WireKey
import com.plainbase.frameworks.objectstore.forEachListedObject
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.time.measureTime

/**
 * `plainbase s3-smoke` - the HIDDEN, credentialed arm of the C0 native-HTTPS gate (storage plan,
 * rev 3.2/3.3). Exercises all five ops plus both conditional-PUT forms against a REAL
 * S3-compatible endpoint (R2 primary, AWS S3 secondary), TLS certificate validation always ON (no
 * way to disable it). The endpoint MUST be https so SigV4 credentials are never sent in cleartext,
 * unless the SAME `PLAINBASE_INSECURE_HTTP` override the bind guard honors is set (a loopback test
 * proxy, say - never a new smoke-specific knob). It prints the provider's raw status codes
 * (409-vs-412, etag quoting) so a run maps 1:1 onto the findings note
 * (`.crew/plans/sp1-conditional-write-findings.md`), and can record raw ListObjectsV2 bodies as
 * the `ListResponseParser` goldens. Run it from the NATIVE binary on each release platform - the
 * JVM run proves nothing about baked trust material.
 *
 * **AWS space-encoding is an EXPLICIT pre-release gate (ADR-0010 SP1, PENDING AWS column).** Two things
 * catch a mismatch, both decode-dependent-yet-verifying: the `list-decode-get` probe decodes every LISTED
 * key through [S3WireKey] and GETs it back (a wrong decode 404s or drifts), and `cleanup` deletes the
 * decoded keys then RE-LISTS the prefix (raw, decode-independent) and FAILS the run on any survivor. This
 * matters because [S3WireKey] decodes on the R2-proven assumption that `encoding-type=url` emits `%20` for
 * a space and never `+`; AWS S3 is UNVERIFIED here and may emit `+`. If a real-AWS run makes either check
 * fail, `S3WireKey`/its goldens MUST be adjusted for the `+`-for-space case before the AWS column of the
 * SP1 table can go green (see docs/DEVELOPMENT.md pre-release checklist).
 *
 * Isolation (rev 3.3): every key lives under a unique per-run prefix (`smoke-<uuid>/`) inside a
 * DEDICATED scratch bucket (never a production/content bucket); every key the run created is
 * deleted on exit, and the scratch bucket should carry a short-expiry lifecycle rule as backstop
 * cleanup, so repeated manual runs can neither collide nor pollute.
 *
 * Config comes from env, never argv (credentials on a command line leak via process listings):
 *
 *   PLAINBASE_SMOKE_ENDPOINT            https endpoint (R2: https://<account>.r2.cloudflarestorage.com);
 *                                       http refused unless PLAINBASE_INSECURE_HTTP=1
 *   PLAINBASE_SMOKE_REGION              SigV4 region (R2: auto)
 *   PLAINBASE_SMOKE_BUCKET              the scratch bucket
 *   PLAINBASE_SMOKE_ACCESS_KEY_ID
 *   PLAINBASE_SMOKE_SECRET_ACCESS_KEY
 *   PLAINBASE_SMOKE_ADDRESSING          optional: path (default) | virtual-host
 *   PLAINBASE_SMOKE_CAPTURE_DIR         optional: directory for raw LIST XML captures (goldens)
 *   PLAINBASE_SMOKE_SOAK_GETS           optional: soak-arm GET count (default 100; 0 skips; must be >= 0)
 *
 * stdout is a CLI result contract: one line per
 * probe. Exit codes: 0 success / 1 probe or runtime failure / 2 usage. The soak arm is
 * NON-BLOCKING for v1 (plan C0): its result is printed and recorded but never changes the exit
 * code - unless the ops phase already failed.
 */
object S3SmokeCommand {

    fun runAsMain(args: List<String>, output: CommandOutput = systemCommandOutput()): Int =
        run(args, System.getenv(), output)

    fun run(
        args: List<String>,
        env: Map<String, String>,
        output: CommandOutput = systemCommandOutput(),
    ): Int {
        if (args.isNotEmpty()) {
            output.error(USAGE)
            return 2
        }
        val config = configFrom(env, output) ?: return 2
        val captureDir = env["PLAINBASE_SMOKE_CAPTURE_DIR"]?.let(Path::of)
        val soakGets = soakGetsFrom(env, output) ?: return 2
        val prefix = "smoke-${UUID.randomUUID()}/"
        output.result("s3-smoke: endpoint=${config.endpoint} bucket=${config.bucket} region=${config.region}")
        output.result(
            "s3-smoke: addressing=${config.addressing.name.lowercase()} prefix=$prefix runtime=${System.getProperty("java.vm.name")}",
        )

        return S3ObjectClient(config).use { client ->
            runBlocking {
                val opsPassed = runCatching { probes(client, prefix, captureDir, output) }
                    .onFailure { output.result("FAIL  ${it.chain()}") }
                    .isSuccess
                if (soakGets > 0) soak(client, prefix, soakGets, output)
                // Cleanup can FAIL the run: its re-LIST emptiness assert is a decode-independent survivor check
                // (a wrong LIST-key decode leaves keys behind), so a non-empty prefix after the delete loop is a
                // real gate failure, not a best-effort WARN. The lifecycle rule remains the backstop for LEAKED
                // keys, but a survivor here means the delete path is broken and must be surfaced.
                val cleanedUp = runCatching { cleanup(client, prefix) }
                    .onFailure { output.result("FAIL  cleanup left keys under $prefix (${it.chain()})") }
                    .isSuccess
                if (opsPassed && cleanedUp) {
                    output.result("s3-smoke OK - record the codes above in .crew/plans/sp1-conditional-write-findings.md")
                    0
                } else {
                    1
                }
            }
        }
    }

    private suspend fun probes(client: S3ObjectClient, prefix: String, captureDir: Path?, output: CommandOutput) {
        val key = "${prefix}page.md"
        val v1 = "# smoke v1\n".toByteArray()
        val v2 = "# smoke v2\n".toByteArray()
        val v3 = "# smoke v3\n".toByteArray()

        check(client.head(key) == null) { "HEAD of an absent key did not 404" }
        pass("head-missing", "HEAD absent key -> 404 (null)", output)

        val created = client.put(key, v1, PutCondition.IfAbsent, contentType = "text/markdown").stored("If-None-Match:* create")
        pass("create-if-absent", "PUT If-None-Match:* -> stored, etag=${created.etag}", output)

        val exists = client.put(key, v2, PutCondition.IfAbsent)
        pass("create-exists", "PUT If-None-Match:* on an EXISTING key -> ${exists.describeRefusal("second exclusive create")}", output)

        val fetched = client.get(key).orFail("GET after create")
        check(fetched.bytes.contentEquals(v1)) { "GET returned different bytes than the create wrote" }
        check(fetched.etag == created.etag) { "GET etag ${fetched.etag} != PUT etag ${created.etag}" }
        pass("get-roundtrip", "GET -> ${v1.size} bytes, etag matches the PUT response", output)

        val stat = client.head(key).orFail("HEAD after create")
        check(stat.etag == created.etag) { "HEAD etag ${stat.etag} != PUT etag ${created.etag}" }
        pass("head-etag", "HEAD -> etag matches, size=${stat.size}", output)

        val replaced = client.put(key, v2, PutCondition.IfMatch(created.etag)).stored("CAS replace with the current etag")
        pass("cas-replace", "PUT If-Match(current) -> stored, etag=${replaced.etag}", output)

        val stale = client.put(key, v3, PutCondition.IfMatch(created.etag))
        pass("cas-stale", "PUT If-Match(STALE) -> ${stale.describeRefusal("stale CAS")}", output)
        val afterStale = client.get(key).orFail("GET after refused CAS")
        check(afterStale.bytes.contentEquals(v2) && afterStale.etag == replaced.etag) { "a REFUSED CAS mutated the object" }
        pass("cas-stale-intact", "the refused CAS left bytes + etag untouched", output)

        val unconditional = client.put(key, v3).stored("unconditional PUT")
        pass("put-unconditional", "PUT (no condition) -> stored, etag=${unconditional.etag}", output)

        // Hostile key: space, unicode, '&', '$', '+' - feeds the encoding-type=url goldens.
        val hostileKey = "${prefix}dir/ünicode & \$pecial+key.md"
        client.put(hostileKey, v1, PutCondition.IfAbsent).stored("hostile-key create")
        check(client.get(hostileKey).orFail("hostile-key GET").bytes.contentEquals(v1)) { "hostile-key GET bytes drifted" }
        pass("hostile-key", "PUT+GET round-trip of a space/unicode/&/$/+ key", output)

        client.put("${prefix}b.md", v1, PutCondition.IfAbsent).stored("pagination seed b")
        client.put("${prefix}c.md", v1, PutCondition.IfAbsent).stored("pagination seed c")
        // This probe deliberately does NOT use the shared [forEachListedObject] helper: it captures the
        // RAW ListObjectsV2 bodies (the `ListResponseParser` goldens) over `listRaw`, and forces
        // maxKeys=2 to EXERCISE multi-page pagination on a 4-key corpus - a raw-capture concern the
        // parsed-entry helper cannot serve (it neither exposes raw bodies nor forces a small page size).
        captureDir?.let { Files.createDirectories(it) }
        val pages = buildList {
            var token: String? = null
            do {
                val raw = client.listRaw(prefix, continuationToken = token, maxKeys = 2)
                add(raw)
                // Persist each raw page AS FETCHED, BEFORE parsing it: a parse refusal mid-loop (the exact AWS
                // `+`-for-space failure this evidence exists to diagnose) must never lose the earlier captures.
                captureDir?.let { dir -> Files.writeString(dir.resolve("list-page-$size.xml"), raw) }
                val page = ListResponseParser.parse(raw)
                token = page.nextContinuationToken
            } while (page.isTruncated)
        }
        captureDir?.let { pass("captures", "wrote ${pages.size} raw LIST bodies to $it (record as ListResponseParser goldens)", output) }
        val listedKeys = pages.flatMap { ListResponseParser.parse(it).contents }.map { it.key }
        check(listedKeys.size == 4) { "LIST pagination saw ${listedKeys.size} keys, expected 4: $listedKeys" }
        pass("list-paginated", "LIST v2 max-keys=2 paginated ${pages.size} pages, 4 keys (raw): $listedKeys", output)

        // Close the LIST -> decode -> GET loop PER PROVIDER (C4 hydrates every listed key through S3WireKey,
        // so a wrong decode would hydrate wrong filenames or 404 follow-up GETs). This is the ONLY probe that
        // exercises the decode of a LISTED key back into a live GET - critical because AWS S3's
        // `encoding-type=url` MAY emit `+` for space where R2 emits `%20` (see the header note), and only a
        // real GET-back catches that. `page.md` last held v3 (the unconditional PUT); the rest hold v1.
        val expected = mapOf(key to v3, hostileKey to v1, "${prefix}b.md" to v1, "${prefix}c.md" to v1)
        val decoded = listedKeys.map { S3WireKey.decode(it) }
        check(decoded.toSet() == expected.keys) {
            "LIST-decoded keys $decoded != the written keys ${expected.keys} (space-encoding mismatch?)"
        }
        decoded.forEach { rawKey ->
            val got = client.get(rawKey).orFail("GET of LIST-decoded key '$rawKey'")
            check(got.bytes.contentEquals(expected.getValue(rawKey))) { "GET of LIST-decoded key '$rawKey' returned drifted bytes" }
        }
        pass("list-decode-get", "every LIST-decoded key GET round-tripped (closes LIST->decode->GET for this provider)", output)

        client.delete(key)
        check(client.head(key) == null) { "HEAD still finds the key after DELETE" }
        client.delete(key)
        pass("delete", "DELETE -> gone; second DELETE of the same key succeeded (idempotent)", output)
    }

    /** The NON-BLOCKING soak arm (plan C0): LIST + N sequential GETs on the ONE client instance. */
    private suspend fun soak(client: S3ObjectClient, prefix: String, gets: Int, output: CommandOutput) {
        val key = "${prefix}soak.md"
        val body = "# soak\n".toByteArray()
        val result = runCatching {
            client.put(key, body, PutCondition.IfAbsent)
            val elapsed = measureTime {
                client.list(prefix)
                repeat(gets) {
                    val got = client.get(key).orFail("soak GET #${it + 1}")
                    check(got.bytes.contentEquals(body)) { "soak GET #${it + 1} returned different bytes" }
                }
            }
            elapsed
        }
        result.fold(
            onSuccess = { output.result("soak: OK - LIST + $gets sequential GETs on one client in $it") },
            onFailure = { output.result("soak: FAILED (non-blocking for v1, record it: TLS instability = C0 FAIL) - ${it.chain()}") },
        )
    }

    /** Deletes every key the run created (everything under [prefix]), then RE-LISTS to prove the prefix is empty. */
    private suspend fun cleanup(client: S3ObjectClient, prefix: String) {
        // Keys come back URL-encoded (encoding-type=url) with the WHOLE key - `/` separators included -
        // percent-encoded (`smoke-<uuid>%2Fb.md`); DELETE takes the raw key. Decode via the shared
        // S3-wire helper (%2F -> '/', never '+' -> space, so the hostile-key probe's '+' survives). A
        // per-segment decodeOnce refuses %2F and would strand every scratch key. Pagination (token loop
        // + isTruncated termination) lives in [forEachListedObject], the one shared helper.
        client.forEachListedObject(prefix) { entry -> client.delete(S3WireKey.decode(entry.key)) }
        // Then RE-LIST the prefix: a re-LIST returns RAW keys and so is DECODE-INDEPENDENT - it genuinely
        // catches a wrong decode (a HEAD of the DECODED key would probe a key that never existed -> null ->
        // a false "no survivor"). S3ObjectClient.delete also treats a 404 as success, so a wrong decode
        // deletes nothing yet looks clean; only this raw re-LIST exposes the keys still standing.
        val survivors = buildList { client.forEachListedObject(prefix) { add(it.key) } }
        check(survivors.isEmpty()) { "keys survived delete (wrong LIST-key decode, or the provider ignored the delete): $survivors" }
    }

    private fun configFrom(env: Map<String, String>, output: CommandOutput): S3ClientConfig? {
        val required = listOf(
            "PLAINBASE_SMOKE_ENDPOINT",
            "PLAINBASE_SMOKE_REGION",
            "PLAINBASE_SMOKE_BUCKET",
            "PLAINBASE_SMOKE_ACCESS_KEY_ID",
            "PLAINBASE_SMOKE_SECRET_ACCESS_KEY",
        )
        val missing = required.filter { env[it].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            output.error(USAGE)
            output.error("missing env: ${missing.joinToString(", ")}")
            return null
        }
        // Same endpoint gate as the object-storage config: an absolute http(s) URL, and https UNLESS the
        // shared PLAINBASE_INSECURE_HTTP override is set - never send SigV4 credentials over cleartext on a typo.
        val endpoint = env.getValue("PLAINBASE_SMOKE_ENDPOINT")
        if (!PlainbaseConfig.isAbsoluteHttpUrl(endpoint)) {
            output.error("PLAINBASE_SMOKE_ENDPOINT is not an absolute http(s) URL: '$endpoint'")
            return null
        }
        if (!insecureHttpOverride(env) && !PlainbaseConfig.isHttpsUrl(endpoint)) {
            output.error(
                "PLAINBASE_SMOKE_ENDPOINT must be https to protect S3 credentials in transit: '$endpoint' " +
                    "(set PLAINBASE_INSECURE_HTTP=1 to knowingly send credentials over plaintext)",
            )
            return null
        }
        val addressing = when (val raw = env["PLAINBASE_SMOKE_ADDRESSING"] ?: "path") {
            "path" -> S3Addressing.PATH_STYLE
            "virtual-host" -> S3Addressing.VIRTUAL_HOST
            else -> {
                output.error("unknown PLAINBASE_SMOKE_ADDRESSING '$raw' - legal values: path | virtual-host")
                return null
            }
        }
        return S3ClientConfig(
            endpoint = endpoint,
            region = env.getValue("PLAINBASE_SMOKE_REGION"),
            bucket = env.getValue("PLAINBASE_SMOKE_BUCKET"),
            accessKeyId = env.getValue("PLAINBASE_SMOKE_ACCESS_KEY_ID"),
            secretAccessKey = env.getValue("PLAINBASE_SMOKE_SECRET_ACCESS_KEY"),
            addressing = addressing,
        )
    }

    /**
     * The soak-arm GET count: absent defaults to 100, present must be a NON-NEGATIVE integer (0 skips the
     * soak) or it is a usage error - the same strict-parse stance as the endpoint/addressing validation
     * above, never a silent coerce-to-100 or a negative that quietly skips.
     */
    private fun soakGetsFrom(env: Map<String, String>, output: CommandOutput): Int? {
        val raw = env["PLAINBASE_SMOKE_SOAK_GETS"] ?: return 100
        return raw.toIntOrNull()?.takeIf { it >= 0 } ?: run {
            output.error("PLAINBASE_SMOKE_SOAK_GETS must be a non-negative integer, got '$raw'")
            null
        }
    }

    /** The SAME PLAINBASE_INSECURE_HTTP override the bind guard honors (no smoke-specific knob); fail-safe to false. */
    private fun insecureHttpOverride(env: Map<String, String>): Boolean =
        env["PLAINBASE_INSECURE_HTTP"]?.trim()?.lowercase() in setOf("1", "true")

    private fun pass(name: String, detail: String, output: CommandOutput) =
        output.result("PASS  ${name.padEnd(22)} $detail")

    private fun PutOutcome.stored(what: String): PutOutcome.Stored =
        this as? PutOutcome.Stored ?: throw IllegalStateException("$what was refused: $this")

    /** A refusal is the EXPECTED outcome here; a Stored means the provider ignored the condition. */
    private fun PutOutcome.describeRefusal(what: String): String = when (this) {
        is PutOutcome.PreconditionFailed -> "refused with HTTP $status (record this code)"
        is PutOutcome.Stored -> throw IllegalStateException("$what was NOT refused - the provider ignored the precondition")
    }

    private fun FetchedObject?.orFail(what: String): FetchedObject = checkNotNull(this) { "$what found no object" }
    private fun ObjectStat?.orFail(what: String): ObjectStat = checkNotNull(this) { "$what found no object" }

    private fun Throwable.chain(): String = generateSequence(this) {
        it.cause
    }.joinToString(" <- ") { "${it::class.simpleName}: ${it.message}" }

    private val USAGE = "usage: plainbase s3-smoke  (config via PLAINBASE_SMOKE_* env - see S3SmokeCommand)"
}
