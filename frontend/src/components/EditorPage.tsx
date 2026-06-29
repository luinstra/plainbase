import { defaultKeymap, history, historyKeymap } from "@codemirror/commands";
import { markdown } from "@codemirror/lang-markdown";
import { HighlightStyle, syntaxHighlighting } from "@codemirror/language";
import { tags } from "@lezer/highlight";
import { EditorState } from "@codemirror/state";
import { EditorView, keymap } from "@codemirror/view";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter, useRouterState } from "@tanstack/react-router";
import { useEffect, useRef, useState } from "react";
import { ApiError, createPage, putPageRaw, type SaveResult } from "../api/client";
import { encodeTreePath, invalidateAfterWrite, pageByPathQuery, previewQuery } from "../api/queries";
import type { WriteConflictReason } from "../api/types";
import { frontmatterValue, splitFrontmatter } from "../lib/frontmatter";
import { insertLink, toggleBold, toggleCode, toggleItalic } from "../lib/markdownCommands";
import { PAGE_TEMPLATES } from "../lib/pageTemplates";
import { previewPath } from "../lib/slugPreview";
import { useDebounced } from "../lib/useDebounced";
import { EditorToolbar } from "./EditorToolbar";
import { MetaForm } from "./MetaForm";
import { NotFoundView } from "./NotFound";
import { Prose } from "./Prose";

/**
 * The `?mode=edit` editor surface (W6, D-1/D-4): a CodeMirror 6 Markdown editor over the FULL document
 * buffer (frontmatter + body as one document), a debounced server-preview pane, and a CAS save against
 * `PUT /api/v1/pages/{id}` with `base_hash` carried as the `If-Match` ETag. Owns its OWN `useQuery` for
 * the initial buffer (component-level data-fetching — no route loader). The server is the identity
 * authority: a tampered id/slug surfaces the 422 refusal, never a silent save (D-4).
 */
export function EditorPage({ path }: { path: string }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const page = useQuery(pageByPathQuery(path));
  const location = useRouterState({ select: (s) => ({ pathname: s.location.pathname, searchStr: s.location.searchStr, hash: s.location.hash }) });

  // Rename-stable mid-edit (D-1): mirror the read route's canonical-redirect so an alias edit URL
  // resolves to `/docs/<canonical>?mode=edit`. The replace carries the router location's search + hash
  // (so `?mode=edit` survives the path canonicalization — the verified clincher behind the route choice).
  const resolvedFor = `/docs/${encodeTreePath(path)}`;
  const resolved = page.data;
  useEffect(() => {
    if (!resolved || location.pathname !== resolvedFor) return;
    const canonicalUrl = resolved.url;
    if (canonicalUrl && canonicalUrl !== resolvedFor) {
      if (canonicalUrl.startsWith("/docs/")) {
        const canonicalPath = canonicalUrl.slice("/docs/".length).split("/").map(decodeURIComponent).join("/");
        queryClient.setQueryData(pageByPathQuery(canonicalPath).queryKey, resolved);
      }
      const suffix = location.hash ? `${location.searchStr}#${location.hash}` : location.searchStr;
      router.history.replace(canonicalUrl + suffix);
    }
  }, [resolved, location, resolvedFor, router, queryClient]);

  if (page.isPending) {
    return (
      <p className="py-16 text-center text-faint" data-pb-loading>
        Loading…
      </p>
    );
  }
  if (page.isError) {
    if (page.error instanceof ApiError && (page.error.isNotFound || page.error.status === 400)) return <NotFoundView />;
    return (
      <div className="py-16 text-center" data-pb-error>
        <h1 className="text-2xl font-bold text-ink">Something went wrong</h1>
        <p className="mt-3 text-muted">{page.error.message}</p>
      </div>
    );
  }

  // Key by id so a navigation to a different page remounts the editor with a fresh buffer/base_hash.
  return (
    <Editor
      key={page.data.id}
      id={page.data.id}
      initialPath={page.data.path}
      initialUrl={page.data.url}
      initialBuffer={page.data.markdown}
      initialHash={page.data.content_hash}
    />
  );
}

/** A 409 conflict the editor is showing the user — the buffer is ALWAYS preserved beside it (D-5). */
interface ConflictView {
  reason: WriteConflictReason;
  message: string;
  currentContent: string | null;
  currentPath: string | null;
}

