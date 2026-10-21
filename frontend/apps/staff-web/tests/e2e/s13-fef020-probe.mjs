// FEF-020 synthetic probe (also the shape the SLO probe can reuse): 100 deltas pushed by a mock Core travel through
// the real BFF events() proxy and a real EventSource (Node's, needs the flag) into the stream client; prints the
// event-to-callback latency p50/p95/max. Run: node --experimental-eventsource tests/e2e/s13-fef020-probe.mjs
import http from 'node:http';
import { createLiveStream } from '../../../../packages/api-client/sse/stream.ts';
import { events } from '../../app/lib/bffEvents.ts';

if (typeof EventSource === 'undefined') { console.log('SKIP: run with --experimental-eventsource'); process.exit(0); }
const SAMPLES = Number(process.env.SAMPLES ?? 100);
const open = new Set();
const core = http.createServer((rq, rs) => {
  rs.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' });
  rs.write('event: snapshot\nid: 0\ndata: {}\n\n'); open.add(rs); rq.on('close', () => open.delete(rs));
});
await new Promise((r) => core.listen(0, r));
const deps = { core: `http://127.0.0.1:${core.address().port}/v1`, fetch: (u, i) => fetch(u, i), origins: [], log: () => {} };
const bff = http.createServer(async (rq, rs) => {
  const ctl = new AbortController(); rs.on('close', () => ctl.abort());
  const res = await events(new Request(`http://127.0.0.1${rq.url}`, { headers: rq.headers, signal: ctl.signal }), deps);
  rs.writeHead(res.status, Object.fromEntries(res.headers)); 
  for await (const ch of res.body) rs.write(ch);
  rs.end();
});
await new Promise((r) => bff.listen(0, r));

const lat = []; let snap = false;
const stream = createLiveStream({
  url: `http://127.0.0.1:${bff.address().port}/api/events?topics=queue`,
  onMessage: (m) => { if (m.event === 'snapshot') snap = true; else lat.push(Date.now() - m.data.sent); },
  onStatus: () => {}, onFallbackPoll: () => {},
  deps: { EventSource, locks: null, BroadcastChannel, isHidden: () => false, onVisibility: () => () => {}, setTimeout, clearTimeout, setInterval, clearInterval, random: Math.random },
});
for (let i = 0; i < 100 && !snap; i++) await new Promise((r) => setTimeout(r, 50));
if (!snap) { console.log('FAIL no snapshot'); process.exit(1); }
for (let i = 1; i <= SAMPLES; i++) {
  const frame = `event: delta\nid: ${i}\ndata: ${JSON.stringify({ type: 'queue.entry.updated.v1', id: `q${i}`, v: 1, sent: Date.now() })}\n\n`;
  open.forEach((rs) => rs.write(frame));
  await new Promise((r) => setTimeout(r, 20));
}
await new Promise((r) => setTimeout(r, 300));
stream.stop(); open.forEach((rs) => rs.end()); bff.closeAllConnections(); core.closeAllConnections(); bff.close(); core.close();
lat.sort((a, b) => a - b);
const p = (q) => lat[Math.min(lat.length - 1, Math.ceil(q * lat.length) - 1)];
console.log(`samples=${lat.length} p50=${p(0.5)}ms p95=${p(0.95)}ms max=${lat.at(-1)}ms`);
const ok = lat.length === SAMPLES && p(0.95) < 2000;
console.log(ok ? 'PASS probe: p95 < 2000 ms with all samples delivered' : 'FAIL probe');
process.exit(ok ? 0 : 1);
