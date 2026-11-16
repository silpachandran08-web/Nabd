// FEF-056: solo doctor shell: prototype nav order and role home, permission + entitlement gating (absent; direct URL = no-access),
// SSE-fed badges, sidebar / rail / bottom nav (top 4 + More), thumb-zone primary actions, the throttled-3G budget route.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { navFor, homeFor, rolesFor, counterRouteState, bottomNavSplit, jumpTargets, findItem, isActive, itemVisible, NAV, SOLO_PROTOTYPE_ORDER } from "../../app/shell/navRegistry.ts";
import { landingPathFor } from "../../app/lib/session.ts";

const MODULES = ["queue_eta", "whatsapp_booking", "emr_erx", "billing", "packages", "lab", "patient_app", "teleconsult"];
const entWith = (codes) => ({ modules: MODULES.map((code) => ({ code, enabled: codes.includes(code) })), killedFeatures: [], seatLimit: null });
const ALL = entWith(MODULES);
const without = (...off) => entWith(MODULES.filter((m) => !off.includes(m)));
// The built-in Owner role holds every grant; the solo doctor is that owner.
const OWNER = ["queue:view", "queue:edit", "queue:create", "clinical:view", "clinical:edit", "patients:view", "billing:view", "billing:create", "setup:view", "setup:edit", "reports:view", "packages:view", "staff:view"];
const solo = (perms = OWNER, ent = ALL, extra = {}) => navFor({ perms, owner: true, ent, solo: true, ...extra });
const ids = (n) => n.items.map((i) => i.id);
const app = (p) => readFileSync(new URL(`../../app/${p}`, import.meta.url), "utf8");
const msg = (l) => JSON.parse(readFileSync(new URL(`../../../../packages/i18n/messages/${l}/shell.json`, import.meta.url), "utf8"));

test("AC1: solo role, all modules entitled: lands on Today and the nav is the prototype solo order", () => {
  const n = solo();
  assert.equal(n.role, "solo");
  assert.deepEqual(n.roles, ["solo", "owner", "doctor"], "the owner and doctor navs stay one switch away");
  assert.deepEqual(SOLO_PROTOTYPE_ORDER, ["today", "consult", "patients", "money", "specialty", "setup"]);
  assert.deepEqual(ids(n), SOLO_PROTOTYPE_ORDER.map((x) => `solo-${x}`));
  assert.equal(homeFor({ perms: OWNER, owner: true, ent: ALL, solo: true }), "/solo");
  assert.equal(landingPathFor(OWNER, true, true), "/solo", "login lands on the role home");
  assert.equal(landingPathFor(OWNER, true, true), homeFor({ perms: OWNER, owner: true, ent: null, solo: true }));
  // Not a solo-mode clinic, or not the owner: shells unchanged.
  assert.deepEqual(rolesFor(OWNER, true), ["owner", "doctor"]);
  assert.equal(landingPathFor(OWNER, true), "/overview");
  assert.deepEqual(rolesFor(OWNER, false, true), ["doctor"], "solo mode never applies to a non-owner");
  assert.equal(landingPathFor(["clinical:edit", "queue:view"], false, true), "/my-day");
  assert.deepEqual(rolesFor(["reports:view", "setup:view"], true, true), ["owner"], "an owner who is not a doctor keeps the owner shell");
  assert.equal(landingPathFor(["reports:view", "setup:view"], true, true), "/overview");
  // The user can switch back to the full owner nav (context override, as for any owner who is also a doctor).
  assert.equal(solo(OWNER, ALL, { override: "owner" }).role, "owner");
  assert.equal(homeFor({ perms: OWNER, owner: true, ent: ALL, solo: true, override: "owner" }), "/overview");
});

test("AC1: labels come from packages/i18n (en equals the registry text, hi present) and the solo catalogue block is used", () => {
  const en = msg("en"), hi = msg("hi");
  assert.equal(en.role.solo, "Solo doctor");
  assert.ok(hi.role.solo);
  for (const i of NAV.solo) {
    assert.deepEqual([en.soloNav[i.id]?.label, en.soloNav[i.id]?.short], [i.label, i.short], `en ${i.id}`);
    assert.ok(hi.soloNav[i.id]?.label && hi.soloNav[i.id]?.short, `hi ${i.id}`);
  }
  assert.match(app("shell/navText.ts"), /role === "solo" \? "soloNav"/, "the solo nav reads its own catalogue block");
});

