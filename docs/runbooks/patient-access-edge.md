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
* Cohort B GA (ACC-045): SLOs, outage drills, per-clinic rollout (`clinic_rollout`, V21), funnel (`funnel_steps`, V20), load test
  and on-call: `docs/runbooks/edge-cohort-b.md`.

## Reminder buttons, travel time and leave-now (ACC-041, ACC-044)
- Button action ids (signed with the shared button key, 24 h token; COM-043 issues them on the reminders): `confirm:<appointment id, 32 hex>`, `resched:<id>`, `cancel:<id>`, `callme:<id>`, `status`. Follow-ups the Edge issues itself: `slot:<epoch seconds>`, `why:<reason code>`, `travel:<10|20|30>` and `travel:change`. `ReminderIntent.action(verb, appointmentId)` builds them.
- Core calls (all by the patient's own patientRef, replay-safe by `Idempotency-Key` = hash of message id + action): `GET /appointments/{id}`, `POST /appointments/{id}/{confirm|reschedule|cancel|call-me}`, `GET /status/{phoneHash}`. A change inside `cancellation_window_hours` is refused 422 INSIDE_CANCELLATION_WINDOW; the bot explains the window and offers Call me, which opens a desk exception (`appointment_desk_exceptions`, kind `call_me`).
- Travel time: table `travel_buffers` (10/20/30/45/60). Asked once after the first Confirm (the booking flow, ACC-040, calls `ReminderIntent.askTravel` for the first booking); the patient changes it by sending "change travel time".
- Leave-now / ETA-shift (`NotifyRules`, pure): consumed event `visit.eta.updated.v1` on `ev:<region>:visit` with `{tenantId, appointmentId, p50, mode, arrived}`; a 30 s sweep (`edge_due_visits()`, SECURITY DEFINER) fires the clock-driven leave-now. State in `visit_notification_state`. Metrics: `edge_eta_notify_total{kind}`, `edge_eta_notify_send_failed_total`, `edge_eta_notify_errors_total`.
- Pilot KPI plan (criterion: calls asking for queue position fall >= 70% after 4 weeks; Needs external action, pilot clinics): record per clinic, in the baseline week and in weeks 1-4, the desk's tally of "where am I in the queue" calls and the count of `status` replies (`edge_inbound_total` with the status intent); success = tally in week 4 <= 30% of the baseline tally.

## Channel exclusivity and the output guard (COM-016)
- Rule: a conversation in clinic context (the clinic's own number, a shared-number conversation routed to the clinic by COM-011, or a clinic deep link) shows only that clinic's doctors, slots, packages and content. Every Edge conversation is bound to one tenant, so discovery of other clinics (T-369, Phase 3, shared Nabd number with no clinic context only) is never offered by the Edge.
- In code: flows read clinic content only through `ClinicContent` (bound to the conversation's tenant; no method takes a tenant id). `OutputGuard` checks every reply before it is committed: another tenant's id, doctor id, clinic name or doctor name, or a `discover*` button, replaces the reply with "Let me connect you to the clinic" and moves the thread to HANDOFF (reception answers; `HandoffService.handBack` returns it to the bot).
- Alert `EdgeCrossTenantReplyBlocked` (page) on `edge_output_guard_blocked_total{reason=foreign_tenant|discovery}`. The ERROR log line carries the tenant, the conversation id and the foreign tenant id only (no text, no contact). Treat it as a possible cross-tenant leak: find which Core read returned the foreign content (Core logs for that tenant at that minute), check the projection keys `edge:avail:{tenant}:*`, and follow the security incident process. Ownership is decided from the conversation's own clinic profile, the same on every instance and in every order (S24 T1-BUG-5/6): a doctor a reply offers or has chosen must be in that profile (the log then says "names a doctor that is not in this clinic's profile"; a doctor removed from online booking in the middle of a chat reads the same), and a profile row whose `tenantId` is another clinic's is foreign. A doctor listed in two clinics' profiles is each clinic's own. Other clinics' names and ids in free text are known only for clinics the instance has read a profile of. A false positive (two clinics sharing a distinctive word in their names) is visible in the log as the same foreign tenant every time; note it in the incident and tell the clinic.
