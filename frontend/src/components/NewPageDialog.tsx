import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "@tanstack/react-router";
import { useEffect, useRef, useState } from "react";
import { createPage } from "../api/client";
import { encodePathSegment, invalidateAfterWrite, treeQuery } from "../api/queries";
import type { RootTree } from "../api/types";
import { PAGE_TEMPLATES } from "../lib/pageTemplates";
import { approxSlug } from "../lib/slugPreview";
import { breadcrumbTrail } from "../lib/breadcrumbs";
import { entryFor, foldersByPath, rootAcceptsWrites, rootLabel } from "../lib/tree";

/** Known folder addresses can differ from their file paths; only the unknown tail is approximate. */
export function creationUrlPreview(target: RootTree | null, folder: string, titleOrSlug: string, section: boolean): string | null {
  if (!target) return null;
  const folders = new Map([["", target.tree], ...foldersByPath(target.tree)]);
  let ancestor = folder;
  while (!folders.has(ancestor) && ancestor) ancestor = ancestor.slice(0, Math.max(0, ancestor.lastIndexOf("/")));
  const base = folders.get(ancestor)?.url;
  if (!base) return null;
  const tail = folder.slice(ancestor.length).replace(/^\//, "");
  const segments = tail ? tail.split("/").map((segment) => encodePathSegment(approxSlug(segment, "folder"))) : [];
  if (!section) segments.push(encodePathSegment(approxSlug(titleOrSlug)));
  return [base, ...segments].join("/");
}

export function NewPageDialog({ root, initialFolder, onClose, canLeave }: {
  root?: string; initialFolder: string; onClose: () => void; canLeave: () => boolean;
}) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const tree = useQuery(treeQuery);
  const target = root ? entryFor(tree.data?.roots ?? [], root) : null;
  const writable = rootAcceptsWrites(tree.data?.roots, root ?? null);
  const [title, setTitle] = useState("");
  const [folder, setFolder] = useState(initialFolder);
  const [customFolder, setCustomFolder] = useState(false);
  const folders = target ? foldersByPath(target.tree) : new Map();
  const folderChoices = [...folders.keys()].map((path: string) => ({ path,
    label: breadcrumbTrail(tree.data?.roots ?? [], root ?? "", `${path}/_`).slice(1).map((crumb) => crumb.label).join(" / ") }));
  const useCustomFolder = customFolder || (folder !== "" && !folders.has(folder));
  const [slug, setSlug] = useState("");
  const [section, setSection] = useState(false);
  const [templateId, setTemplateId] = useState("blank");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const dialog = useRef<HTMLDialogElement>(null);
  const titleInput = useRef<HTMLInputElement>(null);
  const slugInput = useRef<HTMLInputElement>(null);
  const options = useRef<HTMLDetailsElement>(null);
  const pendingRef = useRef(false);
  const alive = useRef(true);
  const unblock = useRef<(() => void) | null>(null);
  const folderPath = folder.trim().replace(/\/+$/, "");
  const sectionReady = !section || folderPath !== "";
  const preview = creationUrlPreview(target, folderPath, slug.trim() || title.trim(), section);
  const blockedReason = tree.isPending ? "Loading spaces…" : tree.isError ? "Could not load spaces. Try again when the connection returns."
    : !target ? "This space is not configured." : !target.available ? "This space is unavailable."
    : !target.editable ? "This space is read-only." : null;

  useEffect(() => {
    alive.current = true;
    const element = dialog.current!;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    element.showModal();
    titleInput.current?.focus();
    return () => {
      alive.current = false;
      unblock.current?.();
      unblock.current = null;
      element.close();
      document.body.style.overflow = overflow;
    };
  }, []);
  function close() { if (!pendingRef.current) onClose(); }
  async function submit() {
    if (pendingRef.current || notice || !title.trim() || !sectionReady || !writable || !root || !canLeave()) return;
    pendingRef.current = true;
    setPending(true);
    setError(null);
    unblock.current = router.history.block({ blockerFn: () => pendingRef.current, enableBeforeUnload: true });
    try {
      const result = await createPage({ root, folder: folderPath || undefined, title: title.trim(),
        slug: section ? "index" : slug.trim() || undefined,
        body: PAGE_TEMPLATES.find((template) => template.id === templateId)?.body || undefined });
      if (!alive.current) return;
      if (result.kind === "created") {
        invalidateAfterWrite(queryClient, { id: result.created.id, url: result.created.url });
        if (result.created.warning || !result.created.url) {
          setNotice(`${result.created.warning?.message ?? "Saved, but not yet indexed."} It will appear after reconciliation.`);
        } else {
          setNotice("Page created. It is available in the sidebar.");
          // Other navigation guards remain authoritative; release only this request's guard.
          unblock.current?.();
          unblock.current = null;
          // A separate guard may keep navigation unresolved. The write is already
          // complete, so it must not keep this form in a pending or resubmittable state.
          void router.navigate({ to: result.created.url, search: { mode: "edit" }, replace: true });
        }
      } else if (result.kind === "degraded") setNotice("Submitted as a proposal for review.");
      else setError(result.kind === "exists" ? `A page already exists at ${result.exists.path}.` : result.error.message);
    } catch (failure) {
      if (alive.current) setError(failure instanceof Error ? failure.message : "Could not create the page.");
    } finally {
      unblock.current?.();
      unblock.current = null;
      pendingRef.current = false;
      if (alive.current) setPending(false);
    }
  }
  return <dialog ref={dialog} className="pb-new-dialog" aria-labelledby="pb-new-heading" data-pb-new-dialog
    onCancel={(event) => { event.preventDefault(); close(); }}
    onClick={(event) => {
      if (event.target !== event.currentTarget) return;
      const bounds = event.currentTarget.getBoundingClientRect();
      if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) close();
    }}>
    <div data-pb-new-page-form>
      <header className="flex items-center justify-between gap-4">
        <h1 id="pb-new-heading" className="text-lg font-semibold text-ink">New page</h1>
        <button type="button" className="pb-new-close" aria-label="Close new page" disabled={pending} onClick={close}>×</button>
      </header>
      <form className="mt-5 flex flex-col gap-5" onSubmit={(event) => { event.preventDefault(); void submit(); }}>
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-muted">
          <span>In <strong className="font-medium text-ink">{target ? rootLabel(target) : root ?? "Loading…"}</strong></span>
          <span aria-hidden="true">/</span>
          <label className="min-w-0 flex-1"><span className="sr-only">Folder</span>
            <select data-pb-new-folder className="pb-new-location text-sm" disabled={pending || !!notice}
              value={JSON.stringify(useCustomFolder ? ["custom"] : ["folder", folder])}
              onChange={(event) => {
                const [kind, value] = JSON.parse(event.target.value) as [string, string?];
                setCustomFolder(kind === "custom");
                if (kind === "folder") setFolder(value ?? "");
              }}>
              <option value={JSON.stringify(["folder", ""])}>Space home</option>
              {folderChoices.map(({ path, label }) => <option key={path} value={JSON.stringify(["folder", path])} title={path}>
                {label}{folderChoices.filter((choice) => choice.label === label).length > 1 ? ` (${path})` : ""}
              </option>)}
              <option value={JSON.stringify(["custom"])}>Custom folder…</option>
            </select>
          </label>
        </div>
        {useCustomFolder && <label className="flex flex-col gap-1.5 text-sm text-muted">Custom folder path
          <input data-pb-new-custom-folder className="pb-new-field font-mono" value={folder} disabled={pending || !!notice}
            onChange={(event) => setFolder(event.target.value)} placeholder="team/new-folder" />
        </label>}
        <label className="flex flex-col gap-2"><span className="sr-only">Title</span>
          <input ref={titleInput} className="pb-new-title" data-pb-new-title value={title} disabled={pending || !!notice}
            onChange={(event) => setTitle(event.target.value)} placeholder="Give your page a title" />
        </label>
        <div className="flex min-w-0 items-start gap-3 text-xs text-faint">
          <span className={`min-w-0 flex-1 break-all${preview && (title.trim() || slug.trim()) ? " font-mono" : ""}`} {...(title.trim() || slug.trim() ? { "data-pb-new-preview": "" } : {})}>
            {title.trim() || slug.trim() ? preview ? `≈ ${preview}` : "Final address unavailable until creation" : "The page address will follow your title"}
          </span>
          {!section && <button type="button" className="shrink-0 text-link hover:underline" disabled={pending || !!notice}
            onClick={() => { if (options.current) options.current.open = true; slugInput.current?.focus(); }}>Edit URL</button>}
        </div>
        <fieldset disabled={pending || !!notice}><legend className="mb-2 text-sm font-medium text-ink">Start with a template</legend>
          <div className="pb-template-grid">{PAGE_TEMPLATES.map((template) => <label className="pb-template-card" key={template.id}>
            <input type="radio" name="template" value={template.id} checked={templateId === template.id} data-pb-new-template
              onChange={() => setTemplateId(template.id)} />
            <span><strong className="block text-sm font-medium">{template.label}</strong><span className="mt-1 block text-xs text-faint">{template.purpose}</span></span>
          </label>)}</div>
        </fieldset>
        <details ref={options} className="pb-new-options"><summary className="cursor-pointer text-sm text-muted">More options</summary>
          <div className="mt-4 flex flex-col gap-4">
            {!section && <label className="flex flex-col gap-1.5 text-sm text-muted">Custom URL slug
              <input ref={slugInput} className="pb-new-field font-mono" data-pb-new-slug value={slug} disabled={pending || !!notice}
                onChange={(event) => setSlug(event.target.value)} placeholder="Use the title" />
            </label>}
            <label className="flex items-start gap-2 text-sm text-muted"><input type="checkbox" data-pb-new-section className="mt-1"
              checked={section} disabled={pending || !!notice} onChange={(event) => setSection(event.target.checked)} />
              <span>Folder landing page<span className="mt-1 block text-xs text-faint">Introduce a folder with its own page. Requires a folder above.</span></span>
            </label>
            {section && <span className="text-xs text-faint">Creates <span className="font-mono">{folderPath ? `${folderPath}/index.md` : "<folder>/index.md"}</span></span>}
          </div>
        </details>
        {blockedReason && <p className="text-sm text-muted" role="status">{blockedReason}</p>}
        {error && <p className="text-sm text-muted" role="alert">{error}</p>}
        {notice && <p className="pb-create-notice text-sm" role="status" data-pb-create-notice>{notice}</p>}
        <footer className="flex justify-end gap-3 border-t border-edge pt-4">
          <button type="button" className="rounded-md px-3 py-2 text-sm text-muted hover:bg-hovered hover:text-ink" disabled={pending} onClick={close}>{notice ? "Close" : "Cancel"}</button>
          <button type="submit" className="rounded-md border border-primary-edge bg-primary px-4 py-2 text-sm font-medium text-primary-ink disabled:opacity-50"
            data-pb-new-create disabled={pending || !!notice || !title.trim() || !sectionReady || !writable}>
            {pending ? "Creating…" : "Create page"}
          </button>
        </footer>
      </form>
    </div>
  </dialog>;
}
