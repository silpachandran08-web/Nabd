// S9 QA FEF-003: decodeJwtPayload + atob ban, independent of the engineer's tests.
import { test } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { decodeJwtPayload } from "../../app/lib/jwt.ts";

const fe = new URL("../../", import.meta.url).pathname;
const mk = (obj) => "h." + Buffer.from(JSON.stringify(obj), "utf8").toString("base64url") + ".s";

test("four required scripts decode exactly, both claims", () => {
  for (const [s, c] of [["د. محمد", "عيادة"], ["डॉ. विक्रम", "क्लिनिक"], ["ಡಾ. ರಾವ್", "ಕ್ಲಿನಿಕ್"], ["డా. రెడ్డి", "క్లినిక్"]]) {
    const r = decodeJwtPayload(mk({ staffName: s, tenantName: c, sub: "1" }));
    assert.equal(r.staffName, s); assert.equal(r.tenantName, c); assert.equal(r.sub, "1");
  }
});
test("emoji / astral, mixed bidi, ZWJ", () => {
  for (const s of ["Dr 👩‍⚕️ Ali", "Dr. محمد (MD)", "𝔘nicode"]) assert.equal(decodeJwtPayload(mk({ n: s })).n, s);
});
test("padding: every unpadded length decodes without throwing, even when payload is padded with '='", () => {
  for (let i = 0; i < 8; i++) {
    const o = { n: "x".repeat(i) };
    assert.deepEqual(decodeJwtPayload(mk(o)), o);
    const p = Buffer.from(JSON.stringify(o)).toString("base64url");
    assert.deepEqual(decodeJwtPayload("h." + p + "=".repeat((4 - (p.length % 4)) % 4) + ".s"), o);
  }
});
test("standard-base64 chars (+ /) and url chars (- _) both handled", () => {
  const o = { n: "ÿþ??>>" };
  const std = Buffer.from(JSON.stringify(o)).toString("base64");
  assert.deepEqual(decodeJwtPayload("h." + std + ".s"), o);
});
test("garbage never throws and returns null", () => {
  for (const t of ["", "x", "a.b", "a..c", "a.%%%.c", "a." + Buffer.from("not json").toString("base64url") + ".c", "a.e30.c".replace("e30", "")]) {
    assert.doesNotThrow(() => decodeJwtPayload(t));
  }
  assert.equal(decodeJwtPayload(""), null);
  assert.equal(decodeJwtPayload("a." + Buffer.from("not json").toString("base64url") + ".c"), null);
});

function walk(d, out = []) {
  for (const f of readdirSync(d)) {
    if (["node_modules", ".next", "tests"].includes(f)) continue;
    const p = join(d, f);
    statSync(p).isDirectory() ? walk(p, out) : /\.(tsx?|mjs|js|jsx)$/.test(f) && out.push(p);
  }
  return out;
}
test("grep: no atob( outside app/lib/jwt.ts in app code and scripts", () => {
  const hits = [...walk(fe + "app"), ...walk(fe + "scripts")].filter((p) => !p.endsWith("app/lib/jwt.ts") && /\batob\s*\(/.test(readFileSync(p, "utf8")));
  assert.deepEqual(hits, []);
});
test("grep: no JSON.parse(atob / window.atob / globalThis.atob anywhere in frontend sources", () => {
  const hits = walk(fe + "app").filter((p) => /(window|globalThis|self)\s*\.\s*atob|\batob\b/.test(readFileSync(p, "utf8")) && !p.endsWith("app/lib/jwt.ts"));
  assert.deepEqual(hits, []);
});
test("eslint: atob in a new file is an error; jwt.ts is clean", () => {
  const f = fe + "app/lib/__s9qa_tmp.ts";
  writeFileSync(f, "export const x = (s: string) => atob(s);\n");
  try {
    const bad = spawnSync("npx", ["eslint", "--no-warn-ignored", "app/lib/__s9qa_tmp.ts", "-f", "json"], { cwd: fe, encoding: "utf8" });
    const msgs = JSON.parse(bad.stdout)[0].messages.map((m) => m.ruleId);
    assert.ok(msgs.includes("no-restricted-globals"), bad.stdout);
  } finally { spawnSync("rm", ["-f", f]); }
  const ok = spawnSync("npx", ["eslint", "app/lib/jwt.ts"], { cwd: fe, encoding: "utf8" });
  assert.equal(ok.status, 0, ok.stdout);
});