function Editor({
  id,
  initialPath,
  initialUrl,
  initialBuffer,
  initialHash,
}: {
  id: string;
  initialPath: string;
  initialUrl: string | null;
  initialBuffer: string;
  initialHash: string;
}) {
  const queryClient = useQueryClient();

  const [buffer, setBuffer] = useState(initialBuffer);
  // The latest buffer, readable SYNCHRONOUSLY from an event handler that fires before the next render
  // (the tag-input blur commits a new buffer, then a Save click reads it — the render hasn't flushed, so a
  // closed-over `buffer` is stale; this ref is not). EVERY buffer write goes through `commitBuffer`, which
  // updates the ref in the same tick it schedules the setState — so the ref always leads the rendered state.
  const bufferRef = useRef(initialBuffer);
  const commitBuffer = useRef((update: (prev: string) => string) => {
    bufferRef.current = update(bufferRef.current);
    setBuffer(bufferRef.current);
  }).current;
  // The CAS token. ALWAYS server-issued — the initial GET, or a 200/409 response — never recomputed.
  const [baseHash, setBaseHash] = useState(initialHash);
  // The page's live content-relative path. page_moved updates it so the editor never drifts (D-5).
  const [docPath, setDocPath] = useState(initialPath);
  // The LAST-SAVED buffer — the dirty baseline. It starts as the GET payload and advances on every
  // successful save, so a saved buffer reads clean (Save disabled, no redundant PUT) until the user
  // edits again. Tracked separately from `baseHash` (the CAS token), which advances independently.
  const [savedBuffer, setSavedBuffer] = useState(initialBuffer);
  const dirty = buffer !== savedBuffer;

  const [conflict, setConflict] = useState<ConflictView | null>(null);
  const [refusal, setRefusal] = useState<{ field: string; message: string } | null>(null);
  const [deleted, setDeleted] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  // The preview OVERLAYS the body editor (it's a preview *of the body*): the Page-info form rail stays
  // visible the whole time, and CodeMirror stays mounted underneath the overlay (toggling off reveals it
  // with cursor/scroll/undo intact). Preview off by default also gates its server fetch (below), so a
  // normal edit session never POSTs `/api/v1/preview`.
  const [showPreview, setShowPreview] = useState(false);
  // The live body `EditorView`, lifted out of `CodeMirrorEditor` (private there) so the formatting
  // toolbar can run commands against it (D-3). A callback prop (not a forwarded ref) so this `useState`
  // re-renders the toolbar the moment the view mounts — and re-fires with the fresh view on a key-remount.
  const [editorView, setEditorView] = useState<EditorView | null>(null);

  // Byte-fidelity guard (W6): the read path serves `markdown` as a lossy UTF-8 decode of the
  // SAME bytes it hashes into `content_hash` (server IndexBuilder: `String(bytes, UTF_8)` +
  // `sha256(bytes)`, no NFC/EOL transform on content). So for a valid-UTF-8 page the identity
  // `sha256(utf8(markdown)) == content_hash` holds exactly; a mismatch means the seeded text is
  // NOT a faithful view of the on-disk bytes (invalid UTF-8 → U+FFFD), and re-encoding it on save
  // would silently corrupt bytes the user never touched. A text editor can't byte-faithfully
  // round-trip non-UTF-8, so we DETECT and refuse to save (reading is never blocked).
  const editable = useEditableGuard(initialBuffer, initialHash);

  const debounced = useDebounced(buffer, 300);
  // Gate the preview fetch on the pane being open: AND `showPreview` into the query's own `enabled`
  // (text-non-empty) so a hidden preview never POSTs `/api/v1/preview`.
  const previewOptions = previewQuery(debounced, docPath);
  const preview = useQuery({ ...previewOptions, enabled: showPreview && previewOptions.enabled });

  // Split-view (C2/D-3): the body CodeMirror holds the BODY SLICE only — the `---` fence and metadata
  // lines never enter the CM doc, so the body editor shows prose only and the metadata form owns the
  // frontmatter region. `body` is the exact tail `buffer.slice(bodyStart)`, so the frontmatter prefix is
  // the head before it; a body edit recombines by splicing the body region (preserving the frontmatter
  // region byte-for-byte). A FORM edit leaves `body` unchanged → CodeMirror's reconcile guard short-
  // circuits → the body view/cursor/undo are untouched for free.
  const { body } = splitFrontmatter(buffer);
  // Recombine via a FUNCTIONAL updater over the LATEST buffer (not the render-scope `buffer`/`body`): a
  // body edit and a form edit can land in the same React batch, so re-derive the frontmatter prefix from
  // `prev` each time rather than the stale closed-over slice.
  const recombineBody = (nextBody: string) => commitBuffer((prev) => prev.slice(0, prev.length - splitFrontmatter(prev).body.length) + nextBody);

  const save = useMutation({
    // Read the LATEST buffer (bufferRef), not the render-scope `buffer`: a tag-input blur commits its draft
    // via setBuffer and a Save click can fire before that re-render, so the closed-over `buffer` would be
    // stale (the just-typed tag lost, the editor left dirty). The ref always leads the rendered state.
    // Capture the exact sent bytes so the saved baseline advances to them on success (even mid-request).
    mutationFn: (): Promise<{ result: SaveResult; sent: string }> => {
      const sent = bufferRef.current;
      return putPageRaw(id, sent, baseHash).then((result) => ({ result, sent }));
    },
    onSuccess: ({ result, sent }) => applySaveResult(result, sent),
  });

  function applySaveResult(result: SaveResult, sent: string) {
    switch (result.kind) {
      case "saved": {
        setBaseHash(result.written.content_hash);
        // Advance the dirty baseline to the saved bytes — the editor reads clean (Save disabled, no
        // redundant PUT) until the user edits again.
        setSavedBuffer(sent);
        setConflict(null);
        setRefusal(null);
        setDeleted(false);
        setNotice("warning" in result.written ? result.written.warning.message : "Saved.");
        // ONE invalidation point (queries.ts): tree, search (full-text goes stale on any edit), this page's
        // id-keyed + by-path reads. The by-path leg uses the mounted URL splat (NOT the `.md` file path); a
        // page_moved changes that key and the 200 doesn't carry the new URL, so the helper also clears the
        // whole by-path namespace to leave neither the old nor the new location stale.
        invalidateAfterWrite(queryClient, { id, url: initialUrl });
        return;
      }
      case "conflict": {
        const { reason } = result.conflict;
        // Each terminal outcome fully supersedes the previous one — clear the sibling banners so a
        // stale refusal/notice can't linger beside (or, under the notice render-gate, mask) this one.
        setRefusal(null);
        setNotice(null);
        // A deliberate re-save targets the new base — the hash is ALWAYS the server's `current_hash`.
        if (result.conflict.current_hash) setBaseHash(result.conflict.current_hash);
        if (reason === "page_deleted") {
          setDeleted(true);
          setConflict(null);
          return;
        }
        setDeleted(false);
        if (reason === "page_moved" && result.conflict.current_path) setDocPath(result.conflict.current_path);
        setConflict({
          reason,
          message: result.conflict.message,
          currentContent: result.conflict.current_content,
          currentPath: result.conflict.current_path,
        });
        return;
      }
      case "degraded":
        // P5: an agent COMMIT write outside `agentDirectCommit.globs` was filed as a proposal, NOT applied.
        // Do NOT advance the saved baseline (the editor stays dirty — these bytes are not on disk) and surface a
        // clear non-"Saved" notice. Unreachable from the Human/cookie-auth SPA, but the result kind is exhaustive.
        clearOutcomeBanners();
        setNotice("Submitted as a proposal for review.");
        return;
      case "unsupported":
        clearOutcomeBanners();
        setRefusal({ field: result.unsupported.field, message: result.unsupported.message });
        return;
      case "too-large":
        // Clear any prior conflict/refusal/deleted first — otherwise the notice render-gate
        // (`!conflict && !refusal && !deleted`) hides this fresh failure behind a stale banner.
        clearOutcomeBanners();
        setNotice(`Document exceeds ${result.maxBytes} bytes — trim it and try again.`);
        return;
      case "error":
        clearOutcomeBanners();
        setNotice(result.error.status === 503 ? "Couldn't save (transient) — please retry." : result.error.message);
        return;
    }
  }

  /** Clears the three outcome banners so a fresh notice/refusal always surfaces (FIX 2 — no stale masking). */
  function clearOutcomeBanners() {
    setConflict(null);
    setRefusal(null);
    setDeleted(false);
  }

  return (
    <div className="pb-editor flex min-w-0 flex-1 gap-8" data-pb-editor>
      <div className="flex min-w-0 flex-1 flex-col gap-3">
        <div className="flex items-center justify-between gap-3">
          <Breadcrumb path={docPath} />
          <div className="flex items-center gap-2">
            <button
              type="button"
              className="inline-flex items-center gap-1.5 rounded-md border border-edge bg-surface px-3 py-1.5 text-sm font-medium text-muted hover:text-ink aria-pressed:bg-hovered aria-pressed:text-ink"
              data-pb-preview-toggle
              aria-pressed={showPreview}
              onClick={() => setShowPreview((shown) => !shown)}
            >
              <EyeIcon />
              Preview
            </button>
            <button
              type="button"
              className="pb-editor-save rounded-md border border-edge bg-accent px-3 py-1.5 text-sm font-medium text-accent-contrast disabled:opacity-50"
              data-pb-save
              disabled={save.isPending || !dirty || !editable}
              onClick={() => save.mutate()}
            >
              {save.isPending ? "Saving…" : "Save"}
            </button>
          </div>
        </div>

        {!editable && <UneditableBanner />}
        {conflict && <ConflictBanner conflict={conflict} />}
        {refusal && <RefusalBanner refusal={refusal} />}
        {deleted && <DeletedBanner buffer={buffer} initialPath={initialPath} />}
        {notice && !conflict && !refusal && !deleted && (
          <p className="text-sm text-muted" data-pb-editor-notice>
            {notice}
          </p>
        )}

        {/* Formatting toolbar (C3): body-only, edit-mode only; hidden while the preview overlay covers the
            editing surface. Acts on the SAME body view the keymap binds, via CM dispatch → recombineBody. */}
        <EditorToolbar view={editorView} disabled={showPreview} />

        {/* The CodeMirror region is the positioning context for the preview overlay: CM stays mounted
            (preserving cursor/scroll/undo) and the preview, when shown, covers it with an opaque surface. */}
        <div className="relative min-h-0 flex-1">
          <CodeMirrorEditor value={body} onChange={recombineBody} onViewChange={setEditorView} />
          {showPreview && (
            <div className="absolute inset-0 overflow-y-auto rounded-md bg-surface" data-pb-preview>
              {preview.data ? <Prose html={preview.data.html} /> : <p className="text-sm text-faint">Preview appears as you type.</p>}
            </div>
          )}
        </div>
      </div>

      <aside className="pb-rail hidden w-[clamp(14rem,18vw,20rem)] shrink-0 overflow-y-auto xl:block" data-pb-edit-rail>
        <MetaForm buffer={buffer} onChange={commitBuffer} />
      </aside>
    </div>
  );
}

