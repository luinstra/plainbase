// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { RootBadge } from "../components/RootBadge";
afterEach(cleanup);
describe("root badge data and friendly names", () => {
  it.each([["Team docs", "font-sans"], [undefined, "font-mono"], ["docs", "font-mono"]])("renders %s with %s", (label, family) => {
    render(<RootBadge root="docs" label={label} />);
    const badge = screen.getByText(label ?? "docs");
    expect(badge.classList.contains(family!)).toBe(true);
    expect(badge.classList.contains("text-xs")).toBe(true);
    expect(badge.getAttribute("data-pb-root-badge")).toBe("docs");
  });
});
