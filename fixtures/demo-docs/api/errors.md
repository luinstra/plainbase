---
title: API Errors
tags: [api]
---

# API Errors

Errors carry `{error: {code, message}}`. Common HTTP statuses:

| Status | Meaning |
|--------|---------|
| 401    | No principal |
| 403    | Action denied |
| 409    | Stale `base_hash` (optimistic concurrency) |

## Change-review codes

| Code | Status | Meaning |
|------|--------|---------|
| `conflicted` | 409 | An apply hit disk drift; the change is rebasable. |
| `not_pending` | 409 | The change is no longer pending (already decided/in flight). |
| `not_conflicted` | 409 | A rebase targeted a change that is not conflicted. |
| `apply_failed` | 422 | An edit could not be applied, or a rebase target was deleted. |
| `create_apply_unsupported` | 422 | Approving a `create` change (deferred to a later release). |
