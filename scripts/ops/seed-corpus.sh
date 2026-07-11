#!/usr/bin/env bash
# Seed a representative markdown corpus for object-mode drills.
#
# Generates COUNT synthetic pages (frontmatter + headings + a link + list + blockquote, so the
# renderer and index rebuild have realistic structure to process) into a directory, then prints
# that directory on stdout. Upload it to a bucket to clear the cloud-startup-budget.sh corpus floor
# (>= 800 objects under the configured prefix) or to give an object-mode server a real ~1k-page
# corpus for the pre-release DR / budget drills (docs/DEVELOPMENT.md pre-release checklist).
#
# No fixed ~1k corpus is checked in on purpose (fixtures/demo-docs is ~41 pages, and
# RenderCorpusPerfTest builds its 1000-page corpus in a temp dir only); this mints a reproducible one.
#
# Usage: seed-corpus.sh [count=1000] [output-dir]
#   count       number of pages to generate (default 1000)
#   output-dir  where to write them (default: a fresh mktemp dir, whose path is printed on stdout)
#
# Example:
#   dir=$(scripts/ops/seed-corpus.sh 1000)
#   rclone copy "$dir" "r2:$PLAINBASE_S3_BUCKET/$PLAINBASE_S3_PREFIX"
#   # or: aws s3 sync "$dir" "s3://$PLAINBASE_S3_BUCKET/$PLAINBASE_S3_PREFIX/" --endpoint-url "$PLAINBASE_S3_ENDPOINT"
set -euo pipefail

COUNT=${1:-1000}
case "$COUNT" in
  ''|*[!0-9]*) echo "count must be a non-negative integer, got '$COUNT'" >&2; exit 2 ;;
esac
[ "$COUNT" -ge 1 ] || { echo "count must be >= 1" >&2; exit 2; }

OUT=${2:-$(mktemp -d)}
mkdir -p "$OUT"

# Zero-pad the page number to at least 4 digits (and wide enough for COUNT) so keys sort naturally.
width=${#COUNT}
[ "$width" -ge 4 ] || width=4

i=1
while [ "$i" -le "$COUNT" ]; do
  n=$(printf "%0${width}d" "$i")
  nxt=$(printf "%0${width}d" "$(( (i % COUNT) + 1 ))")
  cat > "$OUT/page-$n.md" <<EOF
---
title: Test Page $n
tags: [drill, sample]
---

# Test Page $n

Representative body text for page $i in the drill corpus, so the object-store hydrate and index
rebuild have realistic content to process rather than a trivial one-liner.

## Overview

Paragraph one with a [link to the next page](page-$nxt.md) plus some **bold** and _italic_ text.

## Details

- bullet one
- bullet two
- bullet three

> A short blockquote so the renderer has something structural to do.
EOF
  i=$((i + 1))
done

echo "seeded $COUNT pages into $OUT" >&2
echo "$OUT"
