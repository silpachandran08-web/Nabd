// FEF-057: billing, pharmacist and lab shells: prototype nav order, role home = login landing, permission + entitlement gating (absent,
// not disabled; direct URL = no-access), SSE badge, responsive variants, standalone shells, keyboard-wedge barcode handler.
import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { navFor, homeFor, rolesFor, counterRouteState, bottomNavSplit, findItem, isActive, jumpTargets, NAV, COUNTER_ROLES, COUNTER_PROTOTYPE_ORDER, LAB_GRANT } from "../../app/shell/navRegistry.ts";
import { landingPathFor } from "../../app/lib/session.ts";
import { createWedge, registerScanTarget, activeScanTarget, WEDGE_MAX_GAP_MS } from "../../app/shell/barcodeWedge.ts";

const MODULES = ["queue_eta", "whatsapp_booking", "emr_erx", "billing", "pharmacy_external", "pharmacy_inhouse", "lab", "packages", "branded_site", "hr", "insurance", "ipd", "patient_app", "ai_safety"];
const entWith = (codes) => ({ modules: MODULES.map((code) => ({ code, enabled: codes.includes(code) })), killedFeatures: [], seatLimit: null });
const ALL = entWith(MODULES);
const without = (...off) => entWith(MODULES.filter((m) => !off.includes(m)));
const BILLING = ["billing:view", "billing:create", "billing:edit", "billing:approve", "queue:view", "packages:view", "patients:view"];
const PHARMACIST = ["pharmacy:view", "pharmacy:create", "pharmacy:edit", "billing:view", "billing:create"];
const LAB = [LAB_GRANT];
const ids = (n) => n.items.map((i) => i.id);
const nav = (perms, ent) => navFor({ perms, owner: false, ent });
const src = (p) => readFileSync(new URL(`../../app/${p}`, import.meta.url), "utf8");
const prefixed = { billing: "billing-", pharmacist: "ph-", lab: "lab-" };

test("billing role, all modules entitled: nav in the prototype order, landing on the role home (checkout queue)", () => {
  const n = nav(BILLING, ALL);
  assert.equal(n.role, "billing");
  assert.deepEqual(ids(n), COUNTER_PROTOTYPE_ORDER.billing.map((x) => `billing-${x}`));
  assert.deepEqual(COUNTER_PROTOTYPE_ORDER.billing, ["checkout", "invoices", "preauth", "claims", "packages", "dayclose"]);
  assert.equal(homeFor({ perms: BILLING, owner: false, ent: ALL }), "/arrivals?tab=checkout_pending");
  assert.equal(landingPathFor(BILLING, false), "/arrivals?tab=checkout_pending", "login lands on the role home");
});

test("pharmacist and lab roles, all modules entitled: prototype order; pharmacist lands on dispensing", () => {
  const p = nav(PHARMACIST, ALL);
  assert.deepEqual(p.roles, ["pharmacist", "billing"], "a counter that also takes payments can switch to the billing context");
  assert.deepEqual(ids(p), COUNTER_PROTOTYPE_ORDER.pharmacist.map((x) => `ph-${x}`));
  assert.deepEqual(COUNTER_PROTOTYPE_ORDER.pharmacist, ["dispense", "pos", "stock", "purchase", "registers", "insights"]);
  assert.equal(landingPathFor(PHARMACIST, false), "/pharmacy");
  assert.equal(homeFor({ perms: PHARMACIST, owner: false, ent: null }), landingPathFor(PHARMACIST, false));
  const l = nav(LAB, ALL);
  assert.equal(l.role, "lab");
  assert.deepEqual(ids(l), COUNTER_PROTOTYPE_ORDER.lab.map((x) => `lab-${x}`));
  assert.deepEqual(COUNTER_PROTOTYPE_ORDER.lab, ["worklist", "collect", "results", "validate", "catalog"]);
  assert.equal(homeFor({ perms: LAB, owner: false, ent: ALL }), "/lab");
});

