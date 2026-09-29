# Unreleased

## Added

- **Rendered block source ranges.** Page HTML now carries `data-pb-src` half-open UTF-8 byte ranges on rendered Markdown blocks, including list items, escaped HTML blocks, and comments. HTML comment blocks now render inside a `<p>` carrier, which is a visible layout change.
- **Discussion reads.** The header's Discussions link opens the root inbox and standalone discussions. Pages also offer a Show discussions panel. Before upgrading, rename any configured root named `discussions`; that name is now reserved and will make the root configuration fail to load.
