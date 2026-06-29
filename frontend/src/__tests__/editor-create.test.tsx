import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, RouterProvider } from "@tanstack/react-router";
import { fireEvent, render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { pageByPathQuery, pageHtmlQuery, sessionQuery, treeQuery } from "../api/queries";
import type { PageHtmlResponse, PageResponse, TreeResponse } from "../api/types";
import { PAGE_TEMPLATES } from "../lib/pageTemplates";
import { createAppRouter } from "../router";

const MEETING_BODY = PAGE_TEMPLATES.find((t) => t.id === "meeting")!.body;

const emptyTree: TreeResponse = { root: { type: "folder", name: "", title: null, description: null, path: "", url: "/docs", page_count: 0, children: [] } };

/**
 * W6 new-page creation (D-2 acceptance #4). `POST /api/v1/pages` returns the minted id + the
 * server-authoritative canonical url; the client navigates DIRECTLY to that url (no tree re-resolve, no
 * client slug derivation). A 409 page_exists surfaces the server `path`.
 */

const NEW_ID = "01900000-0000-7000-8000-000000000001";
const NEW_URL = "/docs/guides/my-new-page";
const HASH = "sha256:0000000000000000000000000000000000000000000000000000000000000000";

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

function pageResponse(): PageResponse {
  return {
    id: NEW_ID,
    path: "guides/my-new-page.md",
    slug: "my-new-page",
    url: NEW_URL,
    title: "My New Page",
    markdown: "# My New Page\n",
    frontmatter: {},
    content_hash: HASH,
    id_materialized: true,
    commit: null,
    citation: { page_id: NEW_ID, heading_id: null, path: "guides/my-new-page.md", content_hash: HASH, commit: null, uri: `plainbase://${NEW_ID}@${HASH}` },
  };
}

function htmlResponse(): PageHtmlResponse {
  return {
    id: NEW_ID,
    path: "guides/my-new-page.md",
    slug: "my-new-page",
    url: NEW_URL,
    title: "My New Page",
    html: '<h1 id="t">My New Page</h1>',
    content_hash: HASH,
    commit: null,
    headings: [{ id: "t", level: 1, text: "My New Page" }],
    citation: { page_id: NEW_ID, heading_id: null, path: "guides/my-new-page.md", content_hash: HASH, commit: null, uri: `plainbase://${NEW_ID}@${HASH}` },
  };
}

function renderNew(createResponse: Response, prime: (qc: QueryClient) => void = () => {}) {
  // Route the destination page reads to VALID responses: after a successful create the editor invalidates
  // and navigates to the new page, whose refetch would otherwise hit the generic `{html,headings}` stub,
  // error, and unmount — with the async-CSRF create hop that error-unmount's React work can land after
  // jsdom teardown ("window is not defined"). Non-page reads (preview/tree) keep the benign stub.
  const fetchSpy = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (init?.method === "POST") return createResponse.clone();
    const url = typeof input === "string" ? input : input.toString();
    if (url.includes("/pages/by-path/")) return jsonResponse(pageResponse());
    if (url.endsWith("/html")) return jsonResponse(htmlResponse());
    if (url.includes(`/pages/${NEW_ID}`)) return jsonResponse(pageResponse());
    // A create success invalidates the tree (Sidebar) — serve a valid empty tree so the refetch can't crash it.
    if (url.endsWith("/api/v1/tree")) return jsonResponse(emptyTree);
    return jsonResponse({ html: "", headings: [] });
  });
  vi.stubGlobal("fetch", fetchSpy);

  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(treeQuery.queryKey, emptyTree);
  queryClient.setQueryData(sessionQuery.queryKey, { authenticated: false, username: null, csrf_token: null, auth_mode: "off" });
  prime(queryClient);
  const history = createMemoryHistory({ initialEntries: ["/new"] });
  const router = createAppRouter(queryClient, history);
  const view = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { view, history, fetchSpy };
}

function submitCreate(view: ReturnType<typeof render>) {
  const title = view.container.querySelector<HTMLInputElement>("[data-pb-new-title]")!;
  fireEvent.change(title, { target: { value: "My New Page" } });
  fireEvent.click(view.container.querySelector<HTMLButtonElement>("[data-pb-new-create]")!);
}

afterEach(() => vi.unstubAllGlobals());

