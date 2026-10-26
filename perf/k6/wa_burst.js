// OPS-047.2 WhatsApp reminder burst: the runner injects WA_BURST (5,000) reminder sends (perf/run.sh wa_burst does it with
// perf/data/wa_burst.sql, WhatsApp provider = perf/wa-stub.mjs: 200 ms, 5% 5xx) and this profile only watches the drain:
// outbox pending events (actuator, from METRICS_URL) must reach 0 within DRAIN_MIN (10) minutes and the stub must have seen
// 0 duplicate sends (STUB_URL/stats). No load is generated against the API.
import http from 'k6/http';
import { sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const drain = new Trend('outbox_drain_seconds'), duplicates = new Counter('duplicate_sends');
export const options = { vus: 1, iterations: 1, thresholds: { outbox_drain_seconds: [`max<${parseInt(__ENV.DRAIN_MIN || '10', 10) * 60}`], duplicate_sends: ['count==0'] } };
const pending = (text) => (text.match(/^outbox_pending_events\{[^}]*\} (\S+)/gm) || []).reduce((s, l) => s + parseFloat(l.split(' ').pop()), 0);

export default function () {
  const t0 = Date.now(), limit = parseInt(__ENV.DRAIN_MIN || '10', 10) * 60 * 1000 + 60000;
  while (Date.now() - t0 < limit && pending(http.get(__ENV.METRICS_URL).body || '') > 0) sleep(5);
  drain.add((Date.now() - t0) / 1000);
  const stats = http.get(`${__ENV.STUB_URL}/stats`).json();
  duplicates.add(stats.duplicates);
}
export function handleSummary(data) {
  return { 'wa_burst-summary.json': JSON.stringify({ profile: 'wa_burst', drainSeconds: data.metrics.outbox_drain_seconds?.values?.max, duplicates: data.metrics.duplicate_sends?.values?.count, thresholds: Object.fromEntries(Object.entries(data.metrics).filter(([, m]) => m.thresholds).map(([k, m]) => [k, m.thresholds])) }, null, 2) };
}
