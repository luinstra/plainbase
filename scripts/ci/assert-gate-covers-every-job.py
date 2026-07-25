#!/usr/bin/env python3
"""Assert the CI gate job depends on EVERY other job in the workflow.

The gate job (`ci-gate`) is the single required status check on `main`. That indirection exists
because a required check is matched by NAME, so pinning individual job names in the branch ruleset
detaches silently the moment someone renames a job, and it fails OPEN: the pull request waits
forever on a check nothing will ever report, which reads as a stuck test rather than a config error.
That is exactly what happened when the Playwright job gained `+ multi-root` in its name.

Routing every job through one gate fixes that, but it moves the coupling rather than deleting it:
add a job and forget to list it in the gate's `needs`, and it quietly stops gating. This script is
what makes that failure LOUD. It runs as the gate's own first step, so the gate refuses to pass
while it is incomplete.

Deliberately no YAML dependency: it reads the top-level keys under `jobs:` and the gate's `needs:`
list textually. The workflow is ours and well-formed, and a parser dependency in CI is a worse trade
than a strict reader that fails closed on anything it does not recognise.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

WORKFLOW = Path(__file__).resolve().parents[2] / ".github" / "workflows" / "ci.yml"
GATE = "ci-gate"


def fail(message: str) -> None:
    print(f"assert-gate-covers-every-job: {message}", file=sys.stderr)
    sys.exit(1)


def main() -> None:
    if not WORKFLOW.is_file():
        fail(f"workflow not found at {WORKFLOW}")
    lines = WORKFLOW.read_text(encoding="utf-8").splitlines()

    try:
        jobs_at = next(i for i, line in enumerate(lines) if line.rstrip() == "jobs:")
    except StopIteration:
        fail("no top-level `jobs:` key found")

    # A job id is a two-space-indented key directly under `jobs:`. Anything more deeply indented
    # belongs to a job's body, and anything at column 0 has left the jobs block.
    jobs: list[str] = []
    for line in lines[jobs_at + 1:]:
        if line.strip() and not line.startswith(" "):
            break
        match = re.fullmatch(r"  ([A-Za-z0-9_-]+):\s*", line)
        if match:
            jobs.append(match.group(1))

    if not jobs:
        fail("parsed zero jobs, which means this reader no longer understands the workflow")
    if GATE not in jobs:
        fail(f"no `{GATE}` job in {sorted(jobs)}")

    # `needs: [a, b, c]` on one line, or a block list. Read whichever the gate uses.
    gate_at = next(i for i, line in enumerate(lines) if re.fullmatch(rf"  {GATE}:\s*", line))
    body: list[str] = []
    for line in lines[gate_at + 1:]:
        if line.strip() and not line.startswith("    "):
            break
        body.append(line)

    needs: set[str] = set()
    for i, line in enumerate(body):
        inline = re.fullmatch(r"\s*needs:\s*\[(.*)\]\s*", line)
        if inline:
            needs = {n.strip() for n in inline.group(1).split(",") if n.strip()}
            break
        if re.fullmatch(r"\s*needs:\s*", line):
            for follow in body[i + 1:]:
                item = re.fullmatch(r"\s*-\s*([A-Za-z0-9_-]+)\s*", follow)
                if not item:
                    break
                needs.add(item.group(1))
            break

    if not needs:
        fail(f"`{GATE}` declares no `needs`, so it gates nothing")

    expected = set(jobs) - {GATE}
    missing = sorted(expected - needs)
    unknown = sorted(needs - expected)

    if missing:
        fail(
            f"these jobs are NOT gated: {missing}. Add them to `{GATE}`'s `needs`, or this workflow "
            f"reports green on `main` while they fail.",
        )
    if unknown:
        fail(f"`{GATE}` needs jobs that do not exist: {unknown}. A stale name here gates nothing.")

    print(f"ci gate covers all {len(expected)} job(s): {', '.join(sorted(expected))}")


if __name__ == "__main__":
    main()
