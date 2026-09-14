import { expect, test } from "./smoke-fixtures";
import { existsSync } from "node:fs";
import { createServer } from "node:http";
import { runCredentialCommand, startSmokeServer, type SmokeServer } from "./smoke-server";

test("a healthy foreign listener is never adopted and only its attempt is cleaned", async ({}, testInfo) => {
  test.setTimeout(90_000);
  const foreign = createServer((_, response) => {
    response.writeHead(200, { Connection: "close" });
    response.end("foreign");
  });
  await listen(foreign);
  const address = foreign.address();
  if (!address || typeof address === "string") throw new Error("foreign listener did not expose a port");

  let attemptRoot: string | undefined;
  let started: SmokeServer | undefined;
  try {
    try {
      started = await startSmokeServer(
        { authMode: "off", roots: "single" },
        testInfo.outputPath("collision-server.log"),
        { port: address.port, onAttemptCreated: (root) => (attemptRoot = root) },
      );
    } catch {
      // The occupied port must fail before health can adopt the foreign server.
    }

    expect(started).toBeUndefined();
    expect(attemptRoot).toBeDefined();
    expect(existsSync(attemptRoot!)).toBe(false);
    const foreignResponse = await fetch(`http://127.0.0.1:${address.port}/healthz`);
    expect(foreignResponse.status).toBe(200);
    expect(await foreignResponse.text()).toBe("foreign");
  } finally {
    await started?.stop();
    await close(foreign);
  }
});

test("a timed-out credential child is hard-killed and reaped", async () => {
  const startedAt = Date.now();
  await expect(
    runCredentialCommand(
      process.execPath,
      ["-e", "process.on('SIGTERM', () => {}); setInterval(() => {}, 1000);"],
      process.env,
      100,
    ),
  ).rejects.toThrow(/timeout phase \(SIGKILL sent; code=.*signal=/);
  expect(Date.now() - startedAt).toBeLessThan(2_000);
});

function listen(server: ReturnType<typeof createServer>): Promise<void> {
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => resolve());
  });
}

function close(server: ReturnType<typeof createServer>): Promise<void> {
  return new Promise((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())));
}
