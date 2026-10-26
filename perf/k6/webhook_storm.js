// OPS-047.2: the nightly name for the COM-001 webhook storm (200 rps incl. 20% duplicates, ack p95 < 100 ms). Same profile, one source.
import { summarize } from './lib.js';
export { default, options } from './whatsapp-webhook-storm.js';
export const handleSummary = (data) => summarize(data, 'webhook', 'webhook_storm');
