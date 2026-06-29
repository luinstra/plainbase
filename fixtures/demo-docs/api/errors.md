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
| `apply_failed` | 422 | A change could not be applied — an unsupported edit, a create the server couldn't materialize, or a rebase whose target page was deleted. |
| `invalid_create_content` | 400 | A create proposal supplied its own frontmatter `id`; the server mints it. |
