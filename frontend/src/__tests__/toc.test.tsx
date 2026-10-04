import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { Toc } from "../components/Toc";

afterEach(() => vi.restoreAllMocks());

it("shows an outline only when at least two eligible headings exist", () => {
  const { rerender } = render(<Toc headings={[]} />);
  expect(screen.queryByRole("navigation")).toBeNull();
  rerender(<Toc headings={[{ id: "title", text: "Title", level: 1 }, { id: "one", text: "One", level: 2 }]} />);
  expect(screen.queryByRole("navigation")).toBeNull();
  rerender(<Toc headings={[{ id: "one", text: "One", level: 2 }, { id: "two", text: "Two", level: 3 }]} />);
  expect(screen.getByRole("navigation", { name: "On this page" })).toBeTruthy();
  expect(screen.getAllByRole("link")).toHaveLength(2);
});

it("marks the section above the reading position and updates on scroll", async () => {
  let secondTop = 300;
  const { container } = render(<><h2 id="one">First section</h2><h2 id="two">Second section</h2>
    <Toc headings={[{ id: "one", text: "First", level: 2 }, { id: "two", text: "Second", level: 2 }]} /></>);
  vi.spyOn(container.querySelector("#one")!, "getBoundingClientRect").mockImplementation(() => ({ top: -100 } as DOMRect));
  vi.spyOn(container.querySelector("#two")!, "getBoundingClientRect").mockImplementation(() => ({ top: secondTop } as DOMRect));
  fireEvent.scroll(window);
  await waitFor(() => expect(screen.getByRole("link", { name: "First" }).getAttribute("aria-current")).toBe("location"));
  secondTop = 70;
  fireEvent.scroll(window);
  await waitFor(() => expect(screen.getByRole("link", { name: "Second" }).getAttribute("aria-current")).toBe("location"));
  expect(screen.getByRole("link", { name: "First" }).hasAttribute("aria-current")).toBe(false);
  expect(screen.getByRole("link", { name: "Second" }).getAttribute("href")).toBe("#two");
});
