// FEF-027: document.title per route and the no-hand-rolled-dialog guard.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
const { routeTitle, pageName } = await import("../../app/lib/routeTitle.ts");

test("title is '<Page> · <Clinic> · Nabd'", () => {
  assert.equal(routeTitle("/patients", "Synthetic Clinic"), "Patients · Synthetic Clinic · Nabd");
  assert.equal(routeTitle("/billing", "Synthetic Clinic"), "Billing · Synthetic Clinic · Nabd");
  assert.equal(routeTitle("/checkout/abc", "Synthetic Clinic"), "Checkout · Synthetic Clinic · Nabd");
});
test("no clinic yet (signed out) and unknown routes degrade without empty parts", () => {
  assert.equal(routeTitle("/login", null), "Sign in · Nabd");
  assert.equal(routeTitle("/nowhere", "C"), "C · Nabd");
  assert.equal(routeTitle(null, undefined), "Nabd");
  assert.equal(pageName("/patientsx"), "Nabd"); // prefix match needs a path boundary
});

function walk(dir, out = []) {
  for (const f of readdirSync(dir)) {
    const p = join(dir, f);
    if (statSync(p).isDirectory()) walk(p, out); else if (/\.tsx$/.test(f)) out.push(p);
  }
  return out;
}
test("only ui/Dialog (and the idle-lock screen, the card picker) declares role=dialog", () => {
  const allowed = ["components/ui/Dialog.tsx", "IdleLockGuard.tsx", "components/checkout/RxLinkPicker.tsx"];
  const bad = walk(new URL("../../app", import.meta.url).pathname).filter((f) => /role="dialog"/.test(readFileSync(f, "utf8")) && !allowed.some((a) => f.endsWith(a)));
  assert.deepEqual(bad, []);
});
test("no window-level Escape handler is left in page code (Dialog owns Escape and answers only the top-most)", () => {
  // IdleLockGuard is the lock screen (no parent to protect); TopBar's is a dropdown menu, not a dialog.
  const bad = walk(new URL("../../app", import.meta.url).pathname).filter((f) => /window\.addEventListener\("keydown"/.test(readFileSync(f, "utf8")) && !/(IdleLockGuard|TopBar)\.tsx$/.test(f));
  assert.deepEqual(bad.map((f) => f.split("/app/")[1]), []);
});
test("every <th> in app code carries a scope or is a rowgroup/row header", () => {
  const bad = walk(new URL("../../app", import.meta.url).pathname).flatMap((f) => (readFileSync(f, "utf8").match(/<th(?:\s[^>]*)?>/g) ?? []).filter((t) => !/scope=/.test(t)).map((t) => `${f}: ${t}`));
  assert.deepEqual(bad, []);
});
