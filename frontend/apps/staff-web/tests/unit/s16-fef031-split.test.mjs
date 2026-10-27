// FEF-031 AC1 (measured): each page file < 300 LOC and no component has more than 8 useState hooks; error boundary present;
// the vitals rules that reject implausible values live in packages/domain.
import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const APP = fileURLToPath(new URL("../../app/", import.meta.url));
const walk = (d) => readdirSync(d).flatMap((n) => (statSync(join(d, n)).isDirectory() ? walk(join(d, n)) : [join(d, n)]));
const dirs = ["(clinic)/nursing", "components/nursing", "(clinic)/patients"];
const files = dirs.flatMap((d) => walk(join(APP, d))).filter((f) => /\.tsx?$/.test(f));

/** Split a file into top-level function bodies (a component or a hook) and count `useState` calls in each. */
function useStateCounts(src) {
  const out = [];
  const re = /^(?:export\s+)?(?:default\s+)?function\s+(\w+)/gm;
  const starts = [...src.matchAll(re)].map((m) => ({ name: m[1], at: m.index }));
  starts.forEach((s, i) => {
    const body = src.slice(s.at, starts[i + 1]?.at ?? src.length);
    out.push([s.name, (body.match(/\buseState\b\s*[<(]/g) ?? []).length]);
  });
  return out;
}

test("AC1: nursing and patients pages are < 300 LOC", () => {
  for (const p of ["(clinic)/nursing/page.tsx", "(clinic)/patients/page.tsx"]) {
    const n = readFileSync(join(APP, p), "utf8").split("\n").length;
    assert.ok(n < 300, `${p} has ${n} lines`);
  }
});

test("AC1: every split file < 300 LOC and no component/hook has more than 8 useState hooks", () => {
  for (const f of files) {
    const src = readFileSync(f, "utf8");
    if (f.endsWith(".css")) continue;
    assert.ok(src.split("\n").length < 300, `${f} is ${src.split("\n").length} lines`);
    for (const [name, n] of useStateCounts(src)) assert.ok(n <= 8, `${f}: ${name} has ${n} useState hooks`);
  }
});

test("the nursing route has an error boundary", () => {
  assert.ok(existsSync(join(APP, "(clinic)/nursing/error.tsx")));
});

test("AC2: implausible vitals are rejected by the domain rules the dialog uses", async () => {
  const { nurseVitalsErrors, EMPTY_VITALS, EMPTY_EXTRAS } = await import("@nabd/domain");
  const e = nurseVitalsErrors({ ...EMPTY_VITALS, pulseBpm: "900" }, "", EMPTY_EXTRAS);
  assert.ok(e.pulseBpm, "pulse 900 is rejected");
  assert.equal(nurseVitalsErrors({ ...EMPTY_VITALS, pulseBpm: "72", tempCelsius: "36.8" }, "120/80", EMPTY_EXTRAS).pulseBpm, undefined);
  assert.ok(nurseVitalsErrors({ ...EMPTY_VITALS, pulseBpm: "72" }, "20/5", EMPTY_EXTRAS).bp, "implausible BP text rejected");
});
