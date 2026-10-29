// FEF-029: route groups, boundaries, PHI-free boundary reports. `pnpm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { boundaryReport } from "../../app/lib/telemetryScrub.ts";

const APP = new URL("../../app/", import.meta.url).pathname;
const read = (p) => readFileSync(join(APP, p), "utf8");
const walk = (d) => readdirSync(d).flatMap((n) => (statSync(join(d, n)).isDirectory() ? (n === "node_modules" ? [] : walk(join(d, n))) : [join(d, n)]));

test("boundary report: class, route template and release only; no patient identifiers", () => {
  const err = new TypeError("Asha Rao 9812345678 MRN-0042 failed");
  const r = boundaryReport(err, "https://x.example/patients/123e4567-e89b-12d3-a456-426614174000/notes/42?name=Asha+Rao&phone=9812345678#top", "clinic", "a1b2c3d4e5f6");
  assert.equal(r.kind, "error");
  assert.equal(r.metric, "boundary.clinic.TypeError");
  assert.equal(r.route, "/patients/:id/notes/:id");
  assert.equal(r.release, "a1b2c3d4e5f6");
  assert.equal(r.message, "");
  const wire = JSON.stringify(r);
  for (const leak of ["Asha", "9812345678", "MRN-0042", "123e4567", "name=", "failed"]) assert.ok(!wire.includes(leak), leak);
});

test("boundary report: hostile release / group / error name are sanitised and bounded", () => {
  const e = new Error("x"); e.name = "Bad Name <script>";
  const r = boundaryReport(e, "/x", "cl inic!", "rel ease/../1");
  assert.match(r.metric, /^[A-Za-z0-9_.:-]{1,64}$/);
  assert.match(r.release, /^[A-Za-z0-9._-]+$/);
  assert.equal(boundaryReport("not an Error", "/x", "clinic", undefined).metric, "boundary.clinic.Error");
  assert.equal(boundaryReport(new Error("x"), "/x", "clinic", undefined).release, undefined);
});

test("every route group has a layout and an error boundary; clinic and platform answer unknown URLs inside their shell", () => {
  for (const g of ["(clinic)", "(auth)", "(owner)", "(platform)"]) {
    assert.ok(existsSync(join(APP, g, "layout.tsx")), `${g}/layout.tsx`);
    assert.ok(existsSync(join(APP, g, "error.tsx")), `${g}/error.tsx`);
    assert.ok(existsSync(join(APP, g, "not-found.tsx")), `${g}/not-found.tsx`);
  }
  assert.ok(existsSync(join(APP, "global-error.tsx")) && existsSync(join(APP, "not-found.tsx")));
  for (const p of ["(clinic)/[...slug]/page.tsx", "(platform)/platform/[...slug]/page.tsx", "(owner)/owner/[...slug]/page.tsx"]) assert.match(read(p), /notFound\(\)/, p);
  assert.match(read("global-error.tsx"), /<html/);
});

test("clinic shell is chosen by structure: only the (clinic) layout mounts nav, top bar and idle lock", () => {
  assert.match(read("(clinic)/layout.tsx"), /AppShell[\s\S]*IdleLockGuard|IdleLockGuard[\s\S]*AppShell/);
  const root = read("layout.tsx");
  assert.ok(!/AppShell|IdleLockGuard|LiveProvider/.test(root.replace(/\/\/.*|\/\*[\s\S]*?\*\//g, "")), "root layout carries no clinic shell");
  for (const g of ["(auth)", "(owner)", "(platform)"]) assert.ok(!/AppShell|ClinicNav|TopBar/.test(read(`${g}/layout.tsx`).replace(/\/\/.*/g, "")), g);
});

test("SHELL_SKIP_PREFIXES no longer exists anywhere in the app", () => {
  const hits = walk(APP).filter((f) => /\.(ts|tsx|mjs)$/.test(f) && readFileSync(f, "utf8").includes("SHELL_SKIP_PREFIXES"));
  assert.deepEqual(hits, []);
});

test("forced-throw routes exist per group and answer 404 unless NABD_QA_THROW=1", () => {
  for (const p of ["(clinic)/qa-throw", "(auth)/login/qa-throw", "(owner)/owner/qa-throw", "(platform)/platform/qa-throw"]) {
    const src = read(`${p}/page.tsx`);
    assert.match(src, /NABD_QA_THROW !== "1"\) notFound\(\)/, p);
  }
});

test("the home page sends a signed-in user to the landing page, others to /login (never via /login first)", () => {
  const src = read("HomeRedirect.tsx");
  assert.match(src, /landingPathFor/);
  assert.match(src, /router\.replace\(claims \? landingPathFor/);
  assert.ok(!/redirect\("\/login"\)/.test(read("page.tsx")));
});
