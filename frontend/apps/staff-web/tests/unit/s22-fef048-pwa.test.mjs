// FEF-048: installable manifest + icons, the shell-only service worker (run in a vm against fake Cache Storage), the push
// notification it shows, CSP for the worker, the offline page strings (en + hi), push helpers and sign-out removal.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { buildCsp } from "../../app/security/csp.ts";
import { PUSH_SUB_KEY, dropPushSubscription, pushSupport, swUrl, vapidKeyBytes } from "../../app/lib/push.ts";

const app = new URL("../../", import.meta.url).pathname;
const msgDir = new URL("../../../../packages/i18n/messages/", import.meta.url).pathname;
const ORIGIN = "https://staff.nabd.test";

const pngSize = (file) => { const b = readFileSync(`${app}public${file}`); assert.equal(b.toString("ascii", 1, 4), "PNG", file); return [b.readUInt32BE(16), b.readUInt32BE(20)]; };

test("manifest: installable (name, standalone, start_url) with 192, 512 and maskable PNG icons that exist at those sizes", () => {
  const m = JSON.parse(readFileSync(`${app}app/manifest.webmanifest`, "utf8"));
  assert.equal(m.name, "Nabd");
  assert.equal(m.display, "standalone");
  assert.equal(m.start_url, "/");
  const by = (purpose, size) => m.icons.find((i) => i.purpose === purpose && i.sizes === size);
  for (const [purpose, size] of [["any", "192x192"], ["any", "512x512"], ["maskable", "512x512"]]) {
    const icon = by(purpose, size);
    assert.ok(icon, `${purpose} ${size}`);
    assert.equal(icon.type, "image/png");
    assert.deepEqual(pngSize(icon.src), size.split("x").map(Number));
  }
  assert.deepEqual(pngSize("/icons/apple-touch-icon.png"), [180, 180]);
  const layout = readFileSync(`${app}app/layout.tsx`, "utf8");
  assert.ok(layout.includes("appleWebApp: { capable: true") && layout.includes("<PwaBoot />"), "iOS Home Screen meta and the worker registration");
});

/** A same-origin browser response (Response.type "basic"; Node's own Response says "default"). */
const basic = (r) => Object.defineProperty(r, "type", { value: "basic" });

/** Loads public/sw.js in a sandbox with fake caches/fetch/clients; returns the handlers and the cache contents. */
function loadWorker(query = "v=r1&ready=Token+%7Btoken%7D+ready", network = (url) => basic(new Response(`body of ${url}`, { status: 200 }))) {
  const stores = new Map();
  const store = (name) => {
    if (!stores.has(name)) stores.set(name, new Map());
    const m = stores.get(name);
    return {
      match: async (req) => m.get(typeof req === "string" ? new URL(req, ORIGIN).href : req.url)?.clone(),
      put: async (req, res) => { m.set(typeof req === "string" ? new URL(req, ORIGIN).href : req.url, res); },
      addAll: async (urls) => { for (const u of urls) m.set(new URL(u, ORIGIN).href, await network(new URL(u, ORIGIN).href)); },
    };
  };
  const handlers = {};
  const shown = [];
  const opened = [];
  const fetched = [];
  const self = {
    location: new URL(`/sw.js?${query}`, ORIGIN),
    addEventListener: (type, fn) => { handlers[type] = fn; },
    skipWaiting: async () => {},
    clients: { claim: async () => {}, matchAll: async () => [], openWindow: async (u) => { opened.push(u); } },
    registration: { showNotification: async (title, opts) => { shown.push({ title, opts }); } },
  };
  const caches = {
    open: async (n) => store(n),
    keys: async () => [...stores.keys()],
    delete: async (n) => stores.delete(n),
    match: async (req) => { for (const n of stores.keys()) { const r = await store(n).match(req); if (r) return r; } return undefined; },
  };
  const fetch = async (req) => { const url = typeof req === "string" ? req : req.url; fetched.push(url); return network(url); };
  vm.runInNewContext(readFileSync(`${app}public/sw.js`, "utf8"), { self, caches, fetch, URL, Response, Promise, Number, String });
  const fire = async (type, event) => { const waits = []; handlers[type]({ ...event, waitUntil: (p) => waits.push(p), respondWith: (p) => waits.push(event.responded = p) }); await Promise.all(waits); return event; };
  const request = (path, { method = "GET", mode = "cors" } = {}) => ({ url: new URL(path, ORIGIN).href, method, mode });
  return { stores, shown, opened, fetched, fire, request };
}

test("worker cache after a day of use: only the offline shell, icons and hashed /_next/static files; 0 /api/ responses, nothing cross-origin", async () => {
  const w = loadWorker();
  await w.fire("install", {});
  const day = [
    w.request("/_next/static/chunks/app-1a2b.js"), w.request("/_next/static/css/9f8e.css"),
    w.request("/my-day", { mode: "navigate" }), w.request("/arrivals?tab=waiting", { mode: "navigate" }),
    w.request("/api/v1/queue?doctorId=x"), w.request("/api/events?topics=queue"), w.request("/api/v1/me/push-subscriptions", { method: "POST" }),
    w.request("https://nabd-api.example/v1/patients/123"), w.request("/patients/123"), w.request("/icons/icon-192.png"),
  ];
  for (let i = 0; i < 50; i++) for (const r of day) { const e = await w.fire("fetch", { request: r }); if (e.responded) await e.responded; }
  const keys = [...w.stores.values()].flatMap((m) => [...m.keys()]);
  assert.equal(w.stores.size, 1);
  assert.equal(keys.filter((k) => k.includes("/api/")).length, 0);
  for (const k of keys) assert.match(k, /^https:\/\/staff\.nabd\.test\/(offline$|icons\/|_next\/static\/)/, k);
  assert.ok(keys.includes(`${ORIGIN}/_next/static/chunks/app-1a2b.js`) && keys.includes(`${ORIGIN}/offline`), keys.join());
});

