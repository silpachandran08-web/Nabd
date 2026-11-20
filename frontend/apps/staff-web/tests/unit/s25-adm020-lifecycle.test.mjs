// ADM-020: the operator's lifecycle console (stepper, reason, history) and the clinic-side status banner. Pure rules plus the
// source gates that keep restricted controls absent.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import {
  LIFECYCLE_STATES, MAX_REASON, MIN_REASON, TRANSITION_EFFECT, actorLabel, canMove, canViewLifecycle, nextStates, reasonProblem, stateLabel,
} from "../../app/(platform)/platform/fleet/lifecycle.ts";
import { PAY_HREF, amountDue, statusBanner } from "../../app/lib/tenantStatus.ts";
import { platformItemFor, platformRouteAllowed } from "../../app/(platform)/platform/surfaces.ts";

const APP = join(import.meta.dirname, "../../app");
const read = (p) => readFileSync(join(APP, p), "utf8");
const ent = (tenantStatus) => ({ modules: [], killedFeatures: [], seatLimit: null, tenantStatus });

test("the stepper shows the seven states in ladder order, each with a label", () => {
  assert.deepEqual([...LIFECYCLE_STATES], ["provisioning", "trialing", "active", "overdue", "suspended", "offboarding", "offboarded"]);
  assert.equal(stateLabel("offboarding"), "Offboarding");
});

test("the next states are the server's list in ladder order, never a local copy of the table", () => {
  assert.deepEqual(nextStates({ allowedTransitions: ["offboarding", "active"] }), ["active", "offboarding"]);
  assert.deepEqual(nextStates({ allowedTransitions: [] }), []); // offboarded: terminal
  assert.deepEqual(nextStates({}), []); // an older server: nothing is offered
  assert.deepEqual(nextStates(null), []);
  assert.deepEqual(nextStates({ allowedTransitions: ["deleted"] }), []); // an unknown state is never offered
});

test("every state a tenant can move to explains what the move does; the ones that take access away are marked", () => {
  for (const s of LIFECYCLE_STATES.filter((x) => x !== "provisioning")) {
    assert.ok(TRANSITION_EFFECT[s].title.length > 0 && TRANSITION_EFFECT[s].note.length > 20, s);
  }
  assert.deepEqual(Object.keys(TRANSITION_EFFECT).filter((s) => TRANSITION_EFFECT[s].danger).sort(), ["offboarded", "offboarding", "suspended"]);
  assert.match(TRANSITION_EFFECT.suspended.note, /view and export/);
  assert.match(TRANSITION_EFFECT.overdue.note, /keeps working/);
});

test("a reason is required: blank, too short and too long are refused before the call", () => {
  assert.match(reasonProblem(""), /at least 10/);
  assert.match(reasonProblem("   short   "), /at least 10/);
  assert.equal(reasonProblem("x".repeat(MIN_REASON)), null);
  assert.equal(reasonProblem("  invoice 30 days late  "), null);
  assert.equal(reasonProblem("x".repeat(MAX_REASON)), null);
  assert.match(reasonProblem("x".repeat(MAX_REASON + 1)), /under 500/);
});

test("the history names who made the move: the operator, the clinic's staff or the system", () => {
  assert.equal(actorLabel({ actorType: "operator", operatorName: "Asha Operator", changedBy: "o1" }), "Asha Operator");
  assert.equal(actorLabel({ actorType: "operator", operatorName: null, changedBy: "o1" }), "Nabd operator");
  assert.equal(actorLabel({ actorType: "staff", operatorName: null, changedBy: null }), "Clinic staff");
  assert.equal(actorLabel({ actorType: "system", operatorName: null, changedBy: null }), "System");
  assert.equal(actorLabel({ changedBy: null }), "System"); // an older server without actorType
  assert.equal(actorLabel({ changedBy: "o1" }), "Nabd operator");
});

