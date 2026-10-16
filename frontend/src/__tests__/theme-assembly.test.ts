// @vitest-environment node
import { describe, expect, it } from "vitest";
import { assemble, builtinSlots, customSlots, selectors, type Mode, type Slots } from "./themeCandidates";

const modes: Mode[] = ["light", "dark"];
const generic = `${selectors.light} { --t-g: var(--t-a); } ${selectors.dark} { --t-g: var(--t-b); }`;
const light = `${selectors.light} { --t-l: var(--t-a); }`;
const dark = `${selectors.dark} { --t-l: var(--t-b); }`;
const base: Slots = [generic, light, dark];
const escape = (literal: string) => literal.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
const exact = (message: string) => new RegExp("^" + escape(message) + "$");
const violations: Array<{ name: string; slots: Slots; override?: string; message: string }> = [
  { name: "rejects a root block in a definition", slots: [generic, light + ":root { --t-r: var(--t-a); }", dark],
    message: "root block in light slot" },
  { name: "rejects the other exclusive scope in a definition", slots: [generic, light, dark + light],
    message: `wrong scope ${selectors.light} in dark slot` },
  { name: "requires the definition's own block", slots: [generic, light, ""], message: "missing dark block in dark slot" },
  { name: "requires both generic blocks", slots: [`${selectors.light} { --t-g: var(--t-a); }`, light, dark],
    message: "missing dark block in generic slot" },
  { name: "rejects duplicate definition blocks", slots: [generic, light + light, dark], message: "duplicate light block in light slot" },
  { name: "rejects duplicate generic blocks", slots: [generic + `${selectors.dark} { --t-g: var(--t-b); }`, light, dark],
    message: "duplicate dark block in generic slot" },
  { name: "rejects duplicate properties in a definition", slots: [generic, `${selectors.light} { --t-l: var(--t-a); --t-l: var(--t-b); }`, dark],
    message: "duplicate property --t-l in light slot" },
  { name: "rejects an unwrapped light selector", slots: [generic, light + ':root:not([data-theme="dark"]) { --t-x: var(--t-a); }', dark],
    message: 'unsupported selector :root:not([data-theme="dark"]) in light slot' },
  { name: "rejects a differently quoted dark selector", slots: [generic, light, dark + ":where(:root[data-theme='dark']) { --t-x: var(--t-a); }"],
    message: "unsupported selector :where(:root[data-theme='dark']) in dark slot" },
  { name: "rejects an unsupported override selector", slots: base, override: ".t-x { --t-o: var(--t-a); }",
    message: "unsupported selector .t-x in override slot" },
];

describe("strict theme assembly", () => {
  for (const mode of modes) for (const row of violations) {
    it(`${row.name} in ${mode} mode`, () => {
      expect(() => assemble(mode, row.slots, row.override)).toThrow(exact(row.message));
    });
  }
  for (const mode of modes) for (const [row, prefix] of [
    [violations[7], "unsupported selector :root:not("],
    [violations[8], "unsupported selector :where(:root[data-theme='dark'])"],
  ] as const) {
    it(`selector prefix control for ${row.name} in ${mode} mode`, () => {
      expect(() => assemble(mode, row.slots)).toThrow(new RegExp("^" + escape(prefix)));
    });
  }
  for (const mode of modes) {
    it(`assembles the valid base in ${mode} mode`, () => {
      const value = mode === "light" ? "var(--t-a)" : "var(--t-b)";
      expect(assemble(mode, base)).toEqual({ "--t-g": value, "--t-l": value });
    });
    it(`applies root and exclusive overrides last in ${mode} mode`, () => {
      const override = `:root { --t-l: var(--t-c); } ${selectors.dark} { --t-g: var(--t-c); }`;
      expect(assemble(mode, base, override)).toEqual({
        "--t-g": mode === "light" ? "var(--t-a)" : "var(--t-c)", "--t-l": "var(--t-c)",
      });
    });
    for (const rootFirst of [true, false]) {
      it(`root overrides outrank exclusive overrides when root comes ${rootFirst ? "first" : "last"} in ${mode} mode`, () => {
        const root = ":root { --t-l: var(--t-c); }";
        const exclusive = `${selectors[mode]} { --t-l: var(--t-d); }`;
        const override = rootFirst ? root + exclusive : exclusive + root;
        expect(assemble(mode, base, override)).toEqual({
          "--t-g": mode === "light" ? "var(--t-a)" : "var(--t-b)", "--t-l": "var(--t-c)",
        });
      });
    }
    it(`allows repeated override blocks and properties in ${mode} mode`, () => {
      const override = ":root { --t-l: var(--t-c); } :root { --t-l: var(--t-d); --t-g: var(--t-c); --t-g: var(--t-d); }";
      expect(assemble(mode, base, override)).toEqual({ "--t-g": "var(--t-d)", "--t-l": "var(--t-d)" });
    });
    for (const [name, slots] of [
      ["built-in", builtinSlots()], ["opaque custom", customSlots()], ["translucent custom", customSlots("translucent-dark")],
    ] as const) {
      it(`assembles ${name} definitions in ${mode} mode`, () => {
        expect(() => assemble(mode, slots)).not.toThrow();
      });
    }
  }
  it("validation messages distinguish every rule", () => {
    for (const [index, row] of violations.entries()) {
      const pattern = exact(row.message);
      expect(pattern.test(row.message)).toBe(true);
      for (const [otherIndex, other] of violations.entries()) {
        if (otherIndex !== index) expect(pattern.test(other.message)).toBe(false);
      }
    }
  });
});
