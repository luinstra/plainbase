# Public object reader (`PLAINBASE_PROFILE=reader`)

A reader serves public, read-only content from an S3-compatible bucket. It hydrates a local mirror and
builds its index before accepting requests. Reads are anonymous; editing, Git history and discussions
are disabled. The bucket stays authoritative, and the reader never writes to it. An R2 token scoped to
the bucket with **Object Read** permission is enough.

## Select a release

Treat a release as the pair **(image, prefix)**. Set `PLAINBASE_PROFILE=reader` beside
`PLAINBASE_S3_PREFIX` in the deployment environment:

```sh
export PLAINBASE_PROFILE=reader
export PLAINBASE_S3_PREFIX=releases/r1
export PLAINBASE_STORAGE_BACKEND=object
export PLAINBASE_HOST=127.0.0.1
export DATA_DIR=/var/lib/plainbase/reader
export PLAINBASE_S3_ENDPOINT='https://<account-id>.r2.cloudflarestorage.com'
export PLAINBASE_S3_BUCKET='<your-bucket>'
export PLAINBASE_S3_ACCESS_KEY_ID='<r2-access-key-id>'
export PLAINBASE_S3_SECRET_ACCESS_KEY='<r2-secret-access-key>'
```

The profile env var alone selects the reader, even without `plainbase.conf`. A file containing
`profile = reader` also selects it; neither source can turn off a reader selected by the other.
Keep Git disabled, `auth.mode = off`, `auth.insecureHttp = false`, the trusted-proxy list empty and
the proxy secret absent. Contradictory settings refuse boot. Object readers use a synthesized,
non-editable root with history and discussions off; an explicit `roots {}` block is refused.

The prefix must come from `PLAINBASE_S3_PREFIX`. Do not put `storage.object.prefix` in
`plainbase.conf`, even if the env var is also set. An empty prefix or a trailing `/` refuses boot.
The endpoint must use HTTPS, and the endpoint, bucket and both credential env vars are required.
See the [configuration reference](../configuration.md#reference-table) for the remaining object-store
settings and the [R2 walkthrough](object-storage.md#r2-walkthrough-the-primary-path) for bucket setup.

**Release prefixes must remain immutable after publication.** Do not overwrite, add or delete objects
under a published prefix. Publish changed content under a new prefix and deploy fresh readers for it;
readers that hydrate a changed prefix at different times can serve different content.

## A fresh DATA_DIR on every start

**Preparing a fresh DATA_DIR is the operator's deployment responsibility.** Arrange deployment startup
to prepare it before every boot, including a retry after a refused boot. Use a plain subdirectory,
never a mount root. Mount `plainbase.conf` as a **file** if you need one. An env-selected reader needs
no configuration file.

Start with an empty directory apart from optional `plainbase.conf` and `plainbase.lock`. The engine
allows only those names, takes its DATA_DIR lock, and refuses if it cannot list the directory or finds
anything else, including `roots.conf`, `plainbase.db` or `mirror/`. Stop any process using that
DATA_DIR before clearing it; a second process cannot take the same lock.

A refusal after database creation and hydration can leave `plainbase.db` and `mirror/` behind. A retry
against that directory is refused too. Prepare it afresh instead of just restarting the process.
The freshness check enforces this requirement; it does not clean the directory for you.

After preparing the directory and setting the environment, run `./plainbase serve`.

## Complete-or-refuse boot

The reader must finish a strict hydrate and a complete first index before it serves. It refuses on:

- A failed LIST self-check, including an unreachable endpoint, a rejected TLS certificate, bad
  credentials or a rejected signature.
- A listed object's GET that fails, returns 404, or returns a truncated body.
- A GET body over the per-object size cap: the larger of **64 MiB** and
  **`PLAINBASE_MAX_ASSET_BYTES` plus 1 MiB** of headroom.
- A mirror-write failure, or a failure to remove a bucket-absent mirror file during hydration.
- An object replaced or removed between the listing and its fetch.
- A prefix with no eligible Markdown pages, even if it contains assets or ignored objects.
- A first index missing a listed page, asset or folder metadata file below the root, or containing
  one that was not listed.
- A listed `_folder.yaml` below the root that is non-UTF-8 or symlinked. Its metadata cannot be read,
  so the refusal names it as a "listed other file(s) not indexed".
- A skipped root, an incomplete mirror walk or generation check, or an incomplete page read.

A throttled GET (429 or 503) retries up to three times after the initial request. A failure after
those retries still refuses boot; other GET failures are not retried. Cold boot downloads and indexes
the corpus, so its duration grows with the release size. Size startup deadlines for that work.

A wrong prefix holding a complete release **boots successfully**. After boot, check
`GET /api/v1/session`: it must report `auth_mode: "reader"` and the expected `release`. For
`PLAINBASE_S3_PREFIX=releases/r1`, the opaque release label is `"r1"`. Check it as part of deployment;
boot success alone does not prove that you selected the intended release.

## Tunnel ingress and HTTP methods

Bind Plainbase to loopback (`PLAINBASE_HOST=127.0.0.1`) and use the **Cloudflare tunnel as its only
ingress**. TLS terminates at Cloudflare; the tunnel connector reaches Plainbase's loopback HTTP
listener. A non-loopback bind is refused. **No override admits one.** The connector must be able to
reach that loopback listener.

Before publishing the hostname, configure a **Cloudflare edge rule with a GET/HEAD method allowlist
and a rate limit that counts HEAD**. This is the only control in front of search load, including
`GET /api/v1/search`. The binary's only rate limiter is login's, and login is not mounted on a reader.
Search is available to anonymous callers.

HEAD answers on every mounted URL, **the API included**, so HEAD uptime probes work. It executes the
full GET handler before dropping the body: asset bytes are read and searches execute. **HEAD costs
what GET costs** and must count toward the edge rate limit. OPTIONS is unsupported.

## Several readers and health

Several readers, each with its own DATA_DIR, may read one prefix at once. Answers agree across readers
and fresh restarts only for an **immutable release prefix** whose pages all have **unique, valid
frontmatter ids**. Missing or duplicate ids can require newly minted identities, so that agreement is
not guaranteed for them.

Readers do not poll the bucket after boot. `PLAINBASE_S3_POLL_SECONDS` has no effect on a reader, but
it is still validated and must be a positive integer when set. Deploy a fresh reader to change releases.
Reader `/healthz` never reports `coverage`.

## See also

- [Object storage: writable-instance exclusion](object-storage.md#one-instance-per-bucket-the-v1-rule).
- [Object storage: least-privilege IAM](object-storage.md#least-privilege-iam-stated-honestly).
- [Configuration reference](../configuration.md#reference-table).
- [Operating Plainbase: object storage](../operating-plainbase.md#object-storage-backend-storagebackendobject).
