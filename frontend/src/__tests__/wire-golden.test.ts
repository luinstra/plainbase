// @vitest-environment node
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import type {
  ApplyResultResponse,
  AuditListResponse,
  BodyTooLargeEnvelope,
  ChangeDetail,
  ConflictedResponse,
  CreatePageRequest,
  CreatedResponse,
  CreatedTokenResponse,
  DegradedToProposalResponse,
  DiffResponse,
  ErrorEnvelope,
  HistoryResponse,
  ListChangesResponse,
  PageExistsEnvelope,
  PageHtmlResponse,
  PageResponse,
  PreviewResponse,
  RebasedResponse,
  RejectChangeRequest,
  RoleListResponse,
  SearchResponse,
  SessionResponse,
  TokenListResponse,
  TreeResponse,
  UnsupportedEditEnvelope,
  UserListResponse,
  WriteConflictEnvelope,
  WrittenButUnindexedResponse,
  WrittenResponse,
} from "../api/types";

/**
 * The frontend half of the shared wire golden: every fixture entry has a TYPED LITERAL twin, so a
 * types.ts field rename/removal/retype is a compile error (excess-property checking catches a field
 * the fixture lacks), and `toEqual` chains the literal to the fixture the server test asserts
 * against its DTOs. The fixture is fs-loaded (JSON.parse yields `any` — all type pinning comes from
 * the twins; a JSON module import is unresolvable under this tsconfig, no `resolveJsonModule`).
 */
const fixture = JSON.parse(
  readFileSync(fileURLToPath(new URL("../api/__fixtures__/wire-golden.json", import.meta.url)), "utf8"),
) as Record<string, unknown>;

const PAGE_1 = "01970000-0000-7000-8000-000000000001";
const PAGE_2 = "01970000-0000-7000-8000-000000000002";
const HASH_A = "sha256:" + "a".repeat(64);
const HASH_B = "sha256:" + "b".repeat(64);
const HASH_C = "sha256:" + "c".repeat(64);
const HASH_D = "sha256:" + "d".repeat(64);
const COMMIT_1 = "1111111111111111111111111111111111111111";
const COMMIT_2 = "2222222222222222222222222222222222222222";
const REINDEX_WARNING = {
  code: "reindex_deferred",
  message: "Saved to disk; search/history update deferred to next startup reconciliation.",
};

const treeResponse: TreeResponse = {
  roots: [
    {
      root: "main",
      available: true,
      editable: true,
      primary: true,
      tree: {
        type: "folder",
        name: "",
        title: null,
        description: null,
        path: "",
        url: "/docs/main",
        page_count: 0,
        children: [
          {
            type: "folder",
            name: "guides",
            title: "Guides",
            description: "How-to guides",
            path: "guides",
            url: "/docs/main/guides",
            page_count: 1,
            children: [
              {
                type: "page",
                id: PAGE_1,
                title: "Deploy Guide",
                slug: "deploy-guide",
                path: "guides/deploy-guide.md",
                url: "/docs/main/guides/deploy-guide",
                status: "published",
                updated: "2026-06-01",
              },
            ],
          },
          { type: "folder", name: "attic", title: null, description: null, path: "attic", url: null, page_count: 0, children: [] },
          { type: "page", id: PAGE_2, title: "Shadowed", slug: "shadowed", path: "shadowed.md", url: null, status: "published", updated: null },
        ],
      },
    },
  ],
};

// url is deliberately null here (the collision-loser branch): every `| null` field must be null in
// at least one entry, so a TS de-nullification can't ship silently. The non-null page url shape
// stays pinned by treeResponse/createdResponse.
const pageResponse: PageResponse = {
  id: PAGE_1,
  root: "main",
  path: "guides/deploy-guide.md",
  slug: "deploy-guide",
  url: null,
  title: "Deploy Guide",
  markdown: "# Deploy Guide\n\nShip it.\n",
  frontmatter: { title: "Deploy Guide", tags: ["ops", "deploy"] },
  content_hash: HASH_A,
  id_materialized: true,
  commit: null,
  citation: {
    page_id: PAGE_1,
    heading_id: null,
    path: "guides/deploy-guide.md",
    content_hash: HASH_A,
    commit: null,
    uri: `plainbase://main/${PAGE_1}@${HASH_A}`,
  },
};

const pageHtmlResponse: PageHtmlResponse = {
  id: PAGE_2,
  root: "main",
  path: "shadowed.md",
  slug: "shadowed",
  url: null,
  title: "Shadowed",
  html: '<h1 id="shadowed">Shadowed</h1>',
  // Top-level commit null (the off-Git branch) for the null-coverage policy; the citation keeps a
  // non-null commit — the only entry pinning CitationDto.commit's populated form.
  content_hash: HASH_B,
  commit: null,
  headings: [{ id: "shadowed", level: 1, text: "Shadowed" }],
  citation: {
    page_id: PAGE_2,
    heading_id: "shadowed",
    path: "shadowed.md",
    content_hash: HASH_B,
    commit: COMMIT_1,
    uri: `plainbase://main/${PAGE_2}@${HASH_B}`,
  },
};

