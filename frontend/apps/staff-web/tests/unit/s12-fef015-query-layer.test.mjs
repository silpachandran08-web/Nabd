// FEF-015: query key factory + staleTime table, and the AC5 grep over the two migrated pages.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { arrivalsSource } from "./arrivalsSource.mjs";
import { qk, qkPrefix, STALE } from "../../../../packages/api-client/queries/keys.ts";

test("keys are ['t', tenantId, resource, params] and a prefix matches every params", () => {
  assert.deepEqual(qk("t1", "queue"), ["t", "t1", "queue", {}]);
  assert.deepEqual(qk("t1", "patient", { id: "p1" }), ["t", "t1", "patient", { id: "p1" }]);
  const pre = qkPrefix("t1", "queue");
  assert.deepEqual(qk("t1", "queue", { priority: true }).slice(0, pre.length), [...pre]);
  assert.notDeepEqual(qk("t2", "queue").slice(0, 3), [...pre], "another tenant never shares a key");
});

test("staleTime per data class", () => {
  assert.equal(STALE.roster, 600_000);
  assert.equal(STALE.departments, 600_000);
  assert.equal(STALE.queue, 0);
  assert.equal(STALE.patient, 0); // AC4: a corrected name shows on the next focus
});

// Body of every useEffect(...) call, by brace matching from its first "{".
function effectBodies(src) {
  const out = [];
  for (let i = src.indexOf("useEffect(("); i !== -1; i = src.indexOf("useEffect((", i + 1)) {
    let j = src.indexOf("{", i), depth = 0, k = j;
    for (; k < src.length; k++) { if (src[k] === "{") depth++; else if (src[k] === "}" && --depth === 0) break; }
    out.push(src.slice(j, k + 1));
  }
  return out;
}

for (const page of ["arrivals", "nursing"]) {
  const src = page === "arrivals" ? arrivalsSource() : readFileSync(new URL(`../../app/(clinic)/${page}/page.tsx`, import.meta.url), "utf8"); // FEF-028: Arrivals is a folder
  test(`${page}: 0 Promise.resolve().then, 0 useEffect fetches, 0 hand-written API types`, () => {
    assert.equal((src.match(/Promise\.resolve\(\)\.then/g) ?? []).length, 0);
    const fetching = effectBodies(src).filter((b) => /\b(fetch|authedFetch|apiFetch|api\.(get|post|patch|put|delete)|load\w*)\(/.test(b));
    assert.deepEqual(fetching, []);
    assert.doesNotMatch(src, /useLatestLoad|patientCache/);
    // Response shapes come from the generated client; local types only narrow or compose them, or are view models.
    assert.match(src, /from "@nabd\/api-client\/generated"/);
    assert.doesNotMatch(src, /^type (QueueEntry|PatientOption|GuardianOption|PatientDetail|CallbackEntry|TransferTarget|AdministrationOrder|ProcedureOrder|ActivityEntry|Problem|StaffOption) = \{/m);
  });
}
