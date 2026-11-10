// FEF-058: the owner shell. Sixteen areas in five sections, prototype order inside each section, permission + entitlement gating (absent, not disabled),
// the Plan screen's upgrade options, landing = first visible item, a route skeleton for every item, catalogue labels, badges.
import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { navFor, homeFor, ownerUpgradeOptions, NAV, OWNER_SECTIONS, OWNER_PROTOTYPE_ORDER, SECTION_LABEL, itemVisible } from "../../app/shell/navRegistry.ts";
import { landingPathFor } from "../../app/lib/session.ts";

const app = new URL("../../app/", import.meta.url).pathname;
const msgs = JSON.parse(readFileSync(new URL("../../../../packages/i18n/messages/en/shell.json", import.meta.url), "utf8"));
const MODULES = ["queue_eta", "whatsapp_booking", "emr_erx", "billing", "pharmacy_external", "pharmacy_inhouse", "lab", "packages", "branded_site", "hr", "insurance", "ipd", "patient_app", "ai_safety"];
const entWith = (codes) => ({ modules: MODULES.map((code) => ({ code, enabled: codes.includes(code) })), killedFeatures: [], seatLimit: null });
const ALL = entWith(MODULES);
const PERMS = ["reports:view", "queue:view", "billing:view", "packages:view", "staff:view", "setup:view", "compliance:view"];
const ids = (n) => n.items.map((i) => i.id);

test("owner with every module and grant: sixteen items, five sections in order, prototype order inside each section", () => {
  const n = navFor({ perms: PERMS, owner: true, ent: ALL });
  assert.equal(n.role, "owner");
  assert.equal(n.items.length, 16);
  assert.deepEqual([...ids(n)].sort(), [...OWNER_PROTOTYPE_ORDER].sort());
  const secs = [...new Set(n.items.map((i) => i.section))];
  assert.deepEqual(secs, [...OWNER_SECTIONS]);
  assert.deepEqual(Object.keys(SECTION_LABEL), [...OWNER_SECTIONS]);
  for (const sec of OWNER_SECTIONS) {
    const inSec = n.items.filter((i) => i.section === sec).map((i) => OWNER_PROTOTYPE_ORDER.indexOf(i.id));
    assert.deepEqual(inSec, [...inSec].sort((a, b) => a - b), `${sec} follows the prototype order`);
    assert.ok(inSec.length >= 2, sec);
  }
  assert.equal(n.items[0].id, "overview");
});

test("signing in lands on the role home: the first owner item, and the login landing path agrees", () => {
  assert.equal(homeFor({ perms: PERMS, owner: true, ent: ALL }), "/overview");
  for (const perms of [PERMS, ["queue:view", "billing:view"], ["staff:view", "billing:view"], ["billing:view"], ["packages:view", "staff:view"], ["setup:view", "reports:view"], ["compliance:view"], ["queue:view", "setup:view"]]) {
    for (const ent of [null, ALL, entWith(["billing", "packages"])]) assert.equal(homeFor({ perms, owner: true, ent }), landingPathFor(perms, true), JSON.stringify([perms, ent && ent.modules.filter((m) => m.enabled).length]));
  }
});

