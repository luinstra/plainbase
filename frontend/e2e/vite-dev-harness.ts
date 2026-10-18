import { spawn } from "node:child_process";
import { cpSync, existsSync, mkdirSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { mkdtemp } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { stripVTControlCharacters } from "node:util";
import { allocatePort } from "./smoke-server";
import { test as base } from "./smoke-fixtures";

const frontendDir = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const startupTimeoutMs = 60_000;
const gracefulShutdownMs = 5_000;
const forcedShutdownMs = 2_000;

export type ViteDev = { baseURL: string; runRoot: string; stop: () => Promise<void> };

export const test = base.extend<{ viteDev: ViteDev }>({
  viteDev: [
    async ({ smokeServer }, use, testInfo) => {
      const logPath = testInfo.outputPath("vite.log");
      let dev: ViteDev | undefined;
      try {
        dev = await startViteDev(smokeServer.baseURL, logPath);
        await use(dev);
      } finally {
        let teardownError: unknown;
        try {
          await dev?.stop();
          if (dev) {
            const removed = !existsSync(dev.runRoot);
            writeFileSync(testInfo.outputPath("vite-cleanup.json"), JSON.stringify({ runRoot: dev.runRoot, removed }, null, 2) + "\n");
            if (!removed) throw new Error("Owned Vite run root was not removed");
          }
        } catch (error) {
          teardownError = error;
        }
        try {
          if ((teardownError !== undefined || testInfo.status !== testInfo.expectedStatus) && existsSync(logPath)) {
            await testInfo.attach("vite-log", { path: logPath, contentType: "text/plain" });
          }
        } catch (error) {
          teardownError ??= error;
        }
        if (teardownError) throw teardownError;
      }
    },
    { timeout: 90_000 },
  ],
});

/** Runs the copied project config in an owned child without changing the worker's environment. */
export async function startViteDev(target: string, logPath: string): Promise<ViteDev> {
  const deadline = Date.now() + startupTimeoutMs;
  let runRoot: string | undefined;
  let child: ReturnType<typeof spawn> | undefined;
  let stdout = "";
  let stderr = "";
  let closed = false;
  let spawnError: Error | undefined;
  let exitStatus = "not started";
  let terminated = Promise.resolve();
  let stopPromise: Promise<void> | undefined;
  const startupAbort = new AbortController();

  const failure = (reason: string) => new Error(`${reason}\n${`${stdout}\n${stderr}`.trim().slice(-4_000)}`);
  const stop = (): Promise<void> => {
    stopPromise ??= cleanup();
    return stopPromise;
  };

  async function waitForExit(timeoutMs: number): Promise<boolean> {
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      await Promise.race([
        terminated,
        new Promise<void>((resolve) => { timer = setTimeout(resolve, timeoutMs); }),
      ]);
      return closed;
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  async function cleanup(): Promise<void> {
    const errors: unknown[] = [];
    startupAbort.abort();
    if (child && !closed) {
      try {
        child.kill("SIGTERM");
      } catch (error) {
        errors.push(error);
      }
      if (!(await waitForExit(gracefulShutdownMs))) {
        try {
          child.kill("SIGKILL");
        } catch (error) {
          errors.push(error);
        }
        if (!(await waitForExit(forcedShutdownMs))) errors.push(failure("Vite child did not terminate in time"));
      }
    }
    try {
      mkdirSync(path.dirname(logPath), { recursive: true });
      writeFileSync(logPath, [
        `pid=${child?.pid ?? "none"}`,
        `runRoot=${runRoot ?? "none"}`,
        `closed=${closed}`,
        `exit=${exitStatus}`,
        "--- stdout ---", stdout, "--- stderr ---", stderr,
      ].join("\n"));
    } catch (error) {
      errors.push(error);
    }
    if (!child || closed) {
      if (runRoot) {
        try {
          rmSync(runRoot, { recursive: true, force: true });
        } catch (error) {
          errors.push(error);
        }
      }
    } else {
      errors.push(failure("Retaining Vite run root because child termination was not confirmed"));
    }
    if (errors.length > 0) throw new AggregateError(errors, "Vite teardown failed");
  }

  try {
    const viteCli = path.join(frontendDir, "node_modules", "vite", "bin", "vite.js");
    if (!existsSync(viteCli)) throw new Error(`Missing Vite CLI at ${viteCli}`);
    const runsDir = path.join(frontendDir, ".smoke-runs");
    mkdirSync(runsDir, { recursive: true });
    runRoot = await mkdtemp(path.join(runsDir, "dev-"));
    runRoot = realpathSync(runRoot);
    for (const entry of ["index.html", "package.json", "tsconfig.json", "vite.config.ts", "src", "plugins", "public"]) {
      cpSync(path.join(frontendDir, entry), path.join(runRoot, entry), { recursive: true });
    }
    const port = await allocatePort();
    const wrapperPath = path.join(runRoot, "vite.harness.config.ts");
    writeFileSync(wrapperPath, [
      'import project from "./vite.config.ts";',
      'import type { UserConfig } from "vite";',
      "",
      "const config: UserConfig = project;",
      'if (!config.server?.proxy) throw new Error("Project config must define server.proxy");',
      "const target = process.env.PB_SMOKE_VITE_TARGET!;",
      "",
      "export default {",
      "  ...config,",
      `  root: ${JSON.stringify(runRoot)},`,
      `  cacheDir: ${JSON.stringify(path.join(runRoot, ".vite"))},`,
      "  server: {",
      "    ...config.server,",
      '    host: "127.0.0.1",',
      "    port: Number(process.env.PB_SMOKE_VITE_PORT),",
      "    strictPort: true,",
      "    fs: {",
      "      ...config.server.fs,",
      `      allow: ${JSON.stringify([runRoot, path.join(frontendDir, "node_modules")])},`,
      "    },",
      "    proxy: Object.fromEntries(Object.entries(config.server.proxy).map(([key, value]) => [",
      '      key, { ...(typeof value === "string" ? {} : value), target },',
      "    ])),",
      "  },",
      "};",
      "",
    ].join("\n"));
    child = spawn(process.execPath, [viteCli, "--config", wrapperPath], {
      cwd: runRoot,
      env: { ...process.env, NO_COLOR: "1", PB_SMOKE_VITE_TARGET: target, PB_SMOKE_VITE_PORT: String(port) },
      stdio: ["ignore", "pipe", "pipe"],
    });
    child.stdout!.setEncoding("utf8");
    child.stderr!.setEncoding("utf8");
    child.stdout!.on("data", (chunk: string) => { stdout += chunk; });
    child.stderr!.on("data", (chunk: string) => { stderr += chunk; });
    terminated = new Promise<void>((resolve) => {
      child!.once("error", (error) => {
        spawnError = error;
        stderr += `\n${error.message}`;
        if (child!.pid === undefined) {
          closed = true;
          exitStatus = "spawn failed";
          resolve();
        }
        startupAbort.abort();
      });
      child!.once("close", (code, signal) => {
        closed = true;
        exitStatus = `code=${code ?? "none"}, signal=${signal ?? "none"}`;
        startupAbort.abort();
        resolve();
      });
    });
    const baseURL = `http://127.0.0.1:${port}`;
    while (Date.now() < deadline) {
      if (closed || spawnError) throw failure("Vite child ended before readiness");
      let ready = false;
      try {
        const response = await fetch(`${baseURL}/`, {
          headers: { Accept: "text/html" },
          signal: AbortSignal.any([startupAbort.signal, AbortSignal.timeout(Math.max(1, Math.min(1_000, deadline - Date.now())))]),
        });
        ready = response.status === 200 && (await response.text()).includes('<div id="root">');
      } catch {
        // The owned child may still be loading its project config and optimizing dependencies.
      }
      if (closed || spawnError) throw failure("Vite child ended before readiness");
      const plainStdout = stripVTControlCharacters(stdout);
      if (ready && plainStdout.includes(`${baseURL}/`)) return { baseURL, runRoot, stop };
      await new Promise<void>((resolve) => setTimeout(resolve, 100));
    }
    throw failure("Vite child did not become ready before the startup deadline");
  } catch (error) {
    try {
      await stop();
    } catch (cleanupError) {
      throw new AggregateError([error, cleanupError], "Vite startup and cleanup failed");
    }
    throw error;
  }
}
