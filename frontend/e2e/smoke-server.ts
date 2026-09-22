import { spawn, type ChildProcessByStdio } from "node:child_process";
import { cpSync, existsSync, mkdirSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { mkdtemp } from "node:fs/promises";
import { createServer } from "node:net";
import type { Readable } from "node:stream";
import path from "node:path";
import { fileURLToPath } from "node:url";

const frontendDir = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const repoRoot = path.dirname(frontendDir);
const binary = path.join(repoRoot, "server", "build", "install", "plainbase", "bin", "plainbase");
const startupTimeoutMs = 60_000;
const gracefulShutdownMs = 5_000;
const forcedShutdownMs = 2_000;
const credentialOutputLimit = 64 * 1024;

export type SmokeSeed = { setupToken: string; agentToken: string };

export type SmokeScenario = {
  authMode: "off" | "builtin";
  roots: "single" | "multi" | "multi-missing";
};

export type SmokeServer = {
  baseURL: string;
  seed?: SmokeSeed;
  stop: () => Promise<void>;
};

export type SmokeServerOptions = {
  port?: number;
  onAttemptCreated?: (attemptRoot: string) => void;
};

type LogBuffer = {
  stdout: string;
  stderr: string;
};

type SmokeProcess = ChildProcessByStdio<null, Readable, Readable>;

type ProcessEvent =
  | { kind: "error" }
  | { kind: "exit"; code: number | null; signal: NodeJS.Signals | null };

type SmokeProcessState = {
  child: SmokeProcess;
  logs: LogBuffer;
  bindReady: Promise<void>;
  event: Promise<ProcessEvent>;
  exited: boolean;
  exitCode: number | null;
  signalCode: NodeJS.Signals | null;
  terminated: Promise<void>;
};

type CredentialProcessResult = {
  code: number | null;
  signal: NodeJS.Signals | null;
  timedOut: boolean;
  outputExceeded: boolean;
};

class CredentialCommandFailure extends Error {
  constructor(message: string) {
    super(message);
    this.name = "CredentialCommandFailure";
  }
}

/** Starts one owned server attempt against fresh fixture state. */
export async function startSmokeServer(
  scenario: SmokeScenario,
  logPath: string,
  options: SmokeServerOptions = {},
): Promise<SmokeServer> {
  const startupDeadline = Date.now() + startupTimeoutMs;
  if (!existsSync(binary)) {
    throw new Error(`Missing ${binary}; run ./gradlew :server:installDist first.`);
  }

  let runRoot: string | undefined;
  let logs: LogBuffer = { stdout: "", stderr: "" };
  let processState: SmokeProcessState | undefined;
  let stop: (() => Promise<void>) | undefined;

  try {
    const runsDir = path.join(frontendDir, ".smoke-runs");
    mkdirSync(runsDir, { recursive: true });
    runRoot = realpathSync(await mkdtemp(path.join(runsDir, "attempt-")));
    options.onAttemptCreated?.(runRoot);
    const dataDir = path.join(runRoot, "data");
    const contentDir = path.join(runRoot, "content");
    const extraDir = path.join(runRoot, "extra");
    mkdirSync(dataDir, { recursive: true });
    cpSync(path.join(repoRoot, "fixtures", "demo-docs"), contentDir, { recursive: true });
    mkdirSync(path.join(contentDir, "diagrams"), { recursive: true });
    writeFileSync(path.join(contentDir, "diagrams", "standalone.mmd"), "flowchart LR\n  A[Standalone] --> B[Mermaid]\n");
    writeFileSync(
      path.join(contentDir, "diagrams", "bom-crlf.mmd"),
      Buffer.from([0xef, 0xbb, 0xbf, ...Buffer.from("flowchart LR\r\n  A[BOM] --> B[CRLF]\r\n", "utf8")]),
    );
    writeFileSync(path.join(contentDir, "diagrams", "space name!'().mmd"), "flowchart LR\n  A[Encoded] --> B[Name]\n");
    writeFileSync(path.join(contentDir, "diagrams", "fallback.mmd"), "not a diagram <script>alert('xss')</script>\r\n");
    writeFileSync(path.join(contentDir, "diagrams", "shared.mmd"), "flowchart LR\n  A[Docs] --> B[Root]\n");

    const permalinkFixture = path.join(frontendDir, "e2e", "fixtures", "permalink");
    cpSync(permalinkFixture, path.join(contentDir, "permalink"), { recursive: true });
    if (scenario.roots !== "single") {
      if (scenario.roots === "multi") {
        cpSync(path.join(repoRoot, "fixtures", "demo-docs", "guides"), path.join(extraDir, "guides"), { recursive: true });
        cpSync(permalinkFixture, path.join(extraDir, "permalink"), { recursive: true });
        mkdirSync(path.join(extraDir, "diagrams"), { recursive: true });
        writeFileSync(path.join(extraDir, "diagrams", "shared.mmd"), "flowchart LR\n  A[Extra] --> B[Root]\n");
      }
      writeFileSync(
        path.join(dataDir, "plainbase.conf"),
        [
          "roots {",
          `  docs { path = ${JSON.stringify(contentDir)} }`,
          `  extra { path = ${JSON.stringify(extraDir)}, editable = true }`,
          "}",
          "",
        ].join("\n"),
      );
    }

    const port = options.port ?? (await allocatePort());
    const env: NodeJS.ProcessEnv = {
      ...process.env,
      CONTENT_DIR: contentDir,
      DATA_DIR: dataDir,
      PLAINBASE_AUTH_MODE: scenario.authMode,
      PLAINBASE_GIT_ENABLED: "true",
      PLAINBASE_HOST: "127.0.0.1",
      PLAINBASE_LOG_LEVEL: "INFO",
      PLAINBASE_PORT: String(port),
    };

    const seed = scenario.authMode === "builtin" ? await mintSeed(env, startupDeadline) : undefined;
    const serverProcess = spawn(binary, ["serve"], { env, stdio: ["ignore", "pipe", "pipe"] });
    processState = trackProcess(serverProcess, port, logs);
    logs = processState.logs;
    stop = createStopper(processState, runRoot, logPath);

    const baseURL = `http://127.0.0.1:${port}`;
    await waitForOwnedBind(processState, logs, startupDeadline);
    await waitForHealth(processState, baseURL, logs, startupDeadline);
    return { baseURL, seed, stop };
  } catch (error) {
    const cleanupErrors: unknown[] = [];
    try {
      if (stop) {
        await stop();
      } else {
        try {
          writeServerLog(logPath, logs);
        } catch (logError) {
          cleanupErrors.push(logError);
        }
        if (runRoot) {
          try {
            rmSync(runRoot, { recursive: true, force: true });
          } catch (cleanupError) {
            cleanupErrors.push(cleanupError);
          }
        }
      }
    } catch (cleanupError) {
      cleanupErrors.push(cleanupError);
    }
    if (cleanupErrors.length > 0) {
      throw new AggregateError([error, ...cleanupErrors], "Smoke server startup and cleanup failed");
    }
    throw error;
  }
}

async function allocatePort(): Promise<number> {
  const probe = createServer();
  await new Promise<void>((resolve, reject) => {
    probe.once("error", reject);
    probe.listen(0, "127.0.0.1", () => resolve());
  });
  const address = probe.address();
  const port = typeof address === "object" && address ? address.port : undefined;
  await new Promise<void>((resolve, reject) => probe.close((error) => (error ? reject(error) : resolve())));
  if (!port) throw new Error("Could not allocate a loopback smoke-test port");
  return port;
}

async function mintSeed(env: NodeJS.ProcessEnv, deadline: number): Promise<SmokeSeed> {
  return {
    setupToken: await mintToken(env, ["admin", "setup-token"], "setup token", deadline),
    agentToken: await mintToken(env, ["admin", "mint-token", "smoke-agent", "propose"], "agent token", deadline),
  };
}

async function mintToken(env: NodeJS.ProcessEnv, args: string[], label: string, deadline: number): Promise<string> {
  let stdout: string;
  try {
    stdout = await runCredentialCommand(binary, args, env, remainingMs(deadline));
  } catch (error) {
    const detail = error instanceof CredentialCommandFailure ? error.message : "execute phase failed";
    throw new Error(`Could not mint the smoke server ${label}: ${detail}`);
  }
  try {
    return mintedToken(stdout);
  } catch {
    throw new Error(`Could not mint the smoke server ${label}: parse phase failed`);
  }
}

/** Runs one credential CLI without exposing its output or underlying error. */
export function runCredentialCommand(
  command: string,
  args: string[],
  env: NodeJS.ProcessEnv,
  timeout: number,
): Promise<string> {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { env, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let outputExceeded = false;
    let timedOut = false;
    let settled = false;
    let spawnFailed = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const finish = (result: CredentialProcessResult | { spawnFailed: true }) => {
      if (settled) return;
      settled = true;
      if (timer) clearTimeout(timer);
      if ("spawnFailed" in result) {
        reject(new CredentialCommandFailure("spawn phase failed"));
      } else if (result.timedOut) {
        reject(new CredentialCommandFailure(`timeout phase (SIGKILL sent; ${exitStatus(result)})`));
      } else if (result.outputExceeded) {
        reject(new CredentialCommandFailure("output-limit phase failed"));
      } else if (result.code !== 0 || result.signal !== null) {
        reject(new CredentialCommandFailure(`exit phase failed (${exitStatus(result)})`));
      } else {
        resolve(stdout);
      }
    };

    child.stdout.setEncoding("utf8");
    child.stdout.on("data", (chunk: string) => {
      if (outputExceeded) return;
      stdout += chunk;
      if (stdout.length > credentialOutputLimit) {
        outputExceeded = true;
        killCredentialChild(child);
      }
    });
    child.stderr.resume();
    child.once("error", () => {
      spawnFailed = true;
    });
    child.once("close", (code, signal) => {
      finish(spawnFailed ? { spawnFailed: true } : { code, signal, timedOut, outputExceeded });
    });
    timer = setTimeout(() => {
      timedOut = true;
      killCredentialChild(child);
    }, Math.max(1, timeout));
  });
}

