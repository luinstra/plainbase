#!/usr/bin/env bash
# Cloud startup budget (object mode) - operator/pre-release drill, NOT wired into CI.
#
# Boots the given plainbase launcher in object mode against a REAL S3-compatible bucket and gates two
# medians: a COLD boot (fresh DATA_DIR - full hydrate: LIST + a GET per object under the prefix) and a
# WARM boot (retained DATA_DIR - etag-diff LIST, no refetch). Budgets default to warm under 3 s, cold
# under 10 s at the ~1k-page contract (the master plan constraint-4 numbers). The gate is strict `<`
# (matching native-startup-bound.sh): a median exactly at the bound FAILS.
#
# WHY it is not in CI: CI has no bucket and no credentials, so unlike the local-backend startup tripwire
# (scripts/ci/native-startup-bound.sh, wired at .github/workflows/ci.yml) there is NO between-release
# regression guard for cloud startup budgets. This script is therefore a REQUIRED pre-release drill
# (docs/DEVELOPMENT.md pre-release checklist), run by hand against a seeded bucket.
#
# WHAT the WARM number actually means: every boot runs a full IndexBuilder.rebuild() over the hydrated
# mirror before /healthz serves 200, so the warm measurement is exec -> first 200 /healthz INCLUDING
# that index rebuild - a rebuild+serve budget, not a cold-JVM-start figure. At ~1k pages the rebuild can
# dominate the warm budget; this is exactly why the corpus floor below matters (the number is
# meaningless at 41 pages).
#
# CONFIG comes from ENV, never argv (the S3SmokeCommand rationale). This boots the REAL server, so it
# reads the same runtime keys the server reads - the PLAINBASE_S3_* family (see docs/configuration.md),
# NOT the separate PLAINBASE_SMOKE_* family the s3-smoke command uses:
#   PLAINBASE_S3_ENDPOINT, PLAINBASE_S3_BUCKET, PLAINBASE_S3_ACCESS_KEY_ID, PLAINBASE_S3_SECRET_ACCESS_KEY
#   PLAINBASE_S3_REGION   (optional, default auto), PLAINBASE_S3_PREFIX (optional, default empty = root)
# The run is bucket-READ-ONLY: this script FORCES PLAINBASE_GIT_ENABLED=false so the measured boot is
# the hydrate-only path (it does not leave the operator's inherited value in place - a git-enabled run
# would ship a bundle mid-measurement, skewing the timings AND writing to the real bucket during a
# supposedly read-only drill).
#
# CORPUS FLOOR: a budget measured against an empty or tiny prefix is a false green that would falsely
# certify a release, so this refuses below 800 raw LIST entries under the configured prefix. Two ways to
# supply the count, evaluated in this order:
#   1. PLAINBASE_BUDGET_OBJECT_COUNT (portable, always-available): if set, used verbatim and the aws/
#      rclone probe is short-circuited entirely - no S3 config is touched. This is the credential-free
#      seam the verification sweep uses to exercise the sub-800 refusal on a runner with no bucket.
#   2. otherwise: the four PLAINBASE_S3_* keys above are required, and the count is measured live under
#      the configured prefix. Probe precedence when the override is unset: aws CLI first (when
#      installed), else rclone. The count is RAW LIST entries (matching forEachListedObject), not logical
#      pages - a stray zero-length directory-marker key is acceptable for a coarse floor gate.
#     - aws:    aws s3 ls "s3://$BUCKET/$SCOPE" --recursive --endpoint-url "$ENDPOINT" | wc -l
#               (R2-capable: the endpoint + path-style are what make it work against R2; keys/region are
#                mapped from PLAINBASE_S3_* into the AWS_* the CLI reads).
#     - rclone: requires PLAINBASE_BUDGET_RCLONE_REMOTE naming a remote configured to the SAME
#               endpoint/keys; rclone size "$REMOTE:$BUCKET/$SCOPE" --json (the "count" field).
#
# SEEDING a ~1k corpus (no ~1k fixture is checked in - fixtures/demo-docs is ~41 pages, and
# RenderCorpusPerfTest generates its 1000-page corpus in a temp dir only). Any representative real
# corpus is equally valid; to mint a reproducible one:
#   dir=$(mktemp -d)
#   for i in $(seq 1 1000); do printf '# Page %s\n\nbody %s\n' "$i" "$i" > "$dir/page-$i.md"; done
#   rclone copy "$dir" "$REMOTE:$BUCKET/$PREFIX"      # or: aws s3 sync "$dir" "s3://$BUCKET/$PREFIX/" --endpoint-url "$ENDPOINT"
#
# LOOP contract (isolation mirrors native-startup-bound.sh):
#   COLD phase: for each of 3 runs -> rm -rf $DATA_DIR; mkdir; boot; time exec -> first 200 /healthz;
#               kill+wait. Full hydrate (LIST + every GET under the prefix) is measured. Median (the 2nd
#               of 3 sorted) vs the cold budget.
#   WARM phase: RETAIN $DATA_DIR across 5 runs (etag-diff LIST, no refetch), so only the first run
#               hydrates and the rest measure warm-mirror boot. Median (the 3rd of 5 sorted) vs the warm
#               budget.
#
# Usage: cloud-startup-budget.sh <plainbase-launcher> [port=8083] [warm-budget-ms=3000] [cold-budget-ms=10000]
# Exits non-zero on the corpus-floor preflight, a missing-credentials refusal, or a median budget breach.
set -euo pipefail

