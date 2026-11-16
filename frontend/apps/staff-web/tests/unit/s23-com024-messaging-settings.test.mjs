// COM-024: the Setup > WhatsApp messaging settings card (quiet hours, prayer windows). The model is pure; the wiring is checked
// on the source (form absent without setup:edit, loading/error states, draft in the setup store, strings in en and hi).
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { invalidReason, METHODS, toCoord } from "../../app/(clinic)/setup/messagingSettings.ts";

const card = readFileSync(new URL("../../app/(clinic)/setup/MessagingSettingsCard.tsx", import.meta.url), "utf8");
const tab = readFileSync(new URL("../../app/(clinic)/setup/whatsapp/page.tsx", import.meta.url), "utf8");
const en = JSON.parse(readFileSync(new URL("../../../../packages/i18n/messages/en/common.json", import.meta.url), "utf8")).messagingSettings;
const hi = JSON.parse(readFileSync(new URL("../../../../packages/i18n/messages/hi/common.json", import.meta.url), "utf8")).messagingSettings;

const ok = () => ({
  quietHours: { enabled: true, start: "21:00", end: "09:00" },
  prayerWindows: { enabled: true, method: "umm_al_qura", bufferMinutes: 20, fridayBlock: { start: "11:30", end: "13:30" }, latitude: 24.7136, longitude: 46.6753 },
});

test("model: the server's INVALID_WINDOW rules, checked before saving", () => {
  assert.equal(invalidReason(ok()), null);
  const s = ok(); s.quietHours.end = "21:00"; assert.equal(invalidReason(s), "sameQuiet");
  const f = ok(); f.prayerWindows.fridayBlock = { start: "13:30", end: "11:30" }; assert.equal(invalidReason(f), "friday");
  const b = ok(); b.prayerWindows.bufferMinutes = 61; assert.equal(invalidReason(b), "buffer");
  const c = ok(); c.prayerWindows.longitude = null; assert.equal(invalidReason(c), "coords");
  const r = ok(); r.prayerWindows.latitude = 91; assert.equal(invalidReason(r), "coords");
  const n = ok(); n.prayerWindows.latitude = null; n.prayerWindows.longitude = null; assert.equal(invalidReason(n), "needCoords");
  n.prayerWindows.enabled = false; assert.equal(invalidReason(n), null, "off without a location is fine (India)");
  assert.deepEqual([...METHODS], ["umm_al_qura", "qatar", "dubai", "mwl"]); // MessagingSettingsService.METHODS
  assert.equal(toCoord(""), null);
  assert.equal(toCoord("24.5"), 24.5);
});

test("card: endpoint, loading/error states, form absent without setup:edit, draft survives tab switches", () => {
  assert.match(card, /getter<MessagingSettings>\("\/setup\/messaging-settings"\)/);
  assert.match(card, /api\.put<MessagingSettings>\("\/setup\/messaging-settings"/);
  assert.match(card, /<Skeleton/);
  assert.match(card, /<ErrorState/);
  assert.match(card, /can\("setup:edit"\)/);
  assert.match(card, /!canEdit \? \(/, "read-only view instead of the form");
  assert.match(card, /useSetupState<Draft \| null>\("messagingSettingsDraft"/);
  assert.match(tab, /<MessagingSettingsCard \/>/);
});

test("strings: every key used exists in en and hi", () => {
  const flat = (o, p = "") => Object.entries(o).flatMap(([k, v]) => (typeof v === "object" ? flat(v, `${p}${k}.`) : [`${p}${k}`]));
  assert.deepEqual(flat(hi).sort(), flat(en).sort());
  for (const m of card.matchAll(/t\("([a-zA-Z.]+)"/g)) assert.ok(flat(en).includes(m[1]), `missing ${m[1]}`);
  for (const m of METHODS) assert.ok(en.method[m] && hi.method[m]);
  for (const k of ["sameQuiet", "friday", "buffer", "coords", "needCoords"]) assert.ok(en.invalid[k] && hi.invalid[k]);
});
