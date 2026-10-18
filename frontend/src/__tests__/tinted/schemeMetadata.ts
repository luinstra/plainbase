import { readFileSync } from "node:fs";
import { parseDocument } from "yaml";
import { REASONS } from "./pipeline";

export type SchemeMetadata = {
  root: unknown;
  system: unknown;
  variant: { declared: boolean; value: unknown };
  slug: { declared: boolean; value: unknown };
  palette: unknown;
};

export function isMapping(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

export function readSchemeMetadata(file: string): SchemeMetadata | { stage: "preflight"; reason: typeof REASONS.preflight.yamlParse } {
  const document = parseDocument(readFileSync(file, "utf8"), { version: "1.2", uniqueKeys: true, prettyErrors: false });
  const failure = { stage: "preflight", reason: REASONS.preflight.yamlParse } as const;
  if (document.errors.length || document.warnings.length) return failure;
  let root: unknown;
  try { root = document.toJS(); } catch { return failure; }
  const mapping = isMapping(root) ? root : {};
  return {
    root, system: mapping.system,
    variant: { declared: Object.hasOwn(mapping, "variant"), value: mapping.variant },
    slug: { declared: Object.hasOwn(mapping, "slug"), value: mapping.slug },
    palette: mapping.palette,
  };
}
