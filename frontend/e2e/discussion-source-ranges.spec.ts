import { expect, test } from "./smoke-fixtures";
import { gotoExpectStatus } from "./helpers";

const PAGE = "/docs/scratch/todo";
const SOURCE = [
  "---",
  "title: Source range probe",
  "---",
  "",
  "# Mapping probe",
  "",
  "A Target quote 😀 with e\u0301 and 漢字.",
  "",
  "banana",
  "",
  "<div>",
  "Block HTML selection.",
  "</div>",
  "",
  "Across one block.",
  "",
  "Across two blocks.",
  "",
  "> [!NOTE]",
  "> Callout selection source.",
  "",
  "- [ ] Task selection source.",
  "",
  "| Left | Right |",
  "| --- | --- |",
  "| Cell one | Cell two |",
  "",
  "```ts",
  "const probe = 17;",
  "```",
  "",
  "```mermaid",
  "flowchart LR",
  "  Start --> Finish",
  "```",
  "",
].join("\n");

test("maps rendered selections to source block byte ranges", async ({ page }) => {
  await gotoExpectStatus(page, `${PAGE}?mode=edit`);
  const content = page.locator("[data-pb-codemirror] .cm-content");
  await expect(content).toBeVisible();
  await content.click();
  await page.keyboard.press("ControlOrMeta+A");
  await page.keyboard.insertText(SOURCE);
  await page.locator("[data-pb-save]").click();
  await expect(page.locator("[data-pb-editor-notice]")).toBeVisible();

  await gotoExpectStatus(page, PAGE);
  const prose = page.locator(".pb-prose");
  await expect(prose.locator("[data-pb-src]").first()).toBeVisible();
  await expect(prose.locator(".pb-mermaid[data-pb-src]")).toBeVisible();
  const measurements = await page.evaluate((source) => {
    const article = document.querySelector<HTMLElement>(".pb-prose");
    if (!article) throw new Error("rendered page is missing .pb-prose");

    const bytes = new TextEncoder().encode(source);
    const decoder = new TextDecoder();
    const rangeOf = (element: Element | null) => {
      const encoded = element?.getAttribute("data-pb-src");
      const match = encoded?.match(/^(\d+)-(\d+)$/);
      if (!match) throw new Error(`missing source byte range on ${element?.tagName ?? "element"}`);
      const start = Number(match[1]);
      const end = Number(match[2]);
      return { start, end, source: decoder.decode(bytes.slice(start, end)) };
    };
    const attributedAncestor = (node: Node | null): HTMLElement | null => {
      let element = node instanceof Element ? node : node?.parentElement ?? null;
      while (element && element !== article) {
        if (element.hasAttribute("data-pb-src")) return element as HTMLElement;
        element = element.parentElement;
      }
      return null;
    };
    const textNodeContaining = (root: Node, value: string): Text => {
      const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
      while (walker.nextNode()) {
        const candidate = walker.currentNode as Text;
        if (candidate.data.includes(value)) return candidate;
      }
      throw new Error(`text not found: ${value}`);
    };
    const select = (startNode: Text, start: number, endNode = startNode, end = startNode.length) => {
      const range = document.createRange();
      range.setStart(startNode, start);
      range.setEnd(endNode, end);
      const selection = window.getSelection();
      if (!selection) throw new Error("browser selection is unavailable");
      selection.removeAllRanges();
      selection.addRange(range);
      return { range, text: selection.toString(), rangeText: range.toString() };
    };
    const quote = "Target quote 😀";
    const heading = article.querySelector<HTMLElement>("h1#mapping-probe");
    const headingText = heading?.firstChild;
    if (!(headingText instanceof Text)) throw new Error("heading text node is missing");
    const headingAnchorText = heading?.querySelector<HTMLElement>(".pb-heading-anchor")?.firstChild;
    if (!(headingAnchorText instanceof Text)) throw new Error("injected heading anchor text node is missing");
    const headingSelection = select(headingText, 0, headingText, headingText.length);
    const headingAnchorSelection = select(headingText, 0, headingAnchorText, headingAnchorText.length);
    const headingRange = rangeOf(attributedAncestor(headingText));

    const paragraph = [...article.querySelectorAll<HTMLElement>("p")].find((item) => item.textContent?.includes(quote));
    if (!paragraph) throw new Error("unicode paragraph is missing");
    const paragraphText = textNodeContaining(paragraph, quote);
    const paragraphStart = paragraphText.data.indexOf(quote);
    const paragraphSelection = select(paragraphText, paragraphStart, paragraphText, paragraphStart + quote.length);
    const paragraphRange = rangeOf(attributedAncestor(paragraphText));

    const overlapParagraph = [...article.querySelectorAll<HTMLElement>("p")].find((item) => item.textContent === "banana");
    if (!overlapParagraph) throw new Error("overlapping-match paragraph is missing");
    const overlapText = textNodeContaining(overlapParagraph, "banana");
    const overlapSelection = select(overlapText, 1, overlapText, 4);
    const overlapRange = rangeOf(attributedAncestor(overlapText));

    const htmlParagraph = [...article.querySelectorAll<HTMLElement>("p")].find((item) =>
      item.textContent?.includes("Block HTML selection."),
    );
    if (!htmlParagraph) throw new Error("escaped block HTML paragraph is missing");
    const htmlText = textNodeContaining(htmlParagraph, "Block HTML selection.");
    const htmlStart = htmlText.data.indexOf("Block HTML selection.");
    const htmlSelection = select(htmlText, htmlStart, htmlText, htmlStart + "Block HTML selection.".length);
    const htmlRange = rangeOf(attributedAncestor(htmlText));

    const crossStart = [...article.querySelectorAll<HTMLElement>("p")].find((item) => item.textContent === "Across one block.");
    const crossEnd = [...article.querySelectorAll<HTMLElement>("p")].find((item) => item.textContent === "Across two blocks.");
    if (!crossStart || !crossEnd) throw new Error("cross-block paragraphs are missing");
    const crossStartText = textNodeContaining(crossStart, "Across one block.");
    const crossEndText = textNodeContaining(crossEnd, "Across two blocks.");
    const crossSelection = select(crossStartText, 0, crossEndText, crossEndText.length);
    const crossStartRange = rangeOf(attributedAncestor(crossStartText));
    const crossEndRange = rangeOf(attributedAncestor(crossEndText));
    const crossUnion = {
      start: Math.min(crossStartRange.start, crossEndRange.start),
      end: Math.max(crossStartRange.end, crossEndRange.end),
    };
    const crossUnionSource = decoder.decode(bytes.slice(crossUnion.start, crossUnion.end));

    const code = article.querySelector<HTMLElement>("pre code.language-ts");
    if (!code) throw new Error("highlighted code block is missing");
    const codeText = textNodeContaining(code, "probe");
    const codeStart = codeText.data.indexOf("probe");
    const codeSelection = select(codeText, codeStart, codeText, codeStart + "probe".length);
    const codeRange = rangeOf(attributedAncestor(codeText));

    const mermaid = article.querySelector<HTMLElement>(".pb-mermaid[data-pb-src]");
    const hiddenMermaidSource = article.querySelector<HTMLElement>("pre code.language-mermaid")?.parentElement ?? null;
    if (!mermaid || !hiddenMermaidSource) {
      throw new Error("rendered Mermaid diagram or hidden source block is missing");
    }
    const mermaidText = textNodeContaining(mermaid, "Start");
    const mermaidSelection = select(mermaidText, 0, mermaidText, Math.min(mermaidText.length, 5));
    const mermaidRange = rangeOf(attributedAncestor(mermaidText));
    const hiddenMermaidRange = rangeOf(hiddenMermaidSource);

    const calloutRange = rangeOf(article.querySelector(".pb-callout[data-pb-src]"));
    const task = article.querySelector<HTMLElement>("li.task-list-item");
    if (!task) throw new Error("task list item is missing");
    const taskText = textNodeContaining(task, "Task selection source.");
    const taskSelection = select(taskText, 0, taskText, taskText.length);
    const taskRange = rangeOf(attributedAncestor(taskText));
    const cell = article.querySelector<HTMLElement>("td");
    if (!cell) throw new Error("table cell is missing");
    const cellText = textNodeContaining(cell, "Cell one");
    const cellSelection = select(cellText, 0, cellText, cellText.length);
    const cellRange = rangeOf(attributedAncestor(cellText));
    const tableRange = rangeOf(article.querySelector("table[data-pb-src]"));
    const exactOccurrenceCount = (value: string, range: { start: number; end: number }) => {
      const sourceSpan = decoder.decode(bytes.slice(range.start, range.end));
      let count = 0;
      let offset = 0;
      while (value.length > 0 && (offset = sourceSpan.indexOf(value, offset)) !== -1) {
        count += 1;
        offset += 1;
      }
      return count;
    };
    const samples = [
      { name: "heading", text: headingSelection.text, range: headingRange },
      { name: "paragraph", text: paragraphSelection.text, range: paragraphRange },
      { name: "overlapping-match", text: overlapSelection.text, range: overlapRange },
      { name: "block-html", text: htmlSelection.text, range: htmlRange },
      { name: "cross-block", text: crossSelection.text, range: { start: crossUnion.start, end: crossUnion.end } },
      { name: "highlighted-code", text: codeSelection.text, range: codeRange },
      { name: "mermaid", text: mermaidSelection.text, range: mermaidRange },
      { name: "task-item", text: taskSelection.text, range: taskRange },
      { name: "table-cell", text: cellSelection.text, range: cellRange },
    ].map((sample) => {
      const occurrences = exactOccurrenceCount(sample.text, sample.range);
      return { name: sample.name, occurrences, outcome: occurrences === 1 ? "narrowed" : "snapped" };
    });
    const crossBlockSample = samples.find(({ name }) => name === "cross-block");
    if (!crossBlockSample) throw new Error("cross-block selection sample is missing");
    const selectionTextPairs = [
      { name: "heading", text: headingSelection.text, rangeText: headingSelection.rangeText },
      { name: "heading-with-anchor", text: headingAnchorSelection.text, rangeText: headingAnchorSelection.rangeText },
      { name: "paragraph", text: paragraphSelection.text, rangeText: paragraphSelection.rangeText },
      { name: "overlapping-match", text: overlapSelection.text, rangeText: overlapSelection.rangeText },
      { name: "block-html", text: htmlSelection.text, rangeText: htmlSelection.rangeText },
      { name: "cross-block", text: crossSelection.text, rangeText: crossSelection.rangeText },
      { name: "highlighted-code", text: codeSelection.text, rangeText: codeSelection.rangeText },
      { name: "mermaid", text: mermaidSelection.text, rangeText: mermaidSelection.rangeText },
      { name: "task-item", text: taskSelection.text, rangeText: taskSelection.rangeText },
      { name: "table-cell", text: cellSelection.text, rangeText: cellSelection.rangeText },
    ];

    return {
      heading: {
        source: headingRange.source,
        selected: headingSelection.text,
        selectedWithAnchor: headingAnchorSelection.text,
      },
      paragraph: {
        source: paragraphRange.source,
        selected: paragraphSelection.text,
      },
      overlappingMatch: {
        selected: overlapSelection.text,
        occurrences: exactOccurrenceCount(overlapSelection.text, overlapRange),
      },
      crossBlock: {
        selected: crossSelection.text,
        rangeText: crossSelection.rangeText,
        unionSource: crossUnionSource,
        start: crossUnion.start,
        end: crossUnion.end,
        occurrences: crossBlockSample.occurrences,
        outcome: crossBlockSample.outcome,
      },
      code: { source: codeRange.source, selected: codeSelection.text },
      blockHtml: { source: htmlRange.source, selected: htmlSelection.text },
      mermaid: {
        visibleRange: `${mermaidRange.start}-${mermaidRange.end}`,
        hiddenRange: `${hiddenMermaidRange.start}-${hiddenMermaidRange.end}`,
        selected: mermaidSelection.text,
      },
      callout: calloutRange.source,
      task: taskRange.source,
      taskSelected: taskSelection.text,
      cell: cellRange.source,
      cellSelected: cellSelection.text,
      table: tableRange.source,
      samples,
      selectionTextPairs,
    };
  }, SOURCE);

  expect(measurements.heading.source).toBe("# Mapping probe");
  expect(measurements.heading.selected).toBe("Mapping probe");
  expect(measurements.heading.selectedWithAnchor).toBe("Mapping probe");
  expect(measurements.selectionTextPairs.find(({ name }) => name === "heading-with-anchor")?.rangeText).toBe("Mapping probe#");
  measurements.selectionTextPairs
    .filter(({ name }) => name !== "cross-block" && name !== "heading-with-anchor")
    .forEach(({ text, rangeText }) => {
      expect(text).toBe(rangeText);
    });
  expect(measurements.paragraph.source).toBe("A Target quote 😀 with e\u0301 and 漢字.\n");
  expect(measurements.paragraph.selected).toBe("Target quote 😀");
  expect(measurements.overlappingMatch).toEqual({ selected: "ana", occurrences: 2 });
  expect(measurements.blockHtml.source).toContain("<div>\nBlock HTML selection.\n</div>");
  expect(measurements.blockHtml.selected).toBe("Block HTML selection.");
  expect(measurements.crossBlock.selected).toContain("Across one block.");
  expect(measurements.crossBlock.selected).toContain("Across two blocks.");
  expect(measurements.crossBlock.rangeText).toContain("Across one block.");
  expect(measurements.crossBlock.rangeText).toContain("Across two blocks.");
  expect(measurements.crossBlock.unionSource).toContain("Across one block.\n\nAcross two blocks.");
  // The exact separator between Range.toString() and Selection.toString() is Chromium-specific probe evidence.
  // Assert the derived outcome only; its occurrence count determines whether it narrowed or snapped.
  expect(measurements.crossBlock.outcome).toBe(
    measurements.crossBlock.occurrences === 1 ? "narrowed" : "snapped",
  );
  expect(measurements.code.source).toContain("const probe = 17;");
  expect(measurements.code.selected).toBe("probe");
  expect(measurements.mermaid.visibleRange).toBe(measurements.mermaid.hiddenRange);
  expect(measurements.callout).toContain("> [!NOTE]");
  expect(measurements.task).toContain("Task selection source.");
  expect(measurements.taskSelected).toBe("Task selection source.");
  expect(measurements.cell).toContain("Cell one");
  expect(measurements.cellSelected).toBe("Cell one");
  expect(measurements.table).toContain("Cell one");
  const nonCrossBlockOutcomes = measurements.samples
    .filter(({ name }) => name !== "cross-block")
    .map(({ name, outcome }) => [name, outcome]);
  expect(nonCrossBlockOutcomes).toEqual([
    ["heading", "narrowed"],
    ["paragraph", "narrowed"],
    ["overlapping-match", "snapped"],
    ["block-html", "narrowed"],
    ["highlighted-code", "narrowed"],
    ["mermaid", "narrowed"],
    ["task-item", "narrowed"],
    ["table-cell", "narrowed"],
  ]);

  const paragraph = prose.locator("p").filter({ hasText: "banana" });
  await paragraph.click({ clickCount: 3 });
  const tripleClick = await page.evaluate(() => {
    const selection = window.getSelection();
    const range = selection?.rangeCount ? selection.getRangeAt(0) : null;
    return { text: selection?.toString(),
      start: range && { type: range.startContainer.nodeType, offset: range.startOffset, name: range.startContainer.nodeName },
      end: range && { type: range.endContainer.nodeType, offset: range.endOffset, name: range.endContainer.nodeName } };
  });
  expect(tripleClick.text).toContain("banana");
  expect(tripleClick.end).toEqual({ type: 1, offset: 0, name: "P" });
  await page.getByRole("button", { name: "New discussion" }).click();
  await expect(page.getByRole("button", { name: "Confirm passage" })).toBeVisible();
});

