// COM-025: the patient file's message preferences panel. Fakes only.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
const { toRequest, toggleCaregiver, caregiverHas, prefsErrorKey, CAREGIVER_CATEGORIES } = await import("../../app/(clinic)/patients/notificationPrefs.ts");

const base = {
  language: "hi", channel: "whatsapp",
  categories: { careReminders: true, recalls: true, feedback: true, reviews: false },
  quietHours: null, caregivers: [], eligibleCaregivers: [{ personRef: "son", firstName: "Arun", relation: "son" }], version: 2,
};

test("the PUT body is the whole set, without the read-only parts", () => {
  const body = toRequest(base);
  assert.deepEqual(Object.keys(body).sort(), ["caregivers", "categories", "channel", "language", "quietHours"]);
  assert.equal(body.categories.reviews, false);
});

test("a caregiver is added with the first category and dropped with the last", () => {
  let list = toggleCaregiver([], "son", "careReminders", true);
  assert.deepEqual(list, [{ personRef: "son", categories: ["careReminders"] }]);
  list = toggleCaregiver(list, "son", "recalls", true);
  assert.deepEqual(list[0].categories, ["careReminders", "recalls"]);
  assert.ok(caregiverHas(list, "son", "recalls"));
  list = toggleCaregiver(toggleCaregiver(list, "son", "recalls", false), "son", "careReminders", false);
  assert.deepEqual(list, []);
  assert.deepEqual([...CAREGIVER_CATEGORIES], ["careReminders", "recalls"], "never review or feedback requests");
});

test("save errors map to what the desk should know", () => {
  assert.equal(prefsErrorKey({ status: 422, problem: { code: "CAREGIVER_NOT_AUTHORISED" } }), "notAuthorised");
  assert.equal(prefsErrorKey({ status: 403 }), "forbidden");
  assert.equal(prefsErrorKey(new TypeError("fetch failed")), "generic");
});

test("view-only roles get text, not controls; every string exists in en and hi", () => {
  const src = readFileSync(new URL("../../app/(clinic)/patients/NotificationPrefs.tsx", import.meta.url), "utf8");
  const readOnly = src.slice(src.indexOf("if (!canEdit)"), src.indexOf("return (\n    <section className={styles.section} aria-label={t(\"title\")} aria-busy"));
  assert.ok(readOnly.length > 0);
  assert.doesNotMatch(readOnly, /<input|<select|<button/);
  const en = JSON.parse(readFileSync(new URL("../../../../packages/i18n/messages/en/common.json", import.meta.url), "utf8")).notifPrefs;
  const hi = JSON.parse(readFileSync(new URL("../../../../packages/i18n/messages/hi/common.json", import.meta.url), "utf8")).notifPrefs;
  const keys = (o, p = "") => Object.entries(o).flatMap(([k, v]) => (typeof v === "object" ? keys(v, `${p}${k}.`) : [`${p}${k}`]));
  assert.deepEqual(keys(hi).sort(), keys(en).sort());
  for (const k of src.matchAll(/t\("([a-zA-Z.]+)"\)/g)) assert.ok(keys(en).includes(k[1]), `missing ${k[1]}`);
});
