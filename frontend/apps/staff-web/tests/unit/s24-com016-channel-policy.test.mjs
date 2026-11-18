// COM-016: owner.integrations shows the WhatsApp channel exclusivity policy in plain words, in en and hi, behind the same gate as the
// nav item (owner with setup:view and the WhatsApp module); the page is no longer the bare "not built yet" placeholder.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { NAV, itemVisible } from "../../app/shell/navRegistry.ts";

const page = readFileSync(new URL("../../app/(clinic)/integrations/page.tsx", import.meta.url), "utf8");
const card = readFileSync(new URL("../../app/components/integrations/ChannelPolicy.tsx", import.meta.url), "utf8");
const en = JSON.parse(readFileSync(new URL("../../../../packages/i18n/messages/en/common.json", import.meta.url), "utf8")).channelPolicy;
const hi = JSON.parse(readFileSync(new URL("../../../../packages/i18n/messages/hi/common.json", import.meta.url), "utf8")).channelPolicy;
const flat = (o, p = "") => Object.entries(o).flatMap(([k, v]) => (typeof v === "object" ? flat(v, `${p}${k}.`) : [`${p}${k}`]));
const ent = (codes) => ({ modules: ["whatsapp_booking", "lab", "insurance", "patient_app"].map((code) => ({ code, enabled: codes.includes(code) })), killedFeatures: [], seatLimit: null });

test("the integrations page renders the policy inside the owner gate", () => {
  assert.match(page, /<PlannedScreen id="integrations">\s*<ChannelPolicy \/>\s*<\/PlannedScreen>/);
  assert.match(card, /useTranslations\("common\.channelPolicy"\)/);
  for (const k of ["pageTitle", "title", "lead"]) assert.match(card, new RegExp(`t\\("${k}"\\)`));
  assert.match(card, /t\(`points\.\$\{k\}`\)/);
});

test("a WhatsApp clinic's owner can open it; no grant or no channel module = absent", () => {
  const item = NAV.owner.find((i) => i.id === "integrations");
  assert.equal(itemVisible(item, ["setup:view"], ent(["whatsapp_booking"])), true);
  assert.equal(itemVisible(item, [], ent(["whatsapp_booking"])), false);
  assert.equal(itemVisible(item, ["setup:view"], ent([])), false);
  assert.equal(itemVisible(item, ["setup:view"], null), false);
});

test("the policy says it plainly, in en and hi, every point present", () => {
  assert.deepEqual(flat(hi).sort(), flat(en).sort());
  for (const k of ["onlyYours", "never", "guard", "discovery"]) assert.ok(en.points[k] && hi.points[k], k);
  assert.match(en.points.guard, /Let me connect you to the clinic/);
  assert.match(en.points.never, /No other clinic/);
  assert.match(en.points.discovery, /never offered in a chat with your clinic/);
  for (const v of Object.values(flat(hi)).map((k) => k.split(".").reduce((o, x) => o[x], hi))) assert.match(v, /[ऀ-ॿ]/, "Hindi text, not English");
});
