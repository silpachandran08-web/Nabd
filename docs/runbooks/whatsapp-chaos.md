# WhatsApp chaos suite and dead letters (OPS-045)

## What the suite proves
`Ops045WhatsAppChaosApiTest` (backend, class `com.nabd.hms.messaging`) drives the real send path (outbox, `WhatsAppSendHandler`,
`MetaWhatsAppClient`, webhook inbox) against `ChaosWhatsAppStub`, which counts every accepted send by message marker.

| Scenario | Expectation (test) |
|---|---|
| Provider down 30 min (6 backoff steps), recovers | every message sent exactly once, none lost (`providerDownThirtyMinutes...`) |
| Message past its window during the outage | `expire()` makes it `expired`, never sent late (`aMessageThatMissedItsWindow...`) |
| 429 storm | no permanent failure, no duplicate (`a429Storm...`) |
| Every third request 500 | no loss, no duplicate (`partialFiveHundreds...`) |
| Latency above the read timeout | no loss, at most one re-send, the documented window (`providerLatency...`) |
| Application DB connections killed mid-batch (failover emulation) | no loss, at most one re-send (`connectionsKilledMidBatch...`) |
| Webhooks duplicated, delayed, reordered | status only moves forward, an inbound id is processed once |
| STOP during an outage | consent withdrawn < 60 s, queued reminders blocked, none sent |
| Check-in p95 in parallel | below 300 ms with `-Dchaos.latency.strict=true` (default guard 1500 ms on the shared sandbox) |
| Outage longer than 8 attempts (about 63 min) | events `dead`, rows visible, `outbox_dead_events` rises, alert fires |

Known gap (tag `known-defect-OPS-045`, excluded from the default run): a reminder whose send window closes while the provider is down
is still sent late, because the window (`notification_requests.not_after`) is checked only when the request is handed to the sender.
The scheduler that carries the window to the send is COM-023 / T-145. Run the test with `-Dknown.defect.tags=` to see it.

## Nightly run
`.github/workflows/ops045-whatsapp-chaos-nightly.yml` runs the suite at 02:10 UTC with a 20-minute timeout (time-compressed: no real waiting).
Needs external action: Toxiproxy-based network and database faults against a staging cluster, and the same suite against the extracted
Comms Gateway with the COM-010 provider stub.

## When NabdOutboxDeadLetters or NabdWhatsAppInboundDeadLetters fires
1. Find out why: provider status page, `last_error` of the dead rows (`outbox_events.status = 'dead'`, `whatsapp_inbound_events.status = 'dead'`).
2. Fix the cause (credentials, template paused, provider outage over).
3. Redrive: set the dead outbox rows back to `pending` with `attempts = 0` through the redrive command of the outbox relay (OPS-016) or the
   operator console. A message past its window must be expired, not redriven.
4. Check that the count returns to 0 and the alert resolves.
