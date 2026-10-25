# Runbook: Patient Access Edge (ACC-039)

Service `services/patient-access-edge` (Spring Boot, package `com.nabd.edge`). Own Postgres, own Valkey, own image, own CI
(`.github/workflows/patient-access-edge.yml`). Deploying it never touches Core.

## Configuration (environment)

| Variable | Meaning |
|---|---|
| `EDGE_DB_URL`, `EDGE_DB_USER` (`edge_app`), `EDGE_DB_PASSWORD` | Edge database; the app role is DML-only and must not own the tables |
| `VALKEY_URL` | Valkey of the cell (events, projection, rate limits) |
| `EDGE_REGION` | Cell code; streams read are `ev:<region>:comms` and `ev:<region>:scheduling` |
| `EDGE_CORE_URL`, `EDGE_CORE_TOKEN` | Core `/internal/v1` base URL and the Edge's service token (aud=internal, ACC-038) |
| `EDGE_COMMS_URL`, `EDGE_COMMS_TOKEN` | Comms Gateway base URL and token |
| `EDGE_BUTTON_KEY` | Base64, 32+ bytes, shared with the Comms Gateway; empty refuses every button tap |
| `PORT` (8080), `MANAGEMENT_PORT` (8081) | Public port serves `/healthz` only; management port serves `/actuator/health` and `/actuator/prometheus` |

## Provisioning and deploy

1. Create the database, the `edge_app` login role (DML only) and a migrator role with `BYPASSRLS` (needed by `edge_purge_expired()`,
   because FORCE RLS applies to owners). Needs external action in each cell.
2. Migrate: run the image once with `EDGE_FLYWAY=true` and the migrator role as `EDGE_DB_USER`/`EDGE_DB_PASSWORD`, then roll out
   with `edge_app`. Migrations: `V1` conversations and processed_messages, `V2` booking_holds, `V3` sweep function and grants.
3. Roll out the image. Core keeps serving: nothing here is coupled to a Core release.
4. Check `GET /healthz` (200) and `edge_inbound_total` increasing.

## Operating

* Inbound lag: stream group `patient-edge`; `XPENDING ev:<region>:comms patient-edge`. Failed entries retry every 5 s and are moved to
  `ev:<region>:dlq` after 5 attempts (`edge_event_dlq_total`).
* Valkey restart: the projection refills from Core by itself (one call per doctor-day); the consumer recreates its group; events
  published during the outage must be replayed from Core's outbox redrive (events.md section 8).
* Slot looks wrong: the projection lives at most 30 s; `scheduling.booking_policy.published.v1` drops a tenant's projection.
* Metrics and alerts: `infra/observability/patient-edge.rules.yml`, dashboard `infra/observability/dashboards/patient-edge.json`.
