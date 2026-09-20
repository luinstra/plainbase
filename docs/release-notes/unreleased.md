# Unreleased

## Added

- **Embedded Mermaid diagrams.** Markdown pages and editor previews render supported lowercase
  `mermaid` fences in the browser with light/dark theme support. Source remains authoritative and
  visible when a diagram cannot be rendered.

The frontend pins Mermaid's transitive `lodash-es` dependency to 4.18.1 to address
[GHSA-r5fr-rjxr-66jc](https://github.com/advisories/GHSA-r5fr-rjxr-66jc) and
[GHSA-f23m-r3pf-42rh](https://github.com/advisories/GHSA-f23m-r3pf-42rh).

> Promotion: fold this entry into the next versioned `docs/release-notes/<x.y.z>.md` before release.
