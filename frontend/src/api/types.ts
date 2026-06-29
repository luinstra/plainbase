/**
 * PB-REST-1 wire shapes (frozen, §A4) — transcribed from the server DTOs
 * (server: frameworks/ktor/dto/RestDtos.kt). The SPA consumes `id`/`path`/`url`
 * verbatim; URL semantics are never re-derived client-side.
 */

export interface TreeFolder {
  type: "folder";
  name: string;
  title: string | null;
  /** The `_folder.yaml` plaintext summary on the landing card — null when absent/blank (provisional, Chunk-3). */
  description: string | null;
  path: string;
  /** The folder's `/docs` URL prefix (percent-encoded, ready to use) — the landing-view address (ADR-0003); null for a collision-loser subtree. */
  url: string | null;
  /** Count of DIRECT child pages only (not recursive) — drives the `path/ · N pages` meta (provisional, Chunk-3). */
  page_count: number;
  children: TreeNode[];
}

export interface TreePage {
  type: "page";
  id: string;
  title: string;
  slug: string;
  /** Content-relative file path, e.g. "guides/deploy-guide.md". */
  path: string;
  /** Canonical `/docs/...` URL (percent-encoded, ready to use) — null for a collision loser. */
  url: string | null;
  status: string;
  /** Editorial frontmatter date, server-validated to `YYYY-MM-DD` — null when absent/invalid (provisional, Chunk-3). */
  updated: string | null;
}

export type TreeNode = TreeFolder | TreePage;

export interface TreeResponse {
  root: TreeFolder;
}

export interface CitationDto {
  page_id: string;
  heading_id: string | null;
  path: string;
  content_hash: string;
  commit: string | null;
  uri: string;
}

export interface PageResponse {
  id: string;
  path: string;
  slug: string;
  url: string | null;
  title: string;
  markdown: string;
  frontmatter: Record<string, unknown>;
  content_hash: string;
  id_materialized: boolean;
  commit: string | null;
  citation: CitationDto;
}

export interface HeadingDto {
  id: string;
  level: number;
  text: string;
}

export interface PageHtmlResponse {
  id: string;
  path: string;
  slug: string;
  url: string | null;
  title: string;
  html: string;
  content_hash: string;
  commit: string | null;
  headings: HeadingDto[];
  citation: CitationDto;
}

export interface ErrorEnvelope {
  error: { code: string; message: string };
}

/**
 * PB-SEARCH-1 §A2 wire shapes (frozen) — transcribed from the server DTO
 * (server: frameworks/ktor/dto/SearchDtos.kt). Present-null fields are typed `| null`.
 * The SPA consumes `url`/`heading_id`/`heading_text`/`heading_path`/`snippet`/`highlights`
 * verbatim — no client re-derivation (§A4).
 */
export interface SearchHit {
  page_id: string;
  path: string;
  url: string | null;
  title: string;
  heading_id: string | null;
  heading_text: string | null;
  heading_path: string[];
  snippet: string;
  /** §A3: UTF-16 code-unit offsets into `snippet`, half-open `[start, end)`. */
  highlights: { start: number; end: number }[];
  score: number;
  citation: CitationDto;
}

export interface SearchResponse {
  query: string;
  engine: string;
  limit: number;
  offset: number;
  total: number;
  hits: SearchHit[];
}

/**
 * PB-WRITE-1 wire shapes (frozen, §A4) — transcribed from the server DTOs
 * (server: frameworks/ktor/dto/WriteDtos.kt). The variant 200/201 DTOs encode field ABSENCE as
 * shape: a clean save has no `warning` key; only `WrittenButUnindexed` carries one. The hash is
 * SERVER-OWNED — `content_hash` is the next CAS token, echoed verbatim into `If-Match`, never
 * re-derived client-side. The 409/422/413 envelopes are richer than the bare `ErrorEnvelope`.
 */

/** 200 save: exactly `{content_hash, commit}` — no `warning` key. */
export interface WrittenResponse {
  content_hash: string;
  commit: string | null;
}

/** 200 save, R2: the bytes are durable but the search/history sync deferred — a SUCCESS with a `warning`. */
export interface WrittenButUnindexedResponse {
  content_hash: string;
  commit: string | null;
  warning: { code: string; message: string };
}

