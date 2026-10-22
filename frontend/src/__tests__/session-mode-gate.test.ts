// @vitest-environment node
import { existsSync, readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { ApiError } from "../api/client";
import { isBuiltin, isOff, isOffSession, isReader, isSessionKnown, isWorkspaceSession, signInRequired } from "../api/sessionMode";

const sourceRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const pattern = /auth_mode/;
const allowed = new Set(["api/sessionMode.ts", "api/types.ts"]);

function sourceFiles(dir: string): string[] {
  if (!existsSync(dir)) return [];
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return entry.name === "__tests__" || entry.name === "__fixtures__" ? [] : sourceFiles(full);
    return /\.(ts|tsx)$/.test(entry.name) && !/\.test\.(ts|tsx)$/.test(entry.name) ? [full] : [];
  });
}

const files = sourceFiles(sourceRoot);

describe("session mode identifier gate", () => {
  it("sees comparisons, destructuring, switches, indexed reads and comments", () => {
    const samples = [
      'session.auth_mode === "off"',
      "const { auth_mode } = session",
      "switch (session.data?.auth_mode) {",
      '["off"].includes(s["auth_mode"])',
      '// auth_mode !== "builtin"',
    ];
    samples.forEach((sample) => {
      expect(pattern.test(sample), `sample ${JSON.stringify(sample)} must be visible to the gate`).toBe(true);
    });
  });

  it("does not match the authentication flag", () => {
    expect(pattern.test("session.authenticated")).toBe(false);
  });

  it("scans a known, non-trivial production source population", () => {
    expect(files.length).toBeGreaterThan(40);
    for (const file of ["router.tsx", "components/Shell.tsx", "components/PageView.tsx", "components/DiscussionThread.tsx"]) {
      expect(files).toContain(path.join(sourceRoot, file));
    }
  });

  it("keeps the auth mode identifier inside the session module and wire types, including comments", () => {
    const violations = files.flatMap((file) => {
      const relative = path.relative(sourceRoot, file);
      if (allowed.has(relative)) return [];
      return readFileSync(file, "utf8").split(/\r?\n/).flatMap((line, index) =>
        pattern.test(line) ? [`${relative}:${index + 1}`] : []);
    });
    expect(violations, `auth mode identifiers found while scanning ${files.length} production source files`).toEqual([]);
  });
});

const predicates = { isReader, isBuiltin, isOff, signInRequired };

describe("session resolution", () => {
  it.each([
    { label: "cold pending", data: undefined, isPending: true, workspace: false },
    { label: "null pending", data: null, isPending: true, workspace: false },
    { label: "settled 404", data: undefined, error: new ApiError(404, "not_found", "No session"), isPending: false, workspace: true },
    { label: "settled 500", data: undefined, error: new ApiError(500, "unavailable", "No session"), isPending: false, workspace: false },
    { label: "settled transport error", data: undefined, error: new TypeError("Connection lost"), isPending: false, workspace: false },
    { label: "reader", data: { auth_mode: "reader" }, isPending: false, workspace: false },
    { label: "OFF", data: { auth_mode: "off" }, isPending: false, workspace: true },
    { label: "builtin", data: { auth_mode: "builtin" }, isPending: false, workspace: true },
    { label: "unknown mode", data: { auth_mode: "future" }, isPending: false, workspace: true },
    { label: "404 settlement retained during refetch", data: undefined, error: new ApiError(404, "not_found", "No session"), isPending: true, isFetched: true, workspace: true },
    { label: "500 settlement retained during refetch", data: undefined, error: new ApiError(500, "unavailable", "No session"), isPending: true, isFetched: true, workspace: false },
    { label: "refetch without settlement evidence", data: undefined, isPending: true, isFetched: true, workspace: false },
    { label: "settled data, refetching", data: { auth_mode: "off" }, isPending: false, isFetched: true, workspace: true },
  ])("handles $label", ({ data, error, isPending, isFetched, workspace }) => {
    const session = { data, error: error ?? null, isPending, isFetched: isFetched ?? (data != null || !isPending) };
    expect(isWorkspaceSession(session)).toBe(workspace);
    expect(isSessionKnown(session)).toBe(data != null || workspace);
    expect(isOffSession(session)).toBe(data?.auth_mode === "off" || data == null && error instanceof ApiError && error.isNotFound);
  });
});
type SessionCase = {
  label: string;
  session: Parameters<typeof isReader>[0];
  isReader: boolean;
  isBuiltin: boolean;
  isOff: boolean;
  signInRequired: boolean;
};

const cases: SessionCase[] = [
  { label: "reader, authenticated", session: { auth_mode: "reader", authenticated: true },
    isReader: true, isBuiltin: false, isOff: false, signInRequired: false },
  { label: "reader, unauthenticated", session: { auth_mode: "reader", authenticated: false },
    isReader: true, isBuiltin: false, isOff: false, signInRequired: false },
  { label: "builtin, authenticated", session: { auth_mode: "builtin", authenticated: true },
    isReader: false, isBuiltin: true, isOff: false, signInRequired: false },
  { label: "builtin, unauthenticated", session: { auth_mode: "builtin", authenticated: false },
    isReader: false, isBuiltin: true, isOff: false, signInRequired: true },
  { label: "proxy, authenticated", session: { auth_mode: "proxy", authenticated: true },
    isReader: false, isBuiltin: false, isOff: false, signInRequired: false },
  { label: "proxy, unauthenticated", session: { auth_mode: "proxy", authenticated: false },
    isReader: false, isBuiltin: false, isOff: false, signInRequired: true },
  { label: "off, authenticated", session: { auth_mode: "off", authenticated: true },
    isReader: false, isBuiltin: false, isOff: true, signInRequired: false },
  { label: "off, unauthenticated", session: { auth_mode: "off", authenticated: false },
    isReader: false, isBuiltin: false, isOff: true, signInRequired: false },
  { label: "undefined", session: undefined,
    isReader: false, isBuiltin: false, isOff: false, signInRequired: false },
  { label: "null", session: null,
    isReader: false, isBuiltin: false, isOff: false, signInRequired: false },
  { label: "unknown mode, unauthenticated", session: { auth_mode: "x", authenticated: false },
    isReader: false, isBuiltin: false, isOff: false, signInRequired: false },
];

describe("session mode predicates", () => {
  for (const name of ["isReader", "isBuiltin", "isOff", "signInRequired"] as const) {
    it.each(cases)(`${name}($label)`, (row) => {
      expect(predicates[name](row.session)).toBe(row[name]);
    });
  }
});
