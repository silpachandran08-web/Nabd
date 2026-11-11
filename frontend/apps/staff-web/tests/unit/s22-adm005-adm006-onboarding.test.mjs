// ADM-005/ADM-006: the onboarding console (funnel, stuck list, white-glove queue) and the wizard's white-glove booking card.
// Models are pure; the wiring is checked on the source (controls absent without the permission, states present). Synthetic data only.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { barPct, COVERABLE, NEXT_ACTION, isOpen as queueOpen } from "../../app/(platform)/platform/provisioning/activation/activation.ts";
import { isOpen, slotsToIso, WHITE_GLOVE_STEP_UP } from "../../app/components/setup/whiteGlove.ts";

const page = readFileSync(new URL("../../app/(platform)/platform/provisioning/activation/page.tsx", import.meta.url), "utf8");
const card = readFileSync(new URL("../../app/components/setup/WhiteGloveCard.tsx", import.meta.url), "utf8");
const wizard = readFileSync(new URL("../../app/(clinic)/setup/wizard/page.tsx", import.meta.url), "utf8");
const en = JSON.parse(readFileSync(new URL("../../../../packages/i18n/messages/en/billing.json", import.meta.url), "utf8")).whiteGlove;
const hi = JSON.parse(readFileSync(new URL("../../../../packages/i18n/messages/hi/billing.json", import.meta.url), "utf8")).whiteGlove;

test("funnel model: bars are a share of the cohort's sign-ups, capped, empty cohort is 0", () => {
  assert.equal(barPct(20, 40), 50);
  assert.equal(barPct(41, 40), 100);
  assert.equal(barPct(3, 0), 0);
});

test("queue model: one forward action per open status, cancel on open ones, DPA and go-live never coverable", () => {
  assert.equal(NEXT_ACTION.requested.toStatus, "scheduled");
  assert.equal(NEXT_ACTION.scheduled.toStatus, "in_progress");
  assert.equal(NEXT_ACTION.in_progress.toStatus, "done");
  assert.equal(NEXT_ACTION.done, undefined);
  assert.ok(queueOpen("scheduled") && !queueOpen("done") && !queueOpen("cancelled"));
  assert.ok(!COVERABLE.includes("dpa") && !COVERABLE.includes("go_live"));
});

test("booking model: step-up target equals the server's; 1-3 future slots, past or none refused", () => {
  assert.equal(WHITE_GLOVE_STEP_UP, "white-glove:book"); // WhiteGloveService.STEP_UP_TARGET
  const now = new Date("2026-11-11T08:00:00Z");
  assert.deepEqual(slotsToIso(["2026-11-12T10:00:00Z", "", " "], now), ["2026-11-12T10:00:00.000Z"]);
  assert.equal(slotsToIso(["", ""], now), null);
  assert.equal(slotsToIso(["2026-11-10T10:00:00Z"], now), null);
  assert.equal(slotsToIso(["2026-11-12T10:00:00Z", "2026-11-13T10:00:00Z", "2026-11-14T10:00:00Z", "2026-11-15T10:00:00Z"], now), null);
  assert.ok(isOpen({ status: "requested" }) && !isOpen({ status: "done" }) && !isOpen(null));
});

test("console: endpoints, loading/empty/error states, queue actions absent without onboarding:edit, no access before the profile answers", () => {
  for (const p of ["/platform/activation/funnel?", "/platform/activation/stuck?hours=", "/platform/white-glove"]) assert.ok(page.includes(p), p);
  assert.match(page, /const canEdit = access\.ready && !!access\.profile\?\.permissions\.includes\("onboarding:edit"\)/);
  assert.match(page, /\{canEdit && isOpen\(q\.status\) && \(/);
  assert.equal((page.match(/<Skeleton/g) || []).length, 3);
  assert.equal((page.match(/<EmptyState/g) || []).length, 3);
  assert.equal((page.match(/<ErrorState/g) || []).length, 3);
  assert.match(page, /RoleDenied/);
});

test("wizard card: in the wizard header, booking form only for the owner with setup:edit, bound step-up header, en and hi strings", () => {
  assert.match(wizard, /<WhiteGloveCard \/>/);
  assert.match(card, /const canBook = mounted && isOwner\(\) && can\("setup:edit"\)/);
  assert.match(card, /\{canBook \? \(/);
  assert.match(card, /requestStepUpToken\(code, WHITE_GLOVE_STEP_UP\)/);
  assert.match(card, /"X-Step-Up-Token": su\.token/);
  assert.match(card, /<Skeleton/);
  assert.match(card, /<ErrorState/);
  const keys = (o, p = "") => Object.entries(o).flatMap(([k, v]) => (typeof v === "object" ? keys(v, `${p}${k}.`) : [`${p}${k}`]));
  assert.deepEqual(keys(hi).sort(), keys(en).sort());
  for (const k of keys(en)) assert.ok(card.includes(`"${k.split(".")[0]}`) || card.includes("`status."), k);
});
