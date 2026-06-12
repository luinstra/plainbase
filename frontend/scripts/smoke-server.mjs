// Boots the installed Plainbase distribution against the fixture tree for the Playwright
// smoke flow (playwright.config.ts webServer). A fresh DATA_DIR per run keeps id
// adoption state out of the repo and the runs repeatable.
import { spawn } from "node:child_process";
import { existsSync, mkdirSync, rmSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const frontendDir = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const repoRoot = path.dirname(frontendDir);

const binary = path.join(repoRoot, "server", "build", "install", "plainbase", "bin", "plainbase");
if (!existsSync(binary)) {
  console.error(`Missing ${binary}\nRun ./gradlew :server:installDist first (or use ./gradlew :frontend:smokeTest).`);
  process.exit(1);
}

const dataDir = path.join(frontendDir, ".smoke-data");
rmSync(dataDir, { recursive: true, force: true });
mkdirSync(dataDir, { recursive: true });

const child = spawn(binary, ["serve"], {
  stdio: "inherit",
  env: {
    ...process.env,
    CONTENT_DIR: path.join(repoRoot, "fixtures", "demo-docs"),
    DATA_DIR: dataDir,
    PLAINBASE_HOST: "127.0.0.1",
    PLAINBASE_PORT: "4378",
  },
});

child.on("exit", (code) => process.exit(code ?? 1));
for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => child.kill("SIGTERM"));
}
