// ADM-015: the export drawer's request body, progress end states and error messages.
import { test } from "node:test";
import assert from "node:assert/strict";
const { DATASETS, FORMATS, STEP_UP_TARGET, startBody, finished, size, startError } = await import("../../app/lib/dataExport.ts");

test("the body names the scope, the picked datasets and the format; the step-up target is fixed", () => {
  assert.deepEqual(startBody(["patients", "files"], "fhir_ndjson"), { scope: "full_tenant", datasets: ["patients", "files"], format: "fhir_ndjson" });
  assert.equal(STEP_UP_TARGET, "data-export");
  assert.deepEqual(DATASETS.map((d) => d.key), ["patients", "appointments", "visits", "prescriptions", "results", "invoices", "consents", "audit", "files"]);
  assert.deepEqual(FORMATS.map((f) => f.value), ["xlsx", "csv", "fhir_ndjson", "pdf_per_patient"]);
});

test("polling stops on done or failed only", () => {
  assert.equal(finished("done"), true);
  assert.equal(finished("failed"), true);
  assert.equal(finished("running"), false);
  assert.equal(finished(undefined), false);
});

test("sizes and errors read as the owner needs them", () => {
  assert.equal(size(512), "512 bytes");
  assert.equal(size(2 * 1024 * 1024), "2.0 MB");
  assert.match(startError({ status: 409, code: "EXPORT_RUNNING" }), /already running/);
  assert.match(startError({ status: 403, code: "STEP_UP_REQUIRED" }), /authenticator code/);
  assert.match(startError({ status: 403 }), /Only the clinic owner/);
  assert.match(startError(new TypeError("x")), /No export was started/);
});

test("T1-BUG-2: the drawer lives outside SetupTabBody, so the refresh after a start does not unmount it", async () => {
  const { readFileSync } = await import("node:fs");
  const src = readFileSync(new URL("../../app/(clinic)/setup/data/page.tsx", import.meta.url), "utf8");
  assert.ok(src.indexOf("<ExportDrawer") > src.indexOf("</SetupTabBody>"), "ExportDrawer is rendered after </SetupTabBody>");
});

test("T1-BUG-3 UI: Export data is absent unless the session is the clinic Owner and holds setup:export", async () => {
  const { readFileSync } = await import("node:fs");
  const src = readFileSync(new URL("../../app/(clinic)/setup/data/page.tsx", import.meta.url), "utf8");
  assert.match(src, /const canExport = useHasMounted\(\) && isOwner\(\) && can\("setup:export"\)/);
  assert.match(src, /\{canExport && \(/);
  assert.ok(!/can\("setup:export"\) && \(/.test(src), "no button gated on the permission alone");
  assert.ok(!/disabled=\{!canExport/.test(src), "absent, not disabled");
  // The hidden case: a non-owner token holding setup:export (a custom "Practice Manager" role) is not the Owner.
  const store = new Map();
  globalThis.window = {};
  globalThis.localStorage = { getItem: (k) => store.get(k) ?? null, setItem: (k, v) => store.set(k, v), removeItem: (k) => store.delete(k) };
  const b64u = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
  const token = (claims) => `${b64u({ alg: "RS256" })}.${b64u({ sub: "s1", exp: Math.floor(Date.now() / 1000) + 900, ...claims })}.sig`;
  const { isOwner, getPermissions } = await import("../../app/lib/session.ts");
  const can = (p) => getPermissions().includes(p); // = lib/permissions can() (that file is not loadable by node --test)
  store.set("nabd_access_token", token({ permissions: ["setup:view", "setup:export"], owner: false }));
  assert.equal(can("setup:export"), true);
  assert.equal(isOwner(), false, "manager with setup:export: no export control");
  store.set("nabd_access_token", token({ permissions: ["setup:view", "setup:export"], owner: true }));
  assert.equal(isOwner() && can("setup:export"), true, "the Owner sees it");
  delete globalThis.window; delete globalThis.localStorage;
});
