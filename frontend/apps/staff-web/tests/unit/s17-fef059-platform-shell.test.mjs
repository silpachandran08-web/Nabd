// FEF-059: operator console shell. Typed role nav in prototype order, permission + entitlement gating (absent, direct URL = no access),
// support:grant, role home, responsive variants (sidebar / rail / bottom nav top 4 + More), operator vs clinic session separation.
import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import {
  PLATFORM_NAV, PLATFORM_PROTOTYPE_ORDER, SUPPORT_GRANT, platformNavFor, platformHomeFor, platformRouteAllowed, platformItemFor, splitBottomNav, platformMeKey,
} from "../../app/(platform)/platform/surfaces.ts";
import { qk } from "../../../../packages/api-client/queries/keys.ts";

const APP = join(import.meta.dirname, "../../app");
const PLATFORM = join(APP, "(platform)/platform");
const read = (p) => readFileSync(p, "utf8");
const JAVA = join(import.meta.dirname, "../../../../../backend/src/main/java/com/nabd/hms/platform/PlatformPermissions.java");

// Same parse of the server's matrix as the FEF-019 test: the role -> permissions /platform/auth/me returns.
function operatorPermissions() {
  const src = read(JAVA);
  const block = (name) => src.slice(src.indexOf(name), src.indexOf(");", src.indexOf(name)));
  const entries = (b) => [...b.matchAll(/Map\.entry\("([a-z_:]+)", Set\.of\(([^)]*)\)\)|"([a-z_:]+)", Set\.of\(([^)]*)\)/g)]
    .map((m) => [m[1] ?? m[3], [...(m[2] ?? m[4]).matchAll(/"(\w+)"/g)].map((x) => x[1])]);
  const surf = entries(block("SURFACE_ROLES")), act = entries(block("ACTION_ROLES"));
  const roles = [...new Set([...surf, ...act].flatMap(([, r]) => r))];
  return Object.fromEntries(roles.map((r) => [r, [...surf.filter(([, rs]) => rs.includes(r)).map(([k]) => `${k}:view`), ...act.filter(([, rs]) => rs.includes(r)).map(([k]) => k)]]));
}
const ROLES = operatorPermissions();
const SUPER = ROLES.super_admin;
const ALL = { modules: [{ code: "platform_health", enabled: true }], killedFeatures: [], seatLimit: null };
const ids = (items) => items.map((i) => i.id);

test("every permission the nav needs exists on the server", () => {
  const known = new Set(Object.values(ROLES).flat());
  for (const i of PLATFORM_NAV) for (const p of i.perms) assert.ok(known.has(p), `${i.id}: ${p} is not a PlatformPermissions authority`);
});