function killCredentialChild(child: ReturnType<typeof spawn>): void {
  if (child.exitCode !== null || child.signalCode !== null) return;
  try {
    child.kill("SIGKILL");
  } catch {
    // The exit event remains the source of truth if the child won the kill race.
  }
}

function exitStatus(result: CredentialProcessResult): string {
  return `code=${result.code ?? "none"}, signal=${result.signal ?? "none"}`;
}

function mintedToken(stdout: string): string {
  const lines = stdout
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);
  const hint = lines.findIndex((line) => line.startsWith("store this now"));
  if (hint <= 0) throw new Error("Could not parse the smoke server credential output");
  return lines[hint - 1];
}

function trackProcess(child: SmokeProcess, port: number, initialLogs: LogBuffer): SmokeProcessState {
  const logs = initialLogs;
  const bindPattern = new RegExp(`Responding at http://127\\.0\\.0\\.1:${port}(?!\\d)`);
  let resolveBind!: () => void;
  let resolveEvent!: (event: ProcessEvent) => void;
  let resolveTerminated!: () => void;
  const state: SmokeProcessState = {
    child,
    logs,
    bindReady: new Promise((resolve) => {
      resolveBind = resolve;
    }),
    event: new Promise((resolve) => {
      resolveEvent = resolve;
    }),
    exited: false,
    exitCode: null,
    signalCode: null,
    terminated: new Promise((resolve) => {
      resolveTerminated = resolve;
    }),
  };

  const append = (stream: "stdout" | "stderr", chunk: string) => {
    logs[stream] += chunk;
    if (bindPattern.test(logs[stream])) resolveBind();
  };

  child.stdout.setEncoding("utf8");
  child.stderr.setEncoding("utf8");
  child.stdout.on("data", (chunk: string) => append("stdout", chunk));
  child.stderr.on("data", (chunk: string) => append("stderr", chunk));
  child.once("error", () => {
    if (child.pid === undefined) {
      state.exited = true;
      resolveTerminated();
    }
    resolveEvent({ kind: "error" });
  });
  child.once("exit", (code, signal) => {
    state.exited = true;
    state.exitCode = code;
    state.signalCode = signal;
    resolveTerminated();
    resolveEvent({ kind: "exit", code, signal });
  });
  return state;
}