describe("W6 new-page creation", () => {
  it("creating a page POSTs /api/v1/pages and navigates directly to the server-returned url", async () => {
    const { view, history } = renderNew(jsonResponse({ id: NEW_ID, url: NEW_URL, content_hash: HASH, commit: null }, 201), (qc) => {
      // Prime the destination so the post-navigation read renders without a live fetch.
      qc.setQueryData(pageByPathQuery("guides/my-new-page").queryKey, pageResponse());
      qc.setQueryData(pageHtmlQuery(NEW_ID).queryKey, htmlResponse());
    });

    await waitFor(() => expect(view.container.querySelector("[data-pb-new-page-form]")).not.toBeNull());
    submitCreate(view);

    // The navigation target is the SERVER url, verbatim — not a client-derived slug.
    await waitFor(() => expect(history.location.pathname).toBe(NEW_URL));
  });

  it("a created-but-unindexed 201 shows the warning and does NOT navigate into a maybe-404 route (FIX 3)", async () => {
    const { view, history } = renderNew(
      jsonResponse(
        { id: NEW_ID, url: NEW_URL, content_hash: HASH, commit: null, warning: { code: "written_but_unindexed", message: "Created, but not yet indexed." } },
        201,
      ),
    );

    await waitFor(() => expect(view.container.querySelector("[data-pb-new-page-form]")).not.toBeNull());
    submitCreate(view);

    // The warning is surfaced…
    const notice = await waitFor(() => {
      const el = view.container.querySelector("[data-pb-create-notice]");
      expect(el).not.toBeNull();
      return el!;
    });
    expect(notice.textContent).toContain("not yet indexed");
    // …and the flow stays on /new rather than silently landing on a possibly-unresolvable route.
    expect(history.location.pathname).toBe("/new");
  });

  it("a fetch rejection (offline) shows a save-failed notice instead of a silent error (FIX 4)", async () => {
    const fetchSpy = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === "POST") throw new TypeError("Failed to fetch");
      return jsonResponse({ html: "", headings: [] });
    });
    vi.stubGlobal("fetch", fetchSpy);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    queryClient.setQueryData(treeQuery.queryKey, emptyTree);
    queryClient.setQueryData(sessionQuery.queryKey, { authenticated: false, username: null, csrf_token: null, auth_mode: "off" });
    const history = createMemoryHistory({ initialEntries: ["/new"] });
    const router = createAppRouter(queryClient, history);
    const view = render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );

    await waitFor(() => expect(view.container.querySelector("[data-pb-new-page-form]")).not.toBeNull());
    submitCreate(view);

    // The thrown fetch becomes a typed error result (not an unhandled rejection) — the form shows it.
    await waitFor(() => expect(view.container.querySelector("[data-pb-new-page-form]")?.textContent).toContain("Couldn't reach the server"));
    // The form is still usable (the create button re-enables).
    expect(view.container.querySelector<HTMLButtonElement>("[data-pb-new-create]")?.disabled).toBe(false);
  });

  it("the new-section checkbox POSTs slug:index with the folder field as the section path", async () => {
    const SECTION_URL = "/docs/runbooks/index";
    const { view, fetchSpy } = renderNew(
      jsonResponse({ id: NEW_ID, url: SECTION_URL, content_hash: HASH, commit: null }, 201),
      (qc) => {
        // Prime the destination (the index page's own url canonicalizes to /docs/runbooks) so the
        // post-create navigation renders without a live fetch.
        qc.setQueryData(pageByPathQuery("runbooks/index").queryKey, pageResponse());
        qc.setQueryData(pageHtmlQuery(NEW_ID).queryKey, htmlResponse());
      },
    );

    await waitFor(() => expect(view.container.querySelector("[data-pb-new-page-form]")).not.toBeNull());
    fireEvent.click(view.container.querySelector<HTMLInputElement>("[data-pb-new-section]")!);
    fireEvent.change(view.container.querySelector<HTMLInputElement>("[data-pb-new-folder]")!, { target: { value: "runbooks" } });
    fireEvent.change(view.container.querySelector<HTMLInputElement>("[data-pb-new-title]")!, { target: { value: "Runbooks" } });
    fireEvent.click(view.container.querySelector<HTMLButtonElement>("[data-pb-new-create]")!);

    // The load-bearing fact: the create POSTs a section request — slug "index", folder = the section path.
    const post = await waitFor(() => {
      const call = fetchSpy.mock.calls.find(([, init]) => init?.method === "POST");
      expect(call).not.toBeUndefined();
      return call!;
    });
    expect(JSON.parse(post[1]!.body as string)).toEqual({ folder: "runbooks", title: "Runbooks", slug: "index" });
  });

  it("the new-section checkbox with a blank folder leaves Create disabled and does NOT POST", async () => {
    const { view, fetchSpy } = renderNew(jsonResponse({ id: NEW_ID, url: NEW_URL, content_hash: HASH, commit: null }, 201));

    await waitFor(() => expect(view.container.querySelector("[data-pb-new-page-form]")).not.toBeNull());
    fireEvent.change(view.container.querySelector<HTMLInputElement>("[data-pb-new-title]")!, { target: { value: "Runbooks" } });
    fireEvent.click(view.container.querySelector<HTMLInputElement>("[data-pb-new-section]")!);

    // A section needs a path: Create is disabled and a click never POSTs.
    const create = view.container.querySelector<HTMLButtonElement>("[data-pb-new-create]")!;
    expect(create.disabled).toBe(true);
    fireEvent.click(create);
    expect(fetchSpy.mock.calls.some(([, init]) => init?.method === "POST")).toBe(false);
  });

  it("renders the advisory slug/path preview in non-section mode (labelled approximate)", async () => {
    const { view } = renderNew(jsonResponse({ id: NEW_ID, url: NEW_URL, content_hash: HASH, commit: null }, 201));

    await waitFor(() => expect(view.container.querySelector("[data-pb-new-page-form]")).not.toBeNull());
    // No title/slug yet → no preview element (no lonely page.md on an empty form).
    expect(view.container.querySelector("[data-pb-new-preview]")).toBeNull();

    fireEvent.change(view.container.querySelector<HTMLInputElement>("[data-pb-new-title]")!, { target: { value: "My New Page" } });

    const preview = view.container.querySelector("[data-pb-new-preview]");
    expect(preview).not.toBeNull();
    expect(preview!.textContent).toContain("≈");
    expect(preview!.textContent).toContain("approx.");
    expect(preview!.textContent).toContain("my-new-page.md");
  });

  it("forwards the typed slug VERBATIM (case-preserving) — the server is the slug authority", async () => {
    const { view, fetchSpy } = renderNew(jsonResponse({ id: NEW_ID, url: NEW_URL, content_hash: HASH, commit: null }, 201), (qc) => {
      qc.setQueryData(pageByPathQuery("guides/my-new-page").queryKey, pageResponse());
      qc.setQueryData(pageHtmlQuery(NEW_ID).queryKey, htmlResponse());
    });

    await waitFor(() => expect(view.container.querySelector("[data-pb-new-page-form]")).not.toBeNull());
    fireEvent.change(view.container.querySelector<HTMLInputElement>("[data-pb-new-title]")!, { target: { value: "My New Page" } });
    fireEvent.change(view.container.querySelector<HTMLInputElement>("[data-pb-new-slug]")!, { target: { value: "My Page" } });
    // The preview lowercases (advisory)…
    expect(view.container.querySelector("[data-pb-new-preview]")!.textContent).toContain("my-page.md");
    fireEvent.click(view.container.querySelector<HTMLButtonElement>("[data-pb-new-create]")!);

    // …but the POST carries the raw user slug, case preserved — the client never derives a slug.
    const post = await waitFor(() => {
      const call = fetchSpy.mock.calls.find(([, init]) => init?.method === "POST");
      expect(call).not.toBeUndefined();
      return call!;
    });
    expect(JSON.parse(post[1]!.body as string).slug).toBe("My Page");
  });

  it("section mode hides BOTH the slug input and the advisory preview", async () => {
    const { view } = renderNew(jsonResponse({ id: NEW_ID, url: NEW_URL, content_hash: HASH, commit: null }, 201));

    await waitFor(() => expect(view.container.querySelector("[data-pb-new-page-form]")).not.toBeNull());
    fireEvent.change(view.container.querySelector<HTMLInputElement>("[data-pb-new-title]")!, { target: { value: "My New Page" } });
    expect(view.container.querySelector("[data-pb-new-slug]")).not.toBeNull();

    fireEvent.click(view.container.querySelector<HTMLInputElement>("[data-pb-new-section]")!);
    expect(view.container.querySelector("[data-pb-new-slug]")).toBeNull();
    expect(view.container.querySelector("[data-pb-new-preview]")).toBeNull();
  });

  it("a default (Blank) create omits the body field entirely (byte-identical to today)", async () => {
    const { view, fetchSpy } = renderNew(jsonResponse({ id: NEW_ID, url: NEW_URL, content_hash: HASH, commit: null }, 201), (qc) => {
      qc.setQueryData(pageByPathQuery("guides/my-new-page").queryKey, pageResponse());
      qc.setQueryData(pageHtmlQuery(NEW_ID).queryKey, htmlResponse());
    });

    await waitFor(() => expect(view.container.querySelector("[data-pb-new-page-form]")).not.toBeNull());
    submitCreate(view);

    const post = await waitFor(() => {
      const call = fetchSpy.mock.calls.find(([, init]) => init?.method === "POST");
      expect(call).not.toBeUndefined();
      return call!;
    });
    expect("body" in JSON.parse(post[1]!.body as string)).toBe(false);
  });

  it("selecting a template fills the body textarea and POSTs that scaffold", async () => {
    const { view, fetchSpy } = renderNew(jsonResponse({ id: NEW_ID, url: NEW_URL, content_hash: HASH, commit: null }, 201), (qc) => {
      qc.setQueryData(pageByPathQuery("guides/my-new-page").queryKey, pageResponse());
      qc.setQueryData(pageHtmlQuery(NEW_ID).queryKey, htmlResponse());
    });

    await waitFor(() => expect(view.container.querySelector("[data-pb-new-page-form]")).not.toBeNull());
    fireEvent.change(view.container.querySelector<HTMLInputElement>("[data-pb-new-title]")!, { target: { value: "My New Page" } });
    fireEvent.change(view.container.querySelector<HTMLSelectElement>("[data-pb-new-template]")!, { target: { value: "meeting" } });

    const textarea = view.container.querySelector<HTMLTextAreaElement>("[data-pb-new-body]")!;
    expect(textarea.value).toBe(MEETING_BODY);

    fireEvent.click(view.container.querySelector<HTMLButtonElement>("[data-pb-new-create]")!);
    const post = await waitFor(() => {
      const call = fetchSpy.mock.calls.find(([, init]) => init?.method === "POST");
      expect(call).not.toBeUndefined();
      return call!;
    });
    expect(JSON.parse(post[1]!.body as string).body).toBe(MEETING_BODY);
  });

  it("a template body flows to the POST even in section mode (no special-casing)", async () => {
    const SECTION_URL = "/docs/runbooks/index";
    const { view, fetchSpy } = renderNew(jsonResponse({ id: NEW_ID, url: SECTION_URL, content_hash: HASH, commit: null }, 201), (qc) => {
      qc.setQueryData(pageByPathQuery("runbooks/index").queryKey, pageResponse());
      qc.setQueryData(pageHtmlQuery(NEW_ID).queryKey, htmlResponse());
    });

    await waitFor(() => expect(view.container.querySelector("[data-pb-new-page-form]")).not.toBeNull());
    fireEvent.click(view.container.querySelector<HTMLInputElement>("[data-pb-new-section]")!);
    fireEvent.change(view.container.querySelector<HTMLInputElement>("[data-pb-new-folder]")!, { target: { value: "runbooks" } });
    fireEvent.change(view.container.querySelector<HTMLInputElement>("[data-pb-new-title]")!, { target: { value: "Runbooks" } });
    fireEvent.change(view.container.querySelector<HTMLSelectElement>("[data-pb-new-template]")!, { target: { value: "meeting" } });
    fireEvent.click(view.container.querySelector<HTMLButtonElement>("[data-pb-new-create]")!);

    const post = await waitFor(() => {
      const call = fetchSpy.mock.calls.find(([, init]) => init?.method === "POST");
      expect(call).not.toBeUndefined();
      return call!;
    });
    const parsed = JSON.parse(post[1]!.body as string);
    expect(parsed.slug).toBe("index");
    expect(parsed.body).toBe(MEETING_BODY);
  });

  it("a no-op template re-select does NOT clobber a manual body edit", async () => {
    const { view } = renderNew(jsonResponse({ id: NEW_ID, url: NEW_URL, content_hash: HASH, commit: null }, 201));

    await waitFor(() => expect(view.container.querySelector("[data-pb-new-page-form]")).not.toBeNull());
    fireEvent.change(view.container.querySelector<HTMLSelectElement>("[data-pb-new-template]")!, { target: { value: "meeting" } });
    const textarea = view.container.querySelector<HTMLTextAreaElement>("[data-pb-new-body]")!;
    fireEvent.change(textarea, { target: { value: `${MEETING_BODY}## Extra\n` } });
    // Re-firing the SAME value is a no-op — the edit survives.
    fireEvent.change(view.container.querySelector<HTMLSelectElement>("[data-pb-new-template]")!, { target: { value: "meeting" } });
    expect(textarea.value).toBe(`${MEETING_BODY}## Extra\n`);
  });

  it("a create collision surfaces page_exists with the server path", async () => {
    const { view } = renderNew(jsonResponse({ error: { code: "page_exists", message: "A page already exists at guides/my-new-page.md", path: "guides/my-new-page.md" } }, 409));

    await waitFor(() => expect(view.container.querySelector("[data-pb-new-page-form]")).not.toBeNull());
    submitCreate(view);

    await waitFor(() => expect(view.container.querySelector("[data-pb-new-page-form]")?.textContent).toContain("guides/my-new-page.md"));
  });
});