/**
 * The header path as a breadcrumb: dimmed parent folder segments + ` / ` separators + the bright filename
 * (the last segment). Derived from the live content-relative `docPath` (e.g. `infra/kubernetes.md` →
 * `infra / kubernetes.md`). Monospace, matching the C2 bare-path look; `data-pb-editor-path` is preserved
 * as the stable hook (now wrapping the breadcrumb rather than the bare string).
 */
function Breadcrumb({ path }: { path: string }) {
  const segments = path.split("/");
  const file = segments[segments.length - 1];
  const folders = segments.slice(0, -1);
  return (
    <span className="flex min-w-0 items-center font-mono text-sm" data-pb-editor-path>
      {folders.map((folder, index) => (
        <span key={index} className="flex items-center text-muted">
          {folder}
          <span className="px-1.5 text-faint" aria-hidden="true">
            /
          </span>
        </span>
      ))}
      <span className="truncate text-ink">{file}</span>
    </span>
  );
}

/** A minimal outline eye icon (currentColor) for the Preview toggle. */
function EyeIcon() {
  return (
    <svg viewBox="0 0 16 16" width="15" height="15" aria-hidden="true" focusable="false" fill="none" stroke="currentColor" strokeWidth={1.6}>
      <path d="M1.5 8S4 3.5 8 3.5 14.5 8 14.5 8 12 12.5 8 12.5 1.5 8 1.5 8Z" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx="8" cy="8" r="2" />
    </svg>
  );
}

