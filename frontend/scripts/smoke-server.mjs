// Boots the installed Plainbase distribution against the fixture tree for the Playwright
// smoke flow (playwright.config.ts webServer). A fresh DATA_DIR per run keeps id
// adoption state out of the repo and the runs repeatable.
//
// W6 adds the first WRITE-side smoke (edit→save, create), so the server runs against a fresh
// COPY of fixtures/demo-docs — never the committed tree directly — so a save can't dirty the
// repo (the same isolation the JVM WriteRestHarness gives the write-route tests).
//
// P4 parameterizes this for TWO smoke servers (playwright.config.ts runs both): the default
// off-mode server (SMOKE_AUTH unset → auth.mode=off) serves the existing read/write specs, and a
// builtin ENFORCED server (SMOKE_AUTH=builtin, distinct port) serves the review.spec.ts approval
// flow (the ci-runs-auth-off-blind lesson — enforced-mode gates are otherwise invisible). The two
// servers get DISTINCT data/content dirs (suffixed by port) so their DataDirLocks never collide.
//
// Builtin seeding uses EXISTING CLI subcommands ONLY (no server-side test hook): `admin setup-token`
// mints the first-admin bootstrap token, `admin mint-token … propose` mints the agent token. Both run
// SYNCHRONOUSLY (execFileSync) with the SAME env as `serve` and BEFORE it, so each releases the
// DataDirLock before the next acquires it and before the server starts. The tokens land in
// DATA_DIR/seed.json for the spec to read.
import { execFileSync, spawn } from "node:child_process";
import { cpSync, existsSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const frontendDir = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const repoRoot = path.dirname(frontendDir);

const binary = path.join(repoRoot, "server", "build", "install", "plainbase", "bin", "plainbase");
if (!existsSync(binary)) {
  console.error(`Missing ${binary}\nRun ./gradlew :server:installDist first (or use ./gradlew :frontend:smokeTest).`);
  process.exit(1);
}

const port = process.env.SMOKE_PORT ?? "4378";
const authMode = process.env.SMOKE_AUTH ?? "off";

// Distinct dirs per port so the two smoke servers never contend for the same DataDirLock.
const dataDir = path.join(frontendDir, `.smoke-data-${port}`);
rmSync(dataDir, { recursive: true, force: true });
mkdirSync(dataDir, { recursive: true });

// A throwaway copy of the fixture content tree (a SIBLING of DATA_DIR, never nested) — write
// smoke tests mutate THIS, not the committed repo tree.
const contentDir = path.join(frontendDir, `.smoke-content-${port}`);
rmSync(contentDir, { recursive: true, force: true });
cpSync(path.join(repoRoot, "fixtures", "demo-docs"), contentDir, { recursive: true });

const env = {
  ...process.env,
  CONTENT_DIR: contentDir,
  DATA_DIR: dataDir,
  PLAINBASE_HOST: "127.0.0.1",
  PLAINBASE_PORT: port,
  // W7 master criterion 6 (history.spec.ts): force Git mode on so /history returns git_enabled:true.
  // The .smoke-content copy carries NO .git, so prepare() runs `git init` but seeds NO commit — the
  // repo boots with an EMPTY commit list and the FIRST save creates the first commit (MF-4). P4's
  // approve-on-apply also needs Git so the apply commits (agent author + admin committer).
  PLAINBASE_GIT_ENABLED: "true",
  // P4: an ENFORCED builtin server for the review flow; off otherwise (the default smoke behavior).
  ...(authMode === "builtin" ? { PLAINBASE_AUTH_MODE: "builtin" } : {}),
};

if (authMode === "builtin") {
  // Seed SYNCHRONOUSLY with the SAME env so setup-token sees auth.mode=builtin and both act on the
  // SAME store as serve; each releases the DataDirLock on exit before the next runs.
  const setupToken = mintedToken(execFileSync(binary, ["admin", "setup-token"], { env, encoding: "utf8" }));
  const agentToken = mintedToken(execFileSync(binary, ["admin", "mint-token", "smoke-agent", "propose"], { env, encoding: "utf8" }));
  writeFileSync(path.join(dataDir, "seed.json"), JSON.stringify({ setupToken, agentToken }));
}

/**
 * Extracts a minted plaintext token from a CLI stdout that may be PRECEDED by migration/diagnostic log
 * lines (so the token is never reliably line 0). Both `setup-token` and `mint-token` print the plaintext on
 * the line immediately BEFORE the `store this now …` hint — pin to that.
 */
function mintedToken(stdout) {
  const lines = stdout.split("\n").map((line) => line.trim()).filter(Boolean);
  const hint = lines.findIndex((line) => line.startsWith("store this now"));
  // hint must be > 0 (the token is the line BEFORE the hint). hint === -1 (no hint line) or 0 (nothing
  // before it) means the CLI output format changed — fail LOUD with the captured stdout, not a silent
  // wrong line that surfaces downstream as an obscure 401.
  if (hint <= 0) {
    throw new Error(`Could not parse a minted token from CLI stdout (no 'store this now' hint line):\n${stdout}`);
  }
  return lines[hint - 1];
}

const child = spawn(binary, ["serve"], { stdio: "inherit", env });

child.on("exit", (code) => process.exit(code ?? 1));
for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => child.kill("SIGTERM"));
}