/**
 * 202 (P5): an agent COMMIT write outside agentDirectCommit.globs, degraded to a proposal. Both `PUT
 * /api/v1/pages/{id}` (edit) AND `POST /api/v1/pages` (create) can answer with this. Clients MUST check
 * `degraded`/status before treating a 2xx as an applied write/create. NEVER a field on the frozen WrittenResponse.
 */
export interface DegradedToProposalResponse {
  degraded: true;
  proposal_id: string;
  status: "PENDING";
  unified_diff: string;
}

/** The frozen drift `reason` set (additive-only); `page_moved` is producer-reserved. */
export type WriteConflictReason = "content_changed" | "page_moved" | "page_deleted";

/** 409 drift envelope — distinct from `ErrorEnvelope` (adds `reason` + `current_*`). */
export interface WriteConflictEnvelope {
  error: {
    code: string;
    reason: WriteConflictReason;
    message: string;
    /** Null for `page_deleted` (nothing to rebase against). */
    current_content: string | null;
    current_hash: string | null;
    current_path: string | null;
  };
}

/** 422 unsupported-edit envelope (a rename, not a drift): `code` + `field`, NO `reason`, NO `current_*`. */
export interface UnsupportedEditEnvelope {
  error: { code: string; field: string; message: string };
}

/** 413 envelope — the plain `{code, message}` PLUS the authoritative `max_bytes`. */
export interface BodyTooLargeEnvelope {
  error: { code: string; message: string; max_bytes: number };
}

/** 409 create-collision envelope — the plain `{code, message}` PLUS the attempted `path`. */
export interface PageExistsEnvelope {
  error: { code: string; message: string; path: string };
}

/** `POST /api/v1/pages` request — the server mints the id and derives the path/slug; the client never does. */
export interface CreatePageRequest {
  folder?: string;
  title: string;
  slug?: string | null;
  body?: string | null;
}

/**
 * 201 create response — the frozen `WrittenResponse` keys PLUS the minted `id` and the
 * server-authoritative canonical `url` (W6). A clean create (no `warning`) ALWAYS carries a non-null
 * `url`, and the client navigates DIRECTLY to it (never a client-derived slug). The
 * `WrittenButUnindexed` create (with a `warning`) carries `url: null`: the page is unpublished, so
 * there is no reliable canonical url until reconciliation — the client shows the warning and does NOT
 * navigate on that branch, so the null is never read.
 */
export interface CreatedResponse {
  id: string;
  url: string | null;
  content_hash: string;
  commit: string | null;
  warning?: { code: string; message: string };
}

/** `POST /api/v1/preview` response (PRIVATE / NON-CONTRACTUAL): rendered HTML + document-order headings. */
export interface PreviewResponse {
  html: string;
  headings: HeadingDto[];
}

/**
 * W5 history/diff read shapes (NON-FROZEN — server-as-authority, no golden pins them;
 * transcribed from server frameworks/ktor/dto/HistoryDtos.kt). `git_enabled` lets the client tell
 * "Git off" (false) apart from "Git on, no commits yet" (true + empty `commits`). Timestamps are
 * ISO-8601 strings (the server's kotlin.time.Instant never reaches the wire).
 */
export interface CommitDto {
  sha: string;
  author_name: string;
  author_email: string;
  author_time: string; // ISO-8601
  committer_name: string;
  committer_email: string;
  committer_time: string; // ISO-8601
  message: string;
}

/** `GET …/history` — `commits` is NEWEST-FIRST. */
export interface HistoryResponse {
  git_enabled: boolean;
  commits: CommitDto[];
}

/** `GET …/diff` — the file's unified diff between two commits. */
export interface DiffResponse {
  git_enabled: boolean;
  from: string;
  to: string;
  path: string;
  unified_diff: string;
}

// ---- A4b: auth session + admin management wire shapes (server: AuthDtos.kt) -------------------

/** `GET /api/v1/session` — the auth state + a fresh CSRF token + the auth mode (drives the UI hint). */
export interface SessionResponse {
  authenticated: boolean;
  username: string | null;
  csrf_token: string | null;
  auth_mode: string; // "builtin" | "proxy" | "off"
}