test("role selection: front desk, nurse, doctor and owner keep their shells; counters need their grant and no booking grant", () => {
  assert.deepEqual(rolesFor([...BILLING, "queue:create"], false), ["reception"], "a receptionist who also bills stays on the desk shell");
  assert.deepEqual(rolesFor([...PHARMACIST, "queue:create"], false), ["reception"]);
  assert.deepEqual(rolesFor(["nursing:view", "pharmacy:edit"], false), ["nurse"]);
  assert.deepEqual(rolesFor(["clinical:edit", "billing:create"], false), ["doctor"]);
  assert.deepEqual(rolesFor([...PHARMACIST, "reports:view"], true), ["owner"]);
  assert.deepEqual(rolesFor(["billing:view"], false), ["reception"], "view-only billing is not the cashier persona");
  assert.deepEqual(rolesFor(["pharmacy:view", "pharmacy:create"], false), ["reception"]);
  assert.deepEqual(rolesFor([LAB_GRANT, "pharmacy:edit", "billing:create"], false), ["pharmacist", "lab", "billing"]);
  for (const r of COUNTER_ROLES) for (const i of NAV[r]) assert.ok(i.owner && i.id.startsWith(prefixed[r]), i.id);
});

test("module not entitled: the item is absent, and a role left with nothing falls back to the generic staff list", () => {
  assert.deepEqual(ids(nav(BILLING, without("insurance"))), ["billing-checkout", "billing-invoices", "billing-packages", "billing-dayclose"]);
  assert.deepEqual(ids(nav(BILLING, without("billing"))), ["billing-preauth", "billing-claims", "billing-packages"]);
  const none = nav(BILLING, without("billing", "packages", "insurance"));
  assert.equal(none.role, "reception", "no billing screen left: the generic staff list");
  assert.ok(!ids(none).some((x) => x.startsWith("billing-")));
  assert.deepEqual(ids(nav(PHARMACIST, without("pharmacy_inhouse"))), ids(nav(PHARMACIST, ALL)), "either pharmacy module is enough");
  const noPh = nav(PHARMACIST.filter((p) => !p.startsWith("billing:")), without("pharmacy_inhouse", "pharmacy_external"));
  assert.ok(!ids(noPh).some((x) => x.startsWith("ph-")), "pharmacy screens and the counter sale are absent");
  assert.deepEqual(ids(noPh), ["account"], "nothing left: the generic staff list (My account)");
  const noLab = nav([LAB_GRANT, "patients:view"], without("lab"));
  assert.equal(noLab.role, "reception");
  assert.ok(!ids(noLab).some((x) => x.startsWith("lab-")));
  // Unknown entitlements (loading, failed): built screens stay, planned skeletons stay absent.
  assert.deepEqual(ids(nav(BILLING, null)), ["billing-checkout", "billing-packages", "billing-dayclose"]);
  assert.deepEqual(ids(nav(PHARMACIST, null)), ["ph-dispense", "ph-pos", "ph-stock"]);
  const off = { modules: [{ code: "lab", enabled: false }], killedFeatures: [], seatLimit: null };
  assert.equal(nav(LAB, off).role, "reception", "lab module switched off: no lab shell");
  assert.deepEqual(ids(nav(LAB, off)), ["account"]);
});

test("permission missing: the item is absent, also from Ctrl+K", () => {
  const noQueue = nav(BILLING.filter((p) => p !== "queue:view"), ALL);
  assert.ok(!ids(noQueue).includes("billing-checkout"), "the checkout queue reads /queue");
  // S18-BUG-9: without the checkout queue, the first built billing screen in the prototype order (invoices, pre-auth and claims are
  // skeletons, never a home), with or without entitlements loaded; login lands there too.
  const noQ = BILLING.filter((p) => p !== "queue:view");
  for (const ent of [null, ALL]) assert.equal(homeFor({ perms: noQ, owner: false, ent }), "/packages");
  assert.equal(landingPathFor(noQ, false), "/packages");
  const desk = noQ.filter((p) => p !== "packages:view");
  for (const ent of [null, ALL]) assert.equal(homeFor({ perms: desk, owner: false, ent }), "/billing");
  assert.equal(landingPathFor(desk, false), "/billing", "a billing-only desk lands on Day Close");
  assert.ok(!ids(nav(BILLING.filter((p) => p !== "packages:view"), ALL)).includes("billing-packages"));
  assert.ok(!ids(nav(PHARMACIST.filter((p) => p !== "billing:create"), ALL)).includes("ph-pos"), "no counter sale without billing:create");
  assert.deepEqual(jumpTargets({ perms: PHARMACIST.filter((p) => !p.startsWith("billing:")), owner: false, ent: ALL }, "otc"), []);
  assert.deepEqual(jumpTargets({ perms: PHARMACIST, owner: false, ent: ALL }, "otc").map((i) => i.href), ["/checkout/otc"]);
  assert.deepEqual(rolesFor(["pharmacy:view"], false), ["reception"], "no lab or pharmacy grant: no counter shell");
});

