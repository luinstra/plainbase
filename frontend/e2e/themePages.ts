export const themeMarkdown = [
  "---", "title: Theme baseline", "owner: Ada Lovelace", "status: active", "---", "",
  "# Theme baseline", "", "A paragraph with [a link](https://example.com) and inline `code`.", "",
  "> A blockquote.", "", "| Name | Value |", "| --- | --- |", "| Sample | Baseline |", "",
  ...["NOTE", "WARNING", "CAUTION"].flatMap((kind) => [
    `> [!${kind}]`, "> Callout with [a link](https://example.com).", ">", `> > [!${kind}]`, "> > Nested callout.", "",
  ]),
  "```javascript", 'function greet(name) { return { count: 1, label: "x" }; } // note', "```", "",
  "```json", '{ "count": 1, "label": "x" }', "```", "",
  "```mermaid", "flowchart LR", " subgraph Group", " A[Start]", " B[Finish]", " end", " A -->|go| B", "```", "",
].join("\n");
