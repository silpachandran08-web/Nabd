# Runbook: capacity review before cohort B (OPS-060)

Compares four weeks of pilot telemetry with the DP section 6 capacity model and decides whether the cell can take cohort B
(10-15 more clinics) without hitting a pool, Valkey or SSE limit. Run it once the pilot has 4 full weeks of telemetry, and
again before every cohort after B. Bar: every resource has **2x headroom over the 4-week pilot peak**; anything below is an
action with an owner, ticketed before cohort B entry.

Code: `infra/observability/capacity/` (`model.yaml` = resources, queries, capacities, owners; `capacity_review.py` = pull,
report, dashboard). Dashboard: Grafana "Nabd capacity review (OPS-060, per cell)" (`infra/observability/dashboards/capacity-review.json`,
generated from `model.yaml`; CI fails when it is stale). The review and the dashboard read the same queries, so every number
in the report can be reproduced in Grafana: set the time range to the review window and read the legend's Max.

## What is measured
| Resource | Source | Capacity in the model |
|---|---|---|
| Postgres CPU, IOPS, connections | CloudWatch `AWS/RDS` | 100%; gp3 baseline 12,000 IOPS at >= 400 GB (verify in the cell); `max_connections` 200 |
| PgBouncer server connections, client wait | CloudWatch `Nabd/PgBouncer` (OPS-055 `pool-metrics.py`) | 24 + 4 per instance; 100 ms (wait alarm) |
| Hikari pool in use | Prometheus `hikaricp_connections_active` | live `hikaricp_connections_max` (default 20 per pod) |
| Valkey memory, engine CPU (ops) | CloudWatch `AWS/ElastiCache` | 100% |
| SSE connections per pod and in total | Prometheus `realtime_sse_connections` | 2000 per pod (`app.realtime.max-connections`); 2000 total = extraction trigger |
| Outbox lag | Prometheus `outbox_oldest_pending_age_seconds` | 30 s (alert) |
| Check-in rate (rush driver) | Prometheus `http_server_requests_seconds_count{uri="/v1/queue/check-in"}` | the rate the last green k6 rush sustained |

Headroom = capacity / observed peak. "Peak at A+B" scales the per-clinic peak linearly to pilot + cohort B: a conservative
upper bound (part of every pilot number is fixed overhead that does not grow with clinics). "Date to 70%" is the earlier of the
rollout plan (the clinic count at which the scaled peak passes 70%) and the trend of the daily peaks.

## Steps
1. **Pull** (from a host inside the cell that reaches Prometheus and has CloudWatch read in the cell's region; CloudWatch keeps
   5-minute data for 63 days, so pull within 9 weeks of the window end):
   ```
   PROM_TOKEN=<if the Prometheus needs one> python3 infra/observability/capacity/capacity_review.py pull \
     --from <window start> --to <window end> --prom https://prometheus.obs.cell.internal:9090 \
     --db-instance <rds instance id> --valkey-cluster <valkey replication group id> --region <cell region> --out samples.json
   ```
   Terraform outputs `postgres` and `valkey` name the ids. The samples hold daily maxima only (no tenant, patient or request data).
2. **Report**: `python3 infra/observability/capacity/capacity_review.py report samples.json --clinics <live pilot clinics> --cohort-b-date <entry date> --clinics-per-month <rollout pace after B> --out review/`.
   Exit 1 means there are actions. Check every capacity marked `(verify)` against the cell (RDS console: provisioned IOPS).
3. **Re-run the morning rush at A+B** in staging-in with the command the report prints (`PERF_TENANTS=<A+B> PEAK_RPS=<scaled check-ins/s> perf/run.sh rush --seed`;
   `run.sh` drives 2x `PEAK_RPS`). The gate is the OPS-047 rush gate: check-in p95 < 300 ms, board p95 < 200 ms, checkout p95 < 500 ms, DB CPU < 70%.
   Then report again with `--proven-checkin-rps <2 x PEAK_RPS of the green run>` so the check-in row has a capacity.
4. **Ticket the actions**: add `--ticket` (gh CLI, authenticated, label `capacity` must exist). One issue per action, owner from `model.yaml`.
   Cohort B does not start while an action is open, unless the owner records why the risk is accepted.
5. **Publish** `review/capacity-review.md` with the k6 result and the ticket links in the cohort B entry pack (OPS-050 exit review).

## Reading the trigger forecasts
- **Read replica (~100 clinics)**: the date the rollout plan reaches 100 clinics and the Postgres CPU projected at that size.
  If projected CPU at 100 clinics is above 70%, bring the replica forward (ticket it to `@nabd/db-owners`).
- **Realtime extraction (~2-3k connections)**: SSE connections per clinic from the pilot, the clinic count and date at which the
  whole cell holds 2000 and 3000 connections. Plan the extraction (ADR) a quarter before the 2000 date.

## When a resource is below the bar
- Postgres CPU or IOPS: find the top statements first (`pg_stat_statements`), then size up `db_instance_class` / IOPS.
- PgBouncer or Hikari: `docs/runbooks/data-tier.md` (pool budget must fit `max_connections`); raise `pgbouncer_app_pool_size` only if it does.
- Valkey: `valkey_node_type` (memory) or fewer stream entries kept (`app.realtime.retention-seconds`).
- SSE per pod: more Core replicas before raising `app.realtime.max-connections`.
- Outbox lag: `docs/runbooks/NabdConsumerLagging.md`.

## Needs external action
4 weeks of real pilot telemetry, the Grafana CloudWatch IAM read policy, a staging-in k6 run, and the GitHub `capacity` label.
Owner: platform team (`@nabd/platform-owners`).
