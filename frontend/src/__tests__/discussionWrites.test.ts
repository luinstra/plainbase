import { afterEach, expect, it, vi } from "vitest";
import { addDiscussionComment, editDiscussionComment, retractDiscussionComment, purgeDiscussionComment,
  resolveDiscussion, reopenDiscussion, reattachDiscussion, previewDiscussionAnchor, startDiscussion } from "../api/discussions";
import { clearCsrfToken } from "../api/csrf";
import { commentValidation } from "../components/DiscussionComposer";

afterEach(() => { vi.unstubAllGlobals(); clearCsrfToken(); });

it("sends lifecycle POSTs with rooted encoded identities and only the action's request fields", async () => {
  const calls: { url: string; method: string | undefined; body: unknown; token: string | null }[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input) === "/api/v1/session") return Response.json({ csrf_token: "token" });
    calls.push({ url: String(input), method: init?.method, body: JSON.parse(String(init?.body)),
      token: new Headers(init?.headers).get("X-CSRF-Token") });
    return Response.json({ id: "thread/id", comment_id: null, commit: null });
  }));
  const anchor = { kind: "quote" as const, content_hash: "sha256:a", block_start: 0, block_end: 12, selected_text: "😀 passage" };
  await editDiscussionComment("other root", "thread/id", "comment/id", "  **Markdown** 😀  ");
  await retractDiscussionComment("other root", "thread/id", "comment/id");
  await purgeDiscussionComment("other root", "thread/id", "comment/id");
  await resolveDiscussion("other root", "thread/id");
  await reopenDiscussion("other root", "thread/id");
  await reattachDiscussion("other root", "thread/id", anchor);
  await resolveDiscussion("docs", "thread/id");
  expect(calls).toEqual([
    { url: "/api/v1/discussions/thread%2Fid/comments/comment%2Fid/edit?root=other%20root", method: "POST", body: { body: "  **Markdown** 😀  " }, token: "token" },
    { url: "/api/v1/discussions/thread%2Fid/comments/comment%2Fid/retract?root=other%20root", method: "POST", body: {}, token: "token" },
    { url: "/api/v1/discussions/thread%2Fid/comments/comment%2Fid/purge?root=other%20root", method: "POST", body: {}, token: "token" },
    { url: "/api/v1/discussions/thread%2Fid/resolve?root=other%20root", method: "POST", body: {}, token: "token" },
    { url: "/api/v1/discussions/thread%2Fid/reopen?root=other%20root", method: "POST", body: {}, token: "token" },
    { url: "/api/v1/discussions/thread%2Fid/reattach?root=other%20root", method: "POST", body: { anchor }, token: "token" },
    { url: "/api/v1/discussions/thread%2Fid/resolve?root=docs", method: "POST", body: {}, token: "token" },
  ]);
});

it("treats an unexpected lifecycle success as uncertain without replaying it", async () => {
  let posts = 0;
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    if (String(input) === "/api/v1/session") return Response.json({ csrf_token: null });
    posts++;
    return Response.json({ id: "thread", comment_id: null, commit: null }, { status: 201 });
  }));
  await expect(resolveDiscussion("extra", "thread")).rejects.toThrow(/outcome/i);
  expect(posts).toBe(1);
});

it("uses the pre-write CSRF refresh for lifecycle writes but never retries ordinary denials or malformed success", async () => {
  let posts = 0;
  let reads = 0;
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    if (String(input) === "/api/v1/session") return Response.json({ csrf_token: ++reads === 1 ? "old" : "new" });
    return ++posts === 1 ? Response.json({ error: { code: "csrf_failed", message: "stale" } }, { status: 403 }) :
      Response.json({ id: "thread", comment_id: null, commit: null });
  }));
  await expect(resolveDiscussion("extra", "thread")).resolves.toEqual({ id: "thread", comment_id: null, commit: null });
  expect(posts).toBe(2); expect(reads).toBe(2);
  clearCsrfToken(); posts = 0;
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    if (String(input) === "/api/v1/session") return Response.json({ csrf_token: "valid" });
    posts++;
    return Response.json({ error: { code: "forbidden", message: "denied" } }, { status: 403 });
  }));
  await expect(purgeDiscussionComment("extra", "thread", "comment")).rejects.toMatchObject({ status: 403 });
  expect(posts).toBe(1);
  posts = 0;
  vi.stubGlobal("fetch", vi.fn(async () => { posts++; return Response.json({ id: null, comment_id: null, commit: null }); }));
  await expect(retractDiscussionComment("extra", "thread", "comment")).rejects.toThrow(/outcome/i);
  expect(posts).toBe(1);
});

