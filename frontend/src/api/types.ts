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