/** One API token — metadata only (never a secret/plaintext). */
export interface TokenMeta {
  id: string;
  label: string;
  mode: string;
  created_at: string;
  last_used_at: string | null;
  expires_at: string | null;
  revoked_at: string | null;
}

export interface TokenListResponse {
  tokens: TokenMeta[];
}

/** `POST /api/v1/admin/tokens` — the minted token's id + the one-time plaintext (shown ONCE). */
export interface CreatedTokenResponse {
  id: string;
  plaintext: string;
}

/** One audit decision row. */
export interface AuditEntry {
  id: string;
  ts: string;
  principal_kind: string;
  issuer: string | null;
  external_id: string | null;
  action: string;
  resource: string;
  decision: string;
}

export interface AuditListResponse {
  entries: AuditEntry[];
}

/** One subject→role row. */
export interface RoleRow {
  issuer: string;
  external_id: string;
  role: string;
  created_at: string;
}

export interface RoleListResponse {
  roles: RoleRow[];
}

/** One admin-list user — metadata only. */
export interface UserMeta {
  id: string;
  username: string;
  display_name: string | null;
  disabled: boolean;
}

export interface UserListResponse {
  users: UserMeta[];
}

// ---- PB-PROPOSE-1 (P1a/P1b) change shapes (frozen — server: ProposalDtos.kt) -------------------
//
// The agent-facing proposal wire, transcribed 1:1 from the server `@SerialName` strings. Casing is
// ASYMMETRIC and load-bearing: `status` is UPPERCASE (ProposalStatusWire), `operation` is lowercase
// (ProposalOperationWire). `unified_diff` is server-computed text the client renders VERBATIM — never
// re-derived (§0.13(i)). `base_drifted` is a LIVE-derived read flag (present on a still-PENDING row),
// DISTINCT from the `CONFLICTED` status reached only after an apply hit disk-drift.

/** The frozen status set — UPPERCASE on the wire (append-only). */
export type ProposalStatus = "PENDING" | "APPLYING" | "APPLIED" | "REJECTED" | "CONFLICTED" | "FAILED";

/** The frozen operation set — lowercase on the wire (append-only). */
export type ProposalOperation = "edit" | "create";

/** A `list_changes` element. `page_id` carries the target page's id — server-reserved at propose/degrade time
 *  for a create — or null when none is bound; `base_drifted` is the live triage datum. */
export interface ChangeSummary {
  id: string;
  operation: ProposalOperation;
  status: ProposalStatus;
  target_path: string;
  page_id: string | null;
  base_drifted: boolean;
  author_label: string;
  created_at: string;
  rationale: string;
}

/** `GET /api/v1/changes` — a WRAPPER object (never a bare array), so additive pagination can land later. */
export interface ListChangesResponse {
  proposals: ChangeSummary[];
}

/** `get_change` (and the reject success body) — the summary fields PLUS the stored diff + decision fields. */
export interface ChangeDetail {
  id: string;
  operation: ProposalOperation;
  status: ProposalStatus;
  target_path: string;
  page_id: string | null;
  base_hash: string | null;
  base_drifted: boolean;
  author_label: string;
  author_issuer: string;
  author_external_id: string;
  created_at: string;
  rationale: string;
  unified_diff: string;
  approver_issuer: string | null;
  approver_external_id: string | null;
  decision_comment: string | null;
  decided_at: string | null;
  applied_commit: string | null;
  status_reason: string | null;
}

/** `POST …/{id}/approve` 200 — the applied result (the page moved; the diff was committed). */
export interface ApplyResultResponse {
  new_hash: string;
  commit_sha: string | null;
  applied_at: string;
  warnings: string[] | null;
}

/** `POST …/{id}/approve` 409 — an apply hit disk-drift; the proposal is now rebasable. `code` is "conflicted". */
export interface ConflictedResponse {
  code: "conflicted";
  current_hash: string | null;
  current_path: string | null;
}

/** `POST …/{id}/rebase` 200 — the re-pinned base + recomputed diff; `status` is back to "PENDING". */
export interface RebasedResponse {
  new_base_hash: string;
  unified_diff: string;
  status: "PENDING";
}

/** `POST …/{id}/reject` request — an optional reviewer comment (the comment is REJECT-only; approve has no body). */
export interface RejectChangeRequest {
  comment?: string | null;
}