// gate(role, input, path, settled, tab) -> "open" | "wait" | "unverified" | "blocked:<item>"
const gate = (role, perms, ent, path, settled = true, tab = null) => {
  const g = counterRouteState(role, { perms, owner: false, ent }, path, settled, tab);
  return g.kind === "open" ? "open" : `${g.kind}:${g.item.id}`;
};

test("direct URL: a route of an item the session may not see gets the no-access state; built sub-routes stay reachable", () => {
  const off = without("billing");
  assert.equal(gate("billing", BILLING, off, "/billing"), "blocked:billing-dayclose");
  assert.equal(gate("billing", BILLING, off, "/checkout/abc"), "blocked:billing-checkout");
  assert.equal(gate("billing", BILLING, off, "/arrivals", true, "checkout_pending"), "blocked:billing-checkout");
  assert.equal(gate("billing", BILLING, off, "/billing/invoices"), "blocked:billing-invoices");
  assert.equal(gate("billing", BILLING, off, "/billing/invoices/inv-1"), "open", "the built invoice view is not the planned list");
  assert.equal(gate("billing", BILLING, ALL, "/billing"), "open");
  assert.equal(gate("pharmacist", PHARMACIST.filter((p) => p !== "billing:create"), ALL, "/checkout/otc"), "blocked:ph-pos");
  assert.equal(gate("pharmacist", PHARMACIST, without("pharmacy_inhouse", "pharmacy_external"), "/pharmacy"), "blocked:ph-dispense");
  assert.equal(gate("pharmacist", PHARMACIST, without("pharmacy_inhouse", "pharmacy_external"), "/pharmacy/stock-take"), "blocked:ph-stock");
  assert.equal(gate("pharmacist", PHARMACIST, ALL, "/pharmacy/receipt/x"), "open");
  assert.equal(gate("lab", LAB, without("lab"), "/lab/validate"), "blocked:lab-validate");
  assert.equal(gate("reception", [], off, "/billing"), "open", "other shells keep their page gates (unchanged)");
  assert.match(src("AppShell.tsx"), /<RouteGate>\{children\}<\/RouteGate>/);
});

