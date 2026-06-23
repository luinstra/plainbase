import { fireEvent, render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Admin } from "../components/Admin";

/**
 * A4b WI-11 admin UI. Mocked fetch drives the panels: tokens list/mint/revoke (the one-time plaintext shown once),
 * the audit + role panels render rows, a mutation attaches the `X-CSRF-Token` from `/session`, a 403 renders the
 * no-access state, and the builtin-only user panel is ABSENT when `auth_mode === "proxy"`.
 */

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

function session(authMode = "builtin") {
  return { authenticated: true, username: "admin", csrf_token: "csrf-abc", auth_mode: authMode };
}

interface Routes {
  session?: () => Response;
  tokens?: Response;
  audit?: Response;
  roles?: Response;
  users?: Response;
  onMutation?: (url: string, init: RequestInit) => Response;
}

function stubFetch(routes: Routes) {
  const fetchSpy = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input.toString();
    if (init?.method === "POST") return (routes.onMutation ?? (() => jsonResponse({})))(url, init ?? {});
    if (url === "/api/v1/session") return (routes.session ?? (() => jsonResponse(session())))();
    if (url.startsWith("/api/v1/admin/tokens")) return routes.tokens ?? jsonResponse({ tokens: [] });
    if (url.startsWith("/api/v1/admin/audit")) return routes.audit ?? jsonResponse({ entries: [] });
    if (url.startsWith("/api/v1/admin/roles")) return routes.roles ?? jsonResponse({ roles: [] });
    if (url.startsWith("/api/v1/admin/users")) return routes.users ?? jsonResponse({ users: [] });
    return jsonResponse({});
  });
  vi.stubGlobal("fetch", fetchSpy);
  return fetchSpy;
}

afterEach(() => vi.unstubAllGlobals());

describe("A4b admin UI", () => {
  it("renders the token, role, and audit panels", async () => {
    stubFetch({
      tokens: jsonResponse({ tokens: [{ id: "t1", label: "ci", mode: "read-only", created_at: "2026", last_used_at: null, expires_at: null, revoked_at: null }] }),
      audit: jsonResponse({ entries: [{ id: "a1", ts: "2026", principal_kind: "human", issuer: "builtin", external_id: "x", action: "MANAGE", resource: "admin", decision: "allowed" }] }),
      roles: jsonResponse({ roles: [{ issuer: "proxy", external_id: "bob", role: "admin", created_at: "2026" }] }),
    });
    const view = render(<Admin />);
    await waitFor(() => expect(view.container.querySelector("[data-pb-admin-tokens]")).not.toBeNull());
    expect(view.container.querySelector("[data-pb-admin-roles]")).not.toBeNull();
    expect(view.container.querySelector("[data-pb-admin-audit]")).not.toBeNull();
    await waitFor(() => expect(view.container.querySelector("[data-pb-token-row]")?.textContent).toContain("t1"));
    expect(view.container.querySelector("[data-pb-audit-row]")?.textContent).toContain("MANAGE");
    expect(view.container.querySelector("[data-pb-role-row]")?.textContent).toContain("bob");
  });

  it("mint shows the one-time token and attaches the X-CSRF-Token header", async () => {
    let mutationInit: RequestInit | null = null;
    const fetchSpy = stubFetch({
      onMutation: (_url, init) => {
        mutationInit = init;
        return jsonResponse({ id: "tNew", plaintext: "pb_new_secret" }, 201);
      },
    });
    const view = render(<Admin />);
    await waitFor(() => expect(view.container.querySelector("[data-pb-mint-token]")).not.toBeNull());

    fireEvent.change(view.container.querySelector<HTMLInputElement>("[data-pb-token-label]")!, { target: { value: "ci" } });
    fireEvent.click(view.container.querySelector<HTMLButtonElement>("[data-pb-mint-token]")!);

    await waitFor(() => expect(view.container.querySelector("[data-pb-minted-token]")?.textContent).toContain("pb_new_secret"));
    expect((mutationInit!.headers as Record<string, string>)["X-CSRF-Token"]).toBe("csrf-abc");
    expect(fetchSpy).toHaveBeenCalledWith("/api/v1/admin/tokens", expect.objectContaining({ method: "POST" }));
  });

  it("revoke calls the revoke endpoint", async () => {
    const fetchSpy = stubFetch({
      tokens: jsonResponse({ tokens: [{ id: "t1", label: "ci", mode: "read-only", created_at: "2026", last_used_at: null, expires_at: null, revoked_at: null }] }),
      onMutation: () => new Response("", { status: 204 }),
    });
    const view = render(<Admin />);
    await waitFor(() => expect(view.container.querySelector("[data-pb-revoke-token='t1']")).not.toBeNull());
    fireEvent.click(view.container.querySelector<HTMLButtonElement>("[data-pb-revoke-token='t1']")!);
    await waitFor(() => expect(fetchSpy).toHaveBeenCalledWith("/api/v1/admin/tokens/t1/revoke", expect.objectContaining({ method: "POST" })));
  });

  it("a 403 on a panel load renders the no-access state", async () => {
    stubFetch({ tokens: jsonResponse({ error: { code: "forbidden", message: "no" } }, 403) });
    const view = render(<Admin />);
    await waitFor(() => expect(view.container.querySelector("[data-pb-admin-no-access]")).not.toBeNull());
  });

  it("the user-CRUD panel is ABSENT in proxy mode (auth_mode !== builtin)", async () => {
    stubFetch({ session: () => jsonResponse(session("proxy")) });
    const view = render(<Admin />);
    await waitFor(() => expect(view.container.querySelector("[data-pb-admin-tokens]")).not.toBeNull());
    expect(view.container.querySelector("[data-pb-admin-users]")).toBeNull();
  });

  it("the user panel IS present in builtin mode", async () => {
    stubFetch({ session: () => jsonResponse(session("builtin")), users: jsonResponse({ users: [{ id: "u1", username: "alice", display_name: null, disabled: false }] }) });
    const view = render(<Admin />);
    await waitFor(() => expect(view.container.querySelector("[data-pb-admin-users]")).not.toBeNull());
    await waitFor(() => expect(view.container.querySelector("[data-pb-user-row]")?.textContent).toContain("alice"));
  });
});