const errorEnvelope: ErrorEnvelope = {
  error: { code: "page_not_found", message: "No page with that id" },
};

const searchResponse: SearchResponse = {
  query: "deploy",
  engine: "fts5",
  limit: 20,
  offset: 0,
  total: 1,
  hits: [
    {
      page_id: PAGE_2,
      root: "main",
      path: "shadowed.md",
      url: null,
      title: "Shadowed",
      heading_id: null,
      heading_text: null,
      heading_path: [],
      snippet: "Deploy targets are listed here.",
      highlights: [{ start: 0, end: 6 }],
      score: 1.25,
      citation: {
        page_id: PAGE_2,
        heading_id: null,
        path: "shadowed.md",
        content_hash: HASH_B,
        commit: null,
        uri: `plainbase://main/${PAGE_2}@${HASH_B}`,
      },
    },
  ],
};

const writtenResponse: WrittenResponse = { content_hash: HASH_C, commit: null };

const writtenButUnindexedResponse: WrittenButUnindexedResponse = {
  content_hash: HASH_C,
  commit: null,
  warning: REINDEX_WARNING,
};

const degradedToProposalResponse: DegradedToProposalResponse = {
  degraded: true,
  proposal_id: "01970000-0000-7000-8000-0000000000a1",
  status: "PENDING",
  unified_diff: "--- a/guides/deploy-guide.md\n+++ b/guides/deploy-guide.md\n",
};

const writeConflictEnvelope: WriteConflictEnvelope = {
  error: {
    code: "conflict",
    reason: "content_changed",
    message: "The page changed on disk since you loaded it.",
    current_content: "# Deploy Guide\n\nEdited elsewhere.\n",
    current_hash: HASH_D,
    current_path: "guides/deploy-guide.md",
  },
};

const writeConflictEnvelopeDeleted: WriteConflictEnvelope = {
  error: {
    code: "conflict",
    reason: "page_deleted",
    message: "The page no longer exists on disk.",
    current_content: null,
    current_hash: null,
    current_path: null,
  },
};

const unsupportedEditEnvelope: UnsupportedEditEnvelope = {
  error: { code: "slug_change_unsupported", field: "slug", message: "Changing slug is a move, not a save (deferred)." },
};

const bodyTooLargeEnvelope: BodyTooLargeEnvelope = {
  error: { code: "body_too_large", message: "Request body exceeds the 1048576-byte limit", max_bytes: 1048576 },
};

const pageExistsEnvelope: PageExistsEnvelope = {
  error: { code: "page_exists", message: "A page already exists at guides/deploy-guide.md", path: "guides/deploy-guide.md" },
};

// `root` rides the create request (multi-root C4). It is pinned HERE because omitting it is not a type error -
// it is a SILENT default to `main`, i.e. the page lands in the wrong tree with no failure anywhere.
const createPageRequest: CreatePageRequest = { root: "extra", title: "Deploy Guide" };

const createdResponse: CreatedResponse = {
  id: PAGE_1,
  url: "/docs/main/guides/deploy-guide",
  content_hash: HASH_A,
  commit: null,
};

// Asserted against the SAME TS CreatedResponse as the clean create — pinning the documented merge
// of the server's two create DTOs (url present-null + warning on the unindexed branch).
const createdButUnindexedResponse: CreatedResponse = {
  id: PAGE_1,
  url: null,
  content_hash: HASH_A,
  commit: null,
  warning: REINDEX_WARNING,
};

const previewResponse: PreviewResponse = {
  html: '<h1 id="deploy-guide">Deploy Guide</h1>',
  headings: [{ id: "deploy-guide", level: 1, text: "Deploy Guide" }],
};

const historyResponse: HistoryResponse = {
  git_enabled: true,
  commits: [
    {
      sha: COMMIT_1,
      author_name: "Ada Lovelace",
      author_email: "ada@example.com",
      author_time: "2026-06-01T12:00:00Z",
      committer_name: "Ada Lovelace",
      committer_email: "ada@example.com",
      committer_time: "2026-06-01T12:00:00Z",
      message: "docs: update deploy guide",
    },
  ],
};

const diffResponse: DiffResponse = {
  git_enabled: true,
  from: COMMIT_1,
  to: COMMIT_2,
  path: "guides/deploy-guide.md",
  unified_diff: "--- a/guides/deploy-guide.md\n+++ b/guides/deploy-guide.md\n",
};

