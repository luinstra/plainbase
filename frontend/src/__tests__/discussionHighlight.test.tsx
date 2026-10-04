import { act, render, waitFor } from "@testing-library/react";
import { useRef } from "react";
import { expect, it } from "vitest";
import type { DiscussionDetail } from "../api/types";
import { useDiscussionHighlight } from "../lib/useDiscussionHighlight";
import { captureSelectionAnchor } from "../lib/selectionAnchor";

const detail = { id: "thread", state: "exact", range: { byte_start: 15, byte_end: 25 }, range_content_hash: "hash",
  page: { id: "page", path: "note.md", resolution: "by_id" } } as DiscussionDetail;
function View({ value = detail, hash = "hash", active = true }: { value?: DiscussionDetail | null; hash?: string; active?: boolean }) {
  const wrapper = useRef<HTMLDivElement>(null);
  useDiscussionHighlight(wrapper, { root: "docs", id: "page", path: "note.md", hash, html: "fixture", ready: active }, value);
  return <div ref={wrapper}><article data-pb-selection-surface>
    <div><h1 data-pb-src="0-10">Title</h1></div><div data-pb-selection-chrome>Properties</div>
    <div><p data-pb-src="11-30">Repeated quote</p><p data-pb-src="31-50">Repeated quote</p></div>
  </article></div>;
}
it("highlights only the source block, preserving selection and clearing on stale or hidden source", () => {
  const view = render(<View />);
  const article = view.container.querySelector('article')!;
  const blocks = article.querySelectorAll('p');
  expect(blocks[0].classList.contains('pb-discussion-passage')).toBe(true);
  expect(blocks[1].classList.contains('pb-discussion-passage')).toBe(false);
  const range = document.createRange(); range.selectNodeContents(blocks[0]);
  const selection = window.getSelection()!; selection.removeAllRanges(); selection.addRange(range);
  expect(captureSelectionAnchor(article, selection, 'hash')).toEqual({ kind: 'quote', content_hash: 'hash', block_start: 11, block_end: 30, selected_text: 'Repeated quote' });
  view.rerender(<View hash="new" />);
  expect(article.querySelector('.pb-discussion-passage')).toBeNull();
  expect(selection.toString()).toBe('Repeated quote');
  view.rerender(<View />);
  expect(article.querySelector('.pb-discussion-passage')).toBeTruthy();
  view.rerender(<View active={false} />);
  expect(article.querySelector('.pb-discussion-passage')).toBeNull();
  view.rerender(<View />);
  expect(article.querySelector('.pb-discussion-passage')).toBeTruthy();
  view.rerender(<View value={null} />);
  expect(article.querySelector('.pb-discussion-passage')).toBeNull();
});

it.each([
  { ...detail, state: "ambiguous" },
  { ...detail, range_content_hash: undefined },
  { ...detail, page: { ...detail.page, id: "different" } },
  { ...detail, page: { ...detail.page, resolution: "by_path", path: "other.md" } },
  { ...detail, range: { byte_start: -1, byte_end: 25 } },
  { ...detail, range: { byte_start: 25, byte_end: 15 } },
  { ...detail, range: { byte_start: 15.5, byte_end: 25 } },
])("clears a previously valid highlight for untrusted range or identity %j", (value) => {
  const view = render(<View />);
  expect(view.container.querySelector('.pb-discussion-passage')).toBeTruthy();
  view.rerender(<View value={value as DiscussionDetail} />);
  expect(view.container.querySelector('.pb-discussion-passage')).toBeNull();
});
it("uses the resolved by-path identity and includes an original title fragment", () => {
  const view = render(<View value={{ ...detail, range: { byte_start: 0, byte_end: 25 },
    page: { id: "missing-stored-id", path: "note.md", resolution: "by_path" } }} />);
  expect(Array.from(view.container.querySelectorAll('.pb-discussion-passage')).map((node) => node.textContent)).toEqual(['Title', 'Repeated quote']);
  expect(view.container.querySelector('[data-pb-selection-chrome]')?.classList.contains('pb-discussion-passage')).toBe(false);
});
it("retains parent-owned text while avoiding duplicate nested painting", () => {
  function Nested({ start = 15, end = 25 }: { start?: number; end?: number }) {
    const wrapper = useRef<HTMLDivElement>(null);
    useDiscussionHighlight(wrapper, { root: 'docs', id: 'page', path: 'note.md', hash: 'hash', html: 'nested', ready: true },
      { ...detail, range: { byte_start: start, byte_end: end } });
    return <div ref={wrapper}><article data-pb-selection-surface><blockquote data-pb-src="11-30">Direct text
      <p data-pb-src="20-30">Nested text</p></blockquote><pre data-pb-src="31-50"><code data-pb-src="31-50">Code</code></pre></article></div>;
  }
  const view = render(<Nested />);
  expect(Array.from(view.container.querySelectorAll('.pb-discussion-passage')).map((node) => node.tagName)).toEqual(['BLOCKQUOTE']);
  view.rerender(<Nested start={32} end={49} />);
  expect(Array.from(view.container.querySelectorAll('.pb-discussion-passage')).map((node) => node.tagName)).toEqual(['CODE']);
});

it('repaints asynchronous source replacement and disconnects on unmount', async () => {
  const view = render(<View />);
  const before = view.container.querySelector('p')!;
  expect(before.classList.contains('pb-discussion-passage')).toBe(true);
  const replacement = document.createElement('pre');
  replacement.setAttribute('data-pb-src', '11-30'); replacement.textContent = 'Diagram replacement';
  await act(async () => { before.replaceWith(replacement); });
  await waitFor(() => expect(replacement.classList.contains('pb-discussion-passage')).toBe(true));
  expect(before.classList.contains('pb-discussion-passage')).toBe(false);
  expect(replacement.textContent).toBe('Diagram replacement');
  expect(replacement.getAttribute('data-pb-src')).toBe('11-30');
  const article = view.container.querySelector('article')!;
  view.unmount();
  expect(replacement.classList.contains('pb-discussion-passage')).toBe(false);
  await act(async () => { article.appendChild(document.createElement('span')); });
  expect(replacement.classList.contains('pb-discussion-passage')).toBe(false);
});
