import { afterEach, describe, expect, it, vi } from "vitest";
import { createPage, putPageRaw } from "../api/client";

/**
 * FIX 4 (dual-model review): a `fetch` that rejects (offline/timeout → TypeError) must surface as the
 * typed error variant of the write unions — never an unhandled rejection that leaves "Saving…" stuck.
 */
const HASH = "sha256:0000000000000000000000000000000000000000000000000000000000000000";

afterEach(() => vi.unstubAllGlobals());

describe("write client network-error handling", () => {
  it("putPageRaw returns a typed transient error when fetch rejects (does not throw)", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new TypeError("Failed to fetch");
      }),
    );
    const result = await putPageRaw("id", "buffer", HASH);
    expect(result.kind).toBe("error");
    if (result.kind === "error") {
      expect(result.error.status).toBe(503);
      expect(result.error.message).toContain("Couldn't reach the server");
    }
  });

  it("createPage returns a typed transient error when fetch rejects (does not throw)", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new TypeError("Failed to fetch");
      }),
    );
    const result = await createPage({ title: "X" });
    expect(result.kind).toBe("error");
    if (result.kind === "error") {
      expect(result.error.status).toBe(503);
    }
  });
});
