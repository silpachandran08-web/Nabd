// QA-5 (S12) FEF-012 AC5/AC4: CODEOWNERS resolution (GitHub semantics: last matching pattern wins) and no SaaS turbo cache.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const repo = new URL("../../../../../", import.meta.url).pathname;
const rules = readFileSync(repo + ".github/CODEOWNERS", "utf8").split("\n")
  .map((l) => l.replace(/#.*/, "").trim()).filter(Boolean).map((l) => l.split(/\s+/));

// shortcut: covers the anchored-directory and "*" patterns this file uses, not full gitignore globbing.
const ownersOf = (path) => {
  let owners = [];
  for (const [pat, ...o] of rules) {
    const hit = pat === "*" || (pat.endsWith("/") ? ("/" + path).startsWith(pat) : "/" + path === pat || ("/" + path).startsWith(pat + "/"));
    if (hit) owners = o;
  }
  return owners;
};

test("AC5: a change under packages/ui or packages/tokens resolves to the Frontend squad", () => {
  for (const p of ["frontend/packages/ui/src/index.ts", "frontend/packages/ui/package.json", "frontend/packages/tokens/src/index.ts", "frontend/packages/tokens/package.json"]) {
    assert.ok(ownersOf(p).includes("@nabd/frontend"), `${p} -> ${ownersOf(p)}`);
  }
});

test("AC5 neg: other packages are not owned by the Frontend squad rule (rule is scoped)", () => {
  assert.ok(!ownersOf("frontend/packages/api-client/index.ts").includes("@nabd/frontend"));
  assert.ok(!ownersOf("frontend/packages/uikit/x.ts").includes("@nabd/frontend"), "prefix 'ui' must not match 'uikit'");
});

test("AC5: code-owner review is required by branch protection", () => {
  const bp = JSON.parse(readFileSync(repo + ".github/branch-protection.json", "utf8"));
  assert.equal(bp.required_pull_request_reviews.require_code_owner_reviews, true);
  assert.ok(bp.required_pull_request_reviews.required_approving_review_count >= 1);
});

test("AC4: turbo has no remote (SaaS) cache configured; cache dir is local", () => {
  const turbo = readFileSync(repo + "frontend/turbo.json", "utf8");
  assert.doesNotMatch(turbo, /remoteCache|"signature"|apiUrl/);
  assert.match(turbo, /"cacheDir":\s*"\.turbo\/cache"/);
  for (const f of ["ci.yml"]) assert.doesNotMatch(readFileSync(repo + ".github/workflows/" + f, "utf8"), /TURBO_TOKEN|TURBO_TEAM|TURBO_API/);
});
