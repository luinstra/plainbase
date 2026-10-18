import fs from "node:fs";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

const root = fs.realpathSync.native(fileURLToPath(new URL("../../../", import.meta.url)));
if (fs.realpathSync.native(process.cwd()) !== root) {
  throw new Error("Tinted tests require the frontend cwd; run ./gradlew :frontend:tintedTemplateCheck");
}

export default defineConfig({
  root,
  test: {
    environment: "node",
    include: ["src/__tests__/tinted/**/*.tinted.ts"],
    fileParallelism: false,
    testTimeout: 60000,
    hookTimeout: 60000,
  },
});
