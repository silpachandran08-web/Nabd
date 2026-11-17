// CLN-047: consent-first step, compare picks, client compression loop, URL freshness.
import { test } from "node:test";
import assert from "node:assert/strict";
const { fitSize, compressWith, addPhotoStep, togglePick, comparePair, urlFresh, PHOTO_MAX_BYTES } = await import("../../app/lib/dermPhotos.ts");

test("without photo consent, Add photo opens the consent capture before the camera", () => {
  assert.equal(addPhotoStep(undefined, "v1"), "consent");
  assert.equal(addPhotoStep({ consent: null }, "v1"), "consent");
  const consent = { consentId: "c", scope: "record_only", status: "active", signerKind: "patient", recordedAt: "2026-11-01T00:00:00Z" };
  assert.equal(addPhotoStep({ consent }, null), "visit");
  assert.equal(addPhotoStep({ consent }, "v1"), "camera");
});

test("a 12 MP photo is sized to a 2560 px long edge; small ones are never upscaled", () => {
  assert.deepEqual(fitSize(4000, 3000), { width: 2560, height: 1920 });
  assert.deepEqual(fitSize(3000, 4000), { width: 1920, height: 2560 });
  assert.deepEqual(fitSize(800, 600), { width: 800, height: 600 });
});

test("compression lowers quality, then size, until <= 1.5 MB", async () => {
  const calls = [];
  // fake encoder: bytes ~ pixels x quality
  const encode = async (w, h, q) => { calls.push([w, h, q]); return { size: Math.round(w * h * q * 0.6) }; };
  const out = await compressWith(4000, 3000, encode);
  assert.ok(out.size <= PHOTO_MAX_BYTES);
  assert.deepEqual(calls[0], [2560, 1920, 0.85]);
  assert.ok(calls.some(([w]) => w < 2560), "it shrank after the quality steps");
  const small = await compressWith(640, 480, encode);
  assert.ok(small.size <= PHOTO_MAX_BYTES);
});

test("any two dates can be compared: a third pick replaces the oldest; the pair is in date order", () => {
  let p = togglePick([], "a"); p = togglePick(p, "b"); p = togglePick(p, "c");
  assert.deepEqual(p, ["b", "c"]);
  assert.deepEqual(togglePick(p, "b"), ["c"]);
  const photos = [{ id: "c", takenAt: "2026-03-01T00:00:00Z" }, { id: "b", takenAt: "2026-01-01T00:00:00Z" }];
  assert.deepEqual(comparePair(photos, ["c", "b"]).map((x) => x.id), ["b", "c"]);
  assert.equal(comparePair(photos, ["c"]), null);
});

test("a signed URL close to its 5-minute expiry is not used", () => {
  const now = Date.parse("2026-11-17T10:00:00Z");
  assert.equal(urlFresh({ urlExpiresAt: "2026-11-17T10:05:00Z" }, now), true);
  assert.equal(urlFresh({ urlExpiresAt: "2026-11-17T10:00:10Z" }, now), false);
});