async function waitForOwnedBind(
  processState: SmokeProcessState,
  logs: LogBuffer,
  deadline: number,
): Promise<void> {
  const timeout = remainingMs(deadline);
  const outcome = await raceWithTimeout<
    | { kind: "bound" }
    | { kind: "process"; event: ProcessEvent }
    | { kind: "timeout" }
  >([
    processState.bindReady.then(() => ({ kind: "bound" as const })),
    processState.event.then((event) => ({ kind: "process" as const, event })),
  ], timeout, { kind: "timeout" as const });
  if (outcome.kind === "bound") {
    if (!processEnded(processState)) return;
    throw startupFailure("owned server exited after binding before health readiness", logs);
  }
  if (outcome.kind === "process") {
    const reason = outcome.event.kind === "error" ? "owned server spawn failed" : "owned server exited before binding";
    throw startupFailure(reason, logs);
  }
  throw startupFailure("owned server did not bind before the startup deadline", logs);
}

async function waitForHealth(
  processState: SmokeProcessState,
  baseURL: string,
  logs: LogBuffer,
  deadline: number,
): Promise<void> {
  while (Date.now() < deadline) {
    if (processEnded(processState)) throw startupFailure("owned server exited before health readiness", logs);
    try {
      const response = await fetch(`${baseURL}/healthz`, {
        signal: AbortSignal.timeout(Math.min(1_000, remainingMs(deadline))),
      });
      if (response.ok && !processEnded(processState)) return;
    } catch {
      // The server may still be rebuilding its initial index; keep the bounded readiness poll going.
    }
    await delay(Math.min(100, Math.max(1, deadline - Date.now())));
  }
  throw startupFailure("owned server did not become healthy before the startup deadline", logs);
}