test("S18-BUG-6: while the first entitlements answer is pending a module-gated route waits (page not mounted); a failure is fail-closed", () => {
  // Pending: the page must not mount (no feature fetch), whatever the grant.
  assert.equal(gate("billing", BILLING, null, "/billing", false), "wait:billing-dayclose");
  assert.equal(gate("billing", BILLING, null, "/checkout/q1", false), "wait:billing-checkout");
  assert.equal(gate("billing", BILLING, null, "/packages", false), "wait:billing-packages", "NAV_MODULES-gated built screen");
  assert.equal(gate("billing", BILLING, null, "/billing/preauth", false), "wait:billing-preauth");
  assert.equal(gate("pharmacist", PHARMACIST, null, "/pharmacy", false), "wait:ph-dispense");
  // A missing grant needs no entitlements: blocked at once. A route of no counter item is never held back.
  assert.equal(gate("billing", BILLING.filter((p) => p !== "billing:view"), null, "/billing", false), "blocked:billing-dayclose");
  assert.equal(gate("billing", BILLING, null, "/patients", false), "open");
  assert.equal(gate("billing", BILLING, null, "/billing/invoices/inv-1", false), "open");
  assert.equal(gate("reception", ["billing:view"], null, "/billing", false), "open", "other shells unchanged");
  // Answered: entitled opens, module off blocks; a failed answer (settled, no entitlements) is fail-closed with Retry.
  assert.equal(gate("billing", BILLING, ALL, "/billing", true), "open");
  assert.equal(gate("billing", BILLING, null, "/billing", true), "unverified:billing-dayclose");
  const gateSrc = src("shell/RouteGate.tsx");
  assert.match(gateSrc, /useEntitlementsStatus\(\)/);
  assert.match(gateSrc, /gate\.kind === "wait"\) return <main><Skeleton/);
  assert.match(gateSrc, /gate\.kind === "unverified"\) return <main><ErrorState message=.* onRetry=\{retry\} \/><\/main>;/);
  // The S17 lesson: `settled` is set once and never reset (a retry or refetch never swaps the page back to the skeleton); no auto retry.
  const hook = src("lib/entitlements.ts");
  assert.match(hook, /setS\(\(p\) => \(\{ ent: e \?\? p\.ent, settled: true \}\)\)/);
  assert.equal((hook.match(/settled: false/g) ?? []).length, 1, "only the initial state is unsettled");
  assert.match(hook, /const retry = useCallback\(\(\) => \{ inflight = null; setAttempt\(\(a\) => a \+ 1\); \}, \[\]\);/);
  assert.doesNotMatch(hook, /setInterval|retry\(\)/, "nothing calls retry by itself");
});

test("route skeletons: every planned counter item has its thin page on the PlannedScreen gate; built items keep their screens", () => {
  for (const r of COUNTER_ROLES) {
    for (const i of NAV[r]) {
      const page = new URL(`../../app/(clinic)${i.href.split("?")[0]}/page.tsx`, import.meta.url);
      if (i.planned) {
        assert.ok(existsSync(page), i.href);
        assert.match(readFileSync(page, "utf8"), new RegExp(`<PlannedScreen id="${i.id}" />`), i.href);
      }
    }
  }
  for (const href of ["/arrivals", "/packages", "/billing", "/pharmacy", "/checkout/otc", "/pharmacy/stock-take"]) assert.ok(existsSync(new URL(`../../app/(clinic)${href}/page.tsx`, import.meta.url)), href);
  assert.equal(findItem("ph-dispense").planned, undefined);
  assert.match(src("lib/routeTitle.ts"), /\["\/lab", "Lab"\]/);
});

test("active item: the checkout queue covers the checkout tab and a visit's checkout, not the counter sale", () => {
  const co = findItem("billing-checkout"), dc = findItem("billing-dayclose"), inv = findItem("billing-invoices");
  assert.ok(isActive(co, "/arrivals", "checkout_pending") && !isActive(co, "/arrivals", null));
  assert.ok(isActive(co, "/checkout/q1") && !isActive(co, "/checkout/otc"));
  assert.ok(isActive(findItem("ph-pos"), "/checkout/otc", null));
  // S18-BUG-8: PHL-010's stock take is the Stock item's screen.
  const ph = nav(PHARMACIST, ALL).items;
  assert.deepEqual(ph.filter((i) => isActive(i, "/pharmacy/stock-take", null)).map((i) => i.id), ["ph-stock"]);
  assert.equal(findItem("ph-stock").href, "/pharmacy/stock-take");
  assert.ok(!existsSync(new URL("../../app/(clinic)/pharmacy/stock/page.tsx", import.meta.url)), "no duplicate skeleton");
  assert.deepEqual(ph.filter((i) => isActive(i, "/pharmacy", null)).map((i) => i.id), ["ph-dispense"]);
  assert.ok(isActive(dc, "/billing", null) && !isActive(dc, "/billing/invoices", null) && isActive(inv, "/billing/invoices", null));
  assert.ok(isActive(findItem("lab-worklist"), "/lab", null) && !isActive(findItem("lab-worklist"), "/lab/collect", null));
});