test("AC2: a module not entitled or a missing permission removes the item (absent, not disabled), also from Ctrl+K", () => {
  assert.deepEqual(ids(solo(OWNER, without("billing"))), ["solo-today", "solo-consult", "solo-patients", "solo-specialty", "solo-setup"]);
  assert.deepEqual(ids(solo(OWNER, without("emr_erx"))), ["solo-today", "solo-consult", "solo-patients", "solo-money", "solo-setup"]);
  assert.ok(!ids(solo(OWNER, null)).includes("solo-specialty"), "unknown entitlements: the planned workspace stays absent");
  assert.ok(ids(solo(OWNER, null)).includes("solo-money"), "unknown entitlements never hide a built screen");
  const drop = (...g) => OWNER.filter((p) => !g.includes(p));
  assert.ok(!ids(solo(drop("billing:view"))).includes("solo-money"));
  assert.ok(!ids(solo(drop("setup:view"))).includes("solo-setup"));
  assert.ok(!ids(solo(drop("patients:view"))).includes("solo-patients"));
  assert.deepEqual(jumpTargets({ perms: drop("billing:view"), owner: true, ent: ALL, solo: true }, "take payment"), []);
  assert.deepEqual(jumpTargets({ perms: OWNER, owner: true, ent: ALL, solo: true }, "take payment").map((i) => i.href), ["/billing"]);
});

const gate = (perms, ent, path, settled = true) => {
  const g = counterRouteState("solo", { perms, owner: true, ent, solo: true }, path, settled);
  return g.kind === "open" ? "open" : `${g.kind}:${g.item.id}`;
};

test("AC2: a direct URL into an item the session may not see gets the no-access state; nothing mounts before entitlements answer", () => {
  assert.equal(gate(OWNER, without("billing"), "/billing"), "blocked:solo-money");
  assert.equal(gate(OWNER, without("billing"), "/checkout/q-1"), "blocked:solo-money");
  assert.equal(gate(OWNER, without("emr_erx"), "/specialty"), "blocked:solo-specialty");
  assert.equal(gate(OWNER.filter((p) => p !== "setup:view"), ALL, "/setup/workflows"), "blocked:solo-setup");
  assert.equal(gate(OWNER, ALL, "/billing"), "open");
  assert.equal(gate(OWNER, ALL, "/solo"), "open");
  assert.equal(gate(OWNER, null, "/billing", false), "wait:solo-money", "pending: the page does not mount and fetch yet");
  assert.equal(gate(OWNER, null, "/billing", true), "unverified:solo-money", "failed answer: fail-closed with Retry");
  assert.equal(gate(OWNER, without("billing"), "/checkout/otc"), "open", "the counter sale keeps its own page gate");
  // The planned workspace's skeleton page gates on the same grant and module as the nav item.
  const ws = findItem("specialty");
  assert.equal(itemVisible(ws, OWNER, without("emr_erx")), false);
  assert.equal(itemVisible(ws, OWNER, ALL), true);
  assert.match(app("(clinic)/specialty/page.tsx"), /PlannedScreen id="specialty"/);
  assert.match(app("shell/RouteGate.tsx"), /counterRouteState\(role/);
});

test("AC2: each nav item marks its own routes active (Today is not active inside the consult summary)", () => {
  const item = (id) => NAV.solo.find((i) => i.id === id);
  assert.ok(isActive(item("solo-today"), "/solo", null));
  assert.ok(!isActive(item("solo-today"), "/solo/consult", null));
  assert.ok(isActive(item("solo-consult"), "/solo/consult", null));
  assert.ok(isActive(item("solo-consult"), "/consult", null));
  assert.ok(isActive(item("solo-money"), "/checkout/q-1", null));
  assert.ok(isActive(item("solo-setup"), "/setup/workflows", null));
  for (const i of NAV.solo) assert.ok(i.owner, `${i.id} names its owning squad`);
});

test("AC3: badges read the shared queue queries that the SSE applier patches, so a count change updates without a reload", () => {
  const n = solo();
  assert.equal(n.items.find((i) => i.id === "solo-today").badge, "myday");
  assert.equal(n.items.find((i) => i.id === "solo-money").badge, "checkout");
  const b = app("shell/useNavBadges.ts");
  assert.match(b, /role === "doctor" \|\| role === "solo"/, "the doctor's own queue query is on for solo");
  assert.match(b, /role === "billing" \|\| role === "solo"\) && q/, "the checkout count query is on for solo");
  const live = app("lib/live.tsx");
  assert.match(live, /createApplier\(qc, prefix\)/, "SSE deltas patch the TanStack cache the badges read");
  assert.match(live, /perms\.includes\("queue:view"\)\) t\.push\("queue"/);
});

