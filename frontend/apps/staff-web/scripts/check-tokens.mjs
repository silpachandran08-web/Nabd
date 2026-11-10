// FEF-022 gate: (1) every var(--x) in app/ is defined (generated tokens.css or app code), (2) no literal z-index > 10,
// (3) media queries in files migrated to the four custom media names use only those names, (4) no literal font-size in files listed in TOKEN_CLEAN.
// Exported for tests; run as a script it exits 1 on any finding.
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const appDir = join(root, "app");
const tokensCss = readFileSync(join(root, "../../packages/tokens/dist/tokens.css"), "utf8");
export const CUSTOM_MEDIA = ["--bp-phone", "--bp-tablet-portrait", "--bp-tablet", "--bp-desktop"];
// Files that finished the FEF-022/024 migration: custom media names only, no literal font-size.
export const MIGRATED = ["app/clinicNav.module.css"];
// FEF-025: a colour literal does not follow the light theme. Allowed: custom-property definitions (chart palettes) and
// surfaces that are fixed on purpose (waiting-room TV, the paper token slip, white text on the danger fill).
export const FIXED_COLOUR_FILES = ["app/display/display.module.css", "app/components/arrivals/checkin.module.css", "app/(platform)/platform/billing/billing.module.css"];
const COLOUR = /#[0-9a-fA-F]{3,8}\b|\brgba?\(/;
const RUNTIME_VARS = /^--(font-(poppins|deva|knda|telu|ar))$/; // set by next/font in app/fonts.ts

function walk(d, out = []) {
  for (const n of readdirSync(d)) {
    const p = join(d, n);
    if (statSync(p).isDirectory()) walk(p, out); else if (/\.(css|tsx?)$/.test(n)) out.push(p);
  }
  return out;
}

export function scan(files = walk(appDir).map((p) => ({ path: relative(root, p), text: readFileSync(p, "utf8") }))) {
  const defined = new Set([...tokensCss.matchAll(/(--[\w-]+)\s*:/g)].map((m) => m[1]));
  for (const f of files) for (const m of f.text.matchAll(/(--[\w-]+)\s*:/g)) defined.add(m[1]);
  const findings = [];
  for (const f of files) {
    const css = f.path.endsWith(".css");
    for (const m of f.text.matchAll(/var\(\s*(--[\w-]+)/g)) {
      const name = m[1];
      if (!defined.has(name) && !RUNTIME_VARS.test(name) && !name.endsWith("-")) findings.push(`${f.path}: undefined ${name}`);
    }
    for (const m of f.text.matchAll(/z-?[iI]ndex\s*:\s*["']?(\d+)/g)) if (Number(m[1]) > 10) findings.push(`${f.path}: literal z-index ${m[1]}`);
    if (css && !FIXED_COLOUR_FILES.includes(f.path)) {
      const body = f.text.replace(/\/\*[\s\S]*?\*\//g, "").replace(/@media print\s*\{(?:[^{}]*\{[^{}]*\})*[^{}]*\}/g, "");
      for (const m of body.matchAll(/(^|[;{\s])([\w-]+)\s*:\s*([^;{}]+)/g)) if (!m[2].startsWith("--") && COLOUR.test(m[3])) findings.push(`${f.path}: literal colour in ${m[2]}: ${m[3].trim()}`);
    }
    if (MIGRATED.includes(f.path) && css) {
      for (const m of f.text.matchAll(/@media\s*([^{]+)\{/g)) {
        const q = m[1].trim();
        const ok = /^\(--[\w-]+\)$/.test(q) ? CUSTOM_MEDIA.includes(q.slice(1, -1)) : /^\((prefers-|pointer|hover|any-)[^)]*\)$/.test(q) || q === "print";
        if (!ok) findings.push(`${f.path}: media query not a custom media name: ${q}`);
      }
      for (const m of f.text.matchAll(/font-size\s*:\s*(\d[^;}]*)/g)) findings.push(`${f.path}: literal font-size ${m[1].trim()}`);
    }
  }
  return findings;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const f = scan();
  if (f.length) { console.error(f.join("\n")); process.exit(1); }
  console.log("check-tokens: ok");
}
