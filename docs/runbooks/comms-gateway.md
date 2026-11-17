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

## Scheduled sends and the inbox (COM-023, COM-030)
- Scheduler: needs `comms.core.base-url` (claim check). One leader per region (ShedLock `comms-send-scheduler`), 15 s tick. A due send past `notAfter` becomes `expired`, a claim-check 410 or 404 becomes `cancelled`; both publish `comms.message.status_changed.v1`. If Core is down the sends stay `scheduled`, are retried, and expire once their window passes. Alert `CommsReminderWindowMissBurn` (expired share above 1%) means Core or Valkey was unavailable for a while or the tick stopped: check `comms_scheduled_total{outcome}` and the lock row `shedlock.name = 'comms-send-scheduler'`.
- The Valkey due index is rebuilt from the database at start and every 20 ticks; losing Valkey costs nothing (the tick falls back to the partial index on `outbound_messages`).
- Inbox: only active when `comms.field-key` (base64, 32 bytes, from the secret store) is set; `/internal/v1/inbox/**` additionally needs `comms.internal-token`. Message text and phone numbers are encrypted at rest; rotating the field key needs a re-encryption job (not built).

## SMS channel (COM-026)
- On only where `comms.sms.bsp.url` (BSP base URL) and `comms.field-key` are set; `comms.sms.bsp.api-key` (secret store) authenticates to the BSP, `comms.sms.provider` (default `bsp`) names the webhook path. India only: any other `comms.region` refuses every send with 422 `SMS_DISABLED_FOR_REGION` (GCC sender registration is out of scope, T-309).
- Templates: `sms_templates` holds the DLT entity id, header (sender id), DLT template id, category (`otp`, `service_implicit`, `service_explicit`, `promotional`) and the exact registered body with `{#var#}` (1-30 characters each). Load rows from the DLT portal export; `tenant_id` NULL = the Nabd-shared registration (OTP / transactional), a clinic row = its own registration (promotional must be a clinic row). Set `status = 'suspended'` to stop a template. A text that matches no active template is refused locally with 422 `DLT_TEMPLATE_MISMATCH` and never reaches the BSP.
- Send: `POST /internal/v1/sms/messages` (`X-Internal-Token`, `X-Tenant-Id`, body `{to, text, patientRef?, requestId?}`) -> 202 `{messageId, status}` (`sent`, `failed`, `blocked` by the consent gate). No retry lane: a failed OTP is re-requested by the caller.
- Delivery reports: `POST /v1/webhooks/sms/{provider}`, HMAC-SHA256 of the body with `comms.sms.webhook.secret` in `X-Signature: sha256=<hex>`; for a BSP that cannot sign, set `comms.sms.webhook.allowed-ips` (comma-separated exact IPs) and it sends the secret in `X-Webhook-Secret`. With an allow-list set, every report must come from it. Anything else is 401 `BAD_SIGNATURE` (counter `comms.sms.webhook.rejected`). Reports are stored in `inbound_events` (`field = 'sms_dlr'`) and acked; a 2 s job applies them to `outbound_messages` and publishes `comms.message.status_changed.v1` (channel `sms`). A report for a message the gateway does not know yet stays pending for 10 passes, then `ignored`.
- Secret rotation: same steps as the Meta app secret above, with the BSP's console instead of Meta's.

## SMS STOP and DND (COM-028)
- Inbound SMS (MO): `POST /v1/webhooks/sms/{provider}/inbound`, same signature / allow-list rules as the delivery reports, body `{id, from, text}` or an array. Stored in `inbound_events` (`field = 'sms_mo'`, encrypted) and acked; a 2 s job (`comms.sms.mo-tick-ms`, needs `comms.core.base-url` + `comms.core.token`) relays texts of up to 64 characters to Core `POST /internal/v1/consents:sms-keyword`. Core's COM-002 matcher decides; a STOP withdraws `marketing` (never `messaging`): an all-clinics phone suppression plus the consent rows in the clinic of the latest SMS to the number (consent.changed.v1 updates the snapshot). The gateway marks the number opted out (`sms_numbers.marketing_opt_out_at`) and, for a new opt-out, sends one confirmation with the `optout_confirm` SMS template (clinic row first, else the shared row; load it from the DLT export, no variables, category `service_implicit`). No template = no confirmation (counter `comms.sms.mo{outcome="no_confirm_template"}`). Core down: rows go back to `pending` (`last_error_code = CORE_UNAVAILABLE`), `dead` after 100 attempts. START by SMS is not handled: re-subscribing is a desk action.
- Promotional SMS (template category `promotional`) are refused with status `blocked` and a `send_refusals` row when the number sent STOP (`OPTED_OUT`), is on the DND register (`DND`), or has no DND answer younger than `comms.sms.dnd.max-age` (default `PT24H`, to be confirmed by compliance against TRAI TCCCPR) and the BSP scrub (`POST {bsp}/dnd {to}` -> `{dnd}`) fails (`DND_CACHE_STALE`, fail closed). A promotional SMS also needs a patient with marketing consent. OTP and service SMS skip these checks. Counter `comms_send_refused_total{reason}`.
