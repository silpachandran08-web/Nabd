# Comms Gateway runbook (COM-007)

`services/comms-gateway`: WhatsApp/SMS/email sending and the Meta webhook, with its own Postgres (roles `comms_migrator`, `comms_app`) and the Valkey consumer group `comms` on `ev:<region>:comms`. It calls Core only through `/internal/v1/**` (service token, `aud=internal`). Port 3809, health `/actuator/health/readiness` (db + streams). Board: Grafana "Comms Gateway". Alerts: `infra/observability/comms-gateway.rules.yml`.

Status of this document: written, not yet exercised in a game day (Needs external action: a prod-in cell and an on-call rota).

## Who does what when a dependency is down
| Down | What still works | Do |
|---|---|---|
| Core | Webhooks are persisted and acked by Comms (no call to Core on the ack path); the consumer keeps reading | Nothing to do in Comms. Inbound items are processed when Core returns; watch `CommsConsumerLagging` fall afterwards. |
| Comms | Core keeps working; notification requests wait in `ev:<region>:comms` (stream is persistent) | Restart/roll back the deployment; the backlog drains on its own (target: under 10 min). Check lag on the board. |
| Meta | Sends fail or time out; webhooks stop | See "Meta outage". |
| Valkey | Readiness goes down (`streams`), pods leave the load balancer; Core's outbox keeps the events | Restore Valkey; Core redrive (`docs/architecture/events.md` section 8) if entries were lost. |

## Meta outage
1. Confirm at Meta's status page and in `comms_send_total{outcome}` (a rise in `failed`/`timeout`). The `CommsSendSloFastBurn` page is the signal.
2. Do not restart pods: unsent requests stay in the stream/outbox and are retried; a restart changes nothing.
3. Tell the clinics' front desks (reminders are late; OTP falls back to the in-process sender in Core, COM-009).
4. When Meta recovers, watch the burn rate fall and consumer lag return to under 5 s. Messages past their `notAfter` are expired, not sent (COM-003 rules).

## Webhook secret rotation (Meta app secret)
1. Create the new value in the cell secret store (installation class, path `cell/<cell>/platform/meta-app-secret`, see `docs/runbooks/secret-rotation.md`); never paste it in chat or tickets.
2. In Meta's app dashboard set the new app secret; during the overlap Meta signs with one secret only, so do steps 1-3 inside one minute and accept that a few webhooks fail signature checks.
3. Restart the gateway pods one by one (secrets are read at start). Webhook signature failures return to 0 on the board.
4. Meta retries failed webhooks for 7 days; nothing is lost. Record the rotation in the change log.
The webhook signature verification itself is built by the webhook story; until it lands this step only rehearses the secret store part.

## Replay dead letters
1. `CommsDeadLetter` pages with the entry in `ev:<region>:dlq` (`consumer=comms`; fields: `eventId`, `entryId`, `attempts`, `lastError` = exception class only).
2. Find the cause in the logs by `eventId` (no PHI is logged), fix or roll back.
3. Replay: Core's `RelayRedriveCommand`/`DlqReplayCommand` pattern (events.md section 8); the consumer is idempotent on the CloudEvents id, so a replay of an applied event is a no-op.

## Database roles
`comms_migrator` runs Flyway (`services/comms-gateway/src/main/resources/db/migration`) in the migrate job only; `comms_app` serves, DML only, `statement_timeout 5s`, no connect to the Core database. CI proves it (`CommsGrantTest`). Passwords come from the secret store.
