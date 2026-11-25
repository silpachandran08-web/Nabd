// PLT-047: the owner's privacy-request queue: countdown chips, the query, the strings in both languages. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { countdown, loadProblem, queueQuery } from "../../app/(clinic)/compliance/privacyRules.ts";

const msgs = (loc) => JSON.parse(readFileSync(new URL(`../../../../packages/i18n/messages/${loc}/common.json`, import.meta.url), "utf8")).privacyRequests;
const NOW = Date.parse("2027-06-10T09:00:00Z");
const day = 24 * 3600 * 1000;
const item = (o) => ({ status: "received", dueAt: new Date(NOW + 20 * day).toISOString(), dueSoon: false, overdue: false, ...o });

test("a request inside its period is green with the days left, due soon is amber, overdue is red", () => {
  assert.deepEqual(countdown(item({}), NOW), { tone: "ok", key: "daysLeft", days: 20 });
  assert.deepEqual(countdown(item({ dueSoon: true, dueAt: new Date(NOW + 6.5 * day).toISOString() }), NOW), { tone: "warn", key: "dueSoon", days: 7 });
  assert.deepEqual(countdown(item({ overdue: true, dueAt: new Date(NOW - 2.5 * day).toISOString() }), NOW), { tone: "danger", key: "overdue", days: 2 });
});

test("overdue also follows the clock when the server flag is not set yet, and never shows zero days", () => {
  assert.equal(countdown(item({ dueAt: new Date(NOW - 1000).toISOString() }), NOW).tone, "danger");
  assert.equal(countdown(item({ dueAt: new Date(NOW - 1000).toISOString() }), NOW).days, 1);
  assert.equal(countdown(item({ dueAt: new Date(NOW + 1000).toISOString() }), NOW).days, 1);
});

test("a closed request shows its status, never a countdown, even past its date", () => {
  for (const status of ["completed", "rejected"]) {
    assert.deepEqual(countdown(item({ status, overdue: true, dueAt: new Date(NOW - 9 * day).toISOString() }), NOW), { tone: "neutral", key: "closed", days: 0 });
  }
});

test("the open filter is the server's open status; all sends none; type and cursor are added", () => {
  assert.equal(queueQuery("open", ""), "?status=open");
  assert.equal(queueQuery("all", ""), "");
  assert.equal(queueQuery("open", "erasure", "abc=="), "?status=open&type=erasure&cursor=abc%3D%3D");
  assert.equal(queueQuery("all", "access"), "?type=access");
});

test("a 403 is its own sentence, every other failure is the load error", () => {
  assert.equal(loadProblem(403), "forbidden");
  for (const s of [0, 401, 500]) assert.equal(loadProblem(s), "loadError");
});

test("every key the screen reads exists in English and Hindi, and Hindi has no English sentence left", () => {
  const en = msgs("en"), hi = msgs("hi");
  const keys = (o, p = "") => Object.entries(o).flatMap(([k, v]) => (typeof v === "object" ? keys(v, `${p}${k}.`) : [`${p}${k}`]));
  assert.deepEqual(keys(hi).sort(), keys(en).sort());
  for (const s of ["received", "verifying", "in_progress", "awaiting_notice", "completed", "rejected", "extended"]) assert.ok(en.status[s] && hi.status[s], s);
  for (const k of ["access", "correction", "erasure"]) assert.ok(en.type[k] && hi.type[k], k);
  for (const k of ["whatsapp", "web", "desk"]) assert.ok(en.channel[k], k);
  assert.notEqual(hi.title, en.title);
  assert.notEqual(hi.empty, en.empty);
});

test("the screen is mounted on the compliance page (gated by compliance:view there) and reads only the queue", () => {
  const page = readFileSync(new URL("../../app/(clinic)/compliance/page.tsx", import.meta.url), "utf8");
  assert.ok(page.includes("<PrivacyRequests />") && page.includes('permission="compliance:view"'));
  const src = readFileSync(new URL("../../app/(clinic)/compliance/PrivacyRequests.tsx", import.meta.url), "utf8");
  assert.ok(src.includes("/privacy/requests") && !src.includes("toLocale") && !src.includes("new Date().toISOString"), "clinic zone helpers only");
});
