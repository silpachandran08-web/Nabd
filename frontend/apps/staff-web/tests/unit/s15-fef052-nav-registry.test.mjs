// FEF-052/053/054: nav registry gating, role contexts, landing, Ctrl+K targets, one-registry grep.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { navFor, rolesFor, homeFor, jumpTargets, NAV } from "../../app/shell/navRegistry.ts";
import { landingPathFor } from "../../app/lib/session.ts";

const all = (codes) => ({ modules: codes.map((code) => ({ code, enabled: true })), killedFeatures: [], seatLimit: null });
const ENT = all(["billing", "packages", "pharmacy_inhouse", "whatsapp_booking", "queue_eta", "lab", "ipd", "patient_app", "emr_erx", "teleconsult"]);
const labels = (i) => i.items.map((x) => x.id);
const RECEPTION = ["queue:view", "queue:create", "queue:edit", "patients:view", "billing:view"];
const DOCTOR = ["clinical:edit", "clinical:view", "queue:view", "patients:view"];

test("reception order, all modules entitled", () => {
  const n = navFor({ perms: RECEPTION, owner: false, ent: ENT });
  assert.equal(n.role, "reception");
  assert.deepEqual(labels(n).slice(0, 8), ["arrivals", "requests", "calendar", "patients", "checkin", "display", "messages", "billing"]);
});

test("planned screens are absent without the module or with unknown entitlements; live screens (incl. ACC-010 booking requests) stay", () => {
  const none = navFor({ perms: RECEPTION, owner: false, ent: all(["billing"]) });
  assert.deepEqual(labels(none), ["arrivals", "requests", "calendar", "patients", "billing", "account"]);
  assert.deepEqual(labels(navFor({ perms: RECEPTION, owner: false, ent: null })), ["arrivals", "requests", "calendar", "patients", "billing", "account"]);
});

test("a lacking permission removes the item", () => {
  const n = navFor({ perms: ["queue:view"], owner: false, ent: ENT });
  assert.ok(!labels(n).includes("patients") && !labels(n).includes("billing") && !labels(n).includes("messages"));
});

test("doctor order, all modules entitled", () => {
  const n = navFor({ perms: DOCTOR, owner: false, ent: ENT });
  assert.equal(n.role, "doctor");
  assert.deepEqual(labels(n), ["myday", "consult", "patients", "results", "inpatients", "rx", "schedule", "my-schedule", "encounters", "records", "specialty", "recalls", "follow-ups", "tele"]);
  assert.equal(homeFor({ perms: DOCTOR, owner: false, ent: ENT }), "/my-day");
});

test("specialty profile drives the Specialty workspace label and icon", () => {
  const item = navFor({ perms: DOCTOR, owner: false, ent: ENT, specialty: "dental" }).items.find((i) => i.id === "specialty");
  assert.equal(item.label, "Dental workspace");
  assert.equal(item.icon, "sparkle");
  assert.equal(navFor({ perms: DOCTOR, owner: false, ent: ENT }).items.find((i) => i.id === "specialty").label, "Specialty workspace");
});

test("owner who is also a doctor can switch context; landing and items change", () => {
  const perms = [...DOCTOR, "reports:view", "billing:view"];
  assert.deepEqual(rolesFor(perms, true), ["owner", "doctor"]);
  assert.deepEqual(rolesFor(["reports:view"], true), ["owner"]);
  assert.equal(navFor({ perms, owner: true, ent: null }).role, "owner");
  assert.equal(homeFor({ perms, owner: true, ent: null }), "/overview");
  const d = navFor({ perms, owner: true, ent: null, override: "doctor" });
  assert.equal(d.role, "doctor");
  assert.equal(homeFor({ perms, owner: true, ent: null, override: "doctor" }), "/my-day");
  assert.equal(navFor({ perms: ["reports:view"], owner: true, ent: null, override: "doctor" }).role, "owner"); // override not allowed
});

test("nurse and trimmed owner fall back like the old sidebar", () => {
  assert.equal(navFor({ perms: ["nursing:view", "clinical:edit", "queue:view"], owner: false, ent: null }).role, "nurse");
  assert.equal(navFor({ perms: ["patients:view"], owner: true, ent: null }).role, "reception");
});

test("role home agrees with login landing for the same grants (planned hidden)", () => {
  for (const [perms, owner] of [[RECEPTION, false], [DOCTOR, false], [["clinical:edit", "patients:view"], false], [["nursing:view", "queue:view"], false], [["reports:view", "queue:view"], true], [["billing:view"], false]]) {
    assert.equal(homeFor({ perms, owner, ent: null }), landingPathFor(perms, owner), JSON.stringify(perms));
  }
});

test("Ctrl+K: 'stock' lists the permitted Stock screen, nothing without the grant", () => {
  const withP = jumpTargets({ perms: [...RECEPTION, "pharmacy:view"], owner: false, ent: null }, "stock");
  assert.deepEqual(withP.map((i) => i.href), ["/pharmacy"]);
  assert.deepEqual(jumpTargets({ perms: RECEPTION, owner: false, ent: null }, "stock"), []);
  assert.ok(jumpTargets({ perms: [...DOCTOR, "reports:view"], owner: true, ent: null }, "").some((i) => i.href === "/my-day")); // other context's screens
});

test("one registry: ClinicNav holds no nav arrays or badge fetchers", () => {
  const src = readFileSync(new URL("../../app/ClinicNav.tsx", import.meta.url), "utf8");
  for (const n of ["NAV_ITEMS", "NURSING_ITEMS", "OWNER_ITEMS", "DOCTOR_ITEMS", "queueQuery", "adminOrdersQuery", "doctorQueueQuery"]) assert.ok(!src.includes(n), n);
  assert.ok(Object.values(NAV).every((l) => l.every((i) => i.owner && i.href.startsWith("/"))));
});
