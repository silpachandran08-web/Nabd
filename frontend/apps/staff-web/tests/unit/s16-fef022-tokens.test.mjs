// FEF-022: generated outputs, contrast gate, undefined-variable / z-index / media allowlist gate.
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync, writeFileSync, mkdtempSync, cpSync, rmSync } from "node:fs";
import { execFileSync, spawnSync } from "node:child_process";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const tokens = fileURLToPath(new URL("../../../../packages/tokens/", import.meta.url));
const dist = (f) => readFileSync(join(tokens, "dist", f), "utf8");
const { scan } = await import("../../scripts/check-tokens.mjs");

test("AC1: build emits tokens.css, custom-media.css, theme.ts and contrast-report.json from one source (dist is current)", () => {
  const r = spawnSync("node", ["build.mjs", "--check"], { cwd: tokens, encoding: "utf8" });
  assert.equal(r.status, 0, r.stderr);
  for (const f of ["tokens.css", "custom-media.css", "theme.ts", "contrast-report.json"]) assert.ok(dist(f).length > 50, f);
});

test("AC1: snapshot of the generated shape", () => {
  const css = dist("tokens.css");
  assert.match(css, /:root \{[\s\S]*--nb-bg-root: #06090c;/);
  assert.match(css, /:root\[data-theme="light"\] \{[\s\S]*--nb-bg-root: #eef2f4;/); // FEF-025: prototype light values
  for (const v of ["--nb-fs-xs: 12px", "--nb-fs-sm: 13px", "--nb-fs-md: 14px", "--nb-fs-base: 16px", "--nb-fs-lg: 18px", "--nb-fs-xl: 22px", "--nb-fs-2xl: 28px"]) assert.ok(css.includes(v), v);
  const z = { base: 0, sticky: 10, nav: 20, dropdown: 30, overlay: 40, modal: 50, toast: 60, lock: 100 };
  for (const [k, n] of Object.entries(z)) assert.ok(css.includes(`--z-${k}: ${n};`), k);
  for (const v of ["--nb-space-10", "--nb-topbar-h", "--nb-radius-sm", "--acc:", "--tx4:"]) assert.ok(css.includes(v), v);
  assert.equal(dist("custom-media.css").trim().split("\n").filter((l) => l.startsWith("@custom-media")).length, 4);
  assert.match(dist("custom-media.css"), /--bp-phone \(max-width: 430px\)[\s\S]*--bp-tablet-portrait \(max-width: 768px\)[\s\S]*--bp-tablet \(max-width: 1024px\)[\s\S]*--bp-desktop \(min-width: 1440px\)/);
  assert.match(dist("theme.ts"), /export const theme/);
});

test("AC5: every body-text pair >= 4.5 and large/UI pair >= 3 in both themes", () => {
  const rep = JSON.parse(dist("contrast-report.json"));
  assert.equal(rep.pass, true);
  assert.ok(rep.pairs.some((p) => p.theme === "light") && rep.pairs.some((p) => p.theme === "dark"));
  for (const p of rep.pairs) assert.ok(p.ratio >= (p.kind === "text" ? 4.5 : 3), `${p.theme} ${p.fg}/${p.bg} ${p.ratio}`);
});

test("CI: the contrast gate fails the build below threshold", () => {
  const tmp = mkdtempSync(join(tmpdir(), "tok-"));
  try {
    cpSync(tokens, tmp, { recursive: true, filter: (s) => !s.includes("node_modules") });
    const f = join(tmp, "src/source/color.dark.json");
    const j = JSON.parse(readFileSync(f, "utf8"));
    j["text-tertiary"] = "#3a4248"; // ~1.7:1 on the dark surfaces
    writeFileSync(f, JSON.stringify(j));
    const r = spawnSync("node", ["build.mjs"], { cwd: tmp, encoding: "utf8" });
    assert.equal(r.status, 1);
    assert.match(r.stderr, /contrast FAIL dark text-tertiary/);
  } finally { rmSync(tmp, { recursive: true, force: true }); }
});

test("AC2: no undefined custom property is referenced anywhere in staff-web app/", () => {
  assert.deepEqual(scan().filter((x) => x.includes("undefined")), []);
});

test("AC2: the scanner does flag an undefined variable", () => {
  assert.deepEqual(scan([{ path: "app/x.module.css", text: ".a{color:var(--nb-nope, red)}" }]), ["app/x.module.css: undefined --nb-nope"]);
});

test("AC3: migrated files use only the four custom media names; others are flagged", () => {
  assert.deepEqual(scan().filter((x) => x.includes("media")), []);
  const bad = scan([{ path: "app/clinicNav.module.css", text: "@media (max-width: 700px){.a{color:red}} @media (--bp-phone){.b{color:red}} @media (--bp-bogus){.c{color:red}}" }]);
  assert.equal(bad.length, 2);
  assert.ok(bad.every((x) => x.includes("media query")));
});

test("AC4: no literal z-index above 10 and no literal font-size in the migrated shell files", () => {
  assert.deepEqual(scan().filter((x) => /z-index|font-size/.test(x)), []);
  assert.equal(scan([{ path: "app/a.css", text: ".a{z-index:11}" }]).length, 1);
  assert.equal(scan([{ path: "app/a.css", text: ".a{z-index:10}" }]).length, 0);
  assert.equal(scan([{ path: "app/clinicNav.module.css", text: ".a{font-size:13px}" }]).length, 1);
  const lock = readFileSync(new URL("../../app/IdleLockGuard.tsx", import.meta.url), "utf8");
  assert.ok(lock.includes("var(--z-lock)") && !lock.includes("9999"));
});
