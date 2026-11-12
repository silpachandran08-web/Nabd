// FEF-048: shell-only service worker (ADR-12). Caches the offline page, the manifest icons and Next's content-hashed
// /_next/static files, nothing else: never an /api/ response, never another origin, never a page with data (navigations go
// to the network and fall back to the offline page). Registered by app/PwaBoot.tsx as /sw.js?v=<release>&ready=<text>:
// v names the cache (a new release drops the old one), ready is the localised "Token {token} ready" from the i18n catalogue.
const params = new URL(self.location.href).searchParams;
const VERSION = params.get("v") || "dev";
const READY = params.get("ready") || "Token {token} ready";
const CACHE = "nabd-shell-" + VERSION;
const OFFLINE = "/offline";
const PRECACHE = [OFFLINE, "/icons/icon-192.png", "/icons/icon-512.png"];
const MY_DAY = "/my-day";

/** "static" (cache first), "navigate" (network, offline page when it fails) or null: not handled, the browser goes to the network. */
function route(request) {
  if (request.method !== "GET") return null;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin || url.pathname.startsWith("/api/")) return null;
  if (url.pathname.startsWith("/_next/static/") || url.pathname.startsWith("/icons/")) return "static";
  return request.mode === "navigate" ? "navigate" : null;
}

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((c) => c.addAll(PRECACHE)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.filter((k) => k.startsWith("nabd-shell-") && k !== CACHE).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener("fetch", (event) => {
  const kind = route(event.request);
  if (kind === "static") {
    event.respondWith(caches.open(CACHE).then((c) => c.match(event.request).then((hit) => hit || fetch(event.request).then((res) => {
      if (res.ok && res.type === "basic") c.put(event.request, res.clone());
      return res;
    }))));
  } else if (kind === "navigate") {
    event.respondWith(fetch(event.request).catch(() => caches.match(OFFLINE).then((r) => r || Response.error())));
  }
});

// The payload is { kind: "patient_ready", token } and nothing else (no PHI); the tap always opens My Day, never a URL from the push.
self.addEventListener("push", (event) => {
  let data = {};
  try { data = event.data ? event.data.json() : {}; } catch { data = {}; }
  const token = Number.isInteger(data.token) ? String(data.token) : "";
  event.waitUntil(self.registration.showNotification(READY.replace("{token}", token), {
    tag: "patient-ready", renotify: true, icon: "/icons/icon-192.png", data: { url: MY_DAY },
  }));
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  event.waitUntil(self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((list) => {
    const open = list.find((c) => new URL(c.url).origin === self.location.origin);
    if (open) return (open.navigate ? open.navigate(MY_DAY) : Promise.resolve(open)).then((c) => (c || open).focus());
    return self.clients.openWindow(MY_DAY);
  }));
});