/** content_changed / page_moved — the dirty buffer is kept; the server's current content is shown alongside (no auto-merge). */
function ConflictBanner({ conflict }: { conflict: ConflictView }) {
  return (
    <div className="pb-conflict rounded-md border border-edge p-3 text-sm" data-pb-conflict data-pb-conflict-reason={conflict.reason}>
      <p className="font-medium text-ink">{conflict.message}</p>
      <p className="mt-1 text-muted">Your edits are kept. Review the current server version below, then Save again to overwrite it.</p>
      {conflict.reason === "page_moved" && conflict.currentPath && (
        <p className="mt-1 text-muted">This page moved to {conflict.currentPath} — you are now editing it there.</p>
      )}
      {conflict.currentContent !== null && (
        <details className="mt-2" data-pb-conflict-current>
          <summary className="cursor-pointer text-muted">Current server version</summary>
          <pre className="mt-2 overflow-x-auto whitespace-pre-wrap font-mono text-xs text-muted">{conflict.currentContent}</pre>
        </details>
      )}
    </div>
  );
}

/** 422 id/slug/redirect_from — a rename, not a save; the editor stays dirty/unsaved (D-4). */
function RefusalBanner({ refusal }: { refusal: { field: string; message: string } }) {
  return (
    <div className="pb-refusal rounded-md border border-edge p-3 text-sm" data-pb-refusal data-pb-refusal-field={refusal.field}>
      <p className="font-medium text-ink">Changing the page {refusal.field} isn’t a save — it’s a move, which isn’t supported yet.</p>
      <p className="mt-1 text-muted">{refusal.message}</p>
    </div>
  );
}

