# Domain events (OPS-015): the contract

This is the contract that the relay (OPS-016), the consumers (OPS-017) and the Valkey revocation cache (OPS-020) build on.
They use the envelope as it is defined here. Changing it needs a new `.v2` type, not an edit.

## 1. How an event is published

- There is one write path, the existing transactional outbox (`outbox_events`, ADR-03). There is no second outbox.
- A producer calls `EventPublisher.publish(tenantId, DomainEventType.X, aggregateId, data)` inside its own `@Transactional`
  business method. The event row commits only if the business write commits. A rollback leaves no event (tested).
- `event_type` stays the dispatcher's routing key. For a domain event it equals `ce_type`. Older event types such as
  `whatsapp.send`, OTP and break-glass keep `ce_type NULL` and are not domain events.
- Columns added by V1550: `ce_type`, `ce_source`, `aggregate_id` (the CloudEvents `subject`), `trace_parent` and
  `schema_version` (default 1).
- Trigger `trg_outbox_ce_version` (V1550) sets `data.version` to the row id. A later change to the same aggregate waits
  on that aggregate's row lock, so it always gets a higher id. That makes `version` a monotonic number per aggregate.
  Consumers drop any event whose `version` is not higher than the last one they applied for that `subject`.
- Trigger `trg_outbox_notify` (V1551) runs `AFTER INSERT ... FOR EACH STATEMENT` and calls `pg_notify('outbox', '')`.
  Postgres merges identical notifications within one transaction, so a transaction that publishes N events wakes
  `LISTEN outbox` exactly once, at commit. A rollback sends no notification (tested).
- Every catalogue type has one `OutboxEventHandler` bean. `DomainEventHandlers` registers a log-only handler per type, so
  the dispatcher marks these rows done instead of retrying "no handler" forever. **OPS-016:** in a region with Valkey
  the dispatcher no longer claims domain rows at all and the Streams relay owns them (section 8). The log-only
  handlers stay for deployments without Valkey, where no stream exists.

## 2. Envelope: CloudEvents 1.0, structured JSON

View `outbox_cloudevents` (security_invoker, so outbox RLS still applies) is the only place that renders the envelope.
Its schema is `api/events/cloudevent.schema.json`.

| attribute | value |
|---|---|
| `specversion` | `"1.0"` |
| `id` | `outbox_events.id` as a string. It is the dedupe key and is unique within a region database |
| `source` | `core/<module>`, e.g. `core/visit` |
| `type` | `<domain>.<entity>.<verb>.v<n>`, e.g. `visit.queue_entry.created.v1` |
| `time` | the commit transaction's `created_at`, UTC RFC 3339 |
| `subject` | the aggregate id (uuid) |
| `tenantid` | the tenant uuid |
| `region` | the tenant's cell code: `in`, `ae`, `sa` or `qa`, from `tenants.region_code` (extension attribute, OPS-061) |
| `traceparent` | W3C trace context of the HTTP request that published the event (see section 4). Absent for jobs |
| `dataschema` | `urn:nabd:event-schema:<type>`, which is the `$id` of `api/events/<type>.schema.json` |
| `datacontenttype` | `application/json` |
| `data` | the event data, described below |

Stream names for the relay are `ev:<region>:<domain>`, where `<domain>` is the first segment of `type`. For example,
`visit.queue_entry.created.v1` goes to `ev:in:visit`.

## 3. Data rules

- `data` holds only `tenantId`, aggregate ids, status and code values, and `version`. It never holds PHI. Consumers that
  need details read the API, where access checks, consent and the PHI read audit apply.
- A field whose name contains a word on `api/events/phi-denylist.txt` fails CI. The list includes name, phone, dob and
  text.
- Every string field is bounded by a format, enum, const or pattern, so free text cannot slip in.
- The serialised envelope must be smaller than 2 KB.
- `additionalProperties: false`. Schema evolution within v1 is additive only: a new optional field or a new enum value.
  Anything else is a new `.v2` type, published alongside v1 until v1 has no consumers left.

