# NabdSloFastBurn (OPS-027)

Page, clinic hours 07:00-23:00 local only (out of hours it becomes a ticket). The SLO named in the `slo` label is burning its 28 day error budget at more than 14.4x over 1 h and 5 m: about 2% of the budget per hour, so the budget is gone in under 2 days.

## First 10 minutes
1. Open Grafana, folder Nabd, `Nabd SLOs and error budgets`: confirm the SLO, then `Nabd service health` for the same time range.
2. Find the cause by SLO:

| `slo` | Look at | Usual causes |
|---|---|---|
| staff-api-availability | 5xx ratio and latency panels; Loki `{app="core"} |= "ERROR"` | bad deploy, DB failover (503 from DbFailureAdvice), pool exhaustion |
| checkin-queue-latency | p99 panel, DB connections, slow queries | missing index after a migration, lock contention, noisy tenant |
| outbox-oldest-pending-30s | outbox panel, `relay_leader`, Valkey health | relay down or no leader, Valkey unreachable (see docs/architecture/events.md) |
| event-to-screen-*, reminders-in-window, delay-broadcast-60s | SSE / Comms panels (pending emitters) | stuck consumer group, provider outage, Valkey |

3. Recent deploy in the last hour? Roll back first, investigate after.
4. Open a Tempo trace from a slow or failing request (exemplar or Loki trace id) to see which service hop is slow.

## Escalate / close
- Not mitigated in 30 min: declare an incident, page the secondary.
- Resolve when the 5 m ratio is back under the threshold; the alert clears by itself. Write down in the incident which budget was spent.
- If `slo:release_frozen` is 1 for the service, feature releases for it stay frozen until the budget is back above 50% (policy, OPS-027).