for cmd in curl perl; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "missing required tool: $cmd" >&2; exit 1; }
done

BIN=${1:-}
[ -x "$BIN" ] || { echo "launcher not found or not executable: '$BIN'" >&2; exit 1; }
PORT=${2:-8083}
WARM_BUDGET=${3:-3000}
COLD_BUDGET=${4:-10000}
BASE="http://127.0.0.1:$PORT"

# A hard per-run poll deadline so a hung or refusing boot never spins forever. This is NOT the budget
# gate (the medians below are); it is generous enough to let a real ~1k first-boot hydrate complete.
BOOT_DEADLINE_MS=120000
FLOOR=800

# SCOPE mirrors ObjectContentStoreFactory exactly: empty prefix -> bucket root, else "<prefix>/". Needs
# only the prefix, never credentials, so it is computed before any S3-config requirement.
PREFIX=${PLAINBASE_S3_PREFIX:-}
if [ -z "$PREFIX" ]; then SCOPE=""; else SCOPE="${PREFIX}/"; fi

now_ms() { perl -MTime::HiRes=time -e 'printf "%d", time()*1000'; }

require_s3_config() {
  local missing=()
  [ -n "${PLAINBASE_S3_ENDPOINT:-}" ] || missing+=("PLAINBASE_S3_ENDPOINT")
  [ -n "${PLAINBASE_S3_BUCKET:-}" ] || missing+=("PLAINBASE_S3_BUCKET")
  [ -n "${PLAINBASE_S3_ACCESS_KEY_ID:-}" ] || missing+=("PLAINBASE_S3_ACCESS_KEY_ID")
  [ -n "${PLAINBASE_S3_SECRET_ACCESS_KEY:-}" ] || missing+=("PLAINBASE_S3_SECRET_ACCESS_KEY")
  if [ "${#missing[@]}" -gt 0 ]; then
    echo "object-mode drill needs the S3 config in env: missing ${missing[*]}" >&2
    echo "(or set PLAINBASE_BUDGET_OBJECT_COUNT to run the corpus-floor preflight credential-free)" >&2
    exit 1
  fi
}

measure_object_count() {
  if command -v aws >/dev/null 2>&1; then
    AWS_ACCESS_KEY_ID="$PLAINBASE_S3_ACCESS_KEY_ID" \
    AWS_SECRET_ACCESS_KEY="$PLAINBASE_S3_SECRET_ACCESS_KEY" \
    AWS_DEFAULT_REGION="${PLAINBASE_S3_REGION:-auto}" \
      aws s3 ls "s3://$PLAINBASE_S3_BUCKET/$SCOPE" --recursive --endpoint-url "$PLAINBASE_S3_ENDPOINT" | wc -l | tr -d ' '
  elif command -v rclone >/dev/null 2>&1; then
    if [ -z "${PLAINBASE_BUDGET_RCLONE_REMOTE:-}" ]; then
      echo "rclone found but PLAINBASE_BUDGET_RCLONE_REMOTE is unset (name the rclone remote configured to the same endpoint/keys)" >&2
      exit 1
    fi
    rclone size "$PLAINBASE_BUDGET_RCLONE_REMOTE:$PLAINBASE_S3_BUCKET/$SCOPE" --json \
      | perl -ne 'if (/"count":\s*(\d+)/) { print $1; exit }'
  else
    echo "no object-count probe available: install aws CLI or rclone, or set PLAINBASE_BUDGET_OBJECT_COUNT" >&2
    exit 1
  fi
}

