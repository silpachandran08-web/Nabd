// FEF-019: typed <Can>, operator capabilities from permissions (matrix parsed from PlatformPermissions.java), gate rendering.
import { test } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const APP = join(import.meta.dirname, "../..");
const JAVA = join(APP, "../../../backend/src/main/java/com/nabd/hms/platform/PlatformPermissions.java");
const { capabilitiesFrom } = await import("../../app/platform/capabilities.ts");

function operatorPermissions() {
  const src = readFileSync(JAVA, "utf8");
  const block = (name) => src.slice(src.indexOf(name), src.indexOf(");", src.indexOf(name)));
  const entries = (b) => [...b.matchAll(/Map\.entry\("([a-z_:]+)", Set\.of\(([^)]*)\)\)|"([a-z_:]+)", Set\.of\(([^)]*)\)/g)]
    .map((m) => [m[1] ?? m[3], [...(m[2] ?? m[4]).matchAll(/"(\w+)"/g)].map((x) => x[1])]);
  const surf = entries(block("SURFACE_ROLES")), act = entries(block("ACTION_ROLES"));
  const roles = [...new Set([...surf, ...act].flatMap(([, r]) => r))];
  return Object.fromEntries(roles.map((r) => [r, [...surf.filter(([, rs]) => rs.includes(r)).map(([k]) => `${k}:view`), ...act.filter(([, rs]) => rs.includes(r)).map(([k]) => k)]]));
}

test("a permission outside the generated union fails tsc in <Can>", { timeout: 120_000 }, () => {
  const dir = mkdtempSync(join(tmpdir(), "fef019-"));
  try {
    const can = join(APP, "app/components/ui/Can").replace(/\\/g, "/");
    writeFileSync(join(dir, "f.tsx"), `import Can from "${can}";\nexport const ok = <Can permission="queue:view">x</Can>;\nexport const bad = <Can permission="queue:viwe">x</Can>;\nexport const bad2 = <Can any={["billing:view", "nope:view"]}>x</Can>;\n`);
    writeFileSync(join(dir, "tsconfig.json"), JSON.stringify({ extends: join(APP, "tsconfig.json"), compilerOptions: { incremental: false, paths: { "@/*": [join(APP, "*")] } }, include: [join(dir, "f.tsx"), join(APP, "next-env.d.ts")] }));
    const r = spawnSync(join(APP, "node_modules/.bin/tsc"), ["-p", join(dir, "tsconfig.json"), "--noEmit"], { cwd: APP, encoding: "utf8" });
    const errs = r.stdout.split("\n").filter((l) => /f\.tsx.*error TS(2820|2322)/.test(l));
    assert.equal(errs.length, 2, r.stdout);
    assert.ok(errs.some((l) => /f\.tsx\(3,/.test(l)) && errs.some((l) => /f\.tsx\(4,/.test(l)), "both typos fail, the valid line does not");
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("operator capabilities follow permissions for all seven roles", () => {
  const perms = operatorPermissions();
  assert.deepEqual(Object.keys(perms).sort(), ["billing", "commercial", "compliance_dpo", "implementation", "sre", "super_admin", "support_engineer"]);
  const want = (r) => ({ canWrite: ["super_admin", "implementation"].includes(r), canBill: ["super_admin", "billing"].includes(r), isSuper: r === "super_admin", canSeeValues: r === "compliance_dpo" });
  for (const [role, p] of Object.entries(perms)) {
    const c = capabilitiesFrom(p);
    const { canWrite, canBill, isSuper, canSeeValues } = want(role);
    assert.deepEqual({ canWrite: c.canWrite, canBill: c.canBill, isSuper: c.isSuper, canSeeValues: c.canSeeValues }, { canWrite, canBill, isSuper, canSeeValues }, role);
    assert.equal(c.readOnly, !canWrite && !canBill && !isSuper, role);
  }
});

test("capabilities are all false before the profile has loaded", () => {
  assert.deepEqual(capabilitiesFrom(undefined), { canWrite: false, canBill: false, isSuper: false, readOnly: false, canSeeValues: false });
  assert.equal(capabilitiesFrom([]).readOnly, true);
});

test("operator code no longer reads role codes", () => {
  for (const f of ["app/platform/platformApi.ts", "app/platform/audit/page.tsx", "app/platform/capabilities.ts"])
    assert.doesNotMatch(readFileSync(join(APP, f), "utf8"), /role === "|\.role\?|=== "(super_admin|billing|compliance_dpo)/, f);
});
