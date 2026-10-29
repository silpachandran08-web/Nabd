// FEF-055: nurse shell: triage-first home, nav order, permission + entitlement gating, urgent triage badge, skeleton route.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, existsSync } from "node:fs";
import { navFor, homeFor, jumpTargets, findItem, isActive } from "../../app/shell/navRegistry.ts";
import { landingPathFor } from "../../app/lib/session.ts";

const all = (codes) => ({ modules: codes.map((code) => ({ code, enabled: true })), killedFeatures: [], seatLimit: null });
const NURSE = ["nursing:view", "queue:view", "clinical:view", "packages:view"];
const MODS = ["lab", "packages"];
const ids = (n) => n.items.map((i) => i.id);
const src = (p) => readFileSync(new URL(`../../app/${p}`, import.meta.url), "utf8");

test("a nurse lands on triage and the nav is triage, orders, procedures, vaccines, inbox, then the existing tools", () => {
  const n = navFor({ perms: NURSE, owner: false, ent: all(MODS) });
  assert.equal(n.role, "nurse");
  assert.deepEqual(ids(n), ["triage", "orders", "procedures", "vaccines", "nurse-inbox", "priority", "nurse-packages", "activity"]);
  assert.equal(homeFor({ perms: NURSE, owner: false, ent: null }), "/nursing");
  assert.equal(homeFor({ perms: NURSE, owner: false, ent: null }), landingPathFor(NURSE, false), "login landing agrees with the role home");
});

test("module not entitled: the inbox is absent; entitled by lab or emr_erx: present; unknown entitlements hide planned items", () => {
  assert.ok(!ids(navFor({ perms: NURSE, owner: false, ent: all(["billing"]) })).includes("nurse-inbox"));
  assert.ok(!ids(navFor({ perms: NURSE, owner: false, ent: null })).includes("nurse-inbox"));
  assert.ok(ids(navFor({ perms: NURSE, owner: false, ent: all(["emr_erx"]) })).includes("nurse-inbox"));
  const off = { modules: [{ code: "lab", enabled: false }], killedFeatures: [], seatLimit: null };
  assert.ok(!ids(navFor({ perms: NURSE, owner: false, ent: off })).includes("nurse-inbox"));
});

test("permission missing: the item is absent (inbox needs clinical:view, packages needs packages:view), also from Ctrl+K", () => {
  const n = navFor({ perms: ["nursing:view"], owner: false, ent: all(MODS) });
  assert.ok(!ids(n).includes("nurse-inbox") && !ids(n).includes("nurse-packages"));
  assert.deepEqual(ids(n).slice(0, 4), ["triage", "orders", "procedures", "vaccines"]);
  assert.deepEqual(jumpTargets({ perms: ["nursing:view"], owner: false, ent: all(MODS) }, "inbox"), []);
  assert.deepEqual(jumpTargets({ perms: NURSE, owner: false, ent: all(MODS) }, "inbox").map((i) => i.href), ["/inbox"]);
});

test("every nurse item names its owner squad and the nursing items require nursing:view", () => {
  const n = navFor({ perms: NURSE, owner: false, ent: all(MODS) });
  for (const i of n.items) assert.ok(i.owner);
  for (const id of ["triage", "orders", "procedures", "vaccines", "priority", "activity"]) assert.deepEqual(findItem(id).perms, ["nursing:view"], id);
});

test("triage carries the urgent badge (unacknowledged priority flags); exactly one nurse item is urgent", () => {
  const n = navFor({ perms: NURSE, owner: false, ent: null });
  const triage = n.items.find((i) => i.id === "triage");
  assert.equal(triage.badge, "triage-urgent"); assert.equal(triage.urgent, true);
  assert.deepEqual(n.items.filter((i) => i.urgent).map((i) => i.id), ["triage"]);
  const hook = src("shell/useNavBadges.ts");
  assert.match(hook, /"triage-urgent": count\(priority, \(r\) => !r\.priorityAcknowledgedAt\)/);
  const badge = src("shell/UrgentBadge.tsx");
  assert.match(badge, /<Icon name="alert"/); assert.match(badge, /urgent/, "icon AND text, not colour alone");
  const nav = src("ClinicNav.tsx");
  assert.equal((nav.match(/<UrgentBadge/g) ?? []).length, 2, "sidebar/rail and bottom nav both render it");
});

test("active item follows the tab", () => {
  const triage = findItem("triage"), vac = findItem("vaccines"), orders = findItem("orders");
  assert.ok(isActive(triage, "/nursing", null) && !isActive(triage, "/nursing", "vaccines"));
  assert.ok(isActive(vac, "/nursing", "vaccines"));
  assert.ok(isActive(orders, "/nursing", "administration") && !isActive(orders, "/nursing", null));
});

test("route skeleton for the inbox exists, is gated by the same rule as the nav, and has a page title", () => {
  assert.ok(existsSync(new URL("../../app/(clinic)/inbox/page.tsx", import.meta.url)));
  assert.match(src("(clinic)/inbox/page.tsx"), /PlannedScreen id="nurse-inbox"/);
  assert.match(src("lib/routeTitle.ts"), /\["\/inbox", "Clinical inbox"\]/);
});

test("the shell fetches only badge counts (no feature data) for the nurse", () => {
  const hook = src("shell/useNavBadges.ts");
  assert.match(hook, /enabled: role === "nurse"/);
});
