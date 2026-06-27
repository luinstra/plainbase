import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { fireEvent, render, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { clearCsrfToken } from "../api/csrf";
import { sessionQuery, treeQuery } from "../api/queries";
import type { ChangeDetail, TreeResponse } from "../api/types";
import { createAppRouter } from "../router";

/**
 * P4 review detail (WI-5). Mocked fetch drives the GET detail + the approve/reject/rebase POSTs. Pins the F3
 * drift rules (banner = base_drifted || CONFLICTED; approve enabled only on a clean PENDING; rebase only on
 * CONFLICTED) and the F4 wire (approve no body, reject `{comment}`), plus the SERVER diff rendered verbatim.
 */

const ID = "rev-1";
const emptyTree: TreeResponse = { root: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs", page_count: 0, children: [] } };
const AUTHED = { authenticated: true, username: "admin", csrf_token: "csrf-xyz", auth_mode: "builtin" };

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

function detail(over: Partial<ChangeDetail>): ChangeDetail {
  return {
    id: ID,
    operation: "edit",
    status: "PENDING",
    target_path: "guides/deploy-guide.md",
    page_id: "page-1",
    base_hash: "sha256:base",
    base_drifted: false,
    author_label: "agent: ci",
    author_issuer: "agent",
    author_external_id: "tok-1",
    created_at: "2026-06-26T10:00:00Z",
    rationale: "tighten the rollback steps",
    unified_diff: ["@@ -1 +1 @@", " context", "-removed", "+added"].join("\n"),
    approver_issuer: null,
    approver_external_id: null,
    decision_comment: null,
    decided_at: null,
    applied_commit: null,
    status_reason: null,
    ...over,
  };
}

interface Call {
  url: string;
  init: RequestInit;
}

/** Mounts ReviewDetail via the full router; GET serves the (mutable) detail, POSTs are recorded + answered. */
function renderDetail(initial: ChangeDetail, opts: { onPost?: (url: string) => Response; nextDetail?: ChangeDetail } = {}) {
  let current = initial;
  const posts: Call[] = [];
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(treeQuery.queryKey, emptyTree);
  queryClient.setQueryData(sessionQuery.queryKey, AUTHED);

  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === "string" ? input : input.toString();
      const method = init?.method ?? "GET";
      if (url === "/api/v1/session") return jsonResponse(AUTHED);
      if (url === "/api/v1/tree") return jsonResponse(emptyTree);
      if (method === "POST") {
        posts.push({ url, init: init ?? {} });
        const response = opts.onPost ? opts.onPost(url) : jsonResponse({ new_hash: "sha256:new", commit_sha: "c1", applied_at: "2026-06-26T11:00:00Z", warnings: null });
        if (response.ok && opts.nextDetail) current = opts.nextDetail;
        return response;
      }
      if (url.endsWith(`/changes/${ID}`)) return jsonResponse(current);
      return jsonResponse({});
    }),
  );

  const history = createMemoryHistory({ initialEntries: [`/review/${ID}`] });
  const router = createAppRouter(queryClient, history);
  const view = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { view, posts };
}

function approveBtn(view: ReturnType<typeof render>) {
  return view.container.querySelector<HTMLButtonElement>("[data-pb-review-approve]");
}

beforeEach(() => clearCsrfToken());
afterEach(() => vi.unstubAllGlobals());

