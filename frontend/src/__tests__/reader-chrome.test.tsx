import { EditorView } from "@codemirror/view";
import { onlineManager, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { act, fireEvent, render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { pageByPathQuery, pageHtmlQuery, pageQuery, sessionQuery, treeQuery } from "../api/queries";
import type { DiscussionDetailResponse, PageHtmlResponse, PageResponse, SessionResponse, TreeResponse } from "../api/types";
import { createAppRouter } from "../router";
import { primePageDiscussionLists } from "./pageDiscussionFixture";

const PAGE_ID = "01970000-0000-7000-8000-00000000a101";
const PAGE_URL = "/docs/reader/guides/alpha";
const PAGE_PATH = "reader/guides/alpha.md";
const CONTENT_HASH = "sha256:c37988127fa51e0d5904cf5303e78f9e493e2fc427a2cf9cfc8721e20ed76994";
const READER_SESSION: SessionResponse = { authenticated: false, auth_mode: "reader" };
const OFF_SESSION: SessionResponse = { authenticated: false, auth_mode: "off" };
const BUILTIN_SESSION: SessionResponse = { authenticated: true, auth_mode: "builtin", username: "Owner", csrf_token: "csrf" };

const citation = {
  page_id: PAGE_ID, heading_id: null, path: PAGE_PATH, content_hash: CONTENT_HASH, commit: null,
  uri: `plainbase://docs/${PAGE_ID}@${CONTENT_HASH}`,
};
const page: PageResponse = {
  id: PAGE_ID, root: "docs", path: PAGE_PATH, slug: "alpha", url: PAGE_URL, title: "Reader alpha",
  markdown: "# Reader alpha\n\nAlpha chrome sentence.\n", frontmatter: {}, content_hash: CONTENT_HASH,
  id_materialized: true, commit: null, citation,
};
const html: PageHtmlResponse = {
  id: PAGE_ID, root: "docs", path: PAGE_PATH, slug: "alpha", url: PAGE_URL, title: "Reader alpha",
  html: '<h1 id="reader-alpha">Reader alpha</h1><p>Alpha chrome sentence.</p>', content_hash: CONTENT_HASH, commit: null,
  headings: [{ id: "reader-alpha", level: 1, text: "Reader alpha" }], citation,
};

function tree(editable: boolean, discussionsEnabled: boolean): TreeResponse {
  return { roots: [{
    root: "docs", available: true, primary: true, editable, discussionsEnabled,
    tree: {
      type: "folder", name: "", title: null, description: null, path: "", url: "/docs", page_count: 1,
      children: [{
        type: "folder", name: "reader", title: "Reader", description: null, path: "reader",
        url: "/docs/reader", page_count: 1,
        children: [{
          type: "folder", name: "guides", title: "Guides", description: null, path: "reader/guides",
          url: "/docs/reader/guides", page_count: 1,
          children: [{ type: "page", id: PAGE_ID, title: page.title, slug: page.slug, path: PAGE_PATH,
            url: PAGE_URL, status: "published", updated: null }],
        }],
      }],
    },
  }] };
}

interface RenderOptions {
  session?: SessionResponse;
  editable?: boolean;
  discussionsEnabled?: boolean;
  serveSession?: boolean;
  seedSession?: boolean;
  seedTree?: boolean;
  hasHistory?: boolean;
  responses?: Record<string, () => Response | Promise<Response>>;
}

function renderAt(initialPath: string, options: RenderOptions = {}) {
  const { session = READER_SESSION, editable = false, discussionsEnabled = false, serveSession = false } = options;
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(treeQuery.queryKey, tree(editable, discussionsEnabled));
  if (options.seedSession !== false) queryClient.setQueryData(sessionQuery.queryKey, session);
  const record = options.hasHistory ? { ...page, commit: "abc123" } : page;
  queryClient.setQueryData(pageByPathQuery("docs/reader/guides/alpha").queryKey, record);
  queryClient.setQueryData(pageQuery(PAGE_ID, "docs").queryKey, record);
  queryClient.setQueryData(pageHtmlQuery(PAGE_ID, "docs").queryKey, html);
  primePageDiscussionLists(queryClient);
  if (options.seedTree === false) queryClient.removeQueries({ queryKey: treeQuery.queryKey, exact: true });
  const fetchSpy = vi.fn(async (input: RequestInfo | URL) => {
    const response = options.responses?.[String(input)];
    if (response) return response();
    if (serveSession && String(input) === "/api/v1/session") return Response.json(session);
    return Response.json({ error: { code: "page_not_found", message: "No page at that path" } }, { status: 404 });
  });
  vi.stubGlobal("fetch", fetchSpy);
  const history = createMemoryHistory({ initialEntries: [initialPath] });
  const router = createAppRouter(queryClient, history);
  const view = render(<QueryClientProvider client={queryClient}><RouterProvider router={router} /></QueryClientProvider>);
  return { view, history, router, queryClient, fetchSpy };
}

async function expectPage(view: ReturnType<typeof render>) {
  await waitFor(() => {
    const article = view.container.querySelector("[data-pb-page-article]");
    expect(article).not.toBeNull();
    expect(article?.textContent).toContain("Alpha chrome sentence.");
  });
}

afterEach(() => vi.unstubAllGlobals());

function deferredResponse() {
  let resolve!: (response: Response) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<Response>((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}

async function sessionHeld(app: ReturnType<typeof renderAt>) {
  await waitFor(() => expect(app.queryClient.getQueryState(sessionQuery.queryKey)?.fetchStatus).toBe("fetching"));
  await waitFor(() => expect(app.router.state.isLoading).toBe(false));
  await act(async () => {});
}

describe("cold session chrome", () => {
  it("shows pending while a cold failed session retry is paused offline", async () => {
    const held = deferredResponse();
    let requests = 0;
    const app = renderAt("/new", { seedSession: false, editable: true, responses: {
      "/api/v1/session": () => ++requests === 1
        ? Response.json({ error: { code: "unavailable", message: "Session unavailable" } }, { status: 500 }) : held.promise,
    } });
    try {
      expect(await app.view.findByText("Session unavailable")).not.toBeNull();
      act(() => onlineManager.setOnline(false));
      fireEvent.click(app.view.getByRole("button", { name: "Retry session" }));
      await waitFor(() => expect(app.queryClient.getQueryState(sessionQuery.queryKey)?.fetchStatus).toBe("paused"));
      expect(app.queryClient.getQueryState(sessionQuery.queryKey)?.error).toBeNull();
      expect(requests).toBe(1);
      await waitFor(() => expect(app.view.container.querySelector("[data-pb-main] [data-pb-loading]")).not.toBeNull());
      expect(app.view.queryByText("Could not determine the session.")).toBeNull();
      expect(app.view.container.querySelector("[data-pb-new-page-form], [data-pb-not-found]")).toBeNull();
      act(() => onlineManager.setOnline(true));
      await waitFor(() => expect(requests).toBe(2));
      await act(async () => held.resolve(Response.json({ error: { code: "not_found", message: "No session" } }, { status: 404 })));
      await waitFor(() => expect(app.view.container.querySelector("[data-pb-new-page-form]")).not.toBeNull());
    } finally {
      onlineManager.setOnline(true);
      held.resolve(Response.json({ error: { code: "not_found", message: "No session" } }, { status: 404 }));
    }
  });

  it.each(["header", "listing"])("opens creation from the %s at a workspace trailing-slash folder", async (opener) => {
    const app = renderAt("/docs/reader/guides/", { seedSession: false, editable: true });
    await waitFor(() => expect(app.view.container.querySelector("[data-pb-folder]")).not.toBeNull());
    await waitFor(() => expect(app.queryClient.getQueryState(sessionQuery.queryKey)?.status).toBe("error"));
    await waitFor(() => expect(app.queryClient.getQueryState(pageByPathQuery("docs/reader/guides").queryKey)?.status).toBe("error"));
    if (opener === "header") {
      const control = app.view.container.querySelector<HTMLElement>("[data-pb-new-page]");
      expect(control).not.toBeNull();
      await waitFor(() => expect(control?.hasAttribute("disabled")).toBe(false));
      expect(control?.getAttribute("aria-disabled")).not.toBe("true");
      fireEvent.click(control!);
    } else {
      fireEvent.click(app.view.getByRole("link", { name: "New page here" }));
    }
    await waitFor(() => expect(app.view.container.querySelector("[data-pb-new-page-form]")).not.toBeNull());
    const folder = app.view.container.querySelector<HTMLSelectElement>("[data-pb-new-folder]");
    expect(folder).not.toBeNull();
    expect(folder?.value).toBe(JSON.stringify(["folder", "reader/guides"]));
    expect(app.history.location.pathname).toBe("/docs/reader/guides/");
    expect(app.view.container.querySelector("[data-pb-folder]")).not.toBeNull();
  });

  it("marks the workspace trailing-slash folder label current", async () => {
    const app = renderAt("/docs/reader/guides/", { seedSession: false, editable: true });
    await waitFor(() => expect(app.view.container.querySelector("[data-pb-folder]")).not.toBeNull());
    const label = app.view.container.querySelector('.pb-sidebar a[href="/docs/reader/guides"]');
    expect(label).not.toBeNull();
    expect(label?.getAttribute("aria-current")).toBe("page");
  });

  it("expands ancestors and marks the workspace trailing-slash page current", async () => {
    const app = renderAt(`${PAGE_URL}/`, { seedSession: false, editable: true });
    await expectPage(app.view);
    const label = app.view.container.querySelector(`.pb-sidebar a[href="${PAGE_URL}"]`);
    expect(label).not.toBeNull();
    expect(label?.getAttribute("aria-current")).toBe("page");
    expect(app.history.location.pathname).toBe(`${PAGE_URL}/`);
  });

  it.each([404, 500])("shares a settled %s with routes that mount during a held refetch", async (status) => {
    const held = deferredResponse();
    let requests = 0;
    const app = renderAt("/docs/reader", { seedSession: false, editable: true, responses: {
      "/api/v1/session": () => ++requests === 1
        ? Response.json({ error: { code: "unavailable", message: "No session" } }, { status }) : held.promise,
    } });
    await waitFor(() => expect(app.queryClient.getQueryState(sessionQuery.queryKey)?.status).toBe("error"));
    await waitFor(() => expect(app.view.container.querySelector("[data-pb-folder]")).not.toBeNull());
    await act(async () => { void app.queryClient.refetchQueries({ queryKey: sessionQuery.queryKey }); });
    await waitFor(() => expect(requests).toBe(2));
    expect(app.queryClient.getQueryState(sessionQuery.queryKey)?.error).toBeNull();
    await act(async () => app.history.push("/new"));
    if (status === 404) {
      await waitFor(() => expect(app.view.container.querySelector("[data-pb-new-page-form]")).not.toBeNull());
    } else {
      await waitFor(() => expect(app.view.container.querySelector("[data-pb-main] [data-pb-loading]")).not.toBeNull());
      expect(app.view.container.querySelector("[data-pb-new-page-form], [data-pb-not-found]")).toBeNull();
    }
    await act(async () => held.resolve(Response.json(BUILTIN_SESSION)));
    await waitFor(() => expect(app.view.container.querySelector("[data-pb-new-page-form]")).not.toBeNull());
  });

  it.each(["/new", "/admin", "/discussions", `${PAGE_URL}?mode=edit`, `${PAGE_URL}?mode=history`])(
    "keeps workspace UI blocked at %s after the session settles as 500", async (path) => {
      const app = renderAt(path, { seedSession: false, editable: true, discussionsEnabled: true, responses: {
        "/api/v1/session": () => Response.json({ error: { code: "unavailable", message: "Session unavailable" } }, { status: 500 }),
      } });
      await waitFor(() => expect(app.queryClient.getQueryState(sessionQuery.queryKey)?.status).toBe("error"));
      await waitFor(() => expect(app.view.container.querySelector("[data-pb-main] [data-pb-error]")).not.toBeNull());
      expect(app.view.container.querySelector("[data-pb-new-page-form], [data-pb-editor], [data-pb-history], [data-pb-admin]")).toBeNull();
      expect(app.view.container.querySelector("[data-pb-not-found]")).toBeNull();
      expect(app.view.getByRole("button", { name: "Retry session" })).not.toBeNull();
    },
  );

  it("keeps a page readable without workspace affordances after a session 500 and recovers to a reader", async () => {
    let recovered = false;
    const app = renderAt(PAGE_URL, { seedSession: false, editable: true, discussionsEnabled: true, hasHistory: true, responses: {
      "/api/v1/session": () => recovered ? Response.json(READER_SESSION)
        : Response.json({ error: { code: "unavailable", message: "Session unavailable" } }, { status: 500 }),
    } });
    await waitFor(() => expect(app.queryClient.getQueryState(sessionQuery.queryKey)?.status).toBe("error"));
    await expectPage(app.view);
    expect(app.view.container.querySelector("[data-pb-new-page], [data-pb-edit-page], .pb-property-add, [aria-label='Discussions'], [data-pb-history-page]")).toBeNull();
    recovered = true;
    await act(async () => { await app.queryClient.refetchQueries({ queryKey: sessionQuery.queryKey }); });
    expect(app.queryClient.getQueryData(sessionQuery.queryKey)).toEqual(READER_SESSION);
    await expectPage(app.view);
    expect(app.view.container.querySelector("[data-pb-new-page], [data-pb-edit-page]")).toBeNull();
  });

  it("uses OFF discussion wording after the session endpoint's 404", async () => {
    const author = { key: "owner", kind: "human" as const, label: "Owner" };
    const response: DiscussionDetailResponse = {
      discussion: {
        id: "thread", page: { id: PAGE_ID, path: PAGE_PATH, resolution: "by_id" }, status: "open", state: "page_level",
        reason: null, range: null, candidates: null, placement: null, quote: null, comment_count: 1, starter: author,
        created: null, updated: null, anchor: { kind: "page", content_hash: CONTENT_HASH, commit: null }, reattachment: null,
      },
      comments: [{ id: "comment", author, markdown: "OFF comment", html: "<p>OFF comment</p>",
        created: "2026-01-01T00:00:00Z", edited_at: null, retracted: false }],
      next: null, discussions_available: true, reason: null,
    };
    const app = renderAt("/discussions/docs/thread", { seedSession: false, editable: true, discussionsEnabled: true, responses: {
      "/api/v1/discussions/thread?root=docs&limit=50": () => Response.json(response),
    } });
    await waitFor(() => expect(app.queryClient.getQueryState(sessionQuery.queryKey)?.status).toBe("error"));
    expect(await app.view.findByText("OFF comment")).not.toBeNull();
    fireEvent.click(app.view.getByLabelText("Actions for Owner's comment"));
    const purge = [...app.view.container.querySelectorAll("button")].filter((button) => button.textContent?.startsWith("Purge comment"));
    expect(purge).toHaveLength(1);
    expect.soft(purge[0].textContent).toBe("Purge comment");
    expect.soft(app.view.queryByText("Local access allows discussion actions without signing in.")).not.toBeNull();
  });

  it("preserves the OFF editor and unsaved buffer while a settled session error refetches", async () => {
    const held = deferredResponse();
    let requests = 0;
    const app = renderAt(`${PAGE_URL}?mode=edit`, { seedSession: false, editable: true,
      responses: { "/api/v1/session": () => ++requests === 1
        ? Response.json({ error: { code: "not_found", message: "No session" } }, { status: 404 }) : held.promise } });
    const editor = await waitFor(() => {
      const element = app.view.container.querySelector<HTMLElement>("[data-pb-codemirror] .cm-editor");
      expect(element).not.toBeNull();
      return element!;
    });
    expect(app.queryClient.getQueryState(sessionQuery.queryKey)?.status).toBe("error");
    const cm = EditorView.findFromDOM(editor)!;
    await act(async () => cm.dispatch({ changes: { from: cm.state.doc.length, insert: "Unsaved wake-up buffer" } }));
    expect(cm.state.doc.toString()).toContain("Unsaved wake-up buffer");
    await act(async () => { void app.queryClient.refetchQueries({ queryKey: sessionQuery.queryKey }); });
    await waitFor(() => expect(requests).toBe(2));
    expect(app.view.container.querySelector("[data-pb-codemirror] .cm-editor")).toBe(editor);
    expect(EditorView.findFromDOM(editor)?.state.doc.toString()).toContain("Unsaved wake-up buffer");
    await act(async () => held.resolve(Response.json({ error: { code: "not_found", message: "No session" } }, { status: 404 })));
    await waitFor(() => expect(app.queryClient.getQueryState(sessionQuery.queryKey)?.fetchStatus).toBe("idle"));
    expect(app.view.container.querySelector("[data-pb-codemirror] .cm-editor")).toBe(editor);
    expect(EditorView.findFromDOM(editor)?.state.doc.toString()).toContain("Unsaved wake-up buffer");
  });

  it.each(["500", "transport"])("retains OFF edits and creation after a reconnect settles as %s", async (failure) => {
    const held = deferredResponse();
    let requests = 0;
    const app = renderAt(`${PAGE_URL}?mode=edit`, { seedSession: false, editable: true,
      responses: { "/api/v1/session": () => ++requests === 1
        ? Response.json({ error: { code: "not_found", message: "No session" } }, { status: 404 }) : held.promise } });
    const editor = await waitFor(() => {
      const element = app.view.container.querySelector<HTMLElement>("[data-pb-codemirror] .cm-editor");
      expect(element).not.toBeNull();
      return element!;
    });
    const cm = EditorView.findFromDOM(editor)!;
    await act(async () => cm.dispatch({ changes: { from: cm.state.doc.length, insert: "Unsaved failed-refresh buffer" } }));
    await act(async () => { void app.queryClient.refetchQueries({ queryKey: sessionQuery.queryKey }); });
    await waitFor(() => expect(requests).toBe(2));
    expect(app.view.container.querySelector(".cm-editor")).toBe(editor);
    await act(async () => {
      if (failure === "transport") held.reject(new TypeError("Connection lost"));
      else held.resolve(Response.json({ error: { code: "unavailable", message: "Session unavailable" } }, { status: 500 }));
    });
    await waitFor(() => expect(app.queryClient.getQueryState(sessionQuery.queryKey)?.fetchStatus).toBe("idle"));
    expect(app.queryClient.getQueryState(sessionQuery.queryKey)?.status).toBe("error");
    expect.soft(app.view.container.querySelector(".cm-editor")).toBe(editor);
    expect.soft(EditorView.findFromDOM(editor)?.state.doc.toString()).toContain("Unsaved failed-refresh buffer");
    const creation = app.view.container.querySelector<HTMLElement>("[data-pb-new-page]");
    expect.soft(creation).not.toBeNull();
    if (creation) {
      expect(creation.getAttribute("href")).toContain("/new");
      fireEvent.click(creation);
      await waitFor(() => expect(app.view.container.querySelector("[data-pb-new-page-form]")).not.toBeNull());
      expect(app.view.container.querySelector(".cm-editor")).toBe(editor);
      expect(EditorView.findFromDOM(editor)?.state.doc.toString()).toContain("Unsaved failed-refresh buffer");
    }
  });

  it("retries an unknown session from its error view and unlocks the OFF route", async () => {
    let requests = 0;
    const app = renderAt("/new", { seedSession: false, editable: true, responses: {
      "/api/v1/session": () => ++requests === 1
        ? Response.json({ error: { code: "unavailable", message: "Session unavailable" } }, { status: 500 })
        : Response.json({ error: { code: "not_found", message: "No session" } }, { status: 404 }),
    } });
    expect(await app.view.findByText("Session unavailable")).not.toBeNull();
    expect(app.view.container.querySelector("[data-pb-new-page-form], [data-pb-not-found]")).toBeNull();
    fireEvent.click(app.view.getByRole("button", { name: "Retry session" }));
    await waitFor(() => expect(app.view.container.querySelector("[data-pb-new-page-form]")).not.toBeNull());
    expect(requests).toBe(2);
  });

  it.each(["retry", "refetch"])("shows pending during a held session %s after a cold 500", async (trigger) => {
    const held = deferredResponse();
    let requests = 0;
    const app = renderAt("/new", { seedSession: false, editable: true, responses: {
      "/api/v1/session": () => ++requests === 1
        ? Response.json({ error: { code: "unavailable", message: "Session unavailable" } }, { status: 500 }) : held.promise,
    } });
    try {
      expect(await app.view.findByText("Session unavailable")).not.toBeNull();
      if (trigger === "retry") fireEvent.click(app.view.getByRole("button", { name: "Retry session" }));
      else await act(async () => { void app.queryClient.refetchQueries({ queryKey: sessionQuery.queryKey }); });
      await waitFor(() => expect(requests).toBe(2));
      expect(app.queryClient.getQueryState(sessionQuery.queryKey)?.error).toBeNull();
      await waitFor(() => expect(app.view.container.querySelector("[data-pb-main] [data-pb-loading]")).not.toBeNull());
      expect(app.view.queryByText("Could not determine the session.")).toBeNull();
      expect(app.view.container.querySelector("[data-pb-new-page-form], [data-pb-not-found]")).toBeNull();
      await act(async () => held.resolve(Response.json(
        { error: { code: "unavailable", message: "Still unavailable" } }, { status: 500 },
      )));
      expect(await app.view.findByText("Still unavailable")).not.toBeNull();
      expect(app.view.getByRole("button", { name: "Retry session" }).hasAttribute("disabled")).toBe(false);
    } finally {
      await act(async () => held.resolve(Response.json(
        { error: { code: "unavailable", message: "Still unavailable" } }, { status: 500 },
      )));
    }
  });

  it("refreshes a stale builtin session when discussions open and enables posting after sign-in", async () => {
    const held = deferredResponse();
    const anonymous = { ...BUILTIN_SESSION, authenticated: false, username: null, csrf_token: null };
    const app = renderAt("/docs/reader", { session: anonymous, editable: true, discussionsEnabled: true,
      responses: { "/api/v1/session": () => held.promise } });
    await waitFor(() => expect(app.view.container.querySelector("[data-pb-folder]")).not.toBeNull());
    expect(app.fetchSpy.mock.calls.filter(([input]) => String(input) === "/api/v1/session")).toHaveLength(0);
    await act(async () => {
      app.queryClient.setQueryData(sessionQuery.queryKey, anonymous, { updatedAt: Date.now() - 61_000 });
      app.history.push(PAGE_URL);
    });
    await expectPage(app.view);
    fireEvent.click(await app.view.findByRole("button", { name: "Start a discussion" }));
    fireEvent.change(await app.view.findByRole("textbox", { name: "Comment" }), { target: { value: "Now signed in" } });
    const submit = app.view.getByRole("button", { name: "Create discussion" });
    expect(submit.hasAttribute("disabled")).toBe(true);
    await waitFor(() => expect(app.fetchSpy.mock.calls.filter(([input]) => String(input) === "/api/v1/session")).toHaveLength(1));
    await act(async () => held.resolve(Response.json(BUILTIN_SESSION)));
    await waitFor(() => expect(submit.hasAttribute("disabled")).toBe(false));
  });

  it.each([PAGE_URL, `/discussions/docs/${PAGE_ID}`])("settles OFF once when the discussion surface mounts at %s", async (path) => {
    const app = renderAt(path, { seedSession: false, editable: true, discussionsEnabled: true });
    await waitFor(() => expect(app.queryClient.getQueryState(sessionQuery.queryKey)?.status).toBe("error"));
    if (path === PAGE_URL) {
      await expectPage(app.view);
      await waitFor(() => expect(app.view.container.querySelector('[aria-label="Discussions"]')).not.toBeNull());
    } else {
      await waitFor(() => expect(app.fetchSpy.mock.calls.some(([input]) => String(input).startsWith("/api/v1/discussions/"))).toBe(true));
    }
    expect(app.fetchSpy.mock.calls.filter(([input]) => String(input) === "/api/v1/session")).toHaveLength(1);
  });

  it.each(["/admin", "/review", "/review/x", "/discussions", "/discussions/docs", "/discussions/docs/x", "/new"])(
    "waits before mounting the workspace route at %s",
    async (path) => {
      const held = deferredResponse();
      const app = renderAt(path, { seedSession: false, responses: { "/api/v1/session": () => held.promise } });
      await sessionHeld(app);
      expect(app.view.container.querySelector("[data-pb-main] [data-pb-loading]")).not.toBeNull();
      expect(app.view.container.querySelector("[data-pb-new-page-form], [data-pb-admin], [data-pb-review-queue]")).toBeNull();
      await act(async () => held.resolve(Response.json(READER_SESSION)));
      await waitFor(() => expect(app.view.container.querySelector("[data-pb-not-found]")).not.toBeNull());
      expect(app.view.container.querySelector("[data-pb-new-page-form]")).toBeNull();
    },
  );

  it.each(["edit", "history"])("waits before dispatching %s mode", async (mode) => {
    const held = deferredResponse();
    const app = renderAt(`${PAGE_URL}?mode=${mode}`, { seedSession: false,
      responses: { "/api/v1/session": () => held.promise } });
    await sessionHeld(app);
    expect(app.view.container.querySelector("[data-pb-main] [data-pb-loading]")).not.toBeNull();
    expect(app.view.container.querySelector("[data-pb-editor], [data-pb-history]")).toBeNull();
    await act(async () => held.resolve(Response.json(READER_SESSION)));
    await expectPage(app.view);
  });

  it.each([false, true])("hides both creation controls until OFF's 404 settles, editable=%s", async (editable) => {
    const held = deferredResponse();
    const app = renderAt("/docs/reader", { seedSession: false, editable,
      responses: { "/api/v1/session": () => held.promise } });
    await sessionHeld(app);
    expect(app.view.container.querySelector("[data-pb-folder]")).not.toBeNull();
    expect(app.view.container.querySelector("[data-pb-new-page], .pb-folder-create")).toBeNull();
    await act(async () => held.resolve(Response.json({ error: { code: "not_found", message: "No session" } }, { status: 404 })));
    await waitFor(() => {
      for (const selector of ["[data-pb-new-page]", ".pb-folder-create"]) {
        const control = app.view.container.querySelector(selector);
        expect(control, selector).not.toBeNull();
        expect(control?.hasAttribute("disabled")).toBe(!editable);
      }
    });
  });

  it.each(["[data-pb-new-page]", "[data-pb-edit-page]", ".pb-property-add", '[aria-label="Discussions"]', "[data-pb-history-page]"])(
    "keeps a page readable while %s waits for OFF's 404", async (selector) => {
    const held = deferredResponse();
    const app = renderAt(PAGE_URL, { seedSession: false, editable: true, discussionsEnabled: true, hasHistory: true,
      responses: { "/api/v1/session": () => held.promise } });
    await sessionHeld(app);
    await expectPage(app.view);
    expect(app.view.container.querySelector(selector), selector).toBeNull();
    await act(async () => held.resolve(Response.json({ error: { code: "not_found", message: "No session" } }, { status: 404 })));
    await waitFor(() => {
      expect(app.view.container.querySelector(selector), selector).not.toBeNull();
    });
  });

  it("keeps a not-found view readable while its discussion escape waits for the session", async () => {
    const held = deferredResponse();
    const app = renderAt("/nope/x", { seedSession: false, discussionsEnabled: true,
      responses: { "/api/v1/session": () => held.promise } });
    await sessionHeld(app);
    await waitFor(() => expect(app.view.container.querySelector("[data-pb-not-found]")).not.toBeNull());
    expect(app.view.queryByRole("link", { name: /discussions/i })).toBeNull();
    await act(async () => held.resolve(Response.json({ error: { code: "not_found", message: "No session" } }, { status: 404 })));
    await waitFor(() => expect(app.view.queryByRole("link", { name: /discussions/i })).not.toBeNull());
  });

  it.each([
    { path: "/new", selector: "[data-pb-new-page-form]" },
    { path: `${PAGE_URL}?mode=edit`, selector: "[data-pb-editor]" },
    { path: `${PAGE_URL}?mode=history`, selector: "[data-pb-history]" },
  ])("keeps OFF's $path reachable after its session 404", async ({ path, selector }) => {
    const app = renderAt(path, { seedSession: false, editable: true });
    await waitFor(() => expect(app.view.container.querySelector(selector)).not.toBeNull());
    expect(app.queryClient.getQueryState(sessionQuery.queryKey)?.status).toBe("error");
    expect(app.view.container.querySelector("[data-pb-not-found]")).toBeNull();
  });
});

describe("reader chrome", () => {
  it.each([{ label: "reader", session: READER_SESSION, retry: false }, { label: "OFF workspace", session: OFF_SESSION, retry: true }])(
    "keeps failed-tree discussion retry appropriate to a $label", async ({ session, retry }) => {
      const app = renderAt(PAGE_URL, { session, seedTree: false });
      await expectPage(app.view);
      await waitFor(() => expect(app.queryClient.getQueryState(treeQuery.queryKey)?.status).toBe("error"));
      const control = app.view.queryByRole("button", { name: "Retry discussion availability" });
      if (retry) expect(control).not.toBeNull();
      else expect(control).toBeNull();
    },
  );

  it.each([
    ["Review", "[data-pb-review-nav]"],
    ["Admin", 'a[href="/admin"]'],
    ["Edit", "[data-pb-edit-page]"],
    ["property creation", ".pb-property-add"],
    ["discussions", '[aria-label="Discussions"]'],
  ])("keeps %s absent while the page is readable", async (_label, selector) => {
    const { view } = renderAt(PAGE_URL);
    await expectPage(view);
    expect(view.container.querySelector(selector)).toBeNull();
  });

  it("keeps sign-in hints absent while the page is readable", async () => {
    const { view } = renderAt(PAGE_URL);
    await expectPage(view);
    expect(view.queryByText(/sign in/i)).toBeNull();
  });

  it("omits the header New page control on a reader page", async () => {
    const { view } = renderAt(PAGE_URL);
    await expectPage(view);
    expect(view.container.querySelector("[data-pb-new-page]")).toBeNull();
  });

  it("omits New page here while rendering the reader folder listing", async () => {
    const { view } = renderAt("/docs/reader");
    await waitFor(() => expect(view.container.querySelector("[data-pb-folder]")).not.toBeNull());
    expect(view.container.querySelector(".pb-folder-create")).toBeNull();
  });

  it("omits the discussion escape while rendering an unknown-root not-found page", async () => {
    const { view } = renderAt("/nope/x");
    await waitFor(() => expect(view.container.querySelector("[data-pb-not-found]")).not.toBeNull());
    expect(view.queryByRole("link", { name: /discussions/i })).toBeNull();
  });

  it("keeps the disabled header New page control in an OFF workspace with the same tree", async () => {
    const { view } = renderAt(PAGE_URL, { session: OFF_SESSION });
    await expectPage(view);
    const control = view.container.querySelector("[data-pb-new-page]");
    expect(control).not.toBeNull();
    expect(control?.hasAttribute("disabled")).toBe(true);
  });

  it("keeps the disabled folder New page control in an OFF workspace with the same tree", async () => {
    const { view } = renderAt("/docs/reader", { session: OFF_SESSION });
    await waitFor(() => expect(view.container.querySelector("[data-pb-folder]")).not.toBeNull());
    const control = view.container.querySelector(".pb-folder-create");
    expect(control).not.toBeNull();
    expect(control?.hasAttribute("disabled")).toBe(true);
  });

  it("proves the workspace Review, Edit, property and discussion selectors are live", async () => {
    const { view } = renderAt(PAGE_URL, { session: BUILTIN_SESSION, editable: true, discussionsEnabled: true });
    await expectPage(view);
    await waitFor(() => {
      for (const selector of [
        "[data-pb-review-nav]", "[data-pb-edit-page]", ".pb-property-add", '[aria-label="Discussions"]',
      ]) {
        expect(view.container.querySelector(selector), selector).not.toBeNull();
      }
    });
  });
});

describe("reader workspace routes and modes", () => {
  it.each(["/admin", "/review", "/review/x", "/discussions", "/discussions/docs", "/discussions/docs/x"])(
    "renders not-found instead of the workspace surface at %s",
    async (path) => {
      const { view } = renderAt(path);
      await waitFor(() => {
        const main = view.container.querySelector("[data-pb-main]");
        expect(main?.textContent?.trim()).toBeTruthy();
        expect(main?.querySelector("[data-pb-loading]")).toBeNull();
      });
      expect(view.container.querySelector("[data-pb-not-found]"), `reader route ${path}`).not.toBeNull();
      expect(view.container.querySelector("[data-pb-admin]")).toBeNull();
      expect(view.container.querySelector("[data-pb-review-queue]")).toBeNull();
    },
  );

  it("renders not-found at the new-page address without opening the dialog", async () => {
    const { view } = renderAt("/new");
    await waitFor(() => expect(view.container.querySelector("[data-pb-main]")?.textContent?.trim()).toBeTruthy());
    expect(view.container.querySelector("[data-pb-not-found]")).not.toBeNull();
    expect(view.container.querySelector("[data-pb-new-page-form]")).toBeNull();
  });

  it.each(["edit", "history"])("renders the readable page for a reader's %s mode URL", async (mode) => {
    const { view, history } = renderAt(`${PAGE_URL}?mode=${mode}`);
    await waitFor(() => expect(view.container.querySelector(
      "[data-pb-page-article], [data-pb-editor], [data-pb-history]",
    )).not.toBeNull());
    const article = view.container.querySelector("[data-pb-page-article]");
    expect(article, `reader mode ${mode} must render the page`).not.toBeNull();
    expect(article?.textContent).toContain("Alpha chrome sentence.");
    expect(view.container.querySelector("[data-pb-editor]")).toBeNull();
    expect(view.container.querySelector("[data-pb-history]")).toBeNull();
    expect(history.location.href).toBe(`${PAGE_URL}?mode=${mode}`);
  });

  it("reaches Admin's real session load error in an OFF workspace", async () => {
    const { view } = renderAt("/admin", { seedSession: false });
    await waitFor(() => expect(view.container.querySelector("[data-pb-admin-error]")?.textContent).toBe("Could not load the session."));
    expect(view.container.querySelector("[data-pb-not-found]")).toBeNull();
  });
});