test("AC4: the same ClinicNav renders a sidebar (desktop), an icon rail (tablet) and a bottom nav of the top 4 + More (phone)", () => {
  const { primary, more } = bottomNavSplit(solo().items);
  assert.deepEqual(primary.map((i) => i.id), ["solo-today", "solo-consult", "solo-patients", "solo-money"]);
  assert.deepEqual(more.map((i) => i.id), ["solo-specialty", "solo-setup"]);
  const css = readFileSync(new URL("../../app/clinicNav.module.css", import.meta.url), "utf8");
  assert.match(css, /@media \(--bp-tablet\) \{\s*\.nav \{ width: 88px;/, "768-1024: icon rail");
  assert.match(css, /@media \(--bp-phone\) \{\s*\.nav \{ display: none; \}\s*\.bottomNav \{/, "phone: bottom nav instead of the sidebar");
  assert.doesNotMatch(app("ClinicNav.tsx"), /role === "solo"/, "no forked shell: the solo nav goes through the one ClinicNav");
});

test("AC5: on a 360 px phone Call next, Rx and Take payment sit in the bottom bar with targets of at least 48 px", () => {
  const s = app("components/solo/SoloToday.tsx");
  const bar = s.slice(s.indexOf("<nav className={styles.bar}"), s.indexOf("</nav>"));
  for (const label of ["Call next", ">Rx<", "Take payment"]) assert.ok(bar.includes(label), label);
  assert.match(bar, /canPay && /, "Take payment is absent without billing:view or the billing module");
  assert.match(s, /can\("billing:view"\) && navAllowed\("\/billing", ent\)/);
  assert.match(bar, /inRoom && canRx && /, "Rx only with clinical:edit and a patient in the room");
  const css = readFileSync(new URL("../../app/components/solo/solo.module.css", import.meta.url), "utf8");
  assert.match(css, /\.bar \{ position: fixed; inset-inline: 0; inset-block-end: 0;/, "fixed to the bottom (thumb zone)");
  const px = (prop) => Number(new RegExp(`\\.barBtn \\{[^}]*${prop}: (\\d+)px`).exec(css)[1]);
  assert.ok(px("min-block-size") >= 48 && px("min-inline-size") >= 48);
  // 360 px, 12 px side padding, 8 px gaps, four buttons when a patient is in the room: each still wider than 48 px.
  assert.ok((360 - 2 * 12 - 3 * 8) / 4 >= 48);
  assert.match(css, /@media \(max-width: 430px\) \{\s*\.bar \{ inset-block-end: calc\(64px/, "kept above the 64 px bottom nav");
  assert.match(app("(clinic)/solo/page.tsx"), /useMediaQuery\("\(max-width: 640px\)"\)/, "a 360 px phone gets the thumb-zone screen");
});

test("AC6 proxy: the solo Today route is in the throttled-3G, 360 px Lighthouse budget (LCP < 3 s, under the 5 s target)", () => {
  const rc = JSON.parse(readFileSync(new URL("../../lighthouserc.json", import.meta.url), "utf8"));
  assert.ok(rc.nabdBudgets.routes.includes("/solo"));
  assert.ok(rc.nabdBudgets.lcpMs <= 5000);
  const s = rc.ci.collect.settings;
  assert.equal(s.screenEmulation.width, 360);
  assert.ok(s.throttling.downloadThroughputKbps <= 1600 && s.throttling.cpuSlowdownMultiplier >= 4, "3G and a low-end CPU");
});

test("solo mode is read at sign-in (not by the shell on every page) and remembered per clinic", () => {
  const m = app("shell/soloMode.ts");
  assert.match(m, /api\.get<[^>]+>\("\/booking-policy"/);
  assert.match(m, /AbortSignal\.timeout\(3000\)/, "a slow answer never blocks sign-in for long");
  assert.match(m, /rolesFor\(perms, owner, true\)\.includes\("solo"\)/, "only an owner who is the doctor asks");
  assert.match(app("(auth)/login/page.tsx"), /landingPathFor\(perms, owner, solo\)/);
  assert.match(app("(owner)/owner/workspaces/page.tsx"), /loadSoloMode\(/);
  assert.match(app("shell/useShellNav.ts"), /solo: soloMode\(/);
  assert.doesNotMatch(app("shell/useShellNav.ts"), /loadSoloMode/, "the shell fetches no feature data");
});