describe("review detail", () => {
  it("renders the SERVER diff verbatim and the rationale as text", async () => {
    const { view } = renderDetail(detail({}));

    await waitFor(() => expect(view.container.querySelector("[data-pb-diff]")).not.toBeNull());
    const kinds = [...view.container.querySelectorAll("[data-pb-diff-line]")].map((el) => el.getAttribute("data-pb-diff-line"));
    expect(kinds).toEqual(["meta", "context", "del", "add"]); // the mocked unified_diff, NOT a client re-derivation
    expect(view.container.querySelector("[data-pb-review-rationale]")?.textContent).toContain("tighten the rollback steps");
  });

  it("a CONFLICTED change shows the drift banner, disables approve, and offers rebase", async () => {
    const { view } = renderDetail(detail({ status: "CONFLICTED", base_drifted: false }));

    await waitFor(() => expect(view.container.querySelector("[data-pb-review-drift-banner]")).not.toBeNull());
    expect(approveBtn(view)?.disabled).toBe(true);
    expect(view.container.querySelector("[data-pb-review-rebase]")).not.toBeNull();
  });

  it("a live-drifted PENDING shows the banner + disabled approve but NO rebase (F3)", async () => {
    const { view } = renderDetail(detail({ status: "PENDING", base_drifted: true }));

    await waitFor(() => expect(view.container.querySelector("[data-pb-review-drift-banner]")).not.toBeNull());
    expect(approveBtn(view)?.disabled).toBe(true);
    expect(view.container.querySelector("[data-pb-review-rebase]")).toBeNull(); // cannot rebase a PENDING
  });

  it("a clean PENDING enables approve and hides the banner", async () => {
    const { view } = renderDetail(detail({ status: "PENDING", base_drifted: false }));

    await waitFor(() => expect(view.container.querySelector("[data-pb-review-approve]")).not.toBeNull());
    expect(approveBtn(view)?.disabled).toBe(false);
    expect(view.container.querySelector("[data-pb-review-drift-banner]")).toBeNull();
  });

  it("clicking Rebase POSTs /rebase, then the refetch flips to PENDING — approve re-enables, rebase disappears", async () => {
    const { view, posts } = renderDetail(detail({ status: "CONFLICTED", base_drifted: false }), {
      onPost: () => jsonResponse({ new_base_hash: "sha256:rebased", unified_diff: "@@ -1 +1 @@\n+ok", status: "PENDING" }),
      nextDetail: detail({ status: "PENDING", base_drifted: false }),
    });

    await waitFor(() => expect(view.container.querySelector("[data-pb-review-rebase]")).not.toBeNull());
    fireEvent.click(view.container.querySelector<HTMLButtonElement>("[data-pb-review-rebase]")!);

    await waitFor(() => expect(posts.some((c) => c.url.endsWith(`/changes/${ID}/rebase`))).toBe(true));
    await waitFor(() => expect(view.container.querySelector("[data-pb-review-rebase]")).toBeNull());
    expect(approveBtn(view)?.disabled).toBe(false);
    expect(view.container.querySelector("[data-pb-review-drift-banner]")).toBeNull();
  });

  it("approve POSTs /approve with NO body", async () => {
    const { view, posts } = renderDetail(detail({ status: "PENDING", base_drifted: false }));

    await waitFor(() => expect(approveBtn(view)?.disabled).toBe(false));
    fireEvent.click(approveBtn(view)!);

    await waitFor(() => expect(posts.some((c) => c.url.endsWith(`/changes/${ID}/approve`))).toBe(true));
    const call = posts.find((c) => c.url.endsWith(`/changes/${ID}/approve`))!;
    expect(call.init.method).toBe("POST");
    expect(call.init.body).toBeUndefined(); // F4
  });

  it("reject POSTs /reject with the {comment} body", async () => {
    const { view, posts } = renderDetail(detail({ status: "PENDING", base_drifted: false }), {
      onPost: () => jsonResponse(detail({ status: "REJECTED" })),
    });

    await waitFor(() => expect(view.container.querySelector("[data-pb-review-comment]")).not.toBeNull());
    fireEvent.change(view.container.querySelector<HTMLTextAreaElement>("[data-pb-review-comment]")!, { target: { value: "needs work" } });
    fireEvent.click(view.container.querySelector<HTMLButtonElement>("[data-pb-review-reject]")!);

    await waitFor(() => expect(posts.some((c) => c.url.endsWith(`/changes/${ID}/reject`))).toBe(true));
    const call = posts.find((c) => c.url.endsWith(`/changes/${ID}/reject`))!;
    expect(call.init.body).toBe(JSON.stringify({ comment: "needs work" }));
  });

  it("a disabled-on-drift approve is inert — the click fires no POST", async () => {
    const { view, posts } = renderDetail(detail({ status: "PENDING", base_drifted: true }));

    await waitFor(() => expect(approveBtn(view)?.disabled).toBe(true));
    fireEvent.click(approveBtn(view)!);
    // A genuinely-disabled button dispatches no click handler; no approve POST is ever issued.
    expect(posts.some((c) => c.url.endsWith("/approve"))).toBe(false);
  });

  it("a 403 on approve flips the no-access state", async () => {
    const { view } = renderDetail(detail({ status: "PENDING", base_drifted: false }), {
      onPost: () => jsonResponse({ error: { code: "forbidden", message: "nope" } }, 403),
    });

    await waitFor(() => expect(approveBtn(view)?.disabled).toBe(false));
    fireEvent.click(approveBtn(view)!);
    await waitFor(() => expect(view.container.querySelector("[data-pb-review-no-access]")).not.toBeNull());
  });
});
