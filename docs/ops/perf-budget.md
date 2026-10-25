# Performance budget (OPS-039, FRD NFR-02)

- Budgets: `frontend/apps/staff-web/lighthouserc.json` (`nabdBudgets`): LCP < 3000 ms (median of 3), initial JS+CSS < 1536 KB gzip, per route: /login, /arrivals, /consult, /checkout/otc (the checkout root has no page), /my-day (solo Today).
- Gate: `.github/workflows/perf-budget.yml`. `bundle` runs on every frontend PR; `lighthouse` runs on the preview (called by `preview.yml`) with 3G throttling, 4x CPU slowdown, Moto G class UA. A breach fails the job and the PR comment lists route, metric, value, budget and the 3 largest chunks.
- Local: `pnpm exec next build && node scripts/perf-budget.mjs bundle` (from `frontend/apps/staff-web`).
- Production p95 LCP next to the budget (weekly): `node scripts/perf-budget.mjs rum < week.log`, where `week.log` is the last 7 days of Core log lines `client telemetry kind=vital metric=LCP route=... value=...` (OPS-028 sink). Needs external action: a scheduled job that pulls those lines from the log store and posts the table; none exists in this repo.
- Needs external action: a real PR run; `vars.PERF_LOGIN` (`email|pin` of a synthetic doctor from the preview seed).
