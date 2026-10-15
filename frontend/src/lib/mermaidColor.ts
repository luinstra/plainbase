import { ColorSpace, HSL, HWB, Lab, LCH, OKLab, OKLCH, P3, parse, sRGB, to, toGamut } from "colorjs.io/fn";

const ALLOWED_SPACES = new Set(["srgb", "hsl", "hwb", "lab", "lch", "oklab", "oklch", "p3"]);
const LEGACY_RGB = /^rgba?\(\d{1,3}, \d{1,3}, \d{1,3}(?:, [\d.]+)?\)$/;

for (const space of [sRGB, HSL, HWB, Lab, LCH, OKLab, OKLCH, P3]) ColorSpace.register(space);

function isFiniteNumber(value: number | null | undefined): value is number {
  return Number.isFinite(value);
}

function inUnitRange(value: number | null | undefined): value is number {
  return isFiniteNumber(value) && value >= 0 && value <= 1;
}

export function toMermaidColor(computed: string): string {
  if (computed.startsWith("rgb(") || computed.startsWith("rgba(")) {
    if (!LEGACY_RGB.test(computed)) throw new Error("Unsupported computed color");
    const values = computed.slice(computed.indexOf("(") + 1, -1).split(", ").map(Number);
    if (!values.slice(0, 3).every((channel) => isFiniteNumber(channel) && channel <= 255)
      || !inUnitRange(values[3] ?? 1)) throw new Error("Unsupported computed color");
    return computed;
  }

  const match = computed.match(/^color\(srgb\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)(?:\s+\/\s+([\d.]+))?\)$/);
  if (match && match.slice(1, 4).map(Number).every(inUnitRange) && inUnitRange(match[4] === undefined ? 1 : Number(match[4]))) {
    const channels = match.slice(1, 4).map((channel) => Math.round(Number(channel) * 255));
    const alpha = match[4];
    return alpha === undefined ? `rgb(${channels.join(", ")})` : `rgba(${channels.join(", ")}, ${alpha})`;
  }

  const parsed = parse(computed);
  if (!ALLOWED_SPACES.has(parsed.spaceId)) throw new Error("Unsupported computed color space");
  if (!parsed.coords.every((value) => value === null || isFiniteNumber(value))
    || !(parsed.alpha === null || isFiniteNumber(parsed.alpha))) throw new Error("Non-finite computed color");
  if (parsed.alpha === null) parsed.alpha = 0;

  const converted = toGamut(to(parsed, "srgb"), { space: "srgb", method: "css" });
  const channels = converted.coords.map((component) => {
    const channel = component === null ? 0 : component;
    if (!isFiniteNumber(channel)) throw new Error("Non-finite converted channel");
    const byte = Math.round(channel * 255);
    if (!Number.isInteger(byte) || byte < 0 || byte > 255) throw new Error("Converted channel outside its range");
    return byte;
  });
  const alpha = converted.alpha;
  if (!inUnitRange(alpha)) throw new Error("Converted alpha outside its range");
  const formattedAlpha = Number(alpha.toFixed(4));
  return alpha === 1 ? `rgb(${channels.join(", ")})` : `rgba(${channels.join(", ")}, ${formattedAlpha})`;
}
