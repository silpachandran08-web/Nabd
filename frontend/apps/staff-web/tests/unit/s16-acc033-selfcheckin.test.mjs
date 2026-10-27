// ACC-033: the self check-in settings form rules and labels. Fakes only.
import { test } from "node:test";
import assert from "node:assert/strict";
import { check, formOf, outcomeLabel, channelLabel, needsDesk } from "../../app/components/selfcheckin/selfCheckinLogic.ts";

const base = { lat: "12.97", lng: "77.59", radius: "200", geofence: true, walkin: false, wa: "" };

test("an empty config gives a safe default form", () => {
  assert.deepEqual(formOf(null), { lat: "", lng: "", radius: "200", geofence: false, walkin: false, wa: "" });
});

test("a valid form becomes the request body", () => {
  const r = check({ ...base, wa: "+91 98765-43210" });
  assert.equal(r.ok, true);
  assert.deepEqual(r.body, { lat: 12.97, lng: 77.59, geofenceRequired: true, geofenceRadiusM: 200, walkinAllowed: false, whatsappNumber: "919876543210" });
});

test("each rule gives its own reason", () => {
  assert.match(check({ ...base, lng: "" }).error, /both latitude and longitude/);
  assert.match(check({ ...base, lat: "95" }).error, /between -90 and 90/);
  assert.match(check({ ...base, radius: "10" }).error, /20 to 5000/);
  assert.match(check({ ...base, lat: "", lng: "" }).error, /Set the clinic's location/);
  assert.match(check({ ...base, wa: "12" }).error, /8 to 15 digits/);
});

test("geofence off with no location is allowed", () => {
  assert.equal(check({ ...base, lat: "", lng: "", geofence: false }).ok, true);
});

test("labels and the desk-attention flag", () => {
  assert.equal(outcomeLabel("duplicate"), "Already checked in");
  assert.equal(outcomeLabel("other"), "other");
  assert.equal(channelLabel("qr"), "Door QR");
  assert.equal(needsDesk("outside_geofence"), true);
  assert.equal(needsDesk("checked_in"), false);
});
