// FEF-003: UTF-8 safe JWT payload decode. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { decodeJwtPayload } from "../../app/lib/jwt.ts";

const tok = (claims, { pad = false } = {}) => {
  let p = Buffer.from(JSON.stringify(claims), "utf8").toString("base64url");
  if (pad) p += "=".repeat((4 - (p.length % 4)) % 4);
  return `h.${p}.s`;
};

for (const name of ["د. محمد", "डॉ. विक्रम", "ಡಾ. ರಾವ್", "డా. రెడ్డి", "Dr. Smith", "Dr 🩺 Ali"]) {
  test(`round-trips ${name}`, () => {
    const c = decodeJwtPayload(tok({ staffName: name, tenantName: name }));
    assert.equal(c.staffName, name);
    assert.equal(c.tenantName, name);
  });
}

test("unpadded and padded base64url both decode (every residue length)", () => {
  for (const n of ["a", "ab", "abc", "abcd"]) {
    assert.equal(decodeJwtPayload(tok({ n }))?.n, n);
    assert.equal(decodeJwtPayload(tok({ n }, { pad: true }))?.n, n);
  }
});

test("malformed tokens return null, never throw", () => {
  assert.equal(decodeJwtPayload("nope"), null);
  assert.equal(decodeJwtPayload("a.!!!.c"), null);
});