test("keeps a real hash in a heading selection while excluding its link", async ({ page }) => {
  await gotoExpectStatus(page, `${PAGE}?mode=edit`);
  const content = page.locator("[data-pb-codemirror] .cm-content");
  await content.click();
  await page.keyboard.press("ControlOrMeta+A");
  await page.keyboard.insertText("# C# setup\n\nThe heading is source text.\n");
  await page.locator("[data-pb-save]").click();
  await expect(page.locator("[data-pb-editor-notice]")).toBeVisible();
  await gotoExpectStatus(page, PAGE);
  const heading = page.locator("[data-pb-page-article] h1").filter({ hasText: "C# setup" });
  const anchor = heading.locator(".pb-heading-anchor");
  await expect(anchor).toHaveCount(1);
  const start = await heading.evaluate((node) => {
    const text = node.firstChild;
    if (!(text instanceof Text)) throw new Error("heading text missing");
    const range = document.createRange(); range.setStart(text, 0); range.setEnd(text, 1);
    const rect = range.getBoundingClientRect();
    return { x: rect.left, y: rect.top + rect.height / 2 };
  });
  const end = await page.locator("[data-pb-page-article] p").filter({ hasText: "The heading is source text." }).evaluate((node) => {
    const text = node.firstChild;
    if (!(text instanceof Text)) throw new Error("following paragraph text missing");
    const range = document.createRange(); range.setStart(text, 0); range.setEnd(text, 3);
    const rect = range.getBoundingClientRect();
    return { x: rect.right, y: rect.top + rect.height / 2 };
  });
  await page.mouse.move(start.x, start.y);
  await page.mouse.down();
  await page.mouse.move(end.x, end.y, { steps: 12 });
  await page.mouse.up();
  const selected = await page.evaluate(() => {
    const selection = window.getSelection();
    const anchor = document.querySelector("[data-pb-page-article] h1 .pb-heading-anchor");
    return { text: selection?.toString(), crossesLink: !!anchor && !!selection?.rangeCount && selection.getRangeAt(0).intersectsNode(anchor) };
  });
  expect(selected.text).toContain("C# setup");
  expect(selected.text).toContain("The");
  expect(selected.crossesLink).toBe(true);
  await page.getByRole("button", { name: "New discussion" }).click();
  await expect(page.getByRole("button", { name: "Confirm passage" })).toBeVisible();
  await expect(page.locator(".pb-discussion-quote")).toContainText("C# setup");
});
