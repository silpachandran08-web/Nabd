// FEF-026: gate for the categorical chart palette (--nb-chart-1..6). Used by build.mjs (CI: `pnpm --filter @nabd/tokens lint`)
// and by the unit test. Every pair of slots (any two series can sit side by side) must stay apart in normal vision and under
// simulated protanopia and deuteranopia, and every slot must reach 3:1 on the chart surface.
// Difference = Euclidean distance in OKLab x 100. Simulation = Machado, Oliveira & Fernandes (2009), severity 1.0, linear RGB;
// the thresholds are calibrated to that model, so do not swap the model without re-deriving them.
export const CHART_SLOTS = 6;
export const THRESHOLDS = { normal: 15, cvd: 8, contrast: 3 };
const MACHADO = {
  protan: [[0.152286, 1.052583, -0.204868], [0.114503, 0.786281, 0.099216], [-0.003882, -0.048116, 1.051998]],
  deutan: [[0.367322, 0.860646, -0.227968], [0.280085, 0.672501, 0.047413], [-0.011820, 0.042940, 0.968881]],
  tritan: [[1.255528, -0.076749, -0.178779], [-0.078411, 0.930809, 0.147602], [0.004733, 0.691367, 0.303900]],
};
function lin(hex) {
  if (!/^#[0-9a-f]{6}$/i.test(hex)) throw new Error(`chart colour must be #rrggbb, got ${hex}`);
  return [1, 3, 5].map((i) => { const c = parseInt(hex.slice(i, i + 2), 16) / 255; return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; });
}
function oklab([r, g, b]) {
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b), m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b), s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  return [0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s, 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s, 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s];
}
const simulate = (rgb, kind) => MACHADO[kind].map((row) => Math.max(0, Math.min(1, row[0] * rgb[0] + row[1] * rgb[1] + row[2] * rgb[2])));
/** Perceptual difference of two #rrggbb colours; `vision` = "normal" | "protan" | "deutan" | "tritan". */
export function difference(a, b, vision = "normal") {
  const [x, y] = [a, b].map((h) => oklab(vision === "normal" ? lin(h) : simulate(lin(h), vision)));
  return 100 * Math.hypot(x[0] - y[0], x[1] - y[1], x[2] - y[2]);
}
const lum = (hex) => { const [r, g, b] = lin(hex); return 0.2126 * r + 0.7152 * g + 0.0722 * b; };
export function contrast(a, b) { const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x); return (hi + 0.05) / (lo + 0.05); }

/** colors: the slots in order; surface: the chart surface. Tritanopia is reported, not gated (rare; the legend and table view cover it). */
export function validateChartPalette(colors, surface) {
  const failures = [], worst = { normal: Infinity, protan: Infinity, deutan: Infinity, tritan: Infinity, contrast: Infinity };
  const r1 = (n) => Math.round(n * 10) / 10;
  colors.forEach((c, i) => {
    const ratio = contrast(c, surface);
    worst.contrast = Math.min(worst.contrast, ratio);
    if (ratio < THRESHOLDS.contrast) failures.push(`chart-${i + 1} ${c} is ${r1(ratio)}:1 on ${surface} (needs ${THRESHOLDS.contrast}:1)`);
    for (let j = i + 1; j < colors.length; j++) {
      for (const vision of ["normal", "protan", "deutan", "tritan"]) {
        const d = difference(c, colors[j], vision), min = vision === "normal" ? THRESHOLDS.normal : THRESHOLDS.cvd;
        worst[vision] = Math.min(worst[vision], d);
        if (vision !== "tritan" && d < min) failures.push(`chart-${i + 1} ${c} vs chart-${j + 1} ${colors[j]}: ${vision} difference ${r1(d)} < ${min}`);
      }
    }
  });
  for (const k of Object.keys(worst)) worst[k] = Math.round(worst[k] * 100) / 100;
  return { pass: failures.length === 0, failures, worst };
}
