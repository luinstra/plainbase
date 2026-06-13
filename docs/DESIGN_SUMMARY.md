# Plainbase Design Summary

## Concept

Plainbase is an internal documentation and knowledge product built for both humans and AI agents.

It aims to provide the polish and usability people expect from modern tools like Confluence, Notion, Outline, or Docmost, while keeping the durable source of truth simple: ordinary files, folders, assets, metadata, and optional Git history.

The core idea is that company knowledge should be easy for humans to read and edit, while also being structured enough for agents to search, cite, learn from, review, and update through controlled workflows.

Plainbase is not "Confluence, but open source." It is closer to an agent-native internal docs system: a filesystem-native knowledge base where documentation participates in the full lifecycle of work.

## Product Thesis

Internal docs should be:

- Pleasant for humans to browse, search, and maintain.
- Stored as plain, portable files rather than trapped in an application database.
- Versionable, reviewable, and recoverable with standard tools.
- Structured enough for agents to use as reliable working context.
- Safe enough for agents to propose or perform updates under explicit access controls.

The short positioning line:

> Internal docs that humans enjoy using and agents can actually work with.

## Core Operating Principles

### Filesystem-native first

The canonical knowledge base is a simple tree of files and folders. Pages are Markdown files. Assets are ordinary files. Metadata is stored alongside content in frontmatter or sidecar files.

This content tree should be able to live on local disk, a mounted volume, a Git checkout, network storage, or cheap object storage such as S3 or Cloudflare R2.

### Git is a natural layer, not the only foundation

Git is valuable for history, diffs, review, branching, rollback, and collaboration, but the deeper design principle is plain file storage.

Plainbase should work well with Git, but should not require users to understand Git for basic editing. A normal edit in the UI can still produce a clean commit behind the scenes.

### Markdown is the human and agent interface

Markdown keeps content readable without the application. It is also friendly to AI agents, developer tools, search pipelines, and migration workflows.

The product should avoid a proprietary document format as the canonical source of truth.

### Metadata gives stable structure

Pages need identity beyond file paths. Paths can change, titles can change, and pages can be reorganized without breaking references.

Each page should have stable metadata such as:

- Page ID
- Title
- Aliases
- Slug
- Owner
- Tags
- Lifecycle status
- Review date
- Redirects
- Source references

This metadata supports stable links, citations, navigation, search, and agent workflows.

### Indexes are derived state

Search indexes, embeddings, relationship graphs, rendered previews, and media-extracted text are generated from the canonical files.

If the index database disappears, Plainbase should be able to rebuild it from the content tree.

## Agent-native Lifecycle

Agents should be first-class users of the knowledge base, but not privileged users.

They should be able to:

- Search docs.
- Read pages and sections.
- Retrieve stable citations.
- Use docs as context while solving problems.
- Identify missing, stale, or contradictory documentation.
- Propose edits as Markdown patches.
- Review human or agent changes.
- Validate links, metadata, and references.
- Commit directly only where policy allows it.

The ideal agent workflow starts conservatively:

1. Agent searches and reads relevant docs.
2. Agent answers or works with citations.
3. Agent detects a documentation gap or stale information.
4. Agent proposes a Markdown patch.
5. Human reviews a rendered preview and diff.
6. Plainbase commits the accepted change.
7. Indexes are rebuilt or updated.

Direct agent commits should be configurable for low-risk areas. Riskier areas should require human approval.

## Access Control Model

Plainbase should treat humans and agents as security principals with explicit permissions, identity, and audit history.

The early permission model can stay simple, but it should be shaped so it can grow into real RBAC later.

Useful actions:

- `read`: view and search content
- `propose`: submit edits for review
- `edit`: commit changes directly
- `approve`: approve proposed edits
- `manage`: change metadata, policy, integrations, or access settings

Useful initial roles:

- Admin
- Maintainer
- Editor
- Viewer
- Agent

Useful agent modes:

- Read only
- Propose edits
- Commit low-risk edits
- Commit with full assigned permissions

Important boundary: editable page metadata should not be the sole source of access policy. If an agent can edit the metadata file that grants permissions, it could escalate itself. Authoritative access policy should live in protected app configuration, a database, or an identity provider integration.

## Search and Discovery

Search is a core product surface, not an add-on.

Plainbase should support fast, typo-tolerant text search across:

- Page titles
- Headings
- Body content
- Tags
- Owners
- Aliases
- Metadata
- Asset names
- Extracted media text

Meilisearch is a reasonable first search engine because it is simple, fast, and supports a path toward hybrid semantic search.

Media search can become a meaningful differentiator. Plainbase can index extracted content from:

- PDFs
- Screenshots via OCR
- Images via captions or alt text
- Diagrams
- Office documents
- Audio or video transcripts

Semantic search and embeddings should be treated as derived indexes over the same canonical files.

## Agent API Surface

Agents should not have to scrape rendered HTML. They need structured operations with stable references.

Possible API or MCP-style capabilities:

- `search(query)`
- `read_page(page_id)`
- `read_section(page_id, heading_id)`
- `get_related_pages(page_id)`
- `get_page_metadata(page_id)`
- `propose_patch(page_id, diff)`
- `validate_links(page_id)`
- `review_change(change_id)`
- `explain_change(commit_id)`

Responses should include stable citations such as page IDs, heading IDs, file paths, and commit references where appropriate.

## MVP Shape

A credible first version should prove the storage model, human UI, search surface, and agent workflow.

Initial scope:

- Render a filesystem tree of Markdown files as a polished internal docs site.
- Support sidecar metadata or frontmatter for stable page identity.
- Provide fast full-text search.
- Allow browser-based page editing.
- Save changes back to files.
- Optionally commit changes to Git.
- Show page history and diffs when Git is enabled.
- Provide an agent-readable API for search, read, cite, and propose patch.
- Support a review queue for proposed agent edits.
- Run self-hosted with simple deployment.

Deferred until the concept is validated:

- Full enterprise RBAC
- Real-time multiplayer editing
- Complex workflow automation
- Large integration marketplace
- Autonomous edits in high-risk areas
- Advanced importers beyond the first target

## Competitive Framing

Plainbase sits between several existing categories:

- Confluence-style internal wikis
- Notion-style workspaces
- GitBook-style docs platforms
- Docs-as-code static site generators
- Markdown note apps
- Git-backed wiki tools

The closest known product direction is Wiki.js, because it supports Git sync. The key distinction is that Plainbase should be filesystem-native and agent-native at the core, not database-first with Git as a sync module.

Docmost and Outline are stronger examples of modern wiki UX, but they are not primarily plain-file, Git-friendly, or agent-native in the same way.

The differentiator is not simply "Markdown docs." It is:

> Plain files as the source of truth, polished docs UI for humans, structured knowledge access for agents.

## Open Questions

- Should metadata live in frontmatter, sidecar files, or both?
- What is the first storage backend: local filesystem, Git repo, S3/R2, or a pluggable abstraction from day one?
- What is the first editing model: direct save, commit-per-save, or proposed changes?
- Should the first product be self-hosted only, hosted cloud, or both?
- Which importer matters first: Confluence, GitHub wiki, Markdown repo, or Notion export?
- What is the minimum agent API that proves the product thesis?
- What should count as a low-risk area for direct agent commits?

## Name

The working name is Plainbase.

It fits the product thesis: plain files, durable base, simple storage, and a canonical source of knowledge.
