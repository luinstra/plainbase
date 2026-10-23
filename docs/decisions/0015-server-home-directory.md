# 0015: Server state defaults to HOME/.plainbase

Status: Accepted · Date: 2026-10-22 · Deciders: owner and independent server planning panel

## Context

CWD-relative application state makes accidental launches select different databases. Server and
offline commands need a shared default independently of any desktop launcher. Document locations
remain separately configured, filesystem-owned authorities.

## Decision

Default DATA_DIR directly to the absolute HOME environment path's `.plainbase` child. Explicit
nonblank DATA_DIR remains authoritative, including relative paths and `/data` containers. Require
valid HOME only when needed. Keep path selection pure and legacy inspection in the common loader.

Refuse recognizable launch-CWD `data` state rather than silently switching identity/security state.
An explicit DATA_DIR or proven same-directory alias supplies authority to continue. Perform no
automatic migration, merge, parent search, or document relocation. Keep config precedence, content
path resolution, auth and lock protocols unchanged.

## Consequences

Implicit old installs and services without HOME need explicit configuration. Theme-only evidence
can conservatively refuse; explicit DATA_DIR resolves that ambiguity. Operators may keep the old
location indefinitely or follow the offline copy/verify/rollback runbook. Shared home state still
requires absolute content/root paths for launches from different CWDs.
