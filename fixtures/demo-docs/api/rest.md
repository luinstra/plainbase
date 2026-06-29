---
title: REST API
tags: [api]
---

# REST API

## Read a page

`GET /api/v1/pages/{id}`

## Page metadata

`GET /api/v1/pages/{id}/metadata`

The server-derived metadata projection for a page. A 200 carries the id, the
content path, the page url (nullable), the permalink, the content hash, the
commit (nullable when the tree is not under Git), the title, and the
document-order headings (each with its id, text, and level). A 401 or 403 means
the read was not permitted. A 404 means no such page. A 400 invalid page id
means the id was not a canonical-shape UUID.

## Validate links

`GET /api/v1/pages/{id}/validate-links`

The page's link report. A 200 carries a broken list, each entry naming the page,
the target, the link text, and a reason. The reason is broken missing when the
target page does not exist, blocked scheme when the link uses a disallowed
scheme, and broken anchor when the target heading anchor is absent. The same
auth, not-found, and invalid-id rules as the metadata read apply: 401 or 403 if
not permitted, 404 for an unknown page, and 400 for an id that is not a
canonical-shape UUID.

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

### Approve (apply a change)

`POST /api/v1/changes/{id}/approve`

A 200 applies the change to disk and Git — an edit overwrites the page, a create
writes the new file (a section create lands its `<dir>/index.md`) — returning the
new content hash, an optional commit, the applied timestamp, and optional warnings
(a deferred-reindex note when the bytes landed but the search index heals on the
next boot). A 409 conflicted means the page drifted under the proposal; it is
rebasable. A 409 not-pending means the change was already decided. A 404 means no
such change. A 422 apply-failed means the change could not be applied.

### Rebase (a conflicted edit)

`POST /api/v1/changes/{id}/rebase`

Re-pins the change onto the current page (recomputing the base hash, unified diff,
and target path) and returns it to pending for a fresh approve. A 409 not-conflicted
means the change is not in a conflicted state, a 404 means no such change, and a 422
apply-failed means the target page was deleted, so rebase is impossible.