# Corpus-floor preflight - runs BEFORE any boot. The override short-circuits ahead of every S3-config
# requirement so the sub-floor refusal is reachable credential-free.
if [ -n "${PLAINBASE_BUDGET_OBJECT_COUNT:-}" ]; then
  COUNT=$PLAINBASE_BUDGET_OBJECT_COUNT
else
  require_s3_config
  COUNT=$(measure_object_count)
fi
case "$COUNT" in
  ''|*[!0-9]*) echo "could not determine the object count under prefix '${SCOPE:-<root>}' (got: '$COUNT')" >&2; exit 1 ;;
esac
if [ "$COUNT" -lt "$FLOOR" ]; then
  echo "budget drill needs a representative ~1k corpus under prefix '${SCOPE:-<root>}'; found $COUNT, seed the bucket first" >&2
  exit 1
fi
echo "corpus preflight: $COUNT objects under prefix '${SCOPE:-<root>}' (>= $FLOOR floor)"

# Past the floor a real boot is measured, so the full S3 config is now mandatory even when the count
# came from the override.
require_s3_config

tmp=$(mktemp -d)
SERVER_PID=""
trap '[ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true; rm -rf "$tmp"' EXIT

export PLAINBASE_STORAGE_BACKEND=object
export PLAINBASE_HOST=127.0.0.1
export PLAINBASE_PORT="$PORT"
export DATA_DIR="$tmp/data"
# Force git OFF for the measurement (do not inherit a production PLAINBASE_GIT_ENABLED=true): the drill
# must measure the hydrate-only READ path and must never ship a bundle to the real bucket.
export PLAINBASE_GIT_ENABLED=false

# Boots the launcher, times exec -> first 200 /healthz, then kills and CONFIRMS exit before returning.
# Echoes the elapsed milliseconds on stdout; diagnostics go to stderr.
measure_boot() {
  local label=$1 t0 elapsed deadline boot_log
  boot_log="$tmp/boot.log" # server stderr (logback) - kept so a boot refusal shows its actual reason
  t0=$(now_ms)
  "$BIN" serve >/dev/null 2>"$boot_log" &
  SERVER_PID=$!
  deadline=$((t0 + BOOT_DEADLINE_MS))
  elapsed=""
  while :; do
    if curl -fsS "$BASE/healthz" >/dev/null 2>&1; then
      elapsed=$(($(now_ms) - t0))
      break
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      echo "$label: server exited before becoming healthy (a boot refusal? check the endpoint/credentials and the object-mode self-check)" >&2
      sed 's/^/  boot stderr: /' "$boot_log" >&2
      exit 1
    fi
    [ "$(now_ms)" -lt "$deadline" ] || { echo "$label: server not healthy within ${BOOT_DEADLINE_MS} ms" >&2; sed 's/^/  boot stderr: /' "$boot_log" >&2; exit 1; }
    sleep 0.05
  done
  kill "$SERVER_PID" 2>/dev/null || true
  wait "$SERVER_PID" 2>/dev/null || true
  SERVER_PID=""
  printf '%s' "$elapsed"
}

# COLD phase: a fresh DATA_DIR per run forces a full hydrate (LIST + a GET per object) every time.
cold=()
for i in 1 2 3; do
  rm -rf "$tmp/data"
  mkdir -p "$tmp/data"
  cold+=("$(measure_boot "cold run $i")")
done
cold_median=$(printf '%s\n' "${cold[@]}" | sort -n | sed -n '2p')

# WARM phase: retain DATA_DIR across runs, so only the first run hydrates and the median lands on a
# warm-mirror boot.
rm -rf "$tmp/data"
mkdir -p "$tmp/data"
warm=()
for i in 1 2 3 4 5; do
  warm+=("$(measure_boot "warm run $i")")
done
warm_median=$(printf '%s\n' "${warm[@]}" | sort -n | sed -n '3p')

echo "startup-perf: cold runs ${cold[*]} ms; median $cold_median ms (budget ${COLD_BUDGET} ms)"
echo "startup-perf: warm runs ${warm[*]} ms; median $warm_median ms (budget ${WARM_BUDGET} ms, rebuild+serve)"

fail=0
[ "$cold_median" -lt "$COLD_BUDGET" ] || { echo "FAIL: cold median ${cold_median} ms >= budget ${COLD_BUDGET} ms" >&2; fail=1; }
[ "$warm_median" -lt "$WARM_BUDGET" ] || { echo "FAIL: warm median ${warm_median} ms >= budget ${WARM_BUDGET} ms" >&2; fail=1; }
exit "$fail"
