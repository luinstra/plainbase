# Fixture tree notes

`demo-docs/` is the bundled demo content served by the compose quickstart and
used as the test fixture tree. Everything inside it is *fixture content* — its
own `README.md` is a deliberately frontmatter-less page, not documentation
about the fixtures. Meta-notes live here instead.

## Unicode normalization fixtures (NFD vs NFC)

Two filenames exercise Unicode normalization handling:

- `demo-docs/notes/café-notes.md` — committed **NFC** (precomposed `é`,
  bytes `0xC3 0xA9`).
- `demo-docs/notes/réunion.md` — committed **NFD** (`e` + combining acute
  U+0301, bytes `0x65 0xCC 0x81`). Verified in the git tree with
  `git ls-files -z -- 'fixtures/**' | xxd`.

**WARNING — NFD filenames may not survive git round-trips across platforms.**
macOS (APFS/HFS+) tends to normalize names on creation, `core.precomposeunicode`
rewrites paths at the git boundary, and Linux filesystems store whatever bytes
they're given. A clone/checkout cycle on a different OS (or with different git
config) can silently convert the committed NFD path to NFC, making any test
that relies on the *committed* form pass or fail depending on the machine that
checked it out.

**Rule: normalization tests MUST create NFD names at runtime** (e.g.
`"réunion.md"` written into a temp directory) instead of trusting the
committed fixture path. The committed NFD file is a convenience for manual
demos only — never a test invariant.
