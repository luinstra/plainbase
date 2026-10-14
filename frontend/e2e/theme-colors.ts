import type { Page } from "@playwright/test";
import type { Color } from "../src/__tests__/contrastResolver";

// Read the sRGB serialization without pixel readback, which would quantize alpha and channels.
export async function normalizeColors(page: Page, values: string[]): Promise<Color[]> {
  return page.evaluate((values) => {
    type Color = [number, number, number, number];
    const canvas = document.createElement("canvas").getContext("2d")!;
    return values.map((value): Color => {
      if (!CSS.supports("color", value)) throw new Error(`Unsupported color: ${value}`);
      canvas.fillStyle = value;
      const normalized = canvas.fillStyle;
      if (normalized.startsWith("#")) {
        const digits = normalized.slice(1);
        return [0, 2, 4].map((i) => parseInt(digits.slice(i, i + 2), 16) / 255)
          .concat(digits.length === 8 ? parseInt(digits.slice(6), 16) / 255 : 1) as Color;
      }
      const numbers = normalized.match(/[\d.]+/g)?.map(Number);
      if (!numbers || numbers.length < 3) throw new Error(`Unparsed color: ${normalized}`);
      if (normalized.startsWith("color(srgb ")) return [numbers[0], numbers[1], numbers[2], numbers[3] ?? 1];
      if (normalized.startsWith("rgb")) return [numbers[0] / 255, numbers[1] / 255, numbers[2] / 255, numbers[3] ?? 1];
      throw new Error(`Unsupported serialization: ${normalized}`);
    });
  }, values);
}
