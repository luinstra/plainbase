// @vitest-environment node
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
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
  it("runs only during a build", () => {
    const plugin = themeStylesheets();
    expect(plugin.apply).toBe("build");
    expect(plugin.transformIndexHtml).toBeUndefined();
    expect(plugin.configureServer).toBeUndefined();
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