test("worker: API, other origins, non-GET and non-navigation data requests are never answered by the worker (network only)", async () => {
  const w = loadWorker();
  for (const r of [w.request("/api/v1/queue"), w.request("/api/v1/x", { method: "POST" }), w.request("https://other.example/a.js"), w.request("/patients/1")]) {
    assert.equal((await w.fire("fetch", { request: r })).responded, undefined, r.url);
  }
});

test("worker offline: a failed navigation shows the cached offline page; a new release drops the old cache", async () => {
  let online = true;
  const w = loadWorker("v=r1", (url) => (online ? new Response(`page ${new URL(url).pathname}`) : Promise.reject(new TypeError("offline"))));
  await w.fire("install", {});
  online = false;
  const e = await w.fire("fetch", { request: w.request("/my-day", { mode: "navigate" }) });
  assert.equal(await (await e.responded).text(), "page /offline");
  w.stores.set("nabd-shell-r0", new Map());
  w.stores.set("other-app", new Map());
  await w.fire("activate", {});
  assert.deepEqual([...w.stores.keys()].sort(), ["nabd-shell-r1", "other-app"]);
});

test("push: 'Token 14 ready' in the UI language from the payload's token only; extra fields never shown; tap opens My Day", async () => {
  const hi = loadWorker(`v=r1&ready=${encodeURIComponent("टोकन {token} तैयार")}`);
  await hi.fire("push", { data: { json: () => ({ kind: "patient_ready", token: 14, url: "https://evil.example", name: "Asha Rao" }) } });
  assert.equal(hi.shown[0].title, "टोकन 14 तैयार");
  assert.equal(JSON.stringify(hi.shown[0].opts.data), JSON.stringify({ url: "/my-day" }));
  assert.ok(!JSON.stringify(hi.shown[0]).includes("Asha"));
  await hi.fire("notificationclick", { notification: { close() {} } });
  assert.deepEqual(hi.opened, ["/my-day"]);
  const en = loadWorker();
  await en.fire("push", { data: { json: () => { throw new Error("not json"); } } });
  assert.equal(en.shown[0].title, "Token  ready", "a malformed push still shows a (generic) notification, as userVisibleOnly requires");
});

test("CSP allows the same-origin worker and manifest", () => {
  const csp = buildCsp("n").split("; ");
  assert.ok(csp.includes("worker-src 'self'") && csp.includes("manifest-src 'self'"));
});

test("offline page and push strings exist in en and hi with the same placeholders; the offline page says payments cannot be taken", () => {
  const pwa = (loc) => JSON.parse(readFileSync(`${msgDir}${loc}/shell.json`, "utf8")).pwa;
  const en = pwa("en"), hi = pwa("hi");
  assert.deepEqual(Object.keys(hi).sort(), Object.keys(en).sort());
  for (const k of Object.keys(en)) assert.equal((hi[k].match(/\{\w+\}/g) ?? []).join(), (en[k].match(/\{\w+\}/g) ?? []).join(), k);
  assert.match(en.offlinePayments, /Payments cannot be taken/);
  assert.match(en.offlineDrafts, /drafts|notes and vitals/);
  const page = readFileSync(`${app}app/offline/page.tsx`, "utf8");
  for (const k of ["offlineTitle", "offlineDrafts", "offlinePayments", "offlineRetry"]) assert.ok(page.includes(`t("${k}")`), k);
});

test("push helpers: worker URL, VAPID key bytes, support detection", () => {
  assert.equal(swUrl("abc123", "Token {token} ready"), "/sw.js?v=abc123&ready=Token+%7Btoken%7D+ready");
  const key = Buffer.from(Uint8Array.from({ length: 65 }, (_, i) => i)).toString("base64url");
  assert.deepEqual([...vapidKeyBytes(key)], Array.from({ length: 65 }, (_, i) => i));
  const env = { serviceWorker: true, pushManager: true, notification: true, vapidKey: "k" };
  assert.equal(pushSupport(env), "supported");
  assert.equal(pushSupport({ ...env, pushManager: false }), "unsupported", "iOS Safari outside the installed Home Screen app");
  assert.equal(pushSupport({ ...env, vapidKey: undefined }), "not-configured");
});

test("sign-out removes this device's subscription at Core before the logout call, and forgets it locally", async () => {
  const mem = new Map([[PUSH_SUB_KEY, "sub-1"]]);
  globalThis.localStorage = { getItem: (k) => mem.get(k) ?? null, removeItem: (k) => mem.delete(k), setItem: (k, v) => mem.set(k, v) };
  const removed = [];
  await dropPushSubscription(async (id) => { removed.push(id); throw new Error("network"); });
  assert.deepEqual(removed, ["sub-1"]);
  assert.equal(mem.has(PUSH_SUB_KEY), false, "forgotten even when the server call failed");
  await dropPushSubscription(async (id) => removed.push(id));
  assert.deepEqual(removed, ["sub-1"], "nothing stored: no call");
  delete globalThis.localStorage;
  const session = readFileSync(`${app}app/lib/session.ts`, "utf8");
  assert.ok(session.indexOf("dropPushSubscription(") < session.indexOf("/auth/logout"), "before the token is revoked");
  const toggle = readFileSync(`${app}app/components/pwa/PushToggle.tsx`, "utf8");
  assert.ok(toggle.includes("if (!doctor) return null"), "absent for non-doctors");
  assert.ok(readFileSync(`${app}app/(clinic)/account/page.tsx`, "utf8").includes("<PushToggle />"));
});
