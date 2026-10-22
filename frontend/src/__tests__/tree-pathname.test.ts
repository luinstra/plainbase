import { describe, expect, it } from "vitest";
import type { RootTree, TreeFolder } from "../api/types";
import { ancestorFolderPaths, folderByUrl, samePathname } from "../lib/tree";

const folders: TreeFolder[] = [
  ["a%3F", "/library/a%253F"], ["a?", "/library/a%3F"],
  ["café", "/library/caf%C3%A9"], ["a%25", "/library/a%2525"],
].map(([name, url]) => ({ type: "folder", name, path: name, url,
  title: null, description: null, page_count: 0, children: [] }));
const root: RootTree = {
  root: "library", available: true, primary: true, editable: false,
  tree: { type: "folder", name: "", path: "", url: "/library", title: null, description: null,
    page_count: 0, children: folders },
};

describe("router pathname identity", () => {
  it.each(["/library/cafe\u0301", "/library/cafe\u0301/"])("resolves NFC-equivalent folder %s", (pathname) => {
    expect(folderByUrl([root], pathname)?.folder.path).toBe("café");
    expect(ancestorFolderPaths(root.tree, root.root, pathname)).toEqual(["café"]);
    expect(samePathname(pathname.replace(/\/$/, ""), "/library/caf%C3%A9")).toBe(true);
  });

  it("normalizes the decoded server operand as well as the router operand", () => {
    expect(samePathname("/library/café", "/library/cafe%CC%81")).toBe(true);
    expect(samePathname("/library/cafe\u0301", "/library/cafe%CC%81")).toBe(true);
  });

  it("keeps already-NFC identity and avoids compatibility normalization", () => {
    expect(samePathname("/library/café", "/library/caf%C3%A9")).toBe(true);
    expect(samePathname("/library/Ａ", "/library/A")).toBe(false);
    expect(samePathname("/library/Café", "/library/caf%C3%A9")).toBe(false);
  });

  it("expands the Unicode parent of a trailing-slash page", () => {
    const tree: TreeFolder = { ...root.tree, children: [{ ...folders[2], page_count: 1, children: [{
      type: "page", id: "01970000-0000-7000-8000-00000000a199", title: "Cafe page", slug: "page",
      path: "café/page.md", url: "/library/caf%C3%A9/page", status: "published", updated: null,
    }] }] };
    expect(ancestorFolderPaths(tree, root.root, "/library/café/page/")).toEqual(["café"]);
    expect(ancestorFolderPaths(tree, root.root, "/browse/library/café/page/")).toEqual([]);
  });

  it.each([
    { pathname: "/library/a%253F/", name: "a%3F" },
    { pathname: "/library/a%3F/", name: "a?" },
    { pathname: "/library/café/", name: "café" },
    { pathname: "/library/a%2525/", name: "a%25" },
  ])("looks up $name with one trailing slash", ({ pathname, name }) => {
    expect(folderByUrl([root], pathname)?.folder.path).toBe(name);
  });

  it("does not normalize a bare root or multiple trailing slashes at the folder lookup seam", () => {
    expect(folderByUrl([root], "/library")?.folder).toBe(root.tree);
    expect(folderByUrl([root], "/library/")).toBeNull();
    expect(folderByUrl([root], "/library/a%3F//")).toBeNull();
  });

  it.each(["%25", "%5C", "%5c", "%3F", "%3f"])("preserves the router spelling of %s", (escape) => {
    expect(samePathname(`/library/a${escape}`, `/library/a${escape}`)).toBe(true);
    expect(samePathname("/library/a%3f", "/library/a%3F")).toBe(false);
  });

  it.each([
    { pathname: "/library/a%253F", name: "a%3F", url: "/library/a%253F" },
    { pathname: "/library/a%3F", name: "a?", url: "/library/a%3F" },
    { pathname: "/library/café", name: "café", url: "/library/caf%C3%A9" },
    { pathname: "/library/a%2525", name: "a%25", url: "/library/a%2525" },
  ])("resolves and expands only $name", ({ pathname, name, url }) => {
    expect(samePathname(pathname, url)).toBe(true);
    expect(folderByUrl([root], pathname)?.folder.path).toBe(name);
    expect(ancestorFolderPaths(root.tree, root.root, pathname)).toEqual([name]);
    for (const sibling of folders.filter((folder) => folder.path !== name)) {
      expect(samePathname(pathname, sibling.url!)).toBe(false);
    }
  });
});