function createStopper(
  processState: SmokeProcessState,
  runRoot: string,
  logPath: string,
): () => Promise<void> {
  let stopPromise: Promise<void> | undefined;
  return async () => {
    stopPromise ??= stopOwnedProcess(processState, runRoot, logPath);
    return stopPromise;
  };
}

async function stopOwnedProcess(processState: SmokeProcessState, runRoot: string, logPath: string): Promise<void> {
  const errors: unknown[] = [];
  try {
    await terminate(processState);
  } catch (error) {
    errors.push(error);
  }
  try {
    writeServerLog(logPath, processState.logs);
  } catch (error) {
    errors.push(error);
  }
  if (processEnded(processState)) {
    try {
      rmSync(runRoot, { recursive: true, force: true });
    } catch (error) {
      errors.push(error);
    }
  } else {
    errors.push(new Error("Retaining smoke server data because owned process termination was not confirmed"));
  }
  if (errors.length > 0) {
    const tail = diagnosticTail(processState.logs);
    throw new AggregateError(errors, `Smoke server teardown failed${tail ? `\n${tail}` : ""}`);
  }
}

async function terminate(processState: SmokeProcessState): Promise<void> {
  if (processEnded(processState)) return;

  const errors: unknown[] = [];
  try {
    processState.child.kill("SIGTERM");
  } catch (error) {
    errors.push(error);
  }
  if (await waitForExit(processState, gracefulShutdownMs)) {
    if (errors.length > 0) throw new AggregateError(errors, "Smoke server graceful termination failed");
    return;
  }

  try {
    processState.child.kill("SIGKILL");
  } catch (error) {
    errors.push(error);
  }
  if (!(await waitForExit(processState, forcedShutdownMs))) {
    errors.push(new Error("Smoke server did not terminate in time"));
  }
  if (errors.length > 0) throw new AggregateError(errors, "Smoke server termination failed");
}

async function waitForExit(processState: SmokeProcessState, timeoutMs: number): Promise<boolean> {
  if (processEnded(processState)) return true;
  await raceWithTimeout([processState.terminated], timeoutMs, undefined);
  return processEnded(processState);
}

async function raceWithTimeout<T>(promises: Promise<T>[], timeoutMs: number, timeoutValue: T): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      ...promises,
      new Promise<T>((resolve) => {
        timer = setTimeout(() => resolve(timeoutValue), Math.max(1, timeoutMs));
      }),
    ]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

function processEnded(processState: SmokeProcessState): boolean {
  return (
    processState.exited ||
    processState.child.exitCode !== null ||
    processState.child.signalCode !== null
  );
}

function writeServerLog(logPath: string, logs: LogBuffer): void {
  mkdirSync(path.dirname(logPath), { recursive: true });
  writeFileSync(logPath, `--- stdout ---\n${logs.stdout}\n--- stderr ---\n${logs.stderr}`);
}

function startupFailure(reason: string, logs: LogBuffer): Error {
  const tail = diagnosticTail(logs);
  return new Error(`${reason}${tail ? `\n${tail}` : ""}`);
}

function diagnosticTail(logs: LogBuffer): string {
  return `${logs.stdout}\n${logs.stderr}`.trim().slice(-4_000);
}

function remainingMs(deadline: number): number {
  const remaining = deadline - Date.now();
  if (remaining <= 0) throw new Error("Smoke server startup deadline exceeded");
  return remaining;
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