/**
 * Byte-fidelity refusal: the page's on-disk bytes aren't valid UTF-8, so the seeded text is a lossy
 * decode (U+FFFD) and saving would re-encode it — corrupting bytes the user never edited. Reading is
 * fine; Save is disabled. The fix is to edit the file with a byte-faithful tool, externally.
 */
function UneditableBanner() {
  return (
    <div className="pb-uneditable rounded-md border border-edge p-3 text-sm" data-pb-uneditable>
      <p className="font-medium text-ink">This page isn’t valid UTF-8 and can’t be safely edited here.</p>
      <p className="mt-1 text-muted">Saving would change bytes you never touched. Edit it externally with a byte-faithful tool.</p>
    </div>
  );
}

/**
 * Recomputes `sha256(utf8(buffer))` via Web Crypto and compares it to the server's `content_hash`
 * (sans the `sha256:` prefix). Returns `true` (editable) until a mismatch is CONFIRMED — so a normal
 * page, and the brief async window before the digest resolves, never block the user. A digest failure
 * (no `crypto.subtle`) also leaves the page editable rather than false-blocking a valid one.
 */
function useEditableGuard(buffer: string, contentHash: string): boolean {
  const [editable, setEditable] = useState(true);
  useEffect(() => {
    let live = true;
    sha256Hex(buffer)
      .then((hex) => {
        if (live) setEditable(hex === null || hex === stripHashPrefix(contentHash));
      })
      .catch(() => {
        if (live) setEditable(true);
      });
    return () => {
      live = false;
    };
  }, [buffer, contentHash]);
  return editable;
}

