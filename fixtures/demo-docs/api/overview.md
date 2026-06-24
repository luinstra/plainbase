---
title: API Overview
slug: api-overview
tags: [api]
updated: 2026-05-30
---

# API Overview

Agent operations over REST and MCP: search, read_page, read_section,
get_page_metadata, propose_change, validate_links, list_changes, get_change.

Approved changes apply directly to the content tree: an admin reviewer
approves an edit (it commits to disk + Git), rejects it, or rebases a
conflicted edit back onto the current page.

Details: [authentication](authentication.md), [REST](rest.md), [MCP](mcp.md),
[errors](errors.md).
