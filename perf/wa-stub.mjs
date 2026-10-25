// OPS-047.2 stand-in for the WhatsApp Cloud API during the burst: answers 200 after LATENCY_MS (200) and 5xx for FAIL_PCT (5) %
// of requests. Counts how often each message (to + template + body hash of the first send attempt id header) arrives as a SUCCESS
// twice: GET /stats -> {received, ok, failed, duplicates}. node perf/wa-stub.mjs [port]
import { createServer } from 'node:http';
import { createHash } from 'node:crypto';

export function makeStub({ latencyMs = 200, failPct = 5, random = Math.random } = {}) {
  const st = { received: 0, ok: 0, failed: 0, duplicates: 0 };
  const delivered = new Set();
  const server = createServer((req, res) => {
    if (req.method === 'GET' && req.url === '/stats') {
      res.setHeader('content-type', 'application/json');
      return res.end(JSON.stringify(st));
    }
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => setTimeout(() => {
      st.received++;
      if (random() * 100 < failPct) { st.failed++; res.statusCode = 503; return res.end('{}'); }
      const key = createHash('sha256').update(body).digest('hex'); // same payload accepted twice = a duplicate send
      if (delivered.has(key)) st.duplicates++;
      delivered.add(key);
      st.ok++;
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify({ messages: [{ id: 'wamid.' + key.slice(0, 16) }] }));
    }, latencyMs));
  });
  return { server, stats: st };
}

if (process.argv[1]?.endsWith('wa-stub.mjs')) {
  const { server } = makeStub({ latencyMs: +(process.env.LATENCY_MS ?? 200), failPct: +(process.env.FAIL_PCT ?? 5) });
  server.listen(+(process.argv[2] ?? 9099), () => console.log('wa-stub listening'));
}