test("platform role, all modules entitled: nav in the prototype order, landing on the role home", () => {
  const nav = ids(platformNavFor(SUPER, ALL));
  assert.deepEqual(nav, ["tenants", "onboard", "regions", "health", "support", "supportAccess", "revenue", "senders", "pricing", "audit", "command"]);
  assert.deepEqual(nav.filter((id) => PLATFORM_PROTOTYPE_ORDER.includes(id)), PLATFORM_PROTOTYPE_ORDER);
  assert.equal(platformHomeFor(SUPER, ALL), "/platform/fleet");
  assert.equal(platformHomeFor(SUPER), "/platform/fleet");
  // an SRE holds neither clinics nor onboarding: home is the first item it does hold
  assert.equal(platformHomeFor(ROLES.sre), "/platform/territories");
  for (const [role, perms] of Object.entries(ROLES)) assert.equal(platformHomeFor(perms), platformNavFor(perms)[0].href, role);
  assert.equal(platformHomeFor([]), "/platform");
  // login lands there (role home from the token's permissions claim)
  assert.match(read(join(PLATFORM, "login/page.tsx")), /router\.replace\(platformHomeFor\(decodeJwtPayload/);
});

test("a module not entitled removes the item and its route; operator entitlements unknown = planned screens absent", () => {
  assert.ok(!ids(platformNavFor(SUPER)).includes("health"));
  assert.ok(!ids(platformNavFor(SUPER, { modules: [{ code: "platform_health", enabled: false }], killedFeatures: [], seatLimit: null })).includes("health"));
  assert.equal(platformRouteAllowed("/platform/health", SUPER), false);
  assert.equal(platformRouteAllowed("/platform/health", SUPER, ALL), true);
  assert.equal(platformRouteAllowed("/platform/health", ROLES.billing, ALL), false, "entitled but not permitted");
});

test("a permission the operator lacks removes the item; the direct URL (incl. sub-routes) gets the no-access state", () => {
  const noBilling = SUPER.filter((p) => p !== "billing_revenue:view");
  assert.ok(!ids(platformNavFor(noBilling)).includes("revenue"));
  assert.equal(platformRouteAllowed("/platform/billing", noBilling), false);
  assert.equal(platformRouteAllowed("/platform/billing", SUPER), true);
  const noFleet = SUPER.filter((p) => p !== "clinics_fleet:view");
  assert.equal(platformItemFor("/platform/fleet/abc/features").id, "tenants");
  assert.equal(platformRouteAllowed("/platform/fleet/abc/features", noFleet), false);
  assert.equal(platformItemFor("/platform").id, "command");
  assert.equal(platformItemFor("/platform/login"), undefined);
  assert.equal(platformRouteAllowed("/platform/login", []), true);
  // per role: exactly the surfaces of the matrix
  assert.deepEqual(ids(platformNavFor(ROLES.commercial)), ["tenants", "regions", "revenue", "senders", "command"]);
  assert.deepEqual(ids(platformNavFor(ROLES.compliance_dpo)), ["tenants", "regions", "senders", "audit", "command"]);
  // the shell gates the content with the same rule, and shows RoleDenied
  const shell = read(join(PLATFORM, "PlatformShell.tsx"));
  assert.match(shell, /platformRouteAllowed\(pathname, me\.data\.permissions\)/);
  assert.match(shell, /<RoleDenied/);
});

test("an operator without support:grant gets no Support access (nav, route, Fleet row action); tickets stay", () => {
  assert.equal(SUPPORT_GRANT, "support_access:request");
  const noGrant = SUPER.filter((p) => p !== SUPPORT_GRANT);
  assert.ok(!ids(platformNavFor(noGrant)).includes("supportAccess"));
  assert.ok(ids(platformNavFor(noGrant)).includes("support"));
  assert.equal(platformRouteAllowed("/platform/access", noGrant), false);
  assert.equal(platformRouteAllowed("/platform/access", SUPER), true);
  for (const r of ["billing", "sre", "commercial", "compliance_dpo"]) assert.ok(!ids(platformNavFor(ROLES[r])).includes("supportAccess"), r);
  assert.match(read(join(PLATFORM, "fleet/page.tsx")), /includes\("support_access:view"\) && me\.permissions\.includes\(SUPPORT_GRANT\)/);
});

test("responsive variants: sidebar by default, icon rail at --bp-tablet, bottom nav (top 4 + More) at --bp-phone", () => {
  const scale = JSON.parse(read(join(import.meta.dirname, "../../../../packages/tokens/src/source/scale.json")));
  const flat = JSON.stringify(scale);
  assert.match(flat, /"bp-tablet":\{"query":"\(max-width: 1024px\)"/);
  assert.match(flat, /"bp-phone":\{"query":"\(max-width: 430px\)"/); // 375 px phone is inside it, 768-1024 is the rail
  const css = read(join(PLATFORM, "platform.module.css"));
  const block = (q) => { const i = css.indexOf(`@media (${q})`); assert.ok(i >= 0, q); return css.slice(i, css.indexOf("\n}\n", i)); };
  assert.match(css, /\.bottomNav \{ display: none; \}/, "no bottom nav on desktop");
  const tablet = block("--bp-tablet");
  assert.match(tablet, /\.navBar \{\s*width: 72px;/);
  assert.match(tablet, /\.navBarLabel[^{]*\{ display: none; \}/);
  assert.match(tablet, /\.navBarShort \{ display: block;/);
  const phone = block("--bp-phone");
  assert.match(phone, /\.navBar \{ display: none; \}/);
  assert.match(phone, /\.bottomNav \{\s*display: flex; position: fixed;/);
  const items = ["a", "b", "c", "d", "e", "f"];
  assert.deepEqual(splitBottomNav(items), { primary: ["a", "b", "c", "d"], more: ["e", "f"] });
  assert.deepEqual(splitBottomNav(["a", "b"]), { primary: ["a", "b"], more: [] });
  const nav = read(join(PLATFORM, "PlatformNav.tsx"));
  assert.match(nav, /splitBottomNav\(entries\)/);
  assert.match(nav, /data-bottom-nav/);
  assert.match(nav, /aria-haspopup="menu"[\s\S]{0,160}<Icon name="more"/, "More is always there (it also holds Log out)");
});

test("operator and clinic sessions are separate: storage keys, cookies, query cache keys", () => {
  const plat = read(join(PLATFORM, "platformSession.ts"));
  assert.match(plat, /"nabd_platform_access_token"/);
  assert.match(plat, /"nabd_platform_refresh_token"/);
  assert.ok(!/nabd_access_token"|document\.cookie|__Host-nabd/.test(plat), "the operator session never touches the clinic token or cookies");
  const bff = read(join(APP, "lib/bff.ts"));
  assert.match(bff, /AT = "__Host-nabd_at"/);
  assert.match(read(join(APP, "lib/bffShim.ts")), /url\.startsWith\(base \+ "\/platform\/"\)\) return null/, "operator calls never go through the clinic BFF cookies");
  // query cache: clinic keys are ["t", tenantId, ...]; the operator's live under "platform"
  assert.equal(qk("tenant-1", "queue")[0], "t");
  assert.deepEqual([...platformMeKey], ["platform", "me"]);
  assert.match(read(join(PLATFORM, "PlatformNav.tsx")), /removeQueries\(\{ queryKey: PLATFORM_QK \}\)/, "operator logout clears its own cache");
});

test("every nav item has a route, an owning squad and en + hi catalogue labels; badges stay unwired without a source", () => {
  const cat = (loc) => JSON.parse(read(join(import.meta.dirname, `../../../../packages/i18n/messages/${loc}/shell.json`))).platformNav;
  const en = cat("en"), hi = cat("hi");
  assert.deepEqual(Object.keys(en).sort(), PLATFORM_NAV.map((i) => i.id).sort());
  assert.deepEqual(Object.keys(hi).sort(), Object.keys(en).sort());
  for (const i of PLATFORM_NAV) {
    const dir = i.href === "/platform" ? PLATFORM : join(PLATFORM, i.href.slice("/platform/".length));
    assert.ok(existsSync(join(dir, "page.tsx")), `${i.id}: no page for ${i.href}`);
    assert.ok(i.owner, i.id);
    assert.equal(en[i.id].label, i.label, i.id);
    assert.ok(en[i.id].short && hi[i.id].label && hi[i.id].short, i.id);
    assert.equal(i.badge, undefined, `${i.id}: no operator SSE topic exists to feed a badge`);
  }
});
