import { useEffect, useState } from "react";
import * as admin from "../api/admin";
import { ApiError } from "../api/client";
import type { AuditEntry, RoleRow, SessionResponse, TokenMeta, UserMeta } from "../api/types";
import { clearCsrfToken } from "../api/csrf";

/**
 * A4b minimal admin UI (WI-11): API-token list/mint/revoke + audit read + role grants + (builtin-only) user list.
 * The auth mode comes from `GET /api/v1/session`, fetched once on mount. Mutations carry the `X-CSRF-Token` via the
 * SHARED csrf helper (admin.ts → csrf.ts) — the same mechanism the page editor uses; the component no longer threads
 * the token by hand. The server stays authoritative — the UI only HIDES what the API would 403/404 anyway (the user
 * panel is hidden when `auth_mode !== "builtin"`; a 403 renders the no-access state).
 */
export function Admin() {
  const [session, setSession] = useState<SessionResponse | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  useEffect(() => {
    // A fresh mount re-mints CSRF from this session load — drop any token cached from a prior session.
    clearCsrfToken();
    admin
      .getSession()
      .then(setSession)
      .catch(() => setLoadError("Could not load the session."));
  }, []);

  if (loadError) return <p data-pb-admin-error>{loadError}</p>;
  if (!session) return <p data-pb-admin-loading>Loading…</p>;

  return (
    <section className="pb-admin flex flex-col gap-8" data-pb-admin>
      <h1 className="text-2xl font-semibold text-ink">Administration</h1>
      {forbidden && (
        <p className="rounded-md border border-edge bg-surface px-4 py-3 text-muted" data-pb-admin-no-access>
          You don't have access to this area.
        </p>
      )}
      <TokenPanel onForbidden={() => setForbidden(true)} />
      <RolePanel onForbidden={() => setForbidden(true)} />
      <AuditPanel onForbidden={() => setForbidden(true)} />
      {session.auth_mode === "builtin" && <UserPanel onForbidden={() => setForbidden(true)} />}
    </section>
  );
}

/** Routes a thrown error: a 403 flips the no-access state; anything else is surfaced inline. */
function handle(error: unknown, onForbidden: () => void, setError: (m: string) => void) {
  if (error instanceof ApiError && error.status === 403) onForbidden();
  else setError(error instanceof ApiError ? error.message : "Request failed.");
}

