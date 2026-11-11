# Runbook: Edge general availability for cohort B (ACC-045)

The WhatsApp booking service (Patient Access Edge, `services/patient-access-edge`) at 10-15 clinics. Base runbook (config,
deploy, streams): `docs/runbooks/patient-access-edge.md`. This page adds the SLOs, the alerts, the outage drills, the
per-clinic rollout and the on-call rota.

## SLOs (published; 28-day window, burn-rate alerts from `infra/observability/slo/slos.yaml`)
| SLO | Target | Metric |
|---|---|---|
| Bot step under 500 ms | 95% of steps | `edge_bot_step_seconds` (0.5 s bucket) |
| Availability read under 50 ms | 95% of reads | `edge_availability_read_seconds` (0.05 s bucket) |
| Inbound messages handled | 99.5% | `edge_inbound_total` vs `edge_event_failures_total` |
| WhatsApp booking time | median < 60 s per clinic | `funnel_steps` (Edge DB), `docs/gates/phase1/whatsapp_booking.sql` |
| Bookings via WhatsApp | >= 50% of a clinic's bookings | Core appointments by source over the same 2 weeks (exit review, OPS-050) |

Error-budget policy: `NabdSloFastBurn` / `NabdSloSlowBurn` page; more than half of a 28-day budget burnt freezes Edge releases
(`slo:release_frozen{service="patient-edge"}`). Dashboard: `infra/observability/dashboards/patient-edge.json` (bot step p95,
409 rate, funnel per step, Core unavailable, reply failures).

## Alerts (`infra/observability/patient-edge.rules.yml`)
- **EdgeCoreUnavailable** (page): steps fail because Core is unreachable or answers 5xx. Patients in the booking journey get one
  "Booking is temporarily unavailable" message per message; the message is not claimed, the stream retries it every 5 s and it
  completes on its own when Core is back (after 5 failed attempts it goes to `ev:<region>:dlq`, see Replay). Check Core health
  first (`/actuator/health`, Render status), then Edge -> Core reachability (`EDGE_CORE_URL`, token).
- **EdgeRepliesFailing** (page): answers are not reaching patients. Meta or the Comms Gateway is down (see Meta outage).
- **EdgeBookingConflictRateHigh** (ticket): many confirms hit a slot already taken. Check `edge_projection_total` miss ratio and
  `scheduling.booking_policy.published.v1` traffic; a rush hour at one clinic is expected, a steady rate is not.
- **EdgeConversationsStuck** (ticket): one conversation could not move after 25 version conflicts (see Stuck conversations).

## Drills (game day: fail Meta, Core and Valkey one at a time, in staging first)
For each drill record start/end time, what patients saw, alerts fired, and the booking count before/after in the game-day log.
"No booking is double-created" is checked on Core, per clinic (app.tenant_id bound): `SELECT patient_id, doctor_id, start_time, count(*)
FROM appointments WHERE created_at > :drill_start AND status <> 'cancelled' GROUP BY 1, 2, 3 HAVING count(*) > 1` (expect 0 rows).
Every Edge booking call carries one Idempotency-Key per (conversation, doctor, slot, person) and Core answers a repeat from the
stored result.

### Core outage
1. Stop Core (staging) or block `EDGE_CORE_URL` at the network.
2. Expect: `EdgeCoreUnavailable` within 5 min; a patient mid-booking gets "temporarily unavailable" once; a patient at Confirm gets
   "We are checking your booking..." and no false confirmation.
3. Restore Core. Expect: pending messages complete within one retry cycle (5 s); the patient at Confirm taps Confirm again and gets
   exactly one booking (same idempotency key).

### Meta (WhatsApp) outage
1. Make the Comms Gateway's provider fail (staging: `perf/wa-stub.mjs` with 100% 5xx, or the gateway's provider kill switch).
2. Expect: `EdgeRepliesFailing`; inbound steps still commit (state is saved), answers fail (`edge_reply_failed_total`).
3. Restore. Patients who got no answer re-send; their conversation resumes from the saved step. No booking is made without a
   patient's Confirm, so nothing is double-created. Sender auto-throttle (COM-021) must be live before GA: check the gateway's
   quality-rating throttle is enabled for every cohort B sender.

### Valkey outage
1. Stop the cell's Valkey.
2. Expect: `EdgeRateLimitFailOpen` (limits fail open), projection misses go to Core (`EdgeProjectionMissRatioHigh`), the stream
   consumer stops reading until Valkey is back.
3. Restore. The consumer recreates its group; events published during the outage are replayed from Core's outbox redrive
   (events.md section 8). Projection refills by itself.

## Stuck conversations
A conversation lives 24 h (`conversations.expires_at`); a stuck one heals when the patient writes after expiry. To reset one now
(migrator role, never edit `context` by hand): `UPDATE conversations SET state = 'START', context = '{}', version = version + 1
WHERE tenant_id = :t AND id = :conversation_id;` then ask the clinic to tell the patient to send "Hi".

## Replay (dead-lettered events)
`XRANGE ev:<region>:dlq - +` lists `{originalStream, entryId, consumer}`. Fix the cause, then re-add the original entry to its stream
(`XRANGE <originalStream> <entryId> <entryId>` then `XADD`). The message id is claimed only on success, so a replay is processed once.

## Per-clinic rollout (cohort B onboarding)
The bot answers a clinic only when its `clinic_rollout` row (Edge DB, V21) is `enabled`; the database refuses `enabled` until the
checklist is complete. Clinics without a row follow `edge.rollout.default-enabled` (env `EDGE_ROLLOUT_DEFAULT_ENABLED`, default true
so cohort A keeps working).

Onboarding checklist, per clinic (migrator role; one statement per item, the person's name in `enabled_by`):
1. Row created, bot still off: `INSERT INTO clinic_rollout (tenant_id) VALUES (:t);`
2. WhatsApp number live on the clinic's WABA, test message received: `UPDATE clinic_rollout SET number_live = true, updated_at = now() WHERE tenant_id = :t;`
3. Templates approved by Meta for the clinic's languages (native-speaker reviewed): `... SET templates_approved = true ...`
4. QR poster with the click-to-chat link (`https://wa.me/<number>?text=Hi`) printed and at the desk: `... SET qr_poster_issued = true ...`
5. Switch on: `UPDATE clinic_rollout SET enabled = true, enabled_by = '<name>', enabled_at = now(), updated_at = now() WHERE tenant_id = :t;`
   (fails with `clinic_rollout_checklist_before_enable` if any item is missing).
Rollback: `UPDATE clinic_rollout SET enabled = false ... WHERE tenant_id = :t;` (the bot goes silent for that clinic only).
Once every cohort A clinic has an enabled row, set `EDGE_ROLLOUT_DEFAULT_ENABLED=false` so a forgotten row can never switch a clinic on.

## Load test (3x expected cohort-B peak)
`perf/k6/edge_cohort_b.js` (see its header). Expected peak is an input (`PEAK_MSG_PER_MIN`), not a guess in this repo; the run is at 3x.
Pass = no SLO breach (bot step p95 < 500 ms, error ratio < 0.5%) and no lost message (every injected inbound id is in
`processed_messages`, checked by the script's teardown). Running it needs staging with the Edge, Comms stub and Core: Needs external action.

## On-call rota
| Week | Primary | Secondary |
|---|---|---|
| (to be staffed by the platform lead before GA) | | |
Primary acknowledges a page within 15 min (business hours of the cohort's region) and owns the incident until handed over; the
secondary covers when the primary does not acknowledge. Names and phone numbers live in the paging tool, not in this repo.
