import { readFileSync } from "node:fs";
import path from "node:path";
import type { Plugin } from "vite";

const themeSheets = [
  ["src/styles/themes/generic.css", "plainbase-theme-generic.css"],
  ["src/styles/themes/light.css", "_plainbase/plainbase-theme-light.css"],
  ["src/styles/themes/dark.css", "_plainbase/plainbase-theme-dark.css"],
] as const;

export function themeStylesheets(): Plugin {
  let frontendRoot = "";

  return {
    name: "plainbase-theme-stylesheets",
    apply: "build",
    configResolved(config) {
      frontendRoot = config.root;
    },
    generateBundle() {
      for (const [sourcePath, fileName] of themeSheets) {
        this.emitFile({
          type: "asset",
          fileName,
          source: readFileSync(path.join(frontendRoot, sourcePath)),
        });
      }
    },
  };
}