function TokenPanel({ onForbidden }: { onForbidden: () => void }) {
  const [tokens, setTokens] = useState<TokenMeta[]>([]);
  const [minted, setMinted] = useState<string | null>(null);
  const [label, setLabel] = useState("");
  const [mode, setMode] = useState("read-only");
  const [error, setError] = useState<string | null>(null);

  const refresh = () =>
    admin
      .listTokens()
      .then((r) => setTokens(r.tokens))
      .catch((e) => handle(e, onForbidden, setError));

  useEffect(() => {
    refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const mint = async () => {
    try {
      const created = await admin.mintToken(label, mode);
      setMinted(created.plaintext); // shown ONCE
      setLabel("");
      await refresh();
    } catch (e) {
      handle(e, onForbidden, setError);
    }
  };

  const revoke = async (id: string) => {
    try {
      await admin.revokeToken(id);
      await refresh();
    } catch (e) {
      handle(e, onForbidden, setError);
    }
  };

  return (
    <div className="pb-admin-tokens flex flex-col gap-3" data-pb-admin-tokens>
      <h2 className="text-lg font-medium text-ink">API tokens</h2>
      {error && <p className="text-broken">{error}</p>}
      {minted && (
        <p className="rounded-md border border-edge bg-surface px-3 py-2 font-mono text-sm text-ink" data-pb-minted-token>
          Copy this now — it is shown once: {minted}
        </p>
      )}
      <div className="flex items-center gap-2">
        <input
          className="rounded-md border border-edge bg-surface px-2 py-1 text-sm"
          placeholder="label"
          value={label}
          onChange={(e) => setLabel(e.target.value)}
          data-pb-token-label
        />
        <select className="rounded-md border border-edge bg-surface px-2 py-1 text-sm" value={mode} onChange={(e) => setMode(e.target.value)} data-pb-token-mode>
          <option value="read-only">read-only</option>
          <option value="propose">propose</option>
          <option value="commit">commit</option>
        </select>
        <button type="button" className="rounded-md border border-edge px-3 py-1 text-sm text-ink" onClick={mint} data-pb-mint-token>
          Mint
        </button>
      </div>
      <ul className="flex flex-col gap-1" data-pb-token-list>
        {tokens.map((t) => (
          <li key={t.id} className="flex items-center justify-between text-sm" data-pb-token-row>
            <span className="font-mono text-muted">
              {t.id} · {t.label} · {t.mode}
              {t.revoked_at ? " · revoked" : ""}
            </span>
            {!t.revoked_at && (
              <button type="button" className="text-broken" onClick={() => revoke(t.id)} data-pb-revoke-token={t.id}>
                Revoke
              </button>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}

function RolePanel({ onForbidden }: { onForbidden: () => void }) {
  const [roles, setRoles] = useState<RoleRow[]>([]);
  const [issuer, setIssuer] = useState("proxy");
  const [externalId, setExternalId] = useState("");
  const [role, setRole] = useState("viewer");
  const [error, setError] = useState<string | null>(null);

  const refresh = () =>
    admin
      .listRoles()
      .then((r) => setRoles(r.roles))
      .catch((e) => handle(e, onForbidden, setError));

  useEffect(() => {
    refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const grant = async () => {
    try {
      await admin.grantRole(issuer, externalId, role);
      setExternalId("");
      await refresh();
    } catch (e) {
      handle(e, onForbidden, setError);
    }
  };

  return (
    <div className="pb-admin-roles flex flex-col gap-3" data-pb-admin-roles>
      <h2 className="text-lg font-medium text-ink">Roles</h2>
      {error && <p className="text-broken">{error}</p>}
      <div className="flex items-center gap-2">
        <input className="w-24 rounded-md border border-edge bg-surface px-2 py-1 text-sm" placeholder="issuer" value={issuer} onChange={(e) => setIssuer(e.target.value)} data-pb-role-issuer />
        <input className="rounded-md border border-edge bg-surface px-2 py-1 text-sm" placeholder="subject" value={externalId} onChange={(e) => setExternalId(e.target.value)} data-pb-role-subject />
        <select className="rounded-md border border-edge bg-surface px-2 py-1 text-sm" value={role} onChange={(e) => setRole(e.target.value)} data-pb-role-value>
          <option value="viewer">viewer</option>
          <option value="editor">editor</option>
          <option value="admin">admin</option>
        </select>
        <button type="button" className="rounded-md border border-edge px-3 py-1 text-sm text-ink" onClick={grant} data-pb-grant-role>
          Grant
        </button>
      </div>
      <ul className="flex flex-col gap-1" data-pb-role-list>
        {roles.map((r) => (
          <li key={`${r.issuer}/${r.external_id}`} className="font-mono text-sm text-muted" data-pb-role-row>
            {r.issuer}/{r.external_id} · {r.role}
          </li>
        ))}
      </ul>
    </div>
  );
}

function AuditPanel({ onForbidden }: { onForbidden: () => void }) {
  const [entries, setEntries] = useState<AuditEntry[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    admin
      .listAudit(50)
      .then((r) => setEntries(r.entries))
      .catch((e) => handle(e, onForbidden, setError));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div className="pb-admin-audit flex flex-col gap-3" data-pb-admin-audit>
      <h2 className="text-lg font-medium text-ink">Audit log</h2>
      {error && <p className="text-broken">{error}</p>}
      <ul className="flex flex-col gap-1" data-pb-audit-list>
        {entries.map((a) => (
          <li key={a.id} className="font-mono text-sm text-muted" data-pb-audit-row>
            {a.ts} · {a.principal_kind} · {a.action} · {a.resource} · {a.decision}
          </li>
        ))}
      </ul>
    </div>
  );
}

function UserPanel({ onForbidden }: { onForbidden: () => void }) {
  const [users, setUsers] = useState<UserMeta[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    admin
      .listUsers()
      .then((r) => setUsers(r.users))
      .catch((e) => handle(e, onForbidden, setError));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div className="pb-admin-users flex flex-col gap-3" data-pb-admin-users>
      <h2 className="text-lg font-medium text-ink">Users</h2>
      {error && <p className="text-broken">{error}</p>}
      <ul className="flex flex-col gap-1" data-pb-user-list>
        {users.map((u) => (
          <li key={u.id} className="font-mono text-sm text-muted" data-pb-user-row>
            {u.username}
            {u.disabled ? " · disabled" : ""}
          </li>
        ))}
      </ul>
    </div>
  );
}
