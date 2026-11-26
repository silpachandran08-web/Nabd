// PLT-047: the data package behind an OTP link is shown as a download, from Core's open answer only.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
const { patientApi } = await import("../../app/lib/patientApi.ts");

const TOKEN = Buffer.alloc(48, 9).toString("base64url");
const FILE = { fileName: "my-data.zip", contentBase64: Buffer.from("PK-test").toString("base64"), sha256: "ab".repeat(32) };
const json = (status, body) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
const api = (answer, seen = []) => patientApi(async (url, init) => { seen.push({ url: String(url), init }); return answer(); });

test("a data package in Core's open answer becomes the dataExport content, and the code goes in the body only", async () => {
  const seen = [];
  const r = await api(() => json(200, { fileUrl: null, mime: "application/zip", title: "Your data", packageCard: null, dataExport: FILE }), seen).coreLinkOpen(TOKEN, "123456");
  assert.deepEqual(r, { kind: "dataExport", file: FILE });
  assert.ok(seen[0].url.endsWith(`/v1/public/links/${TOKEN}/open`) && !seen[0].url.includes("123456"));
  assert.deepEqual(JSON.parse(seen[0].init.body), { otp: "123456" });
});

test("an answer without a package or card is still the unsupported file link; the package card is unchanged", async () => {
  assert.deepEqual(await api(() => json(200, { fileUrl: "x", mime: "application/pdf", title: "Report" })).coreLinkOpen(TOKEN, "123456"), { kind: "unsupported" });
  const card = { packageName: "P", status: "active", items: [], sessionsUsed: 0, sessionsTotal: 1, paid: 0, due: 0, saved: 0 };
  assert.equal((await api(() => json(200, { packageCard: card, dataExport: FILE })).coreLinkOpen(TOKEN, "123456")).kind, "package");
});

test("the link page renders the download view and keeps nothing: the file is decoded to a blob URL that is revoked", () => {
  const flow = readFileSync(new URL("../../app/components/LinkFlow.tsx", import.meta.url), "utf8");
  assert.ok(flow.includes('resolved.kind === "dataExport"'));
  const view = readFileSync(new URL("../../app/components/DataExportView.tsx", import.meta.url), "utf8");
  assert.ok(view.includes("createObjectURL") && view.includes("revokeObjectURL") && view.includes("download={file.fileName}"));
  assert.ok(!/localStorage|sessionStorage|document\.cookie/.test(view));
});

test("the strings exist in every language the page ships", () => {
  for (const loc of ["en", "hi", "kn", "te"]) {
    const m = JSON.parse(readFileSync(new URL(`../../../../packages/i18n/messages/${loc}/patient.json`, import.meta.url), "utf8"));
    for (const k of ["dxTitle", "dxHint", "dxDownload", "dxPrivate"]) assert.ok(typeof m[k] === "string" && m[k].length > 5, `${loc}.${k}`);
  }
  const en = JSON.parse(readFileSync(new URL("../../../../packages/i18n/messages/en/patient.json", import.meta.url), "utf8"));
  const hi = JSON.parse(readFileSync(new URL("../../../../packages/i18n/messages/hi/patient.json", import.meta.url), "utf8"));
  assert.notEqual(hi.dxDownload, en.dxDownload);
});