test("a module the plan lacks, or a grant the user lacks, removes the item (absent, not disabled)", () => {
  const noHr = navFor({ perms: PERMS, owner: true, ent: entWith(MODULES.filter((m) => m !== "hr")) });
  assert.ok(!ids(noHr).includes("hr") && noHr.items.length === 15);
  const noBillingMod = navFor({ perms: PERMS, owner: true, ent: entWith(MODULES.filter((m) => m !== "billing")) });
  assert.ok(!ids(noBillingMod).includes("payouts") && !ids(noBillingMod).includes("finance"));
  const noGrant = navFor({ perms: PERMS.filter((p) => p !== "billing:view"), owner: true, ent: ALL });
  assert.ok(!ids(noGrant).includes("finance") && !ids(noGrant).includes("payouts"));
  const noStaff = navFor({ perms: PERMS.filter((p) => p !== "staff:view"), owner: true, ent: ALL });
  assert.ok(!ids(noStaff).includes("staff") && !ids(noStaff).includes("hr"));
  // entitlements unknown (not loaded): planned screens stay invisible, the built ones show
  const unknown = navFor({ perms: PERMS, owner: true, ent: null });
  assert.deepEqual(ids(unknown).filter((i) => ["workflows", "integrations", "hr", "payouts", "growth", "bookingpage"].includes(i)), []);
  assert.ok(ids(unknown).includes("overview") && ids(unknown).includes("finance"));
  // every gated item's own visibility rule is the one the direct-URL screen uses (PlannedScreen calls itemVisible)
  const hr = NAV.owner.find((i) => i.id === "hr");
  assert.equal(itemVisible(hr, PERMS, entWith(MODULES.filter((m) => m !== "hr"))), false);
  assert.equal(itemVisible(hr, PERMS, ALL), true);
});

test("owner.plan lists the missing module as an upgrade option instead", () => {
  const noHr = ownerUpgradeOptions(PERMS, entWith(MODULES.filter((m) => m !== "hr")));
  assert.deepEqual(noHr.map((o) => o.item.id), ["hr"]);
  assert.deepEqual(noHr[0].modules, ["hr"]);
  assert.deepEqual(ownerUpgradeOptions(PERMS, ALL), []);
  assert.deepEqual(ownerUpgradeOptions(PERMS, null), []);
  // an area the user may not open is not offered as an upgrade
  assert.ok(!ownerUpgradeOptions(PERMS.filter((p) => p !== "staff:view"), entWith(MODULES.filter((m) => m !== "hr"))).some((o) => o.item.id === "hr"));
  const bare = ownerUpgradeOptions(PERMS, entWith([])).map((o) => o.item.id);
  for (const id of ["workflows", "integrations", "hr", "payouts", "growth", "bookingpage", "finance", "prices"]) assert.ok(bare.includes(id), id);
});

test("every owner area has a route (page.tsx), a section, an owning squad and catalogue labels", () => {
  for (const i of NAV.owner) {
    const base = i.href.split("?")[0];
    assert.ok(existsSync(`${app}(clinic)${base}/page.tsx`), `${i.id}: no page for ${base}`);
    assert.ok(i.section && i.owner, i.id);
    assert.ok(msgs.ownerNav[i.id]?.label && msgs.ownerNav[i.id]?.short, `${i.id}: shell catalogue label`);
  }
  for (const sec of OWNER_SECTIONS) assert.ok(msgs.ownerSection[sec], sec);
  assert.deepEqual(Object.keys(msgs.ownerNav).sort(), NAV.owner.map((i) => i.id).sort());
});

test("badges: Clinic Operations carries the waiting count and Billing the checkout count (one shared resolver)", () => {
  const byId = Object.fromEntries(NAV.owner.map((i) => [i.id, i.badge]));
  assert.equal(byId.ops, "waiting");
  assert.equal(byId.finance, "checkout");
  const src = readFileSync(`${app}shell/useNavBadges.ts`, "utf8");
  assert.match(src, /role === "reception" \|\| role === "owner"/);
});

test("the sidebar draws section headings from the catalogue and the facility switcher offers All facilities", () => {
  const nav = readFileSync(`${app}ClinicNav.tsx`, "utf8");
  assert.match(nav, /ownerSection\.\$\{e\.section\}/);
  const fs = readFileSync(`${app}shell/FacilitySwitcher.tsx`, "utf8");
  assert.match(fs, /tf\("allFacilities"\)/); // FEF-040: text in the shell catalogue
  assert.equal(msgs.facility.allFacilities, "All facilities");
  assert.match(fs, /all\.length > 1/); // only a chain (2+ facilities) is offered the aggregate
  assert.match(readFileSync(`${app}(clinic)/overview/page.tsx`, "utf8"), /useFacilityScope\(\) === "all" \? <AllFacilities \/>/);
});
