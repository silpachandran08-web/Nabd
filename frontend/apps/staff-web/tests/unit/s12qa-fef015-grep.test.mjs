// S12 QA-6 (independent) FEF-015 AC5: arrivals/page.tsx and nursing/page.tsx have 0 `Promise.resolve().then`
// workarounds, 0 useEffect fetches and 0 hand-written API types; the never-invalidating patientCache is gone.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const FE = fileURLToPath(new URL("../../../../", import.meta.url));
const APP = join(FE, "apps/staff-web");
const PAGES = ["app/arrivals/page.tsx", "app/nursing/page.tsx"];
const src = Object.fromEntries(PAGES.map((p) => [p, readFileSync(join(APP, p), "utf8")]));

/** Body of every `useEffect(` call (brace-matched from the arrow function's `{`). */
function effectBodies(s) {
  const out = [];
  for (const m of s.matchAll(/\buseEffect\(\s*(async\s*)?\([^)]*\)\s*=>\s*\{/g)) {
    let depth = 0, i = m.index + m[0].length - 1;
    for (; i < s.length; i++) { if (s[i] === "{") depth++; else if (s[i] === "}" && --depth === 0) break; }
    out.push(s.slice(m.index, i + 1));
  }
  return out;
}

// Field sets of every generated response schema, to recognise a hand-written copy of one.
const genTypes = readFileSync(join(FE, "packages/api-client/src/generated/types.ts"), "utf8");
const RESPONSES = [...genTypes.matchAll(/^export type (\w+(?:Response|Page|Entry)) = \{ (.*) \};$/gm)]
  .map(([, name, body]) => [name, new Set([...body.matchAll(/(\w+)\??: /g)].map((x) => x[1]))]);

test("FEF-015 AC5: 0 `Promise.resolve().then` (or queueMicrotask/setTimeout-0 fetch) workarounds in both pages", () => {
  for (const [p, s] of Object.entries(src)) {
    assert.doesNotMatch(s, /Promise\.resolve\(\)\s*\.then/, p);
    assert.doesNotMatch(s, /queueMicrotask\(/, p);
    assert.doesNotMatch(s, /setTimeout\([^,]*(fetch|api\.|load)\w*[^,]*,\s*0\s*\)/, p);
  }
});

test("FEF-015 AC5: 0 useEffect fetches (no fetch/api/apiFetch/authedFetch/refetch/load call in any effect body)", () => {
  for (const [p, s] of Object.entries(src)) {
    const bodies = effectBodies(s);
    assert.ok(bodies.length >= 1, `${p}: effects parsed`);
    const fetching = bodies.filter((b) => /\b(fetch|apiFetch|authedFetch)\s*\(|\bapi\.(get|post|patch|put|delete)\b|\.refetch\(|\brefetchQueries\(|\bload\w*\(\)/.test(b));
    assert.deepEqual(fetching, [], `${p}: effect(s) that fetch:\n${fetching.join("\n---\n")}`);
  }
});

test("FEF-015 AC5: no direct fetch() in either page (all reads go through the query layer / api client)", () => {
  for (const [p, s] of Object.entries(src)) {
    const hits = s.split("\n").map((l, i) => [i + 1, l]).filter(([, l]) => /(^|[^.\w])fetch\(/.test(l));
    assert.deepEqual(hits, [], p);
  }
});

// Read by hand: AppointmentOption is built in openCheckin from AppointmentResponse + the cached patient name + the
// roster doctor name, a view model, not a wire shape.
const VIEW_MODELS = new Set(["AppointmentOption"]);

test("FEF-015 AC5: 0 hand-written API types (object-literal types that copy a generated response shape)", () => {
  const found = [];
  for (const [p, s] of Object.entries(src)) {
    for (const m of s.matchAll(/^(?:export\s+)?type (\w+) = \{([^}]*)\};/gm)) {
      const fields = [...m[2].matchAll(/(\w+)\??\s*:/g)].map((x) => x[1]);
      if (fields.length < 3 || VIEW_MODELS.has(m[1])) continue;
      for (const [name, set] of RESPONSES) {
        const shared = fields.filter((f) => set.has(f)).length;
        if (shared >= 3 && shared / fields.length >= 0.6) found.push(`${p}: type ${m[1]} {${fields.join(",")}} copies ${name}`);
      }
    }
    // An inline response shape at a call site is a hand-written API type too.
    for (const m of s.matchAll(/(?:getter|api\.get)<\s*\{[^>]*\}/g)) found.push(`${p}: inline response type ${m[0]}`);
  }
  assert.deepEqual(found, []);
});

test("FEF-015: the never-invalidating arrivals patientCache is deleted everywhere", () => {
  const hits = [];
  const walk = (d) => { for (const e of readdirSync(d, { withFileTypes: true })) { if (["node_modules", ".next", "tests"].includes(e.name)) continue; const p = join(d, e.name); if (e.isDirectory()) walk(p); else if (/\.(ts|tsx)$/.test(e.name) && /patientCache/.test(readFileSync(p, "utf8"))) hits.push(p); } };
  walk(join(APP, "app"));
  assert.deepEqual(hits, []);
});

test("FEF-015: both pages read server state through TanStack Query (useQuery/useQueries) with the tenant key factory", () => {
  for (const [p, s] of Object.entries(src)) {
    assert.match(s, /from "@tanstack\/react-query"/, p);
    assert.match(s, /\buseQuery\(/, p);
    assert.match(s, /\bkey\("/, p);
  }
  const keys = readFileSync(join(FE, "packages/api-client/queries/keys.ts"), "utf8");
  assert.match(keys, /\["t", tenantId, resource, params\]/);
});