## 4. Trace context

`EventPublisher` stores a traceparent in this order of preference:

1. The current OpenTelemetry span, when the agent is attached (production image).
2. Otherwise, the request's own `traceparent` header, only if it is a valid W3C value (a regex check, never free text).
3. Otherwise, nothing.

The relay passes it on as the CloudEvents `traceparent` extension, so one trace spans HTTP -> outbox -> consumer.

## 5. Catalogue (21 types, `DomainEventType`)

| type | producer (business action) | data besides tenantId, version |
|---|---|---|
| `scheduling.appointment.booked.v1` | AppointmentService.book | appointmentId, doctorId, startTime, status |
| `scheduling.appointment.cancelled.v1` | AppointmentService.cancel | appointmentId, doctorId, startTime, status, by?, reasonCode? (ACC-006: who and a coded reason; `clinic_initiated` when a patient took an offered alternative) |
| `scheduling.appointment.rescheduled.v1` | AppointmentService.reschedule (subject = new appointment) | + previousAppointmentId, previousStartTime, by?, reasonCode? |
| `scheduling.appointment.no_show.v1` | NoShowService (ACC-025): staff mark or the grace-period job; consumers: realtime (SseFanoutConsumer), ETA recompute runs in-process, patient-edge rebook message (waits on the edge consumer) | appointmentId, doctorId, markedBy (`system`\|`staff`) |
| `scheduling.appointment.arrived.v1` | QueueService.checkIn with an appointment | appointmentId, doctorId, queueEntryId, status |
| `scheduling.booking.routed.v1` | BookingRequestService (ACC-009/010): a booking routed (outcome `confirmed` or `requested`), a request decided (`confirmed`, `declined`), offered other slots (`suggested`) or past its SLA (`escalated`). One type for the four stories events (requested/confirmed/declined/escalated). Never carries the patient's note | appointmentId, doctorId, startTime, outcome, channel?, decisionSource?, reviewReason?, slaDueAt?, reasonCode?, offerId?, slots?, expiresAt?, reviewerRole?, notifyOwner?, offerKind? (ACC-006: `change` = alternatives offered for a confirmed booking staff moved or blocked; the Edge answers through `/internal/v1/tenants/{tid}/change-offers/{offerId}/accept`) |
| `visit.queue_entry.created.v1` | QueueService.checkIn, transfer (new leg) | entryId, doctorId, queueDate, status |
| `visit.queue_entry.changed.v1` | every status move (CAS), priority reorder, consult extend (ACC-015, stands in for `visit.consult.extended.v1`), call-next (ACC-012), doctor reassign (ACC-022) | entryId, doctorId, queueDate, status, fromStatus?, priority?, extendedMinutes?, orderReason?, fromDoctorId?, newToken?, previousToken? (ACC-022 reassign) |
| `visit.completed.v1` | the checkout move to completed (subject = encounter) | encounterId, entryId, doctorId, queueDate, status |
| `organization.calendar.changed.v1` | CalendarService (ADM-028): a template applied, a holiday's effect set (closed, short with hours, open), a holiday added or removed. Dates and effects only; slot generation reads the calendar live, so the event is for caches and the move-bookings job | dates[{date, effect, hours?}] |
| `scheduling.doctor_delay.changed.v1` | ScheduleService.announceDelay / clearDelay | doctorId, delayId, delayMinutes, status |
| `scheduling.doctor_status.changed.v1` | DoctorSessionService start / pause / resume / end, implicit start at the first in_consult (ACC-015). Status in, paused, out. Replaces the story's `scheduling.doctor_session.changed.v1` | doctorId, status |
| `clinical.rx.signed.v1` | PrescriptionService.sign | prescriptionId, queueEntryId, signedBy, status |
| `clinical.allergy.changed.v1` | AllergyService add / correct / deactivate / no-known-allergies (subject = patient) | patientId, allergyId?, change, status |
| `billing.invoice.paid.v1` | CheckoutService.recordPayment, when the invoice becomes paid | invoiceId, totalMinor, currency, status |
| `billing.payment.recorded.v1` | CheckoutService.recordPayment (converted from the FIN-001 event; same keys) | invoiceId, paymentId, method, amountMinor, currency, balanceMinor, status |
| `billing.refund.approved.v1` | RefundService.approve (FIN-013; subject = refund request). The credit note itself travels as the non-catalogue `billing.credit_note.issued.v1` (ids and totals, log-only handler like `billing.day.closed.v1`) | refundRequestId, invoiceId, amountMinor, methods |
| `consent.changed.v1` | PatientService.withdrawConsent, guardian consent grant / withdraw, ConsentCaptureService (IDC-004 desk and assisted capture) | patientId, consentType, status, noticeVersion?, guardianRef? |
| `organization.config.changed.v1` | SetupService.updatePolicy, updateProfile (key only, never the value) | configKey, status |
| `organization.tenant.region_pinned.v1` | ProvisioningStepRunner.goLive (OPS-061; the region is immutable from here). Consumer: the Control Plane cell-routing record, **needs external action** (the control-plane service is not in this repo) | regionCode, cell |
| `organization.privacy_notice.published.v1` | NoticeService.publish and the first seed when the clinic saves its consent contact (IDC-003). Re-acknowledgement is derived at read time from the patient's latest consent, so core needs no projection. Consumer patient-edge: **needs external action** (service not in this tree) | noticeVersion, languages |
| `shared.attachment.scanned.v1` | FileService.scan (PLT-029) when an upload becomes available, infected or rejected_type. Covers the story's `file_object.available` / `blocked` pair in one type. Consumer: log-only until the realtime push exists | fileId, purpose, status |
| `iam.role.changed.v1` | RoleService.create / update / resetToPreset | roleId, status |
| `iam.staff.changed.v1` | StaffService.patch / suspend | staffId, status |
| `iam.session.revoked.v1` | StaffService: role change, suspend, MFA reset (all sessions of the staff member) | staffId, scope, reasonCode, status |
| `billing.catalogue.changed.v1` | SetupService.addCharge / updateCharge | chargeHeadId, status |
| `comms.notification.requested.v1` | NotificationService.request (COM-004), not when the patient has no consent | requestId, purpose, priority, channelPolicy, notBefore?, notAfter? |
| `comms.notification.cancelled.v1` | NotificationService.cancelBySubject (appointment cancelled / rescheduled), consent withdrawn before the send | requestId, reason |
| `comms.inbound.received.v1` | WhatsAppInboundProcessor, an inbound patient message routed to a clinic by its reply context (COM-001); never the text | inboundId, senderNumberId?, kind, buttonPayload?, replyToWamid?, receivedAt |
| `organization.import.completed.v1` | ImportWorker, when a patient import job (ADM-012) finishes its last chunk. Consumers (control-plane provisioning, core reporting): **needs external action** / not built yet | jobId, importType, created, merged, skipped, errors |
| `comms.message.status_changed.v1` | WhatsAppInboundProcessor, a delivery callback that moved a message forward (COM-001; subject = message), or WhatsAppMessageService.cancel / expire (COM-003: status cancelled or expired) | messageId, channel, status, errorCode?, at |
| `clinical.drug_master.version_activated.v1` | DrugMasterImportService.activate (CLN-019): one event per tenant of the region after the drug master version switch. Consumers: core cache refresh (log-only handler, nothing cached yet), clinical-intelligence KB copy refresh and staff-web index refresh (**need external action**, those consumers are not in this repo) | region, drugMasterVersion, added, changed, retired |
| `comms.sender.throttled.v1` | services/comms-gateway SenderQualityService (COM-021), after commit on every sender rating, tier, block-rate or override change; the id is the sender_quality_events row id; tenantId is the nil UUID for the shared Nabd sender. Consumers core.platform (ops alert) and the owner in-app/WhatsApp notice: **need external action** (OPS-026/027/028 and the owner utility template are not in this tree); log-only handler until then | senderAccountId, kind, from, to, throttleMode |
| `platform.entitlements.changed.v1` | FeatureSwitchController.set (per-clinic override / kill), BillingService.upsert (plan or plan-version move). Not sent for a global kill switch or a plan publish: those change no clinic's pinned plan, replicas converge on the 60 s snapshot TTL (PLT-059) | changedCodes[] |
| `patient.link.opened.v1` | LinkOpenRecorder (PLT-041), once per open attempt of a patient's secure link, any outcome (subject = the link). Never the address, document, OTP or User-Agent string. Consumers: log-only handler until the owner audit screen (PLT-040) and the patient access history read it | linkId, patientId, resourceType, outcome (otp_ok, otp_failed, expired, revoked), deviceFamily |
| `pharmacy.stock.batch_changed.v1` | StockCommandsImpl (PHL-005/006): one event per batch movement (receipt, dispense, restock, adjustment, opening); subject = item. Never a name or price. Consumer: log-only handler until the realtime/reporting projections of the stock screen exist (the story names core.realtime and core.reporting; the story's `pharmacy.stock.changed.v1` is this type) | itemId, batchId, delta, kind, onHand |
| `billing.discount.approval_requested.v1` | DiscountApprovalService.create (FIN-018; subject = approval): a bill's discounts and below-list overrides are above the owner's threshold. Consumers: `sse-fanout` (topic `billing`) and the comms-gateway owner notification (separate story; log-only until then) | approvalId, amountMinor, percent |
| `billing.discount.approval_decided.v1` | DiscountApprovalService.decide (FIN-018; subject = approval), in app or by owner PIN at the desk. Consumer: `sse-fanout` (topic `billing`), so the desk banner updates | approvalId, decision (approved, rejected) |
| `pharmacy.stock_take.posted.v1` | StockTakeService.post (PHL-010): once when a stock-take session is posted (subject = the session); the per-batch adjustments also publish `pharmacy.stock.batch_changed.v1`. Ids and counts only. Consumer core.reporting: log-only handler until the reporting projection exists | stockTakeId, variances, value |
| `pharmacy.return.accepted.v1` | RefundService.approve (PHL-011): once per approved credit note that put a customer's units back into stock (a pharmacy return from POST /v1/pharmacy/returns, or a resaleable FIN-013 refund line); subject = the credit note. One line per batch entered; the batch moves also publish `pharmacy.stock.batch_changed.v1` (kind return_in). Ids and quantities only. Consumers core.billing, core.reporting: log-only handler until they exist | invoiceId, creditId (the credit note; the PHI denylist bars "note" in field names), refundRequestId, lines[] (invoiceLineId, batchId, qty, quarantined) |

The following producers belong to other stories and should publish against this catalogue when they land:

- iam.session.revoked from logout, refresh-reuse and operator logout (PLT-007).

Deprecated, still published until their consumers move over (expand and contract):

- `visit.queue_entry.status_changed.v1` (ACC-002), superseded by `visit.queue_entry.changed.v1`.
- `clinical.prescription.signed.v1`, superseded by `clinical.rx.signed.v1`.

Their handlers stay registered so that rows already in flight drain.

## 6. CI

- Stage 1 runs `python3 scripts/ci/events.py check`. It checks that the catalogue and the schemas match one to one, that
  each schema is valid draft 2020-12 with `additionalProperties: false`, that no field is on the PHI denylist, that no
  string is unbounded, and that each example's envelope validates and is under 2 KB. The same stage runs
  `scripts/ci/test_events.py`, which feeds the gate good and bad samples.
- Stage 3 runs `DomainEventContractApiTest`, which drives every producer over HTTP and checks every envelope it
  published. The test writes those envelopes to `backend/target/event-samples/`, and
  `events.py check --samples backend/target/event-samples` then validates them with full jsonschema.

## 7. Consumers (OPS-017)

Framework: `com.nabd.hms.shared.events.consumer` (`ConsumerGroups`, `EventConsumer`). A consumer is a bean implementing
`EventConsumer`: a group name, the CloudEvents types it handles, an idempotency strategy and (optionally) the version gate.
The app refuses to boot a consumer with no idempotency strategy, a duplicate group, or a version gate without the DB ledger.

### 7.1 Stream entries

- The consumer reads streams `ev:<region>:<domain>` for every region in `app.events.consumer.regions` (default `in,ae,sa,qa`)
  and every domain of its types. Entries of other types are acknowledged without calling the handler.
- **Contract with OPS-016:** each stream entry has one field, `ce`, holding the structured envelope of section 2 as JSON.
  An entry without a valid envelope (specversion 1.0, id, type, tenantid, integral `data.version`) is dead-lettered at
  once with reason `malformed_envelope`.

### 7.2 Delivery

- Each group is created from `0` with `MKSTREAM`. Every replica is one consumer (name = `app.events.consumer.name`,
  default the host name) of every group. The polls therefore carry **no `@SchedulerLock`**: Valkey splits new entries
  across replicas, and a lock would serialise them onto one pod. Nothing in the framework must run only once.
- Each poll (`poll-interval-ms`, 200): `XREADGROUP >` for new entries, then the replica's own pending entries whose backoff
  has passed (`XCLAIM` to itself), then `XAUTOCLAIM` of entries idle for `claim-idle-ms` (60 s) on any consumer. That is
  how a crash mid-batch is recovered: the restarted replica (same name) retries its own pending entries at once, and a
  peer takes the entries of a replica that never comes back after 60 s.
- Idempotency: `DB_LEDGER` inserts a `projector_applied_events` row (V1690) in the same `TenantScope.run` transaction as
  the handler, so a redelivery is a no-op and a failed handler leaves no row. `VALKEY_SET` (stateless consumers) skips an
  event whose key `consumer:applied:{<group>}:<id>` exists and sets it, with a 24 h TTL, after the handler succeeded.
- Version gate (state-carrying consumers, DB ledger only): the event is applied only if `data.version` is higher than
  the highest version this group applied for the same `subject`; otherwise the outcome is `STALE` and the handler is
  not called. A per-subject advisory lock makes this safe across replicas.
- Handlers get the tenant from `tenantid` through `TenantScope.run` (PLT-019). They never read another module's tables;
  for detail they call the owning module's API.

### 7.3 Retries and dead letters

- A failed entry stays pending. It is retried after `retry-base-ms * 2^(failures-1)` (1 s, 2 s, 4 s ... capped at 30 s),
  counted in `consumer:failures:{<group>}:<stream>:<entry>` (TTL 1 day).
- The 8th failure (`max-attempts`) XADDs `{originalStream, entryId, eventId, consumer, attempts, lastError}` to
  `ev:<region>:dlq`, acknowledges the entry, increments `consumer_dlq_total{group}` and logs one ERROR line.
  `lastError` is the exception class name: never the message, never event data.
- Alert `NabdConsumerDeadLetter` (`infra/observability/event-consumers.rules.yml`) fires on the first dead letter.

### 7.4 Replay CLI

A one-off run of the same image. There is no HTTP endpoint: only someone who can start the worker with its database and
Valkey credentials can replay, and every replay is audited (`events.dlq.replayed` in the tenant's chain,
`events.dlq.replay_failed` in the platform chain).

```
valkey-cli XRANGE ev:in:dlq - +          # find the entry id
java -jar app.jar --spring.main.web-application-type=none --app.events.consumer.enabled=false \
  --app.events.dlq.region=in --app.events.dlq.replay=<dlq entry id>[,<id>...] --app.events.dlq.replayed-by=<operator>
```

The replay reads the original entry from `originalStream` (it must still be within the stream's MAXLEN), runs it
through the group's ledger and version gate, and deletes the DLQ entry on success. Exit code 0 = all replayed,
1 = some not, 2 = bad arguments.

### 7.5 Lag

`consumer_lag_events{group,stream}` (entries not yet delivered to the group: Valkey's `lag`, or a capped count on
Redis 6.2) and `consumer_lag_seconds{group,stream}` (age of the oldest undelivered entry). They are refreshed every
`lag-refresh-ms` (15 s) on every replica, so alert on `max()`. `NabdConsumerLagging` fires when a group is more than
60 s behind for 5 minutes.

### 7.6 Groups

| group | strategy | gated | handles | status |
|---|---|---|---|---|
| projector | DB_LEDGER | yes | `clinical.allergy.changed.v1` -> `RegistryProjector.project(patient)` | built (OPS-017); the V221 trigger poller stays until OPS-016 runs everywhere |
| cache-invalidator | VALKEY_SET | no | `organization.config.changed.v1` -> `TenantConfigEvictions`: evicts the tenant's zone/region locally and PUBLISHes the tenant id on `nabd:cfg:changed`, which every instance subscribes to; `billing.catalogue.changed.v1` (OPS-021) -> INCR of the domain version in Valkey, then PUBLISH `<tenant>/CATALOGUE` on the same channel | built (OPS-020, FIX-C). A group delivers each entry to ONE replica, so the pub/sub broadcast is the fan-out to all instances. Without Valkey the outbox dispatcher calls the same bean |
| sse-fanout | VALKEY_SET | no | `visit.queue_entry.*`, `visit.eta.updated.v1` (no producer yet), `scheduling.doctor_status/doctor_delay.changed.v1`, `scheduling.appointment.booked/cancelled/rescheduled/no_show.v1`, `scheduling.booking.routed.v1` (topic `requests`, ACC-010), and `comms.message.status_changed.v1` (topic `messages`, COM-006), and `iam.session.revoked/staff.changed/role.changed.v1` (close streams) -> `RealtimeHub.publish` | built (ACC-021), see valkey.md "Realtime (SSE)" |
| desk-exceptions | DB_LEDGER | no | `scheduling.appointment.arrived/cancelled/rescheduled.v1`, `scheduling.booking.routed.v1`, `visit.completed.v1` close items; `comms.handoff.requested.v1` (ACC-040, not produced yet) opens a hand-off item -> `DeskExceptions` (ACC-032) | built. Pushes to the SSE topic `exceptions` directly after commit (no event type of its own); `DeskExceptionSweepJob` opens the time-based items and closes what the events missed |
| eta-engine, comms, edge-projector | | | | their own stories |

## 8. Relay to Valkey Streams (OPS-016)

`com.nabd.hms.shared.relay.OutboxRelay` moves committed domain events to Valkey Streams. It runs in the Core worker
profile (same image, `SPRING_PROFILES_ACTIVE=worker`, `application-worker.yml` sets `app.relay.enabled=true`).

**Who finishes which row.** The relay and `OutboxDispatcher` never mark the same row done, because the rows are split
between them in SQL (V1680):

| Region | Domain rows (`ce_type` set) | Legacy rows (`whatsapp.send`, OTP, ...) |
|---|---|---|
| Valkey configured (`VALKEY_URL` set) | the relay only: `claim_relay_events` / `finish_relay_events` | the dispatcher only: `claim_outbox_events(limit, lease, true)` |
| no Valkey (dev, most tests) | the dispatcher, with the log-only `DomainEventHandlers` (no stream to publish to) | the dispatcher |

The switch is the presence of `VALKEY_URL`, which every pod of a region shares, so no pod can mark a domain row done
without an XADD in a region that has streams. A region with Valkey but no running worker keeps its domain events
pending and the 30 s alert fires; nothing is lost.

**Leader.** One leader per region database: `pg_try_advisory_lock` on a dedicated direct connection (not the business
pool, not PgBouncer: `RELAY_DB_URL`, falling back to `DB_URL`). Standbys retry every `lock-retry-ms` (2 s). A killed
leader's session ends and releases the lock, so a standby takes over within 10 s and claims from where the outbox
stands: unfinished rows are pending, or processing with a lease (10 s, a batch takes well under a second) that the next claim takes back.

**Loop.** The leader `LISTEN outbox` on the same connection and drains on every notification, and at least every
`poll-ms` (2 s) when a notification is lost. A drain claims batches of `batch-size` (500) with a lease, sends them in
one pipelined round trip (`XADD <stream> MAXLEN ~ <stream-maxlen> * ce <envelope>`), then finishes them fenced
(`status = 'processing' AND attempts = <claimed>`). A row is marked done only after every XADD of its batch
succeeded. On any Valkey error the whole batch goes back to pending at once, `relay_errors_total` counts it, and the
relay backs off `error-backoff-ms` (1 s). Delivery is at-least-once: a batch whose XADDs partly succeeded is sent
again, so consumers dedupe on the CloudEvents `id` (and the `version` gate, section 1).

**Stream entries.** Stream `ev:<region>:<domain>` (`ev:in:visit`, `ev:sa:billing`, ...). Each entry has one field,
`ce`, holding the structured CloudEvents envelope exactly as `outbox_cloudevents` renders it (section 2).

**Retention.** `MAXLEN ~ 1,000,000` per stream (`app.relay.stream-maxlen`), approximate trimming (Valkey trims whole
nodes, so a stream can hold somewhat more). 1,000,000 is 7 days at about 1.65 events/s (about 143,000 events a day)
on the busiest domain stream, at an envelope of under 2 KB (about 1 KB typical) roughly 1 GB per full stream. This is
an estimate for pilot traffic: recompute it as 7 x the busiest day's events of one domain and size the managed Valkey
(OPS-020) to match.

**Durability.** The streams are not a cache: a row is done once its XADD is acknowledged, so Valkey must keep acknowledged
entries across a restart or failover. The cell's ElastiCache Valkey runs with `durability = "sync"` (Valkey 9, Multi-AZ
transaction log before the ACK; `infra/terraform/modules/cell/aws/valkey.tf`, enforced by `check-terraform.py` and
`policy/cell.rego`). A self-managed Valkey needs `appendonly yes` + `appendfsync always` (`everysec` may lose the last
second of acknowledged entries, which the redrive below then has to cover).

**Redrive.** If entries are lost anyway (a Valkey without persistence, an operator `FLUSHALL`), done rows stay 7 days
(`OutboxPurgeJob`), so on-call puts the domain rows finished in a window back to pending and the relay XADDs them again;
consumers dedupe on the CloudEvents `id`. A one-off run of the worker image, audited (`events.relay.redriven`, platform
chain); exit code 0 = redriven, 2 = bad arguments (operator name, window empty or over 7 days):

```
java -jar app.jar --spring.main.web-application-type=none --app.events.consumer.enabled=false \
  --app.relay.redrive-from=2026-10-20T08:00:00Z --app.relay.redrive-to=2026-10-20T09:00:00Z --app.relay.redrive-by=<operator>
```

Pick the window from the restart time back to the oldest entry a consumer might not have read (its lag,
`consumer_lag_seconds`); a wider window only costs duplicate deliveries.

**Metrics and alerts** (`infra/observability/outbox-relay.rules.yml`):
- `relay_leader` (1 on the leader, 0 on standbys), `relay_errors_total`, `relay_events_published_total`.
- `NabdOutboxOldestPendingOver30s` on `outbox_oldest_pending_age_seconds` (OPS-014, every 15 s) > 30, `NabdRelayErrors`,
  `NabdRelayNoLeader`.

**Rules.** The relay never calls an HTTP API (`RelayArchitectureTest`). It sees only ids, attempts, the stream name and
the envelope (PHI-free by section 3).
