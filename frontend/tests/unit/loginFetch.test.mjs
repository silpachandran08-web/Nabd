// LIVE-01: sign-in requests time out (AbortController) and flag "slow" first. `npm run -s test:unit`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { loginFetch, LoginTimeoutError } from "../../app/lib/loginFetch.ts";

const realFetch = globalThis.fetch;
// A fetch that only settles when aborted, like a server that never answers.
const hanging = (_url, init) => new Promise((_, reject) => init.signal.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError"))));

test("a hanging request flags slow, then rejects with LoginTimeoutError", async (t) => {
  t.after(() => { globalThis.fetch = realFetch; });
  globalThis.fetch = hanging;
  let slow = 0;
  await assert.rejects(loginFetch("http://x/login", { method: "POST" }, () => slow++, 40, 10), LoginTimeoutError);
  assert.equal(slow, 1);
});

test("a fast response returns as-is and never flags slow", async (t) => {
  t.after(() => { globalThis.fetch = realFetch; });
  globalThis.fetch = async () => new Response("{}", { status: 401 });
  let slow = 0;
  const res = await loginFetch("http://x/login", {}, () => slow++, 40, 10);
  assert.equal(res.status, 401);
  await new Promise((r) => setTimeout(r, 50));
  assert.equal(slow, 0, "timers are cleared after the response");
});

test("a network error is passed through, not reported as a timeout", async (t) => {
  t.after(() => { globalThis.fetch = realFetch; });
  globalThis.fetch = async () => { throw new TypeError("Failed to fetch"); };
  await assert.rejects(loginFetch("http://x/login", {}, () => {}, 40, 10), TypeError);
});
