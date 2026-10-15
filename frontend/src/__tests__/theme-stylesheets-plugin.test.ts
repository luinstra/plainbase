// @vitest-environment node
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createServer, type ViteDevServer } from "vite";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { themeStylesheets } from "../../plugins/themeStylesheets";

const ownFile = fileURLToPath(import.meta.url);
const frontendRoot = path.resolve(path.dirname(ownFile), "../..");
const expectedSheets = [
  ["src/styles/themes/generic.css", "plainbase-theme-generic.css"],
  ["src/styles/themes/light.css", "_plainbase/plainbase-theme-light.css"],
  ["src/styles/themes/dark.css", "_plainbase/plainbase-theme-dark.css"],
] as const;

type EmittedSheet = { type: string; fileName: string; source: Buffer };
type HookHandler = (...args: unknown[]) => unknown;

function invokeHook(hook: unknown, context: unknown, ...args: unknown[]): unknown {
  const handler = typeof hook === "function"
    ? hook as HookHandler
    : (hook as { handler: HookHandler }).handler;
  return handler.apply(context, args);
}

function emittedSheets(): EmittedSheet[] {
  const plugin = themeStylesheets();
  const sheets: EmittedSheet[] = [];
  invokeHook(plugin.configResolved, {}, { root: frontendRoot });
  invokeHook(plugin.generateBundle, {
    emitFile(sheet: EmittedSheet) {
      sheets.push(sheet);
      return String(sheets.length);
    },
  });
  return sheets;
}

describe("theme stylesheet emission", () => {
  it("applies in both serve and build", () => {
    const plugin = themeStylesheets();
    expect(plugin.apply).toBeUndefined();
  });

  it("the HTML hook appends only the override link after built stylesheets", () => {
    const hook = themeStylesheets().transformIndexHtml;
    expect(hook).toMatchObject({ order: "post" });
    expect(invokeHook(hook, {})).toEqual([{
      tag: "link",
      attrs: { rel: "stylesheet", href: "/_plainbase/custom.css" },
      injectTo: "head",
    }]);
  });

  it("emits exactly the three resource names in source order", () => {
    expect(emittedSheets().map((sheet) => sheet.fileName)).toEqual(expectedSheets.map(([, fileName]) => fileName));
  });

  it("keeps canonical bytes as Buffer sources", () => {
    const sheets = emittedSheets();
    expectedSheets.forEach(([sourcePath, fileName], index) => {
      const sheet = sheets[index];
      expect(sheet.fileName).toBe(fileName);
      expect(Buffer.isBuffer(sheet.source)).toBe(true);
      expect(Buffer.compare(sheet.source, readFileSync(path.join(frontendRoot, sourcePath)))).toBe(0);
    });
  });

  it("uses asset emissions for every sheet", () => {
    expect(emittedSheets().map((sheet) => sheet.type)).toEqual(["asset", "asset", "asset"]);
  });
});

describe("generic stylesheet requests in Vite", () => {
  let server: ViteDevServer | undefined;
  let cacheDir: string | undefined;
  let baseURL: string;

  beforeAll(async () => {
    cacheDir = mkdtempSync(path.join(tmpdir(), "plainbase-theme-vite-"));
    // Vite releases its ephemeral-port probe before binding, so retry one bind conflict.
    for (let attempts = 1; attempts <= 2; attempts++) {
      server = await createServer({
        configFile: path.join(frontendRoot, "vite.config.ts"),
        root: frontendRoot,
        cacheDir,
        logLevel: "silent",
        server: { host: "127.0.0.1", port: 0, watch: null, hmr: false, preTransformRequests: false },
        optimizeDeps: { noDiscovery: true },
      });
      try {
        await server.listen();
        break;
      } catch (error) {
        await server.close();
        server = undefined;
        const portInUse = (error as NodeJS.ErrnoException)?.code === "EADDRINUSE"
          || (error instanceof Error && /^Port \d+ is already in use$/.test(error.message));
        if (!portInUse || attempts === 2) throw error;
      }
    }
    const address = server?.httpServer?.address();
    if (!address || typeof address === "string") throw new Error("Vite did not bind a TCP listener");
    baseURL = `http://127.0.0.1:${address.port}`;
  });

  afterAll(async () => {
    if (server) await server.close();
    if (cacheDir) rmSync(cacheDir, { recursive: true, force: true });
  });

  async function expectGenericStylesheet(response: Response): Promise<void> {
    const body = Buffer.from(await response.arrayBuffer());
    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toMatch(/^text\/css;\s*charset=utf-8$/i);
    expect(response.headers.get("cache-control")).toBe("no-cache");
    expect(response.headers.get("x-content-type-options")).toBe("nosniff");
    expect(response.headers.has("etag")).toBe(false);
    expect(response.headers.has("last-modified")).toBe(false);
    expect(Buffer.compare(body, readFileSync(path.join(frontendRoot, "src/styles/themes/generic.css")))).toBe(0);
  }

  it("serves canonical generic CSS for a browser stylesheet Accept header", async () => {
    await expectGenericStylesheet(await fetch(`${baseURL}/plainbase-theme-generic.css`, {
      headers: { Accept: "text/css,*/*;q=0.1" },
    }));
  });

  it("serves canonical generic CSS for a wildcard Accept header", async () => {
    await expectGenericStylesheet(await fetch(`${baseURL}/plainbase-theme-generic.css`, {
      headers: { Accept: "*/*" },
    }));
  });

  it("ignores the query when serving the generic stylesheet", async () => {
    const response = await fetch(`${baseURL}/plainbase-theme-generic.css?v=1`, { headers: { Accept: "*/*" } });
    const body = Buffer.from(await response.arrayBuffer());
    expect(response.status).toBe(200);
    expect(Buffer.compare(body, readFileSync(path.join(frontendRoot, "src/styles/themes/generic.css")))).toBe(0);
  });

  it("serves the SPA fallback for a missing stylesheet", async () => {
    const response = await fetch(`${baseURL}/missing-stylesheet.css`, { headers: { Accept: "*/*" } });
    const body = await response.text();
    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toMatch(/^text\/html(?:;|$)/i);
    expect(body).toContain('<div id="root">');
  });

  it("delegates a path with a generic stylesheet suffix", async () => {
    const response = await fetch(`${baseURL}/plainbase-theme-generic.cssx`, { headers: { Accept: "*/*" } });
    await response.arrayBuffer();
    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toMatch(/^text\/html(?:;|$)/i);
  });

  it("delegates HEAD requests for the generic stylesheet", async () => {
    const response = await fetch(`${baseURL}/plainbase-theme-generic.css`, { method: "HEAD", headers: { Accept: "*/*" } });
    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toMatch(/^text\/html(?:;|$)/i);
  });
});