const sessionResponse: SessionResponse = {
  authenticated: false,
  username: null,
  csrf_token: null,
  auth_mode: "builtin",
};

const tokenListResponse: TokenListResponse = {
  tokens: [
    {
      id: "01970000-0000-7000-8000-0000000000b1",
      label: "ci-bot",
      mode: "propose",
      created_at: "2026-06-01T12:00:00Z",
      last_used_at: null,
      expires_at: null,
      revoked_at: null,
    },
  ],
};

const createdTokenResponse: CreatedTokenResponse = {
  id: "01970000-0000-7000-8000-0000000000b1",
  plaintext: "pb_c2VjcmV0LXRva2Vu",
};

const auditListResponse: AuditListResponse = {
  entries: [
    {
      id: "01970000-0000-7000-8000-0000000000c1",
      ts: "2026-06-01T12:00:00Z",
      principal_kind: "human",
      issuer: null,
      external_id: null,
      action: "page.write",
      resource: "guides/deploy-guide.md",
      decision: "allow",
    },
  ],
};

const roleListResponse: RoleListResponse = {
  roles: [{ issuer: "proxy", external_id: "ada@example.com", role: "admin", created_at: "2026-06-01T12:00:00Z" }],
};

const userListResponse: UserListResponse = {
  users: [{ id: "01970000-0000-7000-8000-0000000000d1", username: "ada", display_name: null, disabled: false }],
};

const listChangesResponse: ListChangesResponse = {
  proposals: [
    {
      id: "01970000-0000-7000-8000-0000000000a2",
      operation: "create",
      status: "PENDING",
      root: "main",
      target_path: "guides/rollback.md",
      page_id: null,
      base_drifted: false,
      author_label: "ci-bot",
      created_at: "2026-06-01T12:00:00Z",
      rationale: "Add a rollback guide.",
    },
  ],
};

const changeDetail: ChangeDetail = {
  id: "01970000-0000-7000-8000-0000000000a3",
  operation: "create",
  status: "PENDING",
  root: "main",
  target_path: "guides/rollback.md",
  page_id: null,
  base_hash: null,
  base_drifted: false,
  author_label: "ci-bot",
  author_issuer: "plainbase",
  author_external_id: "01970000-0000-7000-8000-0000000000b1",
  created_at: "2026-06-01T12:00:00Z",
  rationale: "Add a rollback guide.",
  unified_diff: "--- /dev/null\n+++ b/guides/rollback.md\n",
  approver_issuer: null,
  approver_external_id: null,
  decision_comment: null,
  decided_at: null,
  applied_commit: null,
  status_reason: null,
};

const applyResultResponse: ApplyResultResponse = {
  new_hash: HASH_D,
  commit_sha: null,
  applied_at: "2026-06-02T09:00:00Z",
  warnings: null,
};

const conflictedResponse: ConflictedResponse = {
  code: "conflicted",
  current_hash: null,
  current_path: null,
};

const rebasedResponse: RebasedResponse = {
  new_base_hash: HASH_D,
  unified_diff: "--- a/guides/deploy-guide.md\n+++ b/guides/deploy-guide.md\n",
  status: "PENDING",
};

const rejectChangeRequest: RejectChangeRequest = {};

/** One twin per fixture entry — the key-set assert below makes a silently-skipped entry impossible. */
const twins: Record<string, unknown> = {
  treeResponse,
  pageResponse,
  pageHtmlResponse,
  errorEnvelope,
  searchResponse,
  writtenResponse,
  writtenButUnindexedResponse,
  degradedToProposalResponse,
  writeConflictEnvelope,
  writeConflictEnvelopeDeleted,
  unsupportedEditEnvelope,
  bodyTooLargeEnvelope,
  pageExistsEnvelope,
  createPageRequest,
  createdResponse,
  createdButUnindexedResponse,
  previewResponse,
  historyResponse,
  diffResponse,
  sessionResponse,
  tokenListResponse,
  createdTokenResponse,
  auditListResponse,
  roleListResponse,
  userListResponse,
  listChangesResponse,
  changeDetail,
  applyResultResponse,
  conflictedResponse,
  rebasedResponse,
  rejectChangeRequest,
};

describe("wire golden (types.ts twins)", () => {
  it("covers exactly the fixture's entries — 31, none silently skipped", () => {
    expect(Object.keys(twins).sort()).toEqual(Object.keys(fixture).sort());
    expect(Object.keys(twins)).toHaveLength(31);
  });

  it.each(Object.keys(twins))("%s matches the shared wire golden", (key) => {
    expect(fixture[key]).toEqual(twins[key]);
  });
});
