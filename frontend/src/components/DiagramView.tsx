import { useQuery } from "@tanstack/react-query";
import { useRouterState } from "@tanstack/react-router";
import { useEffect, useRef } from "react";
import { ApiError } from "../api/client";
import { diagramSourceQuery, diagramSourceUrl } from "../api/queries";
import { parseDiagramPath } from "../lib/diagramPath";
import { renderMermaidBlocks } from "../lib/mermaid";
import { Breadcrumbs } from "./Breadcrumbs";
import { NotFoundView } from "./NotFound";
import { QueryErrorView } from "./ErrorView";

export function DiagramView() {
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const diagram = parseDiagramPath(pathname);
  if (!diagram) return <NotFoundView />;
  return <DiagramDocument root={diagram.root} path={diagram.path} />;
}

function DiagramDocument({ root, path }: { root: string; path: string }) {
  const source = useQuery(diagramSourceQuery(root, path));
  const sourceRef = useRef<HTMLElement>(null);
  const sourceUrl = diagramSourceUrl(root, path);
  const title = path.slice(path.lastIndexOf("/") + 1);

  useEffect(() => {
    document.title = `${title} · Plainbase`;
  }, [title]);

  useEffect(() => {
    if (source.data === undefined || source.isError || !sourceRef.current) return;
    return renderMermaidBlocks(sourceRef.current);
  }, [source.data, source.isError, root, path]);

  if (source.isPending) return <DiagramPending />;
  if (source.isError) {
    if (source.error instanceof ApiError && (source.error.isNotFound || source.error.status === 400)) {
      return <NotFoundView />;
    }
    return <QueryErrorView error={source.error} />;
  }

  return (
    <div className="flex gap-12" data-pb-diagram>
      <div className="min-w-0 flex-1">
        <div className="mx-auto max-w-[72ch]">
          <Breadcrumbs root={root} path={path} title={title} />
          <div className="mb-5 flex items-baseline justify-between gap-4">
            <h1 className="text-3xl font-bold text-ink">{title}</h1>
            <a
              href={sourceUrl}
              download
              className="shrink-0 text-sm text-link hover:text-link-hover hover:underline"
              data-pb-diagram-source
            >
              Download source
            </a>
          </div>
          <p className="mb-6 text-sm text-muted">Read-only view of the Mermaid source file. Edit the file externally to change it.</p>
          <article ref={sourceRef} className="pb-prose" data-pb-prose>
            <pre>
              <code className="language-mermaid">{source.data}</code>
            </pre>
          </article>
        </div>
      </div>
      <div className="hidden w-[clamp(14rem,18vw,20rem)] shrink-0 xl:block" aria-hidden="true" />
    </div>
  );
}

function DiagramPending() {
  return (
    <p className="py-16 text-center text-faint" data-pb-loading>
      Loading…
    </p>
  );
}
