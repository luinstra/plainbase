import { expect, it } from "vitest";
import { captureSelectionAnchor } from "./selectionAnchor";

it("keeps source ranges across prose fragments and refuses property or copy chrome", () => {
  const article = document.createElement("article");
  article.innerHTML = '<div class="pb-prose"><h1 data-pb-src="0-10">Title</h1></div><div data-pb-selection-chrome>Owner</div><div class="pb-prose"><p data-pb-src="20-90"><code>abcdef</code><button data-pb-selection-chrome></button> end</p></div>';
  document.body.append(article);
  const title = article.querySelector("h1")!.firstChild!;
  const code = article.querySelector("code")!.firstChild!;
  expect(select(article, title, 0, title, 5)).toMatchObject({ selected_text: "Title", block_start: 0, block_end: 10 });
  expect(select(article, code, 0, code, 6)).toMatchObject({ selected_text: "abcdef", block_start: 20, block_end: 90 });
  expect(select(article, title, 0, code, 6)).toMatchObject({ reason: expect.any(String) });
  expect(select(article, code, 0, article.querySelector("p")!.lastChild!, 4)).toMatchObject({ reason: expect.any(String) });
  article.remove();
});

function select(article: HTMLElement, start: Node, startOffset: number, end: Node, endOffset: number) {
  const range = document.createRange();
  range.setStart(start, startOffset);
  range.setEnd(end, endOffset);
  const selection = window.getSelection()!;
  selection.removeAllRanges();
  selection.addRange(range);
  return captureSelectionAnchor(article, selection, "sha256:source");
}

it("uses source byte attributes, including zero and cross-block union, with verbatim browser text", () => {
  const article = document.createElement("article");
  article.innerHTML = '<p data-pb-src="0-14">banana</p><p data-pb-src="20-48">漢字 end</p>';
  document.body.append(article);
  const first = article.querySelectorAll("p")[0].firstChild!;
  const second = article.querySelectorAll("p")[1].firstChild!;
  expect(select(article, first, 1, first, 4)).toEqual({
    kind: "quote", content_hash: "sha256:source", block_start: 0, block_end: 14, selected_text: "ana",
  });
  expect(select(article, first, 1, second, 2)).toEqual({
    kind: "quote", content_hash: "sha256:source", block_start: 0, block_end: 48, selected_text: window.getSelection()!.toString(),
  });
  article.remove();
});

it("takes the selected side of element boundaries and refuses missing or outside source", () => {
  const article = document.createElement("article");
  article.innerHTML = '<p data-pb-src="0-5">first</p><p data-pb-src="6-12">second</p><p>unknown</p>';
  document.body.append(article);
  const paragraphs = article.querySelectorAll("p");
  expect(select(article, article, 1, paragraphs[1].firstChild!, 3)).toEqual({
    kind: "quote", content_hash: "sha256:source", block_start: 6, block_end: 12, selected_text: "sec",
  });
  expect(select(article, paragraphs[2].firstChild!, 0, paragraphs[2].firstChild!, 3)).toMatchObject({ reason: expect.any(String) });
  const outside = document.createElement("p"); outside.textContent = "outside"; document.body.append(outside);
  expect(select(article, outside.firstChild!, 0, outside.firstChild!, 3)).toMatchObject({ reason: expect.any(String) });
  article.remove(); outside.remove();
});

it("uses selected text at element and text endpoints that touch an adjacent block", () => {
  const article = document.createElement("article");
  article.innerHTML = '<p data-pb-src="0-5">first</p><p data-pb-src="6-12">second</p><p>unknown</p>';
  document.body.append(article);
  const [first, second, unknown] = article.querySelectorAll("p");
  expect(select(article, first.firstChild!, 1, second, 0)).toEqual({
    kind: "quote", content_hash: "sha256:source", block_start: 0, block_end: 5, selected_text: "irst",
  });
  expect(select(article, first, first.childNodes.length, second.firstChild!, 3)).toEqual({
    kind: "quote", content_hash: "sha256:source", block_start: 6, block_end: 12, selected_text: "sec",
  });
  expect(select(article, first.firstChild!, 1, second.firstChild!, 0)).toMatchObject({ block_start: 0, block_end: 5 });
  expect(select(article, first.firstChild!, 5, second.firstChild!, 3)).toMatchObject({ block_start: 6, block_end: 12 });
  expect(select(article, first.firstChild!, 1, unknown, 0)).toMatchObject({ block_start: 0, block_end: 12 });
  expect(select(article, second.firstChild!, 2, unknown.firstChild!, 3)).toMatchObject({ reason: expect.any(String) });
  article.remove();
});

it("normalizes a backwards selection and takes the nearest nested source span", () => {
  const article = document.createElement("article");
  article.innerHTML = '<div data-pb-src="12-80"><span data-pb-src="20-30">first</span><span data-pb-src="50-60">second</span></div>';
  document.body.append(article);
  const spans = article.querySelectorAll("span");
  const selection = window.getSelection()!;
  selection.removeAllRanges();
  selection.setBaseAndExtent(spans[1].firstChild!, 3, spans[0].firstChild!, 1);
  expect(captureSelectionAnchor(article, selection, "sha256:source")).toEqual({
    kind: "quote", content_hash: "sha256:source", block_start: 20, block_end: 60, selected_text: selection.toString(),
  });
  article.remove();
});

it("fails closed on malformed attributes and generated heading chrome", () => {
  const article = document.createElement("article");
  article.innerHTML = '<p data-pb-src="1-2-3">broken</p><h2 data-pb-src="0-20">Heading<a class="pb-heading-anchor">#</a></h2>';
  document.body.append(article);
  const paragraph = article.querySelector("p")!.firstChild!;
  expect(select(article, paragraph, 0, paragraph, 3)).toMatchObject({ reason: expect.any(String) });
  const heading = article.querySelector("h2")!;
  expect(select(article, heading.firstChild!, 0, heading.querySelector("a")!.firstChild!, 1))
    .toMatchObject({ reason: expect.any(String) });
  article.remove();
});
