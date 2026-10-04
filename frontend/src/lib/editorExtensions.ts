import { syntaxTree } from "@codemirror/language";
import { RangeSet, StateEffect, StateField } from "@codemirror/state";
import { Decoration, EditorView, GutterMarker, ViewPlugin, gutter, type DecorationSet, type ViewUpdate } from "@codemirror/view";
import type { EditorDiagnostic } from "./editorDiagnostics";

export const setLinkDiagnostics = StateEffect.define<EditorDiagnostic[]>();
class LinkMarker extends GutterMarker {
  constructor(readonly line: number, readonly message: string) { super(); }
  eq(other: LinkMarker) { return this.line === other.line && this.message === other.message; }
  toDOM(view: EditorView) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "pb-editor-link-mark";
    button.title = this.message;
    button.setAttribute("aria-label", this.message);
    button.textContent = "!";
    button.addEventListener("click", () => {
      view.dispatch({ selection: { anchor: view.state.doc.line(Math.min(this.line, view.state.doc.lines)).from }, scrollIntoView: true });
      view.focus();
    });
    return button;
  }
}
const links = StateField.define<RangeSet<GutterMarker>>({
  create: () => RangeSet.empty,
  update(markers, transaction) {
    if (transaction.docChanged) markers = RangeSet.empty;
    for (const effect of transaction.effects) if (effect.is(setLinkDiagnostics)) {
      markers = RangeSet.of(effect.value.filter(({ line }) => line > 0 && line <= transaction.state.doc.lines)
        .map(({ line, message }) => new LinkMarker(line, message).range(transaction.state.doc.line(line).from)), true);
    }
    return markers;
  },
});
export const linkDiagnosticGutter = [links, gutter({ class: "pb-editor-link-gutter", markers: (view) => view.state.field(links) })];

function tableLines(view: EditorView): DecorationSet {
  const ranges = new Map<number, ReturnType<Decoration["range"]>>();
  syntaxTree(view.state).iterate({ enter(node) {
    if (node.name !== "Table") return;
    for (let number = view.state.doc.lineAt(node.from).number; number <= view.state.doc.lineAt(node.to).number; number++) {
      const line = view.state.doc.line(number);
      ranges.set(line.from, Decoration.line({ class: "pb-editor-table-line" }).range(line.from));
    }
    return false;
  } });
  return Decoration.set(Array.from(ranges.values()), true);
}
export const tableSourceLayout = ViewPlugin.fromClass(class {
  decorations: DecorationSet;
  constructor(view: EditorView) { this.decorations = tableLines(view); }
  update(update: ViewUpdate) {
    if (update.docChanged || update.viewportChanged || syntaxTree(update.startState) !== syntaxTree(update.state)) this.decorations = tableLines(update.view);
  }
}, { decorations: (plugin) => plugin.decorations });
