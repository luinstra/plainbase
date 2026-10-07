# OKF-compatible knowledge documents

This guide follows version 0.2 of the [Open Knowledge Format specification](https://github.com/GoogleCloudPlatform/open-knowledge-format/blob/main/SPEC.md).

Ordinary new-page creation emits OKF v0.2-compatible concept frontmatter. Blank, How-to,
Reference, and Meeting notes pages all start with `type: "Reference"`; authors may choose a
more descriptive type in the file later. Plainbase keeps its rooted UUID identity and ordinary
Markdown files remain the authoritative content.

This producer change does not certify existing documents, later edits, section or recovery outputs,
or a whole content directory. Plainbase does not add a runtime YAML validator, migrate files,
interpret trust metadata, or convert configuration, internal storage, or discussions to OKF.

## Concept frontmatter

A concept is a UTF-8 Markdown document with parseable YAML between opening and closing `---`
lines. Its only required key is a nonempty string `type`. Save it under an ordinary `.md` filename;
`index.md` and `log.md` have separate roles at every directory level.

This minimal `reference.md` is sufficient:

```markdown
---
type: Reference
---

# Deployment reference

Keep the deployment checklist beside the service source.
```

Plainbase-created files also carry identity and display metadata:

```markdown
---
id: 01900000-0000-7000-8000-000000000001
type: "Reference"
title: "Deployment reference"
---

# Deployment reference
```

Unknown descriptive types such as `Team Runbook` are supported; there is no type registry or
picker. Title, description, resource, tags, provenance, and other extension keys are optional.
Use a YAML string: for example, quote `"42"` or `"true"` if that is the intended type.
An absent, empty, null, numeric, or boolean type does not satisfy the concept's required string field.
Broken links and unknown keys do not by themselves invalidate the frontmatter format.

Optional nested fields can follow the published OKF families:

```markdown
---
type: Team Runbook
title: Release checklist
sources:
  - id: deployment-notes
    resource: notes/deployment-notes.md
    title: Deployment notes
    author: human:ada
generated:
  by: human:ada
  at: "2026-10-04T12:00:00Z"
verified:
  - by: human:lin
    at: "2026-10-04T13:00:00Z"
custom:
  audience: [operations, support]
---

# Release checklist

Check the rollout against the deployment notes.[^deployment-notes]

[^deployment-notes]: Deployment notes
```

Follow the published shapes when using optional families: a sources entry has a `resource`;
actors identify people with `human:<id>`; timestamps include an explicit UTC offset.
`generated` and `verified` describe different events. Plainbase's scalar/list metadata projection
may omit nested values while the underlying YAML remains valid. These fields grant no permission
and do not promise semantic UI consumption, verification, or attestation execution.

## Deliberate repair of existing documents

Inspect the complete file before editing it. For an existing valid YAML block, add a type line
between its fences while preserving its existing id, title, unknown keys, and all unrelated bytes.
For a headerless concept, add a type-only block like the minimal example above; do not invent or
remint an id. Plainbase's metadata controls edit selected fields surgically; the raw editor remains
available for the complete source.

Malformed YAML or invalid encoding needs an explicit author repair. Blindly inserting a type line
cannot repair either problem. Plainbase continues to ingest legacy documents permissively and
does not validate every raw edit against OKF. Authors may change or remove type through a normal
save and thereby change the file's conformance.

Use the current page `ETag`/base hash when saving through the API. The ordinary raw-save guards
still protect the honored id, slug, and redirect fields; adding/removing type does not bypass
them. Raw PUT cannot remove an honored id or perform an identity-aware rename.

## Reserved listing and history roles

An OKF `index.md` is a directory listing, usually without frontmatter:

```markdown
# Operations

* [Deployment reference](deployment-reference.md) - release checks and rollback notes
* [Runbooks](runbooks/) - task procedures
```

Only a bundle-root index may carry the version declaration:

```markdown
---
okf_version: "0.2"
---

# Knowledge

* [Operations](operations/) - deployment references and runbooks
```

That declaration is the exception to the frontmatter type requirement. Adding id, title, or type
does not turn an authored section landing into an OKF listing. Plainbase's current **Folder landing page**
option remains a legacy id/title-bearing landing page, outside this listing role.

A `log.md` records newest-first date-grouped history with `YYYY-MM-DD` headings. It may have no
frontmatter:

```markdown
# Operations history

## 2026-10-04
* Updated the [deployment reference](deployment-reference.md).

## 2026-10-01
* Added the first runbooks.
```

If a log has frontmatter, the YAML must parse and include a nonempty string type:

```markdown
---
type: Update History
---

# Operations history

## 2026-10-04
* Updated the deployment reference.
```

A log header with missing, null, or non-string type fails that frontmatter condition. A valid
header alone does not establish the log's history structure, and the reserved filename still
prevents the file from being a concept.

Typed REST creation rejects a title or slug that derives to `index.md` or `log.md`. In the ordinary
dialog, choose another title or use **Edit URL** to supply a non-reserved custom slug. Under **More options**,
**Folder landing page** continues to create legacy landings. If you deliberately rename an existing reserved landing to
an ordinary name such as `README.md`, it also needs type to become a concept. Preserve its UUID
and review links/routing as part of that rename. Converting a landing to a true listing needs
separate identity-aware review; there is no automatic rename or id removal.

## Other creation and editing paths

- REST creation opts in with an optional string `type`; omitted or explicit null retains legacy
  bytes and behavior. See the [creation request reference](http-api.md#page-creation).
- **Save as new page** always remains legacy. It strips old frontmatter, including type and nested
  metadata, and creates a fresh identity. The actual destination follows its selected title, not
  the deleted file's basename. Deliberately add type afterward if the destination is a concept.
- Adoption records/materializes identity only. It never adds type or converts documents; inserting
  id frontmatter into a true index can spoil the index's header convention.
- External edits and explicit proposals supply author-owned complete documents. For an explicit
  create proposal, use plain patchable frontmatter, no id, and a non-reserved target, for example:

```json
{
  "operation": "create",
  "root": "docs",
  "target_path": "notes/deployment-reference.md",
  "proposed_content": "---\ntype: Reference\ntitle: Deployment reference\n---\n\n# Deployment reference\n",
  "rationale": "Add the deployment reference."
}
```

Send it to `POST /api/v1/changes` or the MCP `propose_change` tool. The server's conservative
patcher adds only its id; it can refuse quoted/nested YAML that is otherwise valid. Approval writes
the stored bytes without restamping type. A type line alone does not repair malformed YAML,
encoding, or reserved-role misuse.
