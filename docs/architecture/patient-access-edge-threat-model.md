# Patient Access Edge: threat model (ACC-039.6, T-077 template)

Scope: `services/patient-access-edge`. The Edge faces the unauthenticated internet through the Comms Gateway webhooks and a single
public endpoint (`GET /healthz`). It holds conversation state, 5-minute soft slot holds and an availability projection; it calls
Core only through `/internal/v1` (ACC-038) and never reads Core's database. `ThreatModelTest` fails when a row below has no
existing automated test, so a threat cannot be listed without a control that is exercised.

Assets: patient contact identity (kept only as a hash), message content (in memory for one step, never stored or logged), slot
availability, tenant isolation.

| ID | Threat | Control | Automated test |
|---|---|---|---|
| T1 | Bots and message floods | Per-phone limit of 20 messages/min (Valkey fixed window, hashed key); one "Please wait a moment" answer per window, the rest dropped before any Core call | `InboundFlowTest#phoneFloodAnswersOnceWithPleaseWaitAndDropsTheRestWithoutCallingCore` |
| T2 | Scraping of availability from the internet | No public data endpoint exists: only `/healthz` is served on the public port, metrics are on a separate port, per-IP limit with Problem+JSON 429 | `SecurityTest#publicSurfaceIsHealthzOnlyAndPerIpLimitedWithProblemJson` |
| T3 | OTP abuse (SMS/WhatsApp pumping through the bot) | The Edge has no OTP endpoint and sends nothing without an inbound message; every inbound is capped by the per-phone limit. OTP issue limits themselves belong to Core and COM-009 | `InboundFlowTest#phoneFloodAnswersOnceWithPleaseWaitAndDropsTheRestWithoutCallingCore` |
| T4 | Enumeration by phone | The Edge only ever holds a contact hash (events carry ids and the hash, never the number); no lookup endpoint; nothing it stores, logs or keys contains the number, name or message text. The household lookup that could confirm a phone is Core's, behind the internal identity | `InboundFlowTest#logsDbRowsAndValkeyKeysNeverHoldPhoneNameOrMessageBody` |
| T5 | Replayed button payloads (also forged or cross-tenant) | HMAC-SHA256 token bound to tenant and action with 30-day expiry (COM-011 scheme); a payload that fails verification is dropped, a genuine one runs once per message id | `InboundFlowTest#forgedExpiredAndForeignTenantButtonPayloadsAreRefusedAndGenuineOneRuns` |
| T6 | Replayed or duplicated webhook delivery | Dedup on message id in the same transaction as the state move (`processed_messages`, 7-day retention) | `InboundFlowTest#duplicateEventDeliveredTwiceIsProcessedOnce` |
| T7 | Cross-tenant access to conversations or holds | Postgres RLS enabled and forced on every Edge table, tenant bound per transaction, API role is not the table owner | `SecurityTest#rowLevelSecurityKeepsTenantsApart` |
| T8 | PHI in logs, keys or stored rows | No contact data, names or bodies in log arguments (CI scan `scripts/log-phi-scan.py`), context key allow-list (ids, first name, age) | `InboundFlowTest#staticLogScanOfEdgeSourcesIsClean` |
| T9 | Clinical data creeping into the Edge database | Schema has no phone, name, body or clinical columns; context keys are allow-listed by the engine | `SecurityTest#edgeSchemaHasNoClinicalOrRawContactColumns` |
| T10 | A compromised Edge reaching Core internals | No Core code or database access: separate module, image, role and workflow; Core is reached only through the internal API with its own identity (ACC-038) | `IndependentDeployTest#edgeHasNoCoreCodeOrBuildDependency` |
| T11 | Stale availability offering a booked slot | 30 s TTL plus invalidation on `scheduling.appointment.*` within 2 s; Core's constraint stays the final arbiter | `ProjectionTest#bookedEventInvalidatesTheDoctorWithinTwoSecondsAndPolicyEventTheTenant` |
| T12 | Two chats offered the same slot | 5-minute soft hold, atomic upsert | `SlotHoldsTest#secondChatChoosingTheSameSlotIsOfferedADifferentOne` |
| T13 | Valkey outage used to bypass limits or stall replies | Limits fail open with a metric and a page-level alert (same stance as Core); availability falls back to one Core call, under 3 s | `ProjectionTest#valkeyOutageStillAnswersFromCoreUnderThreeSecondsAndRecovers` |

Residual risks (not testable here): mTLS and the service identity towards Core and the Comms Gateway (ACC-038, PLT-009), the
real WhatsApp webhook signature check (Comms Gateway), and per-IP trust of `X-Forwarded-For` behind the load balancer (the
Edge uses the socket address until the ingress is configured; Needs external action).