/** Lowercase-hex SHA-256 of the UTF-8 bytes of [text], or null when Web Crypto is unavailable. */
async function sha256Hex(text: string): Promise<string | null> {
  const subtle = globalThis.crypto?.subtle;
  if (!subtle) return null;
  const digest = await subtle.digest("SHA-256", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

const stripHashPrefix = (hash: string): string => (hash.startsWith("sha256:") ? hash.slice("sha256:".length) : hash);

/** page_deleted — no rebase target; offer "save as new page" prefilled with the buffer (no dead-end, D-5). */
function DeletedBanner({ buffer, initialPath }: { buffer: string; initialPath: string }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const folder = initialPath.includes("/") ? initialPath.slice(0, initialPath.lastIndexOf("/")) : "";
  const fallbackTitle = stripExtension(initialPath.slice(initialPath.lastIndexOf("/") + 1)) || "Untitled";

  async function saveAsNew() {
    setSaving(true);
    setError(null);
    // The server PREPENDS its own freshly-minted frontmatter and appends `body` verbatim. Send the BODY
    // ONLY (the old frontmatter — incl. the now-defunct id — must not survive, or the file gets two
    // frontmatter blocks). The title comes from the user's possibly-edited frontmatter, else the filename.
    const { frontmatter, body } = splitFrontmatter(buffer);
    const title = (frontmatter && frontmatterValue(frontmatter, "title")) || fallbackTitle;
    const result = await createPage({ folder, title, body });
    setSaving(false);
    if (result.kind === "created") {
      // Invalidate the DESTINATION url's by-path/page cache BEFORE navigating — save-as-new can reuse a
      // recovered `/docs/...` URL whose by-path entry still points at the deleted old id, so the read route
      // would otherwise render that stale id for up to its staleTime. (A `/p/{id}` permalink no-ops.)
      invalidateAfterWrite(queryClient, { id: result.created.id, url: result.created.url });
      if (result.created.warning || !result.created.url) {
        // Unindexed (or, defensively, no canonical url yet): the page is unpublished, so navigating
        // could land on a not-yet-resolvable route. Surface the warning and stay put.
        setError((result.created.warning ?? { message: "Saved, but not yet indexed." }).message);
        return;
      }
      await router.navigate({ to: result.created.url });
      return;
    }
    setError(result.kind === "exists" ? `A page already exists at ${result.exists.path}.` : result.error.message);
  }

  return (
    <div className="pb-conflict rounded-md border border-edge p-3 text-sm" data-pb-conflict data-pb-conflict-reason="page_deleted">
      <p className="font-medium text-ink">This page no longer exists on disk.</p>
      <p className="mt-1 text-muted">Your edits are kept. Save them as a new page so nothing is lost.</p>
      <button
        type="button"
        className="pb-editor-save mt-2 rounded-md border border-edge bg-accent px-3 py-1.5 text-sm font-medium text-accent-contrast disabled:opacity-50"
        data-pb-save-as-new
        disabled={saving}
        onClick={() => void saveAsNew()}
      >
        {saving ? "Saving…" : "Save as new page"}
      </button>
      {error && <p className="mt-1 text-muted">{error}</p>}
    </div>
  );
}

/**
 * The `/new` route body (D-2/D-3): title (+ optional folder/slug) → `POST /api/v1/pages` → navigate
 * DIRECTLY to the server-returned canonical `url` (no tree re-resolve, no client slug derivation).
 */
export function NewPage() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [title, setTitle] = useState("");
  const [folder, setFolder] = useState("");
  const [slug, setSlug] = useState("");
  const [section, setSection] = useState(false);
  const [templateId, setTemplateId] = useState("blank");
  const [body, setBody] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  // In section mode the Folder field IS the new section's path; a section needs a non-blank path
  // (its `<folder>/index.md` has nowhere to land otherwise), so creation is gated on it.
  const sectionReady = !section || folder.trim() !== "";

  const create = useMutation({
    mutationFn: () =>
      createPage({
        folder: folder.trim() || undefined,
        title: title.trim(),
        // Section forces `index`; else forward the user's slug VERBATIM (case-preserving — the server is the
        // slug authority and slugifies it). Blank → undefined → the server slugifies the title.
        slug: section ? "index" : slug.trim() || undefined,
        body: body || undefined,
      }),
    onSuccess: (result) => {
      if (result.kind === "created") {
        invalidateAfterWrite(queryClient, { id: result.created.id, url: result.created.url });
        if (result.created.warning || !result.created.url) {
          // Created-but-unindexed: the bytes are on disk but NOT yet in the published snapshot, so there
          // is no reliable canonical url (the server returns `url: null`) until reconciliation. Surface
          // the warning and stay put rather than navigate into a possibly-not-found route.
          setNotice(`${(result.created.warning ?? { message: "Saved, but not yet indexed." }).message} It will appear after reconciliation.`);
          return;
        }
        void router.navigate({ to: result.created.url });
        return;
      }
      setError(result.kind === "exists" ? `A page already exists at ${result.exists.path}.` : result.error.message);
    },
  });

  return (
    <div className="mx-auto max-w-[40rem]" data-pb-new-page-form>
      <h1 className="text-2xl font-bold text-ink">New page</h1>
      <form
        className="mt-6 flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          setError(null);
          setNotice(null);
          // Guard the blank-section case explicitly so a section create never POSTs without a path.
          if (title.trim() && sectionReady) create.mutate();
        }}
      >
        <label className="flex flex-col gap-1 text-sm text-muted">
          Title
          <input
            className="rounded-md border border-edge bg-surface px-3 py-2 text-ink"
            data-pb-new-title
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            placeholder="Page title"
            autoFocus
          />
        </label>
        <label className="flex flex-col gap-1 text-sm text-muted">
          {section ? "Section folder path" : "Folder (optional)"}
          <input
            className="rounded-md border border-edge bg-surface px-3 py-2 font-mono text-ink"
            data-pb-new-folder
            value={folder}
            onChange={(event) => setFolder(event.target.value)}
            placeholder="guides"
          />
          {section && <span className="text-xs text-faint">Creates {folder.trim() ? `${folder.trim()}/index.md` : "<folder>/index.md"}</span>}
        </label>
        {/* Slug + advisory path preview: NON-section only (section forces slug "index", so a typed slug would
            be silently overridden). The preview is ADVISORY — it lowercases via approxSlug, but the POST
            forwards the slug verbatim and navigation stays on the server-returned url. */}
        {!section && (
          <label className="flex flex-col gap-1 text-sm text-muted">
            Slug (optional)
            <input
              className="rounded-md border border-edge bg-surface px-3 py-2 font-mono text-ink"
              data-pb-new-slug
              value={slug}
              onChange={(event) => setSlug(event.target.value)}
              placeholder="my-page"
            />
            {(title.trim() || slug.trim()) && (
              <span className="text-xs text-faint" data-pb-new-preview>
                approx. ≈ {previewPath(folder.trim(), slug.trim() || title.trim())}
              </span>
            )}
          </label>
        )}
        <label className="flex flex-col gap-1 text-sm text-muted">
          Template
          <select
            className="rounded-md border border-edge bg-surface px-3 py-2 text-ink"
            data-pb-new-template
            value={templateId}
            onChange={(event) => {
              const next = event.target.value;
              // Guard the no-op re-select so a manual body edit survives re-picking the same template.
              if (next === templateId) return;
              const template = PAGE_TEMPLATES.find((t) => t.id === next);
              setTemplateId(next);
              // Selecting a template is an explicit action: replace the body with its scaffold (Blank → "").
              setBody(template?.body ?? "");
            }}
          >
            {PAGE_TEMPLATES.map((template) => (
              <option key={template.id} value={template.id}>
                {template.label}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm text-muted">
          Body
          <textarea
            className="rounded-md border border-edge bg-surface px-3 py-2 font-mono text-ink"
            data-pb-new-body
            value={body}
            onChange={(event) => setBody(event.target.value)}
            placeholder="Page body (Markdown)"
            rows={8}
          />
        </label>
        <label className="flex items-start gap-2 text-sm text-muted">
          <input
            type="checkbox"
            className="mt-0.5 rounded border border-edge bg-surface text-accent"
            data-pb-new-section
            checked={section}
            onChange={(event) => setSection(event.target.checked)}
          />
          <span>Create a new section (this page becomes its landing page)</span>
        </label>
        <button
          type="submit"
          className="self-start rounded-md border border-edge bg-accent px-4 py-2 text-sm font-medium text-accent-contrast disabled:opacity-50"
          data-pb-new-create
          disabled={create.isPending || !title.trim() || !sectionReady}
        >
          {create.isPending ? "Creating…" : "Create page"}
        </button>
        {error && <p className="text-sm text-muted">{error}</p>}
        {notice && (
          <p className="pb-create-notice text-sm" data-pb-create-notice>
            {notice}
          </p>
        )}
      </form>
    </div>
  );
}

/** The §5.9-token Markdown highlight style — only `var(--pb-*)` references, so dark mode swaps for free. */
const pbHighlightStyle = HighlightStyle.define([
  { tag: tags.heading, color: "var(--pb-syntax-title)", fontWeight: "bold" },
  { tag: tags.strong, color: "var(--pb-text)", fontWeight: "bold" },
  { tag: tags.emphasis, color: "var(--pb-text)", fontStyle: "italic" },
  { tag: tags.link, color: "var(--pb-link)" },
  { tag: tags.url, color: "var(--pb-link)" },
  { tag: tags.monospace, color: "var(--pb-code-text)" },
  { tag: tags.keyword, color: "var(--pb-syntax-keyword)" },
  { tag: tags.string, color: "var(--pb-syntax-string)" },
  { tag: tags.comment, color: "var(--pb-syntax-comment)" },
  { tag: tags.meta, color: "var(--pb-text-muted)" },
  { tag: tags.processingInstruction, color: "var(--pb-text-faint)" },
]);

/** Editor chrome theme — all colors are `var(--pb-*)` references (placed here, never as hex), per the token gate. */
const pbEditorTheme = EditorView.theme({
  "&": { color: "var(--pb-text)", backgroundColor: "var(--pb-surface)" },
  ".cm-content": { fontFamily: "var(--font-mono)", caretColor: "var(--pb-accent)" },
  ".cm-activeLine": { backgroundColor: "var(--pb-surface-raised)" },
  "&.cm-focused": { outline: "none" },
  ".cm-cursor, .cm-dropCursor": { borderLeftColor: "var(--pb-accent)" },
  "&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection": { backgroundColor: "var(--pb-selection-bg)" },
  ".cm-scroller": { fontFamily: "var(--font-mono)" },
});

/**
 * The C3 formatting keymap (D-2). PREPENDED before `defaultKeymap` in the extensions array so CM6's
 * `runFor` reaches these first: `Mod-i` IS bound by default to `selectParentSyntax`, so the prepend plus
 * `toggleItalic` returning `true` whenever it acts is what stops the default from clobbering italic.
 * `Mod-b`/`Mod-k`/`Mod-e` are free. `Mod-` resolves to Cmd on macOS, Ctrl elsewhere (no branching).
 */
const formattingKeymap = keymap.of([
  { key: "Mod-b", run: toggleBold },
  { key: "Mod-i", run: toggleItalic },
  { key: "Mod-e", run: toggleCode },
  { key: "Mod-k", run: insertLink },
]);

/** Mounts a CodeMirror 6 Markdown EditorView over a ref; the React state is the source of truth for the buffer. */
function CodeMirrorEditor({
  value,
  onChange,
  onViewChange,
}: {
  value: string;
  onChange: (next: string) => void;
  onViewChange?: (view: EditorView | null) => void;
}) {
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  // The latest onChange, read inside the (mount-once) update listener without re-creating the view.
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;
  // The latest onViewChange, lifted the same way so the mount-once effect never re-runs on a new callback.
  const onViewChangeRef = useRef(onViewChange);
  onViewChangeRef.current = onViewChange;

  useEffect(() => {
    if (!host.current) return;
    const editor = new EditorView({
      parent: host.current,
      state: EditorState.create({
        doc: value,
        extensions: [
          history(),
          formattingKeymap,
          keymap.of([...defaultKeymap, ...historyKeymap]),
          markdown(),
          syntaxHighlighting(pbHighlightStyle),
          pbEditorTheme,
          EditorView.lineWrapping,
          EditorView.updateListener.of((update) => {
            if (update.docChanged) onChangeRef.current(update.state.doc.toString());
          }),
        ],
      }),
    });
    view.current = editor;
    onViewChangeRef.current?.(editor);
    return () => {
      onViewChangeRef.current?.(null);
      editor.destroy();
      view.current = null;
    };
    // Mount once; external value pushes are reconciled by the effect below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Reconcile an EXTERNAL value change (e.g. a programmatic reset) without clobbering local typing.
  useEffect(() => {
    const editor = view.current;
    if (editor && value !== editor.state.doc.toString()) {
      editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: value } });
    }
  }, [value]);

  return <div ref={host} className="pb-codemirror min-h-[60vh] rounded-md border border-edge" data-pb-codemirror />;
}

function stripExtension(name: string): string {
  return name.endsWith(".md") ? name.slice(0, -3) : name;
}
