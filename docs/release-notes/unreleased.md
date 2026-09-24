# Unreleased

## Added

- **Rendered block source ranges.** Page HTML now carries `data-pb-src` half-open UTF-8 byte ranges on rendered Markdown blocks, including list items, escaped HTML blocks, and comments. HTML comment blocks now render inside a `<p>` carrier, which is a visible layout change.
