// @vitest-environment node
import { fileURLToPath } from "node:url";
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import type { TreeFolder, TreeNode } from "../api/types";
import { landingPage } from "./tree";

interface LandingCase {
  name: string;
  children: { type: "page" | "folder"; path: string; id: string | null }[];
  expected: string | null;
}

const cases = JSON.parse(
  readFileSync(
    fileURLToPath(
      new URL(
        "../../../server/src/test/resources/landing-cases.json",
        import.meta.url,
      ),
    ),
    "utf8",
  ),
) as LandingCase[];

describe("landingPage shared selection corpus", () => {
  for (const entry of cases) {
    it(entry.name, () => {
      const children = entry.children.map((child): TreeNode => {
        if (child.type === "page") {
          if (child.id === null) throw new Error("Page fixture requires an id");
          return {
            type: "page",
            id: child.id,
            title: child.path,
            slug: "unrelated",
            path: child.path,
            url: "/docs/unrelated",
            status: "active",
            updated: null,
          };
        }
        return {
          type: "folder",
          name: child.path,
          title: null,
          description: null,
          path: child.path,
          url: `/docs/${child.path}`,
          page_count: 0,
          children: [],
        };
      });
      const folder: TreeFolder = {
        type: "folder",
        name: "",
        title: null,
        description: null,
        path: "",
        url: "/docs",
        page_count: children.filter((child) => child.type === "page").length,
        children,
      };
      expect(landingPage(folder)?.id ?? null).toBe(entry.expected);
    });
  }
});
