// CLN-020: pure parts of the device drug index (parse, rank-preserving word-prefix search, speed on 3000 rows).
import test from "node:test";
import assert from "node:assert/strict";
import { parseIndex, searchIndex } from "../../app/lib/drugIndex.ts";

const row = (id, display, generic, brand, strength = "500 mg", form = "capsule", sched = []) => [id, display, generic, brand, strength, form, sched];
const doc = { v: 1, master: 3, rev: "abc", items: [
  row("in-mox", "Amoxicillin 500 mg capsule (Mox)", "Amoxicillin", "Mox"),
  row("in-amoxil", "Amoxicillin 500 mg capsule (Amoxil)", "Amoxicillin", "Amoxil", "500 mg", "capsule", ["H"]),
  row("in-para", "Paracetamol 500 mg tablet (Parafast)", "Paracetamol", "Parafast", "500 mg", "tablet"),
] };

test("parseIndex keeps order and rev, refuses a wrong shape", () => {
  const p = parseIndex(doc);
  assert.equal(p.rev, "abc");
  assert.deepEqual(p.entries.map((e) => e.id), ["in-mox", "in-amoxil", "in-para"]);
  assert.equal(parseIndex({ items: [] }), null, "no rev");
  assert.equal(parseIndex({ rev: "x", items: [[1]] }), null);
  assert.equal(parseIndex(null), null);
});

test("'amo' returns the doctor's most used product first (index order is the ranking), generic first display", () => {
  const { entries } = parseIndex(doc);
  const hits = searchIndex(entries, "amo");
  assert.deepEqual(hits.map((h) => h.drugId), ["in-mox", "in-amoxil"]);
  assert.equal(hits[0].display, "Amoxicillin 500 mg capsule (Mox)");
  assert.equal(hits[0].source, "master");
  assert.equal(hits[1].prescriptionOnly, true);
  assert.equal(hits[0].prescriptionOnly, null);
});

test("every word must start a word; strength and form count; no word means no result", () => {
  const { entries } = parseIndex(doc);
  assert.deepEqual(searchIndex(entries, "para 500").map((h) => h.drugId), ["in-para"]);
  assert.deepEqual(searchIndex(entries, "amo tab"), []);
  assert.deepEqual(searchIndex(entries, "%%"), []);
  assert.deepEqual(searchIndex(entries, "zzz"), [], "not in the index: the caller falls back to the server");
  assert.equal(searchIndex(entries, "amo", 1).length, 1, "limit");
});

test("3000 rows: a keystroke is answered well under 100 ms", () => {
  const items = Array.from({ length: 3000 }, (_, i) => row(`id${i}`, `Gen${i} 500 mg tablet (Brand${i})`, `Gen${i}`, `Brand${i}`, "500 mg", "tablet"));
  const { entries } = parseIndex({ rev: "r", items });
  const t0 = performance.now();
  for (let k = 0; k < 20; k++) searchIndex(entries, "bra", 8);
  const per = (performance.now() - t0) / 20;
  assert.ok(per < 20, `search took ${per.toFixed(2)} ms`);
});
