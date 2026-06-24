---
title: REST API
tags: [api]
---

# REST API

## Read a page

`GET /api/v1/pages/{id}`

## Search

`GET /api/v1/search?q=`

Responses carry citations: `plainbase://{page_id}#{heading_id}@{hash}`.

## Changes (review queue)

Agents propose; an admin reviewer decides.

`POST /api/v1/changes` — propose an edit or create. Responds 201 with the new id,
its status, and the unified diff.

`GET /api/v1/changes` — list all changes (newest first).

`GET /api/v1/changes/{id}` — the full change detail, including the stable unified
diff and a live drift flag.

### Reject (decline a change)

`POST /api/v1/changes/{id}/reject`

An admin declines a pending change, optionally with a comment. A 200 marks it
rejected. A 409 not-pending means the change was already decided. A 404 means no
such change.

### Approve (apply an edit)

`POST /api/v1/changes/{id}/approve`

A 200 applies the edit to disk and Git, returning the new content hash, an
optional commit, the applied timestamp, and optional warnings (a deferred-reindex
note when the bytes landed but the search index heals on the next boot). A 409
conflicted means the page drifted under the proposal; it is rebasable. A 409
not-pending means the change was already decided. A 404 means no such change. A
422 apply-failed means the edit could not be applied, and a 422
create-apply-unsupported means approving a create (deferred to a later release).

### Rebase (a conflicted edit)

`POST /api/v1/changes/{id}/rebase`

Re-pins the change onto the current page (recomputing the base hash, unified diff,
and target path) and returns it to pending for a fresh approve. A 409 not-conflicted
means the change is not in a conflicted state, a 404 means no such change, and a 422
apply-failed means the target page was deleted, so rebase is impossible.
