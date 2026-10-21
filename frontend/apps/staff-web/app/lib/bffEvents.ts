// FEF-020.4: GET /api/events -> Core GET /v1/events. Same cookie auth and refresh as every BFF call (proxy() in bff.ts);
// the body is streamed through untouched. EventSource cannot set Last-Event-ID on a new connection, so the client
// sends ?lastEventId= and it becomes the header here. The browser aborting must abort Core's stream (it counts
// streams per user, TOO_MANY_STREAMS), so the request signal rides on the upstream fetch.
import { defaultDeps, proxy, type Deps } from "./bff.ts";

export async function events(req: Request, deps: Deps = defaultDeps()): Promise<Response> {
  const u = new URL(req.url);
  const last = u.searchParams.get("lastEventId");
  u.searchParams.delete("lastEventId");
  const headers = new Headers(req.headers);
  if (last && !headers.has("last-event-id")) headers.set("last-event-id", last);
  const upstream: Deps = { ...deps, fetch: (url, init) => deps.fetch(url, { ...init, signal: req.signal }) };
  const res = await proxy(new Request(req.url, { method: "GET", headers }), `/events${u.search}`, upstream);
  if ((res.headers.get("content-type") ?? "").includes("text/event-stream")) {
    res.headers.set("cache-control", "no-cache, no-transform"); // no-transform keeps compression from buffering frames
    res.headers.set("x-accel-buffering", "no");
  }
  return res;
}
