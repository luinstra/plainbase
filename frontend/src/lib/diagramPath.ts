export interface DiagramPath {
  root: string;
  path: string;
}

/** Decodes one raw rooted browse pathname once, preserving literal percent-looking names. */
export function parseDiagramPath(pathname: string): DiagramPath | null {
  if (!pathname.startsWith("/browse/")) return null;
  const rawSegments = pathname.slice("/browse/".length).split("/");
  if (rawSegments.length < 2 || rawSegments.some((segment) => segment === "")) return null;

  const decoded = rawSegments.map(decodeSegment);
  if (decoded.some((segment) => segment === null)) return null;
  const segments = decoded as string[];
  if (segments.some((segment) => segment === "." || segment === ".." || segment.includes("/") || segment.includes("\\") || segment.includes("\u0000"))) {
    return null;
  }
  const root = segments[0];
  const path = segments.slice(1).join("/");
  if (!root || !path || !path.endsWith(".mmd")) return null;
  return { root, path };
}

function decodeSegment(raw: string): string | null {
  if (/%2f/i.test(raw) || /%5c/i.test(raw) || raw.includes("\\")) return null;
  try {
    return decodeURIComponent(raw).normalize("NFC");
  } catch {
    return null;
  }
}
