// PLT-029/030: upload validation, scan status wording, blob path.
import { test } from "node:test";
import assert from "node:assert/strict";
const { validateUpload, statusLabel, isTerminal, blobPath, defaultKind, MAX_UPLOAD_BYTES } = await import("../../app/lib/attachments.ts");

test("PDF, JPEG and PNG up to 20 MB pass; the boundary is inclusive", () => {
  for (const type of ["application/pdf", "image/jpeg", "image/png"]) assert.equal(validateUpload({ type, size: 1000 }), null);
  assert.equal(validateUpload({ type: "application/pdf", size: MAX_UPLOAD_BYTES }), null);
});
test("other types, empty and oversize files are refused with a line", () => {
  assert.match(validateUpload({ type: "application/x-msdownload", size: 10 }), /PDF, JPEG and PNG/);
  assert.match(validateUpload({ type: "image/jpeg", size: 0 }), /empty/);
  assert.match(validateUpload({ type: "image/jpeg", size: MAX_UPLOAD_BYTES + 1 }), /20 MB/);
});
test("blocked statuses read 'File blocked'; pending ones read 'Scanning'", () => {
  assert.equal(statusLabel("infected"), "File blocked"); assert.equal(statusLabel("rejected_type"), "File blocked");
  assert.equal(statusLabel("pending_scan"), "Scanning"); assert.equal(statusLabel("available"), "Ready");
  assert.equal(isTerminal("pending_scan"), false); assert.equal(isTerminal("infected"), true);
});
test("signed urls outside /v1 are reached by stepping out of the api base", () => {
  assert.equal(blobPath("/files-blob/object?m=PUT"), "/../files-blob/object?m=PUT");
  assert.equal(blobPath("https://store.example/x"), "https://store.example/x");
});
test("default kind follows the type", () => { assert.equal(defaultKind("application/pdf"), "outside_report"); assert.equal(defaultKind("image/png"), "photo"); });
