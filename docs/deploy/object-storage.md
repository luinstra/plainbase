# Object-storage backend (`storage.backend=object`)

Plainbase can serve an S3-compatible **bucket** as its authoritative content store instead of a local
directory. The bucket is the canonical tree (a plain set of objects any S3 tool can read); a local
`DATA_DIR/mirror` is a derived, deletable cache the server hydrates from the bucket at boot and keeps
current with a background poll. **Cloudflare R2 is the primary, first-class target**; AWS S3 (and other
S3-compatible stores) work as a compatibility variant. See also
[ADR-0010](../decisions/0010-object-storage-backend.md) for the design record and
[operating-plainbase.md](../operating-plainbase.md#backups) for backups and disaster-recovery drills.

## R2 walkthrough (the primary path)

1. Create an R2 bucket in the Cloudflare dashboard.
2. Mint an **R2 API token** scoped to that one bucket, with **Object Read & Write** permission (why
   write, not just read, is [Least-privilege IAM](#least-privilege-iam-stated-honestly) below).
3. Point Plainbase at it, keys via **env only** (never `plainbase.conf`, which is not a place for a
   secret):

   ```sh
   export PLAINBASE_STORAGE_BACKEND=object
   export PLAINBASE_S3_ENDPOINT=https://<account-id>.r2.cloudflarestorage.com
   export PLAINBASE_S3_BUCKET=<your-bucket>
   export PLAINBASE_S3_ACCESS_KEY_ID=<r2-access-key-id>
   export PLAINBASE_S3_SECRET_ACCESS_KEY=<r2-secret-access-key>
   # Defaults already orient to R2: PLAINBASE_S3_REGION=auto, PLAINBASE_S3_PATH_STYLE=true.
   # Optional: PLAINBASE_S3_PREFIX=docs   (serve a subtree; empty = bucket root)
   ./plainbase serve
   ```

The first boot LISTs the bucket, hydrates the mirror, then serves. `CONTENT_DIR` is ignored in object
mode (an explicitly set `CONTENT_DIR` warns). The full key reference is in
[configuration.md](../configuration.md#reference-table) (the `PLAINBASE_S3_*` rows).

## S3 compatibility variant

AWS S3 (or MinIO, Wasabi, etc.) differs only in the endpoint/region/path-style knobs, same keys:

```sh
export PLAINBASE_S3_ENDPOINT=https://s3.<region>.amazonaws.com
export PLAINBASE_S3_REGION=<region>          # a real AWS region, not `auto`
export PLAINBASE_S3_PATH_STYLE=false         # AWS uses virtual-hosted-style addressing
```

The small wire divergences between providers (conditional-write status codes, LIST key encoding, ETag
forms) are absorbed by the adapter, not something the operator configures - see the provider-semantics
table in [ADR-0010](../decisions/0010-object-storage-backend.md). One provider column (AWS S3) is still
recorded PENDING there until a credentialed AWS run is done; R2 is fully characterized.

## One instance per bucket (the v1 rule)

**Run exactly one Plainbase instance against a bucket.** The single-writer posture is enforced locally
by the `DataDirLock` on `DATA_DIR`, and by this deployment rule at the bucket. Concretely, if a second
instance writes to the same bucket:

- **Content stays lost-update-safe** - writes use conditional PUTs, so a racing write is refused, never
  silently clobbered.
- **But the loser's local mirror/etag map goes incoherent** until it re-hydrates, and both instances
  race the single `.plainbase/history.bundle` key, so out-of-order bundle PUTs can regress the DR
  artifact. The blast radius is instance-local staleness, never content loss.

This doc rule plus `DataDirLock` **is** the v1 exclusion posture (owner-locked). A bucket-lease
heartbeat that would make multi-instance safe is explicitly deferred to a future v0.x, not scoped here.

**Scale-to-zero / serverless is a configuration trap.** A native binary makes it easy to fan out N
serverless instances against one bucket; serverless concurrency greater than 1 against a single bucket
is a **configuration error**. One-instance-per-bucket binds every deploy shape, including scale-to-zero
and serverless-fanout.

## Least-privilege IAM, stated honestly

The object-mode boot's first bucket LIST doubles as a fail-closed self-check: it proves **TLS + SigV4 +
credentials + READ**. It does **not** prove write permission. So:

- **Read-only credentials pass boot**, then every save's PUT gets a 403 that maps to the frozen
  retryable 503 - forever. This never fail-closes at boot; "boot succeeded" is not "writes proven."
- Grant PUT alongside GET/LIST. This is deliberately NOT fixed by a per-boot PUT probe, which would
  write to the bucket on every boot.

Concrete grants:

- **R2:** an API token with **Object Read & Write**, scoped to the one bucket.
- **AWS S3:** `s3:GetObject`, `s3:PutObject`, `s3:ListBucket` on the bucket/prefix.

`s3:DeleteObject` is **not** needed on the content bucket - the server never deletes bucket objects on
the serving path (its only delete is a local mirror file). Bucket DELETE is exercised only by
`plainbase s3-smoke`'s scratch-bucket cleanup, so grant `s3:DeleteObject` only on a throwaway smoke
bucket, never on the content bucket.

## Migrating a local tree into a bucket

1. Copy the existing tree into the bucket with any S3 tool:
   `rclone copy ./content <remote>:<bucket>/<prefix>` or
   `aws s3 sync ./content s3://<bucket>/<prefix>/ --endpoint-url <endpoint>`.
2. To **carry git history**, seed `DATA_DIR/mirror/.git` from the old repo before the first object-mode
   boot: the restore gate detects a complete pre-seeded repo (`GitState.COMPLETE`, no restore sentinel)
   and takes the ordinary warm path, no bundle restore. Or start clean and let history begin fresh.

**Efficiency caveat:** seeding `.git` gets you the warm git path, but a fresh/empty `mirror-state` means
the first boot's `hydrate()` still re-fetches EVERY bucket key once (correctness is fine - the bucket is
authoritative and the overwrite is byte-identical). A large corpus therefore sees a full first-boot
re-download; every subsequent boot is etag-diff cheap.

## Native binary base-image requirements

The object-store client validates TLS against the **platform trust material**, so an edge / distroless /
alpine base image **must carry a CA bundle** (e.g. `ca-certificates`). Without it the boot self-check
fail-closes with an actionable TLS refusal - that refusal is correct behavior, not a bug (see the
operator-signals section of [operating-plainbase.md](../operating-plainbase.md#operator-signals-object-mode)).
Never disable certificate validation to work around it; install the CA bundle or fix the endpoint.

**Memory floor:** size the container for the native binary's serving RSS plus headroom for the hydrated
mirror. Measured 2026-07-29 during the pre-release drill session: **~120 MiB serving RSS** (native
binary, macOS Apple Silicon, 1000-page corpus hydrated from Cloudflare R2, steady state after reads).
The BOOT peak sits above that: cold hydrate buffers each fetch chunk in memory, packed to a 64 MiB
DECLARED-size budget per chunk (a chunk holds multiple bodies up to that budget, plus one body that
may individually exceed it). With a provider that declares sizes honestly, budget roughly serving RSS
plus 64 MiB plus your largest object for the boot window. The declared sizes are advisory: a provider
that OMITS them is still bounded at 64 bodies per chunk, while one that misdeclares them weakens the
packing bound toward the hard ceiling of 256 bodies at the response cap, which only a hostile or
broken provider approaches. Numbers vary by platform and corpus;
re-measure on your own hardware when sizing tightly (see the
[pre-release checklist](../DEVELOPMENT.md#pre-release-checklist)).

## Platform support, honestly

There is **no in-binary platform gate**: every native binary attempts object mode and fail-closes at
boot on a TLS/signature rejection (the R16 guard). "Supported" here means a platform with a RECORDED
green credentialed `plainbase s3-smoke` from the native binary, cert validation on. Current record:

| Platform | Status |
|---|---|
| macos-arm64 | **PROVEN** 2026-07-06 and re-proven 2026-07-29 - real R2, full TLS handshake + signed round-trip, cert validation on (macOS trust store). Both records are true: a header-emission regression landed between them (`063018c`, 2026-07-09) and the 07-29 run - the first credentialed smoke since - caught and fixed it, so the re-proof is load-bearing, not ceremonial. |
| linux-x64 | TLS + SigV4 **proven credential-free in CI** (the `plainbase spike` 9/9 self-signed-loopback check runs on every PR). The real system-CA-trust-against-R2-under-Linux leg is a documented nice-to-have (owner-deferred 2026-07-07), NOT release-gating. |
| linux-arm64 | Docs-only until a green native s3-smoke is recorded. |
| windows-x64 | Docs-only until a green native s3-smoke is recorded. |

Update this table only from a recorded green `s3-smoke` run (per the
[pre-release checklist](../DEVELOPMENT.md#pre-release-checklist)); it is a docs record, not a code gate.

**The container tier (universal JAR / `docker compose`) is the documented fallback** and the home of the
escape hatch: if a platform's native TLS ever regresses, run the JAR under a JVM (whose TLS/crypto is
mature) rather than branching the artifact, and an SDK-backed `ObjectStoreClient` swap remains available
behind the SPI. There is never a second, object-mode-only build.

## TLS posture

This page is about **outbound** bucket TLS (Plainbase to the S3 endpoint). **Inbound** serving TLS is
unchanged: Plainbase serves plain HTTP behind a reverse proxy that terminates TLS, per
[ADR-0008](../decisions/0008-tls-terminates-at-an-external-reverse-proxy.md).

## See also

- [Configuration](../configuration.md#reference-table) - the `PLAINBASE_S3_*` keys and defaults.
- [Operating Plainbase: backups](../operating-plainbase.md#backups) - per-backend backups and DR drills.
- [ADR-0010](../decisions/0010-object-storage-backend.md) - the design decision and frozen contract.
- [Development: pre-release checklist](../DEVELOPMENT.md#pre-release-checklist) - the credentialed
  smoke, budget drill, and DR-drill obligations before a release.