it("sends rooted preview, start and reply JSON with the shared CSRF token", async () => {
  const calls: { url: string; body: unknown; token: string | null }[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url === "/api/v1/session") return Response.json({ csrf_token: "token" });
    calls.push({ url, body: JSON.parse(String(init?.body)), token: new Headers(init?.headers).get("X-CSRF-Token") });
    return url.includes("preview")
      ? Response.json({ content_hash: "sha256:a", byte_start: 0, byte_end: 5, selection: "snapped", quote_text: "whole" })
      : Response.json({ id: "thread", comment_id: null, commit: null }, { status: 201 });
  }));
  const quote = { kind: "quote" as const, content_hash: "sha256:a", block_start: 0, block_end: 5, selected_text: "part" };
  await previewDiscussionAnchor("other root", "page/id", quote);
  await startDiscussion("other root", "page/id", quote, "  Body 😀  ");
  await addDiscussionComment("other root", "thread/id", " Reply ");
  expect(calls).toEqual([
    { url: "/api/v1/pages/page%2Fid/discussions/anchor-preview?root=other%20root", body: quote, token: "token" },
    { url: "/api/v1/pages/page%2Fid/discussions?root=other%20root", body: { anchor: quote, body: "  Body 😀  " }, token: "token" },
    { url: "/api/v1/discussions/thread%2Fid/comments?root=other%20root", body: { body: " Reply " }, token: "token" },
  ]);
});

it("accepts nullable mutation fields but refuses a malformed successful identity", async () => {
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => String(input) === "/api/v1/session"
    ? new Response(null, { status: 404 }) : Response.json({ id: null, comment_id: null, commit: null }, { status: 201 })));
  await expect(startDiscussion("docs", "page", { kind: "page", content_hash: "sha256:a" }, "body"))
    .rejects.toThrow(/response|outcome/i);
});

it("retries only a CSRF failure once and preserves an ordinary denial", async () => {
  let sessionReads = 0;
  let posts = 0;
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    if (String(input) === "/api/v1/session") return Response.json({ csrf_token: ++sessionReads === 1 ? "stale" : "fresh" });
    posts++;
    return posts === 1 ? Response.json({ error: { code: "csrf_failed", message: "expired" } }, { status: 403 }) :
      Response.json({ id: "thread", comment_id: null, commit: null }, { status: 201 });
  }));
  await expect(addDiscussionComment("docs", "thread", "Reply")).resolves.toMatchObject({ id: "thread" });
  expect(posts).toBe(2);
  expect(sessionReads).toBe(2);

  clearCsrfToken();
  posts = 0;
  sessionReads = 0;
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    if (String(input) === "/api/v1/session") return Response.json({ csrf_token: "valid" });
    posts++;
    return Response.json({ error: { code: "forbidden", message: "denied" } }, { status: 403 });
  }));
  await expect(addDiscussionComment("docs", "thread", "Reply")).rejects.toMatchObject({ status: 403, code: "forbidden" });
  expect(posts).toBe(1);
});

it("refuses malformed Unicode and oversized UTF-8 without changing the draft", () => {
  const malformed = "keep\uD800this";
  const oversized = "😀".repeat(16_385);
  expect(commentValidation(malformed)).toMatch(/character/);
  expect(malformed).toBe("keep\uD800this");
  expect(commentValidation(oversized)).toMatch(/too long/);
  expect(commentValidation("  spaced 😀  ")).toBeNull();
});

it("keeps a safe status error when a proxy returns a malformed envelope", async () => {
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => String(input) === "/api/v1/session"
    ? new Response(null, { status: 404 }) : Response.json({ error: { code: 12, message: null,
      candidates: [{ root: "docs", url: "/docs/page" }, { root: "other", url: 3 }, { url: "/missing" }],
    } }, { status: 409 })));
  await expect(startDiscussion("docs", "page", { kind: "page", content_hash: "sha256:a" }, "body"))
    .rejects.toMatchObject({ status: 409, code: "unknown_error", message: "Request failed with status 409",
      candidates: [{ root: "docs", url: "/docs/page" }] });
});