test("moving needs tenant_detail:edit, reading tenant_detail:view; the page keeps the controls absent without it", () => {
  assert.equal(canMove(["tenant_detail:view"]), false);
  assert.equal(canMove(["tenant_detail:view", "tenant_detail:edit"]), true);
  assert.equal(canMove(undefined), false);
  assert.equal(canViewLifecycle(["clinics_fleet:view"]), false);
  assert.equal(canViewLifecycle(["tenant_detail:view"]), true);
  const page = read("(platform)/platform/fleet/[tenantId]/page.tsx");
  assert.match(page, /const canEdit = access\.ready && canMove\(/); // nothing before /auth/me answered
  assert.match(page, /\{canEdit && allowed\.includes\(s\) && \(/); // move buttons: absent, not disabled
  assert.match(page, /\{canEdit && target && effect && \(/); // the reason form too
  assert.match(page, /<RoleDenied surface="Tenant detail" \/>/); // a 403 says so
  assert.match(page, /<th scope="col">When<\/th><th scope="col">From<\/th><th scope="col">To<\/th><th scope="col">Operator<\/th><th scope="col">Reason<\/th>/);
  assert.match(page, /<Skeleton /); assert.match(page, /<ErrorState /); assert.match(page, /<EmptyState /);
  // the fleet list links to it only for a role that may read it
  assert.match(read("(platform)/platform/fleet/page.tsx"), /canOpenDetail\s*\n?\s*\? <Link className=\{styles\.tenantName\} href=\{`\/platform\/fleet\/\$\{t\.id\}`\}/);
});

test("the tenant detail route sits under Clinics: no clinics_fleet:view, no access", () => {
  assert.equal(platformItemFor("/platform/fleet/abc").id, "tenants");
  assert.equal(platformRouteAllowed("/platform/fleet/abc", ["tenant_detail:view"]), false);
  assert.equal(platformRouteAllowed("/platform/fleet/abc", ["clinics_fleet:view", "tenant_detail:view"]), true);
});

test("clinic banner: suspended is shown to everyone, overdue to the owner only, anything else to nobody", () => {
  assert.equal(statusBanner(ent("suspended"), false), "suspended");
  assert.equal(statusBanner(ent("suspended"), true), "suspended");
  assert.equal(statusBanner(ent("overdue"), true), "overdue");
  assert.equal(statusBanner(ent("overdue"), false), null);
  for (const s of ["active", "trialing", "offboarding", undefined, null]) assert.equal(statusBanner(ent(s), true), null);
  assert.equal(statusBanner(null, true), null); // unknown entitlements: no banner
});

test("amount due: the unpaid Nabd invoices, else the missed renewal, never a made-up figure", () => {
  const renewal = { amountMinor: 499900, currency: "INR" };
  assert.deepEqual(amountDue([{ amountMinor: 120000, currency: "INR", status: "due" }, { amountMinor: 30000, currency: "INR", status: "due" },
    { amountMinor: 999999, currency: "INR", status: "paid" }, { amountMinor: 5, currency: "INR", status: "void" }], renewal), { amountMinor: 150000, currency: "INR" });
  assert.deepEqual(amountDue([], renewal), renewal);
  assert.deepEqual(amountDue(null, renewal), renewal); // the invoices call failed
  assert.deepEqual(amountDue([{ amountMinor: 70000, currency: "INR", status: "due" }], null), { amountMinor: 70000, currency: "INR" });
  assert.equal(amountDue([{ amountMinor: 1, currency: "INR", status: "paid" }], null), null);
  assert.equal(amountDue(null, { amountMinor: 0, currency: "INR" }), null); // a free plan owes nothing
  assert.equal(amountDue(undefined, undefined), null);
  // invoices in another currency than the plan's are not added to it
  assert.deepEqual(amountDue([{ amountMinor: 100, currency: "SAR", status: "due" }], renewal), renewal);
});

test("the banner lives in the shell above the route, links the owner to the plan page and has en and hi strings", () => {
  assert.equal(PAY_HREF, "/plan");
  assert.match(read("AppShell.tsx"), /<TenantStatusBanner \/><EntitlementsBanner \/><RouteGate>/);
  const banner = read("TenantStatusBanner.tsx");
  assert.match(banner, /if \(!kind\) return null;/);
  assert.match(banner, /kind === "overdue" && canSeePlan && \(/); // the pay link is absent for anyone else
  for (const locale of ["en", "hi"]) {
    const m = JSON.parse(readFileSync(join(APP, `../../../packages/i18n/messages/${locale}/shell.json`), "utf8")).tenantStatus;
    assert.deepEqual(Object.keys(m).sort(), ["overdue", "overdueNoAmount", "pay", "suspended"], locale);
    assert.match(m.overdue, /\{amount\}/, locale);
  }
});
