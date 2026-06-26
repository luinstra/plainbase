import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import { clearCsrfToken } from "../api/csrf";
import { approveChange, rebaseChange, rejectChange } from "../api/proposals";

/**
 * PB-PROPOSE-1 decision client (api/proposals.ts). Mocked fetch asserts the wire contract pinned by F4: approve
 * and rebase POST with NO request body; reject POSTs the reviewer `{comment}`. All three ride the shared CSRF
 * header (sourced from `/api/v1/session`) and throw [ApiError] carrying the envelope `code` on a non-2xx.
 */

const ID = "0197a3f2-8c4d-7e91-b3a2-4f8e9d1c6b5a";

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

interface Call {
  url: string;
  init: RequestInit;
}

/** Stubs fetch: `/session` yields the CSRF token; every other call is recorded and answered by [respond]. */
function stub(respond: (url: string, init: RequestInit) => Response) {
  const calls: Call[] = [];
  const spy = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input.toString();
    if (url === "/api/v1/session") {
      return jsonResponse({ authenticated: true, username: "admin", csrf_token: "csrf-xyz", auth_mode: "builtin" });
    }
    calls.push({ url, init: init ?? {} });
    return respond(url, init ?? {});
  });
  vi.stubGlobal("fetch", spy);
  return { calls };
}

beforeEach(() => clearCsrfToken());
afterEach(() => vi.unstubAllGlobals());

describe("api/proposals decision client", () => {
  it("approveChange POSTs with NO body and the CSRF header, returning the ApplyResult", async () => {
    const { calls } = stub(() => jsonResponse({ new_hash: "sha256:abc", commit_sha: "c1", applied_at: "2026-06-26T00:00:00Z", warnings: null }));

    const result = await approveChange(ID);

    expect(result.new_hash).toBe("sha256:abc");
    const call = calls.find((c) => c.url.endsWith(`/changes/${ID}/approve`));
    expect(call).toBeDefined();
    expect(call!.init.method).toBe("POST");
    expect(call!.init.body).toBeUndefined(); // F4: approve sends no body
    expect((call!.init.headers as Record<string, string>)["X-CSRF-Token"]).toBe("csrf-xyz");
  });

  it("rejectChange POSTs the {comment} JSON body", async () => {
    const { calls } = stub(() => jsonResponse({ id: ID, status: "REJECTED" }));

    await rejectChange(ID, "not yet");

    const call = calls.find((c) => c.url.endsWith(`/changes/${ID}/reject`));
    expect(call).toBeDefined();
    expect(call!.init.method).toBe("POST");
    expect(call!.init.body).toBe(JSON.stringify({ comment: "not yet" }));
  });

  it("rejectChange sends comment:null when there is no comment", async () => {
    const { calls } = stub(() => jsonResponse({ id: ID, status: "REJECTED" }));

    await rejectChange(ID, null);

    const call = calls.find((c) => c.url.endsWith(`/changes/${ID}/reject`));
    expect(call!.init.body).toBe(JSON.stringify({ comment: null }));
  });

  it("rebaseChange POSTs with NO body and returns the rebased delta", async () => {
    const { calls } = stub(() => jsonResponse({ new_base_hash: "sha256:def", unified_diff: "@@", status: "PENDING" }));

    const result = await rebaseChange(ID);

    expect(result.status).toBe("PENDING");
    const call = calls.find((c) => c.url.endsWith(`/changes/${ID}/rebase`));
    expect(call!.init.method).toBe("POST");
    expect(call!.init.body).toBeUndefined();
  });

  it("throws ApiError carrying the envelope code on a 409 conflicted approve", async () => {
    stub(() => jsonResponse({ error: { code: "conflicted", message: "the base drifted" } }, 409));

    await expect(approveChange(ID)).rejects.toMatchObject({ status: 409, code: "conflicted" });
    await expect(approveChange(ID)).rejects.toBeInstanceOf(ApiError);
  });

  it("throws ApiError(403) so the caller can flip the no-access state", async () => {
    stub(() => jsonResponse({ error: { code: "forbidden", message: "nope" } }, 403));

    await expect(approveChange(ID)).rejects.toMatchObject({ status: 403 });
  });
});
