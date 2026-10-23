# Harness compatibility

Compatibility evidence checked **2026-10-23**. Recheck these upstream sources when changing loaders
or upgrading harnesses. Project rules live in [AGENTS.md](../../AGENTS.md); the supporting Markdown
documents use ordinary links and explicit read instructions, not a harness-specific import mechanism.

## Claude Code

Direct `AGENTS.md` support began in **2.1.277**; the locally checked CLI was **2.1.308**. Default
fallback loads ancestor/current `AGENTS.md` files only when no ancestor/current `CLAUDE.md`,
`.claude/CLAUDE.md` or `CLAUDE.local.md` exists. Global `~/.claude/CLAUDE.md` does not suppress it.
Nested `AGENTS.md` loads on Read when that directory has none of those CLAUDE variants.

The user-level Project instructions setting can load both formats. Older clients, disabled support,
and some upgrade sessions need a CLAUDE import. This repository therefore retains **only `@AGENTS.md`
in `CLAUDE.md`**: it preserves compatibility and works in nested worktrees with ancestor CLAUDE files.
Claude documents import deduplication. Edit the canonical file, not a second policy copy. No setting
changes are required.

Direct AGENTS loading differs from a CLAUDE import for `InstructionsLoaded` hooks, additional-directory
loading and external imports. Check `/context` and the Project instructions setting if guidance is
missing. Do not assume `AGENTS.override.md` works in Claude.

Source: [Claude Code memory and instruction loading](https://code.claude.com/docs/en/memory#agentsmd).

## Codex

Codex builds the project chain from the repository root down to its starting working directory.
Within each directory, `AGENTS.override.md` precedes `AGENTS.md`, followed by configured fallback
names; it uses at most one file per directory. Global instructions load separately. The default
combined project limit is 32 KiB. Restart in the intended directory after instruction changes;
do not assume an arbitrary descendant file was loaded at session start.

This repository does not need custom fallback names or global configuration. The root entry point
explicitly directs the agent to read the applicable supporting documents.

Source: [OpenAI AGENTS.md guide](https://learn.chatgpt.com/docs/agent-configuration/agents-md).

## Pi

Pi loads context from its agent directory and the current directory's ancestors. Its documented
context filenames include `AGENTS.override.md`, `AGENTS.md` and `CLAUDE.md`. The locally checked
**1.3.0** loader selects `AGENTS.md` before `CLAUDE.md`, with an override taking precedence within
the same directory. Context discovery does not require project trust. `/reload` refreshes instructions.

Keep the explicit read links in the root file; do not assume descendant guidance or Claude's `@path`
import expansion in another harness. Pi's extensions and configured workflows determine which Crew
or delegation capabilities are available.

Sources: [Pi configuration](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/configuration.md),
[Pi context loader](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/src/core/resource-loader.ts).

## Other harnesses and maintenance

If a harness does not discover `AGENTS.md`, explicitly provide it as project context and follow its
read links. A Markdown link is a navigation instruction, not evidence that its target entered context.
Verify the loaded file list using the host's diagnostics; never launch a writing task just to test discovery.

The migration consolidated identical root AGENTS/CLAUDE policies and selected durable lessons from
repo-specific memory. It excluded personal settings, credentials, host paths, old build counts,
release/branch status and obsolete tooling workarounds. No nested instruction files existed in the
audited source checkout outside separate worktrees. Future nested policies need explicit scope and
loader verification; avoid introducing divergent copies for each harness.