test("badges: checkout follows the live queue (SSE 'queue' resource); items without a count source carry no badge", () => {
  assert.equal(findItem("billing-checkout").badge, "checkout");
  for (const r of COUNTER_ROLES) for (const i of NAV[r]) if (i.id !== "billing-checkout") assert.equal(i.badge, undefined, i.id);
  const hook = src("shell/useNavBadges.ts");
  assert.match(hook, /enabled: \(role === "reception" \|\| role === "owner" \|\| role === "billing"\) && q/);
  assert.match(hook, /checkout: count\(reception, \(r\) => r\.status === "checkout_pending"\)/);
  const sse = readFileSync(new URL("../../../../packages/api-client/sse/patch.ts", import.meta.url), "utf8");
  assert.match(sse, /if \(type\.startsWith\("queue\."\)\) return \["queue", "wait-estimate"\]/);
  assert.match(src("lib/live.tsx"), /if \(perms\.includes\("queue:view"\)\) t\.push\("queue"/, "the billing role's queue:view subscribes the queue topic");
});

test("responsive: sidebar on desktop, icon rail at --bp-tablet (768-1024), bottom nav top 4 + More at --bp-phone (375)", () => {
  const scale = JSON.stringify(JSON.parse(readFileSync(new URL("../../../../packages/tokens/src/source/scale.json", import.meta.url), "utf8")));
  assert.match(scale, /"bp-tablet":\{"query":"\(max-width: 1024px\)"/);
  assert.match(scale, /"bp-phone":\{"query":"\(max-width: 430px\)"/);
  const css = src("clinicNav.module.css");
  const block = (q) => { const i = css.indexOf(`@media (${q}) {\n`); assert.ok(i >= 0, q); return css.slice(i, css.indexOf("\n}\n", i)); };
  assert.match(css, /\.bottomNav \{ display: none; \}/);
  assert.match(block("--bp-tablet"), /\.nav \{ width: 88px;/);
  assert.match(block("--bp-tablet"), /\.linkLabel, \.identityText \{ display: none; \}/);
  assert.match(block("--bp-phone"), /\.nav \{ display: none; \}/);
  assert.match(block("--bp-phone"), /\.bottomNav \{\s*display: flex; position: fixed;/);
  const billing = ids(nav(BILLING, ALL));
  assert.deepEqual(bottomNavSplit(billing), { primary: billing.slice(0, 4), more: ["billing-packages", "billing-dayclose"] });
  const ph = ids(nav(PHARMACIST, ALL));
  assert.deepEqual(bottomNavSplit(ph).primary, ["ph-dispense", "ph-pos", "ph-stock", "ph-purchase"]);
  const lab = ids(nav(LAB, ALL));
  assert.deepEqual(bottomNavSplit(lab), { primary: lab, more: [] }, "five destinations fit without More (DESIGN.md §4 max 5)");
  assert.match(src("ClinicNav.tsx"), /const \{ primary: primaryEntries, more: overflowEntries \} = bottomNavSplit\(entries\);/);
});

test("standalone pharmacy-only / lab-only: the pharmacist and lab shells hold no clinic navigation", () => {
  for (const r of ["pharmacist", "lab"]) {
    assert.ok(NAV[r].every((i) => /^\/(pharmacy|lab|checkout\/otc)/.test(i.href)), r);
  }
  assert.deepEqual(ids(nav(PHARMACIST, ALL)).filter((x) => !x.startsWith("ph-")), []);
  assert.deepEqual(ids(nav(LAB, ALL)).filter((x) => !x.startsWith("lab-")), []);
});

test("labels come from packages/i18n (en and hi) for every counter item", () => {
  for (const loc of ["en", "hi"]) {
    const cat = JSON.parse(readFileSync(new URL(`../../../../packages/i18n/messages/${loc}/shell.json`, import.meta.url), "utf8")).counterNav;
    for (const r of COUNTER_ROLES) for (const i of NAV[r]) assert.ok(cat[i.id]?.label && cat[i.id]?.short, `${loc} ${i.id}`);
  }
  const en = JSON.parse(readFileSync(new URL("../../../../packages/i18n/messages/en/shell.json", import.meta.url), "utf8")).counterNav;
  for (const r of COUNTER_ROLES) for (const i of NAV[r]) assert.deepEqual([en[i.id].label, en[i.id].short], [i.label, i.short], i.id);
  assert.match(src("ClinicNav.tsx"), /COUNTER_ROLES\.includes\(role\) \? \{ \.\.\.i0, label: t\(`counterNav\.\$\{i0\.id\}\.label`\)/);
});

// ---- keyboard-wedge barcode handler ----
const body = { tagName: "BODY" }, input = { tagName: "INPUT" }, button = { tagName: "BUTTON" };
function feed(handle, text, { target = body, gap = 5, start = 1000 } = {}) {
  let t = start, last = false;
  for (const key of [...text, "Enter"]) { last = handle({ key, timeStamp: t, target }); t += gap; }
  return last;
}

test("wedge: a fast burst outside an input ending in Enter is routed to the active scan target", () => {
  const got = [];
  const handle = createWedge((c) => { got.push(c); return true; });
  assert.equal(feed(handle, "8901234567890"), true, "the Enter is consumed (caller prevents the default)");
  assert.deepEqual(got, ["8901234567890"]);
  assert.equal(feed(handle, "ABC-123", { target: button, start: 5000 }), true, "focus on a button is not a typing target");
  assert.deepEqual(got, ["8901234567890", "ABC-123"]);
});

test("wedge: normal typing is ignored (in an input, or slow keys outside one, or short bursts, or with modifiers)", () => {
  const got = [];
  const handle = createWedge((c) => { got.push(c); return true; });
  assert.equal(feed(handle, "8901234567890", { target: input }), false, "typing into an input stays the input's");
  assert.equal(feed(handle, "8901234567890", { target: { tagName: "DIV", isContentEditable: true }, start: 3000 }), false);
  assert.equal(feed(handle, "hello", { gap: WEDGE_MAX_GAP_MS + 60, start: 9000 }), false, "human-speed keys are not a scan");
  assert.equal(feed(handle, "12", { start: 20000 }), false, "too short to be a barcode");
  let t = 30000;
  for (const key of ["1", "2", "3", "4"]) handle({ key, timeStamp: (t += 5), target: body, ctrlKey: key === "3" });
  assert.equal(handle({ key: "Enter", timeStamp: (t += 5), target: body }), false, "a Ctrl chord breaks the burst");
  assert.deepEqual(got, []);
});

test("wedge: Shift for capitals does not break a burst; a slow Enter after a fast burst is not a scan", () => {
  const got = [];
  const handle = createWedge((c) => { got.push(c); return true; });
  let t = 0;
  for (const key of ["Shift", "A", "Shift", "B", "1", "2"]) handle({ key, timeStamp: (t += 5), target: body });
  assert.equal(handle({ key: "Enter", timeStamp: (t += 5), target: body }), true);
  assert.deepEqual(got, ["AB12"]);
  for (const key of ["9", "9", "9", "9"]) handle({ key, timeStamp: (t += 5), target: body });
  assert.equal(handle({ key: "Enter", timeStamp: (t += 500), target: body }), false);
  assert.deepEqual(got, ["AB12"]);
});

test("wedge: the latest registered scan target is active; with none the scan is not consumed", () => {
  assert.equal(activeScanTarget(), undefined);
  const handle = createWedge();
  assert.equal(feed(handle, "12345678"), false, "no target: nothing consumes the Enter");
  const a = [], b = [];
  const offA = registerScanTarget((c) => a.push(c));
  const offB = registerScanTarget((c) => b.push(c)); // e.g. a dialog opened over the screen
  assert.equal(feed(handle, "11112222", { start: 50000 }), true);
  offB();
  assert.equal(feed(handle, "33334444", { start: 60000 }), true);
  offA();
  assert.deepEqual([a, b], [["33334444"], ["11112222"]]);
  assert.equal(activeScanTarget(), undefined);
});

test("wedge is mounted only in the pharmacist shell and listens in the capture phase", () => {
  assert.match(src("ClinicNav.tsx"), /\{role === "pharmacist" && <BarcodeWedge \/>\}/);
  const c = src("shell/BarcodeWedge.tsx");
  assert.match(c, /document\.addEventListener\("keydown", onKey, true\)/);
  assert.match(c, /if \(handle\(k\)\) e\.preventDefault\(\);/);
  assert.match(c, /export function useScanTarget/);
});
