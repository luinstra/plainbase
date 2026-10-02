# Unreleased

## Added

- **Rendered block source ranges.** Page HTML now carries `data-pb-src` half-open UTF-8 byte ranges on rendered Markdown blocks, including list items, escaped HTML blocks, and comments. HTML comment blocks now render inside a `<p>` carrier, which is a visible layout change.
- **Discussion reads.** Editable local roots support a root inbox, standalone discussions and page discussions shown by default below Page Info in a stable margin, with local Hide/Show controls. Files under each root's `.plainbase/discussions/` are authoritative; include them in root backups. Read-only and object-mode roots report Discussions unavailable. Before upgrading, rename any root named `discussions` in its declaring `plainbase.conf` or `DATA_DIR/roots.conf`; configuration otherwise refuses, and `root remove` cannot run to repair it. Root-level `.plainbase/` is also reserved (NFC-normalized and case-insensitive); includes with that first literal-prefix segment refuse configuration. Remove or revise such includes before upgrading; wildcard includes cannot expose the collection. See [configuration](../configuration.md#discussions-support-and-authorship).
- **Discussion writing.** New discussion uses selected page text automatically, with a passage preview to confirm; without a selection, it starts a whole-page discussion. Open discussions accept replies. PROPOSE/COMMIT agents write discussions directly with visible agent provenance. Drafts remain available after a failed post, and confirmed posts stay posted if refresh fails. See [agent workflows](../http-agent-workflow.md#discussions-read-preview-start-and-reply).
- **Discussion lifecycle actions.** Edit/retract comments and resolve/reopen discussions. Human reattachment requires fresh passage preview and confirmation, retaining immutable original evidence separately from the latest anchor. Admins can explicitly purge comments; earlier bodies may remain in Git and backups. Inspect uncertain writes before another action; source reload alone does not resolve the outcome. See [operating and recovery guidance](../operating-plainbase.md#discussions).

## Changed

- **Discussion controls.** Discussion buttons are smaller and use a quieter accent, with secondary actions in menus that close when focus moves away.
- **Edit and View.** Switch between reading and editing from the same top-bar position; View asks before discarding unsaved edits.

## Limitations

Object-mode Discussions, selection-performance refactoring, Firefox multirange selection and
cached-session 401 redesign remain deferred.
