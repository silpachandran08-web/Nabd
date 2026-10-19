// OPS-053: light constant load while a new digest rolls out. Run: k6 run -e BASE=https://<lb> rolling-deploy.k6.js
// while `kubectl -n staging-in set image deploy/core core=<image@digest>` is in flight. Fails the run at >= 0.1% errors.
import http from 'k6/http';
import { check } from 'k6';

export const options = {
  vus: 5,
  duration: __ENV.DURATION || '3m',
  thresholds: { http_req_failed: ['rate<0.001'] },
};

export default function () {
  // unauthenticated, public, DB-free endpoint: measures routing and drain, not business logic
  const r = http.get(`${__ENV.BASE}/v1/.well-known/jwks.json`);
  check(r, { 'status 200': (x) => x.status === 200 });
}
