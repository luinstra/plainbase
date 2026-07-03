// Install Chromium only when the pinned revision is absent — `playwright install` is already a
// local no-op when present, but this removes the unconditional installer spawn from every run
// and makes the cold-path download explicit. CI pre-installs + caches (ci.yml frontend-smoke).
import { existsSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { createRequire } from "node:module";
import { chromium } from "@playwright/test";

const executable = chromium.executablePath();
if (existsSync(executable)) {
  console.log(`chromium present (${executable}) — skipping install`);
} else {
  console.log("chromium missing — running playwright install chromium");
  // Resolve the CLI through module resolution (the very file `node_modules/.bin/playwright` links
  // to) and run it under this node — never a bare `playwright` on PATH, which breaks for a direct
  // `node scripts/…` invocation and ENOENTs on Windows.
  const cli = createRequire(import.meta.url).resolve("@playwright/test/cli.js");
  execFileSync(process.execPath, [cli, "install", "chromium"], { stdio: "inherit" });
}
