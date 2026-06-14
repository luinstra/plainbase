import { describe, expect, it } from "vitest";
import type { TreePage } from "../api/types";
import { fuzzyRank, fuzzyScore, type FuzzyCandidate } from "../lib/fuzzy";

/** Fuzzy quick-switcher scorer goldens (criterion 17). Non-contract UI — pins behavior only. */

function page(title: string, path: string): TreePage {
  return { type: "page", id: title, title, slug: title, path, url: `/docs/${path}`, status: "active", updated: null };
}
function candidate(title: string, path = `${title.toLowerCase().replace(/\s+/g, "-")}.md`): FuzzyCandidate {
  const node = page(title, path);
  return { node, label: node.title, hint: node.path };
}

describe("fuzzyScore", () => {
  it("matches a subsequence and rejects a non-subsequence", () => {
    expect(fuzzyScore("dpl", "Deploy Guide")).not.toBeNull();
    expect(fuzzyScore("xyz", "Deploy Guide")).toBeNull();
  });

  it("consecutive run beats scattered (same chars)", () => {
    const consecutive = fuzzyScore("dep", "Deploy")!;
    const scattered = fuzzyScore("dep", "Diet Pepsi")!;
    expect(consecutive).toBeGreaterThan(scattered);
  });

  it("word-boundary initials beat mid-word matches", () => {
    // "gs": initials of "Getting Started" vs mid-word in "Programs".
    const initials = fuzzyScore("gs", "Getting Started")!;
    const midWord = fuzzyScore("gs", "Programs")!;
    expect(initials).toBeGreaterThan(midWord);
  });
});

describe("fuzzyRank", () => {
  it("ranks the consecutive-prefix match first (concrete ordered case)", () => {
    const ranked = fuzzyRank("dev", [candidate("Deploy Guide"), candidate("Getting Started"), candidate("Developer Setup")]);
    expect(ranked.map((m) => m.candidate.label)).toEqual(["Developer Setup"]);
  });

  it("matches a path segment even when the title does not contain the query", () => {
    const ranked = fuzzyRank("guides", [{ node: page("Deploy Guide", "guides/deploy-guide.md"), label: "Deploy Guide", hint: "guides/deploy-guide.md" }]);
    expect(ranked).toHaveLength(1);
    expect(ranked[0].candidate.label).toBe("Deploy Guide");
  });

  it("drops non-matches and orders by score descending", () => {
    const ranked = fuzzyRank("set", [candidate("Developer Setup"), candidate("Settings"), candidate("Deploy Guide")]);
    expect(ranked.map((m) => m.candidate.label)).not.toContain("Deploy Guide");
    // "Settings" leads with a consecutive word-boundary run.
    expect(ranked[0].candidate.label).toBe("Settings");
  });
});
