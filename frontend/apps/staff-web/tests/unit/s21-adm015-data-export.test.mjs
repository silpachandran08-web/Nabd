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
