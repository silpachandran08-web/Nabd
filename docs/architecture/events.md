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
  the dispatcher marks these rows done instead of retrying "no handler" forever. **OPS-016:** replace
  `DomainEventHandlers` with the relay handlers. Keep one handler per type: the dispatcher refuses duplicate types.

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
| `region` | `in` or `ksa`, from `tenants.region` (extension attribute) |
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

## 5. Catalogue (19 types, `DomainEventType`)

| type | producer (business action) | data besides tenantId, version |
|---|---|---|
| `scheduling.appointment.booked.v1` | AppointmentService.book | appointmentId, doctorId, startTime, status |
| `scheduling.appointment.cancelled.v1` | AppointmentService.cancel | appointmentId, doctorId, startTime, status |
| `scheduling.appointment.rescheduled.v1` | AppointmentService.reschedule (subject = new appointment) | + previousAppointmentId, previousStartTime |
| `scheduling.appointment.arrived.v1` | QueueService.checkIn with an appointment | appointmentId, doctorId, queueEntryId, status |
| `visit.queue_entry.created.v1` | QueueService.checkIn, transfer (new leg) | entryId, doctorId, queueDate, status |
| `visit.queue_entry.changed.v1` | every status move (CAS), priority reorder | entryId, doctorId, queueDate, status, fromStatus?, priority? |
| `visit.completed.v1` | the checkout move to completed (subject = encounter) | encounterId, entryId, doctorId, queueDate, status |
| `scheduling.doctor_delay.changed.v1` | ScheduleService.announceDelay / clearDelay | doctorId, delayId, delayMinutes, status |
| `scheduling.doctor_status.changed.v1` | **no producer yet**: there is no doctor-status (available, break, away) action in the product | doctorId, status |
| `clinical.rx.signed.v1` | PrescriptionService.sign | prescriptionId, queueEntryId, signedBy, status |
| `clinical.allergy.changed.v1` | AllergyService add / correct / deactivate / no-known-allergies (subject = patient) | patientId, allergyId?, change, status |
| `billing.invoice.paid.v1` | CheckoutService.recordPayment, when the invoice becomes paid | invoiceId, totalMinor, currency, status |
| `billing.payment.recorded.v1` | CheckoutService.recordPayment (converted from the FIN-001 event; same keys) | invoiceId, paymentId, method, amountMinor, currency, balanceMinor, status |
| `consent.changed.v1` | PatientService.withdrawConsent, guardian consent grant / withdraw | patientId, consentType, status |
| `organization.config.changed.v1` | SetupService.updatePolicy, updateProfile (key only, never the value) | configKey, status |
| `iam.role.changed.v1` | RoleService.create / update / resetToPreset | roleId, status |
| `iam.staff.changed.v1` | StaffService.patch / suspend | staffId, status |
| `iam.session.revoked.v1` | StaffService: role change, suspend, MFA reset (all sessions of the staff member) | staffId, scope, reasonCode, status |
| `billing.catalogue.changed.v1` | SetupService.addCharge / updateCharge | chargeHeadId, status |

The following producers belong to other stories and should publish against this catalogue when they land:

- iam.session.revoked from logout, refresh-reuse and operator logout (PLT-007).
- doctor_status once a doctor-status action exists.

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
