import { readFileSync } from "node:fs";
import path from "node:path";
import type { Connect, Plugin } from "vite";

const themeSheets = [
  ["src/styles/themes/generic.css", "plainbase-theme-generic.css"],
  ["src/styles/themes/light.css", "_plainbase/plainbase-theme-light.css"],
  ["src/styles/themes/dark.css", "_plainbase/plainbase-theme-dark.css"],
] as const;

export function themeStylesheets(): Plugin {
  let frontendRoot = "";

  return {
    name: "plainbase-theme-stylesheets",
    configResolved(config) {
      frontendRoot = config.root;
    },
    configureServer(server) {
      const handler: Connect.NextHandleFunction = (request, response, next) => {
        if (request.method !== "GET" || request.url?.split("?", 1)[0] !== "/plainbase-theme-generic.css") {
          next();
          return;
        }
        const sheet = readFileSync(path.join(frontendRoot, "src/styles/themes/generic.css"));
        response.statusCode = 200;
        response.setHeader("Content-Type", "text/css; charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.end(sheet);
      };
      // Vite's SPA fallback answers missing stylesheet requests accepting any media type with HTML.
      // Register immediately so the canonical stylesheet wins before that fallback.
      server.middlewares.use(handler);
    },
    transformIndexHtml: {
      order: "post",
      handler: () => [{
        tag: "link",
        attrs: { rel: "stylesheet", href: "/_plainbase/custom.css" },
        injectTo: "head",
      }],
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
